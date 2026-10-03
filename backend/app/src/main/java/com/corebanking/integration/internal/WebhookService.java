package com.corebanking.integration.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.integration.core.EndpointGuard;
import com.corebanking.integration.core.RetrySchedule;
import com.corebanking.integration.core.WebhookEvents;
import com.corebanking.integration.core.WebhookSignature;
import com.corebanking.integration.core.provider.HttpTransport;
import com.corebanking.integration.core.provider.ProviderException;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.Json;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signed outbound webhooks (US-121).
 * <ul>
 *   <li><b>Endpoints</b> are registered through maker-checker (entity WEBHOOK_ENDPOINT): https only, a public DNS
 *       name, no credentials in the URL; the host is resolved and checked again before every delivery (SSRF
 *       guard, {@link EndpointGuard}). Redirects are not followed.</li>
 *   <li><b>Signing secret</b>: generated here (32 random bytes), stored encrypted, and handed out exactly once, to
 *       the person who proposed the endpoint or the rotation, after approval. On rotation the previous secret
 *       stays valid for {@code webhook.secret-overlap-hours} and deliveries carry a signature for each secret in
 *       force.</li>
 *   <li><b>Events</b> come from the outbox, are reduced to allow-listed fields ({@link WebhookEvents}) and kept,
 *       so that any event can be replayed.</li>
 *   <li><b>Delivery</b>: at least once; exponential backoff with jitter ({@link RetrySchedule#WEBHOOK}); after
 *       {@code webhook.max-attempts} the delivery is dead-lettered until someone replays it. Receivers
 *       de-duplicate on the event id header.</li>
 * </ul>
 */
@Service
class WebhookService implements OutboxConsumer {

    static final String ENTITY = "WEBHOOK_ENDPOINT";
    private static final String SIGNING_AAD = "integration.webhook_secret";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** openapi.yaml#/components/schemas/WebhookEndpointInput. */
    record Input(String name, String url, List<String> eventTypes) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final Secrets secrets;
    private final JdkHttpTransport http;
    private final TenantProps props;
    private final AuditLog audit;
    private final Json json;

    WebhookService(JdbcTemplate jdbc, ApprovalService approvals, Secrets secrets, JdkHttpTransport http, TenantProps props, AuditLog audit,
                   Json json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.secrets = secrets;
        this.http = http;
        this.props = props;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------------------------------------ endpoints
    private Map<String, Object> checked(Input in) {
        if (in == null || in.name() == null || in.name().isBlank() || in.name().length() > 80) throw ApiException.invalid("name is required (at most 80 characters)");
        String url;
        try {
            url = EndpointGuard.checkUrl(in.url(), EndpointGuard.DEFAULT_PORTS).toString();
        } catch (EndpointGuard.BlockedException e) {
            throw ApiException.invalid("url: " + e.getMessage());
        }
        if (in.eventTypes() == null || in.eventTypes().isEmpty()) throw ApiException.invalid("eventTypes: choose at least one of " + WebhookEvents.TYPES);
        for (String t : in.eventTypes()) {
            if (!WebhookEvents.isType(t)) throw ApiException.invalid("unknown event type " + t + "; choose from " + WebhookEvents.TYPES);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", in.name().trim());
        p.put("url", url);
        p.put("eventTypes", in.eventTypes().stream().distinct().sorted().toList());
        return p;
    }

    @Transactional
    ApprovalRequest proposeCreate(Input in) {
        Map<String, Object> p = checked(in);
        if (!jdbc.queryForList("SELECT 1 FROM integration.webhook_endpoint WHERE lower(name) = lower(?)", p.get("name")).isEmpty()) {
            throw ApiException.conflict("an endpoint named " + p.get("name") + " already exists");
        }
        return approvals.propose(ENTITY, "CREATE", null, p, null, null, null, null);
    }

    @Transactional
    ApprovalRequest proposeUpdate(UUID id, Input in) {
        Map<String, Object> p = checked(in);
        p.put("id", id.toString());
        return approvals.propose(ENTITY, "UPDATE", id.toString(), p, endpoint(id), null, null, null);
    }

    /** DISABLE, ENABLE or ROTATE_SECRET. */
    @Transactional
    ApprovalRequest proposeAction(UUID id, String action) {
        Map<String, Object> current = endpoint(id);
        return approvals.propose(ENTITY, action, id.toString(), Map.of("id", id.toString(), "name", String.valueOf(current.get("name"))),
                current, null, null, null);
    }

    String apply(ApprovalRequest r) {
        Map<String, Object> p = r.payload();
        String events = "{" + String.join(",", p.get("eventTypes") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.<String>of()) + "}";
        UUID id;
        switch (r.action()) {
            case "CREATE" -> {
                id = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO integration.webhook_endpoint (id, name, url, event_types, secret_pending_for, approval_id, created_by)
                        VALUES (?, ?, ?, ?::text[], ?, ?, ?)
                        """, id, p.get("name"), p.get("url"), events, r.maker(), r.id(), r.maker());
            }
            case "UPDATE" -> {
                id = UUID.fromString(String.valueOf(p.get("id")));
                jdbc.update("UPDATE integration.webhook_endpoint SET name = ?, url = ?, event_types = ?::text[], version = version + 1, updated_at = now() WHERE id = ?",
                        p.get("name"), p.get("url"), events, id);
            }
            case "DISABLE", "ENABLE" -> {
                id = UUID.fromString(String.valueOf(p.get("id")));
                jdbc.update("UPDATE integration.webhook_endpoint SET status = ?, updated_at = now() WHERE id = ?",
                        "DISABLE".equals(r.action()) ? "DISABLED" : "ACTIVE", id);
            }
            case "ROTATE_SECRET" -> {
                id = UUID.fromString(String.valueOf(p.get("id")));
                jdbc.update("UPDATE integration.webhook_endpoint SET secret_pending_for = ?, updated_at = now() WHERE id = ?", r.maker(), id);
            }
            default -> throw ApiException.invalid("unknown action " + r.action());
        }
        audit.record(CurrentUser.username(), "WEBHOOK_" + r.action(), ENTITY, id.toString(), Map.of("approvalId", r.id().toString()));
        return id.toString();
    }

    /**
     * Hands out the signing secret, once, to the person the approved request named. A new key id is created; the
     * previous secret keeps verifying for the overlap period.
     */
    @Transactional
    Map<String, Object> claimSecret(UUID id) {
        List<String> pending = jdbc.queryForList("SELECT secret_pending_for FROM integration.webhook_endpoint WHERE id = ? FOR UPDATE", String.class, id);
        if (pending.isEmpty()) throw ApiException.notFound("webhook endpoint " + id);
        String user = CurrentUser.username();
        if (pending.get(0) == null) throw ApiException.conflict("there is no secret to collect: propose a rotation first");
        if (!pending.get(0).equalsIgnoreCase(user)) throw ApiException.forbidden("the secret can be collected only by the user who proposed it");
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Integer kid = jdbc.queryForObject("SELECT coalesce(max(kid), 0) + 1 FROM integration.webhook_secret WHERE endpoint_id = ?", Integer.class, id);
        int overlap = props.number("webhook.secret-overlap-hours", 24, 0, 168);
        jdbc.update("UPDATE integration.webhook_secret SET valid_until = now() + make_interval(hours => ?) WHERE endpoint_id = ? AND valid_until IS NULL",
                overlap, id);
        jdbc.update("INSERT INTO integration.webhook_secret (endpoint_id, kid, secret_cipher, created_by) VALUES (?, ?, ?, ?)",
                id, kid, secrets.seal(secret, SIGNING_AAD), user);
        jdbc.update("UPDATE integration.webhook_endpoint SET secret_pending_for = NULL, updated_at = now() WHERE id = ?", id);
        audit.record(user, "WEBHOOK_SECRET_ISSUED", ENTITY, id.toString(), Map.of("keyId", "k" + kid));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keyId", "k" + kid);
        m.put("secret", secret);                      // shown once; only the ciphertext is kept
        m.put("previousSecretValidHours", overlap);
        return m;
    }

    Map<String, Object> endpoint(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(ENDPOINT + " WHERE e.id = ?", id);
        if (rows.isEmpty()) throw ApiException.notFound("webhook endpoint " + id);
        return view(rows.get(0));
    }

    List<Map<String, Object>> endpoints() {
        return jdbc.queryForList(ENDPOINT + " ORDER BY e.name").stream().map(WebhookService::view).toList();
    }

    private static final String ENDPOINT = """
            SELECT e.id, e.name, e.url, array_to_string(e.event_types, ',') AS "eventTypes", e.status, e.version,
                   e.secret_pending_for IS NOT NULL AS "secretPending", e.created_by AS "createdBy", e.created_at AS "createdAt",
                   (SELECT string_agg('k' || s.kid, ',' ORDER BY s.kid DESC) FROM integration.webhook_secret s
                     WHERE s.endpoint_id = e.id AND (s.valid_until IS NULL OR s.valid_until > now())) AS "keysInForce"
              FROM integration.webhook_endpoint e""";

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>(row);
        m.put("eventTypes", List.of(String.valueOf(row.get("eventTypes")).split(",")));
        String keys = (String) row.get("keysInForce");
        m.put("keysInForce", keys == null ? List.of() : List.of(keys.split(",")));
        return m;
    }

    // ------------------------------------------------------------------------------------------------ fan-out
    @Override
    public void on(long outboxId, String topic, String aggregateId, Map<String, Object> payload, OffsetDateTime at) {
        if (!WebhookEvents.isType(topic)) return;
        UUID eventId = UUID.randomUUID();
        int n = jdbc.update("""
                INSERT INTO integration.webhook_event (id, type, aggregate_id, data, occurred_at, outbox_id) VALUES (?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (outbox_id) DO NOTHING
                """, eventId, topic, aggregateId, json.write(WebhookEvents.data(topic, payload)), at, outboxId);
        if (n == 0) return;
        jdbc.update("""
                INSERT INTO integration.webhook_delivery (id, endpoint_id, event_id)
                SELECT gen_random_uuid(), e.id, ? FROM integration.webhook_endpoint e WHERE e.status = 'ACTIVE' AND e.event_types @> ARRAY[?::text]
                """, eventId, topic);
    }

    // ------------------------------------------------------------------------------------------------ delivery
    private record Claim(UUID id, UUID endpointId, UUID eventId, int attempts) {}

    void deliver() {
        List<Claim> claims = jdbc.query("""
                UPDATE integration.webhook_delivery SET next_attempt_at = now() + interval '3 minutes', attempts = attempts + 1
                 WHERE id IN (SELECT id FROM integration.webhook_delivery WHERE next_attempt_at <= now()
                               ORDER BY next_attempt_at LIMIT 25 FOR UPDATE SKIP LOCKED)
                RETURNING id, endpoint_id, event_id, attempts
                """, (rs, i) -> new Claim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4)));
        RetrySchedule schedule = RetrySchedule.WEBHOOK.withMaxAttempts(props.number("webhook.max-attempts", 12, 1, 30));
        for (Claim c : claims) {
            Map<String, Object> e = jdbc.queryForMap("SELECT url, status FROM integration.webhook_endpoint WHERE id = ?", c.endpointId());
            if (!"ACTIVE".equals(e.get("status"))) {
                finish(c, "DEAD", null, "the endpoint is disabled", null);
                continue;
            }
            List<WebhookSignature.Key> keys = jdbc.query("""
                    SELECT kid, secret_cipher FROM integration.webhook_secret
                     WHERE endpoint_id = ? AND (valid_until IS NULL OR valid_until > now()) ORDER BY kid DESC LIMIT 5
                    """, (rs, i) -> new WebhookSignature.Key("k" + rs.getInt(1), secrets.open(rs.getBytes(2), SIGNING_AAD).getBytes(StandardCharsets.UTF_8)),
                    c.endpointId());
            Integer status = null;
            String error = null;
            long started = System.nanoTime();
            if (keys.isEmpty()) {
                error = "the signing secret has not been collected yet";
            } else {
                Map<String, Object> ev = jdbc.queryForMap("SELECT id, type, data::text AS data, occurred_at FROM integration.webhook_event WHERE id = ?", c.eventId());
                Instant at = ((java.sql.Timestamp) ev.get("occurred_at")).toInstant();
                String body = WebhookEvents.envelope(String.valueOf(ev.get("id")), (String) ev.get("type"), CurrentUser.requireTenant(),
                        at.atOffset(ZoneOffset.UTC).toString(), json.readMap((String) ev.get("data")));
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", "application/json");
                headers.put("User-Agent", "CoreBanking-Webhooks/1");
                headers.put(WebhookSignature.HEADER, WebhookSignature.header(keys, Instant.now().getEpochSecond(), body));
                headers.put(WebhookSignature.EVENT_ID_HEADER, String.valueOf(ev.get("id")));
                headers.put(WebhookSignature.EVENT_TYPE_HEADER, (String) ev.get("type"));
                headers.put(WebhookSignature.DELIVERY_HEADER, c.id().toString());
                try {
                    HttpTransport.Response r = http.guarded().send(new HttpTransport.Request("POST", (String) e.get("url"), headers, body));
                    status = r.status();
                    if (!r.ok()) error = "the endpoint answered " + r.status();
                } catch (ProviderException x) {
                    error = x.getMessage();
                }
            }
            int ms = (int) Math.min((System.nanoTime() - started) / 1_000_000, Integer.MAX_VALUE);
            if (error == null) {
                finish(c, "DELIVERED", status, null, ms);
            } else if (schedule.exhausted(c.attempts())) {
                finish(c, "DEAD", status, error, ms);
            } else {
                jdbc.update("""
                        UPDATE integration.webhook_delivery SET status = 'RETRY', last_status = ?, last_error = ?,
                               next_attempt_at = now() + make_interval(secs => ?) WHERE id = ?
                        """, status, error, schedule.delaySeconds(c.attempts(), ThreadLocalRandom.current().nextDouble()), c.id());
                attempt(c, status, error, ms);
            }
        }
    }

    private void finish(Claim c, String to, Integer status, String error, Integer ms) {
        jdbc.update("""
                UPDATE integration.webhook_delivery SET status = ?, last_status = ?, last_error = ?, next_attempt_at = NULL,
                       delivered_at = CASE WHEN ? = 'DELIVERED' THEN now() END WHERE id = ?
                """, to, status, error, to, c.id());
        attempt(c, status, error, ms);
    }

    private void attempt(Claim c, Integer status, String error, Integer ms) {
        jdbc.update("""
                INSERT INTO integration.webhook_attempt (delivery_id, attempt_no, status_code, duration_ms, error) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, c.id(), c.attempts(), status, ms, error);
    }

    // ------------------------------------------------------------------------------------------------ replay console
    /** Sends an event to its endpoint again, as a new delivery; the original stays as it is. */
    @Transactional
    Map<String, Object> replay(UUID deliveryId) {
        Map<String, Object> d = delivery(deliveryId);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO integration.webhook_delivery (id, endpoint_id, event_id, replay_of, requested_by) VALUES (?, ?, ?, ?, ?)",
                id, d.get("endpointId"), d.get("eventId"), deliveryId, CurrentUser.username());
        audit.record(CurrentUser.username(), "WEBHOOK_REPLAY", "WEBHOOK_DELIVERY", deliveryId.toString(), Map.of("newDeliveryId", id.toString()));
        return delivery(id);
    }

    /** Replays every dead-lettered delivery of an endpoint that has not been replayed yet. */
    @Transactional
    Map<String, Object> replayDead(UUID endpointId) {
        endpoint(endpointId);
        List<UUID> dead = jdbc.queryForList("""
                SELECT d.id FROM integration.webhook_delivery d
                 WHERE d.endpoint_id = ? AND d.status = 'DEAD'
                   AND NOT EXISTS (SELECT 1 FROM integration.webhook_delivery r WHERE r.replay_of = d.id)
                 ORDER BY d.created_at LIMIT 1000
                """, UUID.class, endpointId);
        List<String> created = new ArrayList<>();
        for (UUID id : dead) created.add(String.valueOf(replay(id).get("id")));
        return Map.of("replayed", created.size(), "deliveryIds", created);
    }

    Map<String, Object> delivery(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(DELIVERY + " WHERE d.id = ?", id);
        if (rows.isEmpty()) throw ApiException.notFound("webhook delivery " + id);
        Map<String, Object> m = new LinkedHashMap<>(rows.get(0));
        m.put("attemptLog", jdbc.queryForList("""
                SELECT attempt_no AS "attemptNo", at, status_code AS "statusCode", duration_ms AS "durationMs", error
                  FROM integration.webhook_attempt WHERE delivery_id = ? ORDER BY attempt_no
                """, id));
        return m;
    }

    List<Map<String, Object>> deliveries(UUID endpointId, String status) {
        return jdbc.queryForList(DELIVERY + """
                 WHERE (?::uuid IS NULL OR d.endpoint_id = ?) AND (?::text IS NULL OR d.status = ?)
                 ORDER BY d.created_at DESC LIMIT 500
                """, endpointId, endpointId, status, status);
    }

    private static final String DELIVERY = """
            SELECT d.id, d.endpoint_id AS "endpointId", d.event_id AS "eventId", v.type AS "eventType", v.aggregate_id AS "aggregateId",
                   d.status, d.attempts, d.next_attempt_at AS "nextAttemptAt", d.last_status AS "lastStatus", d.last_error AS "lastError",
                   d.replay_of AS "replayOf", d.requested_by AS "requestedBy", d.created_at AS "createdAt", d.delivered_at AS "deliveredAt"
              FROM integration.webhook_delivery d JOIN integration.webhook_event v ON v.id = d.event_id""";

    @Component
    static class Task implements IntegrationTask {
        private final WebhookService webhooks;

        Task(@Lazy WebhookService webhooks) {
            this.webhooks = webhooks;
        }

        @Override public String name() { return "webhook-delivery"; }

        @Override public void run() { webhooks.deliver(); }
    }

    @Component
    static class Applier implements ApprovalApplier {
        private final WebhookService webhooks;

        Applier(@Lazy WebhookService webhooks) {
            this.webhooks = webhooks;
        }

        @Override public String entityType() { return ENTITY; }

        @Override public String apply(ApprovalRequest r) { return webhooks.apply(r); }
    }
}
