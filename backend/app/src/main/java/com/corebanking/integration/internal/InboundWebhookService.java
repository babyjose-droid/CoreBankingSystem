package com.corebanking.integration.internal;

import com.corebanking.integration.core.Hashing;
import com.corebanking.integration.core.RetrySchedule;
import com.corebanking.integration.core.provider.InboundEvent;
import com.corebanking.integration.core.provider.InboundWebhookParser;
import com.corebanking.integration.core.provider.ProviderKind;
import com.corebanking.integration.core.provider.Simulator;
import com.corebanking.integration.core.provider.WebhookRejectedException;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Provider callbacks (inbound webhooks).
 * <ol>
 *   <li><b>Verify</b>: the provider's adapter checks the signature over the raw body, exactly as received. Only
 *       the tenant's active provider of that kind is accepted. A callback that fails is answered 401 and nothing
 *       is stored.</li>
 *   <li><b>Store</b>: the event is written (raw body encrypted) under the provider's event id. The unique key is
 *       the replay protection: a repeat finds the row and is answered 200 without being processed again.</li>
 *   <li><b>Answer 2xx</b> only after that row is committed.</li>
 *   <li><b>Process</b> afterwards, from the stored row, with retries: posting needs the books to be open, and a
 *       provider must not be made to wait for it.</li>
 * </ol>
 */
@Service
class InboundWebhookService {

    static final int MAX_BODY = 256 * 1024;
    private static final String BODY_AAD = "integration.inbound_event.body";
    private static final Logger log = LoggerFactory.getLogger(InboundWebhookService.class);

    enum Receipt { STORED, DUPLICATE }

    /** The callback is not accepted; {@code status} is the HTTP status to answer with. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private final JdbcTemplate jdbc;
    private final ProviderConfigService providers;
    private final Secrets secrets;
    private final Json json;
    private final PayoutService payouts;
    private final CollectionService collections;
    private final MandateService mandates;
    private final TransactionTemplate tx;

    InboundWebhookService(JdbcTemplate jdbc, ProviderConfigService providers, Secrets secrets, Json json, PayoutService payouts,
                          CollectionService collections, MandateService mandates, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.secrets = secrets;
        this.json = json;
        this.payouts = payouts;
        this.collections = collections;
        this.mandates = mandates;
        this.tx = new TransactionTemplate(manager);
    }

    static ProviderKind kind(String path) {
        return switch (path == null ? "" : path.toLowerCase(Locale.ROOT)) {
            case "payout" -> ProviderKind.PAYOUT;
            case "collection" -> ProviderKind.COLLECTION;
            case "mandate" -> ProviderKind.MANDATE;
            default -> throw new Refused(404, "unknown callback kind");
        };
    }

    /** Verifies and durably stores a callback. The tenant is already bound to the thread. */
    Receipt receive(String kindPath, String providerPath, Map<String, String> headers, byte[] rawBody) {
        ProviderKind kind = kind(kindPath);
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        if (body.length > MAX_BODY) throw new Refused(413, "the callback body is larger than " + MAX_BODY + " bytes");
        String provider = providerPath == null ? "" : providerPath.toUpperCase(Locale.ROOT);
        if (!provider.matches("[A-Z][A-Z0-9_]{1,30}")) throw new Refused(404, "unknown provider");
        ProviderConfigService.Active<InboundWebhookParser> parser;
        try {
            parser = providers.inbound(kind, provider);
        } catch (WebhookRejectedException e) {
            throw new Refused(404, "this provider sends no callbacks of this kind");
        }
        if (parser == null) throw new Refused(404, "this provider is not active for the tenant");
        Map<String, String> lower = new LinkedHashMap<>();
        headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
        InboundEvent e;
        try {
            e = parser.port().parse(lower, body, parser.settings(), Instant.now().getEpochSecond());
        } catch (WebhookRejectedException x) {
            // a security event: the reason, never the body
            log.warn("provider callback rejected: provider={} kind={} reason={}", provider, kind, x.getMessage());
            throw new Refused(401, "the callback could not be verified");
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("reference", e.reference());
        parsed.put("providerRef", e.providerRef());
        parsed.put("status", e.status());
        parsed.put("amount", e.amount() == null ? null : e.amount().toPlainString());
        parsed.put("utr", e.utr());
        parsed.put("method", e.method());
        parsed.put("occurredAt", e.occurredAt() == null ? null : e.occurredAt().toString());
        parsed.put("reasonCode", e.reasonCode());
        parsed.put("reason", e.reason());
        parsed.put("umrn", e.umrn());
        int n = jdbc.update("""
                INSERT INTO integration.inbound_event (id, kind, provider, event_id, body_cipher, body_sha256, parsed)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (provider, kind, event_id) DO NOTHING
                """, UUID.randomUUID(), kind.name(), provider, e.eventId(),
                secrets.seal(new String(body, StandardCharsets.ISO_8859_1), BODY_AAD), Hashing.sha256Hex(body), json.write(parsed));
        return n == 1 ? Receipt.STORED : Receipt.DUPLICATE;
    }

    // ------------------------------------------------------------------------------------------------ processing
    private record Claim(UUID id, String kind, String provider, String eventId, String parsed, int attempts) {}

    void process() {
        List<Claim> claims = jdbc.query("""
                UPDATE integration.inbound_event SET next_attempt_at = now() + interval '3 minutes', attempts = attempts + 1
                 WHERE id IN (SELECT id FROM integration.inbound_event WHERE next_attempt_at <= now() AND status = 'RECEIVED'
                               ORDER BY received_at LIMIT 50 FOR UPDATE SKIP LOCKED)
                RETURNING id, kind, provider, event_id, parsed::text, attempts
                """, (rs, i) -> new Claim(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)));
        for (Claim c : claims) {
            try {
                String outcome = tx.execute(s -> dispatch(c));
                boolean ignored = outcome != null && outcome.startsWith("IGNORED");
                jdbc.update("UPDATE integration.inbound_event SET status = ?, outcome = ?, next_attempt_at = NULL, processed_at = now() WHERE id = ?",
                        ignored ? "IGNORED" : "PROCESSED", outcome, c.id());
            } catch (RuntimeException x) {
                RetrySchedule schedule = RetrySchedule.PROVIDER;
                String why = x instanceof ApiException ? x.getMessage() : x.getClass().getSimpleName();
                if (schedule.exhausted(c.attempts())) {
                    jdbc.update("UPDATE integration.inbound_event SET status = 'FAILED', outcome = ?, next_attempt_at = NULL WHERE id = ?", why, c.id());
                    log.error("provider callback {} could not be processed after {} attempts", c.id(), c.attempts());
                } else {
                    jdbc.update("UPDATE integration.inbound_event SET outcome = ?, next_attempt_at = now() + make_interval(secs => ?) WHERE id = ?",
                            why, schedule.delaySeconds(c.attempts(), ThreadLocalRandom.current().nextDouble()), c.id());
                }
            }
        }
    }

    private String dispatch(Claim c) {
        Map<String, Object> p = json.readMap(c.parsed());
        InboundEvent.Kind kind = switch (c.kind()) {
            case "PAYOUT" -> InboundEvent.Kind.PAYOUT;
            case "MANDATE" -> InboundEvent.Kind.MANDATE;
            default -> InboundEvent.Kind.PAYMENT;
        };
        InboundEvent e = new InboundEvent(c.eventId(), kind, (String) p.get("reference"), (String) p.get("providerRef"), (String) p.get("status"),
                p.get("amount") == null ? null : new BigDecimal(String.valueOf(p.get("amount"))), (String) p.get("utr"), (String) p.get("method"),
                p.get("occurredAt") == null ? null : Instant.parse(String.valueOf(p.get("occurredAt"))), (String) p.get("reasonCode"),
                (String) p.get("reason"), (String) p.get("umrn"));
        return switch (kind) {
            case PAYOUT -> payouts.applyInbound(e);
            case PAYMENT -> collections.applyInbound(e, c.provider(), c.id());
            case MANDATE -> mandates.applyInbound(e);
        };
    }

    // ------------------------------------------------------------------------------------------------ simulator
    /** openapi.yaml#/components/schemas/SimulatedCallback. */
    record Simulated(String kind, String reference, String status, BigDecimal amount, String reasonCode, String reason, String eventId) {}

    /**
     * Plays a callback of the SIMULATOR provider through the same verification and storage as a real one. Only
     * while SIMULATOR is the tenant's active provider of that kind and has a {@code webhookSecret}.
     */
    Map<String, Object> simulate(Simulated in) {
        if (in == null || in.kind() == null || in.reference() == null || in.status() == null) {
            throw ApiException.invalid("kind, reference and status are required");
        }
        ProviderKind kind;
        try {
            kind = kind(in.kind());
        } catch (Refused e) {
            throw ApiException.invalid("kind must be payout, collection or mandate");
        }
        ProviderConfigService.Active<InboundWebhookParser> active = providers.inbound(kind, Simulator.CODE);
        if (active == null) throw ApiException.conflict("SIMULATOR is not the active " + kind + " provider of this tenant");
        String secret = active.settings().secrets().get(Simulator.SECRET);
        if (secret == null) throw ApiException.conflict("configure the simulator's webhookSecret first");
        String eventId = in.eventId() == null ? "sim-" + UUID.randomUUID() : in.eventId();
        InboundEvent.Kind k = kind == ProviderKind.PAYOUT ? InboundEvent.Kind.PAYOUT
                : kind == ProviderKind.MANDATE ? InboundEvent.Kind.MANDATE : InboundEvent.Kind.PAYMENT;
        String token = Hashing.sha256Hex(in.reference()).substring(0, 12).toUpperCase(Locale.ROOT);
        InboundEvent e = new InboundEvent(eventId, k, in.reference(), "SIMCB" + token, in.status().toUpperCase(Locale.ROOT), in.amount(),
                k == InboundEvent.Kind.MANDATE ? null : "SIMUTR" + token, k == InboundEvent.Kind.PAYMENT ? "UPI" : null, Instant.now(),
                in.reasonCode(), in.reason(), k == InboundEvent.Kind.MANDATE ? Simulator.Mandate.umrn(in.reference()) : null);
        String body = Simulator.Webhooks.body(e);
        Receipt r = receive(in.kind(), Simulator.CODE, Map.of(Simulator.SIGNATURE_HEADER,
                Simulator.Webhooks.signature(secret, Instant.now().getEpochSecond(), body)), body.getBytes(StandardCharsets.UTF_8));
        return Map.of("eventId", eventId, "receipt", r.name());
    }

    /** FAILED from INITIATED or SENT; RETURNED from SENT or SUCCESS. */
    static boolean outcomeAllowed(String wanted, String payoutStatus) {
        return "FAILED".equals(wanted) ? payoutStatus.equals("INITIATED") || payoutStatus.equals("SENT")
                : "RETURNED".equals(wanted) && (payoutStatus.equals("SENT") || payoutStatus.equals("SUCCESS"));
    }

    /** openapi.yaml#/components/schemas/SimulatedPayoutOutcome. */
    record PayoutOutcome(String status, String reason) {}

    /**
     * Operator shortcut for local testing: reports a payout as FAILED, or RETURNED after it was credited, by playing
     * a signed SIMULATOR callback for it through the real verification path, then processing the callback now. The
     * normal failure path follows (a staff-approved loan gets a reversal proposed for a checker, per
     * {@code payout.failure-action}; the payout-failures task picks it up within a minute).
     */
    Map<String, Object> simulatePayoutOutcome(UUID payoutId, PayoutOutcome in) {
        String wanted = in == null || in.status() == null ? "" : in.status().trim().toUpperCase(Locale.ROOT);
        if (!wanted.equals("FAILED") && !wanted.equals("RETURNED")) throw ApiException.invalid("status must be FAILED or RETURNED");
        String reason = in.reason() == null || in.reason().isBlank() ? null : in.reason().trim();
        if (reason != null && reason.length() > 200) throw ApiException.invalid("reason is at most 200 characters");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT reference, status FROM integration.payout_instruction WHERE id = ?", payoutId);
        if (rows.isEmpty()) throw ApiException.notFound("payout");
        String status = String.valueOf(rows.get(0).get("status"));
        if (!outcomeAllowed(wanted, status)) throw ApiException.conflict("a payout that is " + status + " cannot be reported " + wanted
                + " (FAILED: from INITIATED or SENT; RETURNED: from SENT or SUCCESS)");
        Map<String, Object> answer = new java.util.LinkedHashMap<>(simulate(new Simulated("payout", String.valueOf(rows.get(0).get("reference")), wanted, null,
                wanted.equals("FAILED") ? "SIM_FAILED" : "SIM_RETURNED",
                reason != null ? reason : wanted.equals("FAILED") ? "account closed (simulated)" : "returned by the beneficiary bank (simulated)", null)));
        process();
        answer.put("payoutStatus", jdbc.queryForObject("SELECT status FROM integration.payout_instruction WHERE id = ?", String.class, payoutId));
        return answer;
    }

    @Component
    static class ProcessTask implements IntegrationTask {
        private final InboundWebhookService inbound;

        ProcessTask(@Lazy InboundWebhookService inbound) {
            this.inbound = inbound;
        }

        @Override public String name() { return "inbound-callbacks"; }

        @Override public void run() { inbound.process(); }
    }
}
