package com.corebanking.integration.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.integration.core.EndpointGuard;
import com.corebanking.integration.core.provider.CollectionGateway;
import com.corebanking.integration.core.provider.EmailSender;
import com.corebanking.integration.core.provider.GenericHttpSmsSender;
import com.corebanking.integration.core.provider.InboundWebhookParser;
import com.corebanking.integration.core.provider.MandateProvider;
import com.corebanking.integration.core.provider.PayoutGateway;
import com.corebanking.integration.core.provider.ProviderCatalog;
import com.corebanking.integration.core.provider.ProviderKind;
import com.corebanking.integration.core.provider.ProviderSettings;
import com.corebanking.integration.core.provider.SmsSender;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.Json;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant provider configuration and the registry that resolves the active provider of a kind.
 * <ul>
 *   <li>Changes go through maker-checker (entity PROVIDER_CONFIG). A new version replaces the active one; old
 *       versions stay as history.</li>
 *   <li>Secrets are encrypted with the tenant's data key — in the approval payload ("sealed", never shown to the
 *       checker) and in the table. No API returns a secret: only its name, that it is set, and its last four
 *       characters. A secret left out of a change keeps its current value.</li>
 *   <li>Settings and secret names are checked against {@link ProviderCatalog}; an unknown name is refused, so a
 *       secret cannot be stored as a plain setting (the database refuses secret-looking setting names too).</li>
 *   <li>A provider can be configured only when the deployment lists it in
 *       {@code corebanking.integration.providers-enabled}. The default is empty: neither the SIMULATOR (which
 *       reports success without moving money) nor an unverified partner adapter works unless switched on.</li>
 * </ul>
 */
@Service
class ProviderConfigService {

    static final String ENTITY = "PROVIDER_CONFIG";
    static final String ROW_AAD = "integration.provider_config.secrets";
    private static final String PAYLOAD_AAD = "approval.provider_config";

    /** openapi.yaml#/components/schemas/ProviderConfigInput. */
    record Input(String kind, String provider, Map<String, String> settings, Map<String, String> secrets) {}

    /** The active provider of a kind, ready to call. */
    record Active<T>(String code, T port, ProviderSettings settings) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final Secrets secrets;
    private final Json json;
    private final AuditLog audit;
    private final JdkHttpTransport http;
    private final Set<String> enabled;
    private final MailEmailSender smtp;

    ProviderConfigService(JdbcTemplate jdbc, ApprovalService approvals, Secrets secrets, Json json, AuditLog audit,
                          JdkHttpTransport http, @Value("${corebanking.integration.providers-enabled:}") String enabled,
                          MailEmailSender smtp) {
        this.smtp = smtp;
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.secrets = secrets;
        this.json = json;
        this.audit = audit;
        this.http = http;
        this.enabled = Arrays.stream(enabled.split(",")).map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean deploymentAllows(String provider) {
        return enabled.contains(provider);
    }

    // ------------------------------------------------------------------------------------------------ views
    List<Map<String, Object>> catalogue() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProviderCatalog.Spec s : ProviderCatalog.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", s.kind().name());
            m.put("provider", s.code());
            m.put("settings", s.settings().stream().sorted().toList());
            m.put("secrets", s.secrets().stream().sorted().toList());
            m.put("requiredSecrets", s.requiredSecrets().stream().sorted().toList());
            m.put("verified", s.verified());
            m.put("note", s.note());
            m.put("enabledInDeployment", deploymentAllows(s.code()));
            out.add(m);
        }
        return out;
    }

    List<Map<String, Object>> list(boolean history) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, kind, provider, settings::text AS settings, secret_hints::text AS hints, status, version,
                       updated_by AS "updatedBy", updated_at AS "updatedAt"
                  FROM integration.provider_config WHERE (? OR status = 'ACTIVE') ORDER BY kind, version DESC
                """, history);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("settings", json.readMap((String) r.get("settings")));
            Map<String, Object> shown = new TreeMap<>();
            for (Map.Entry<String, Object> h : json.readMap((String) r.get("hints")).entrySet()) {
                shown.put(h.getKey(), Map.of("set", true, "last4", String.valueOf(h.getValue())));
            }
            m.remove("hints");
            m.put("secrets", shown);                      // names, "set" and the last four characters: never a value
            ProviderCatalog.Spec spec = specOrNull((String) r.get("kind"), (String) r.get("provider"));
            m.put("verified", spec != null && spec.verified());
            m.put("enabledInDeployment", deploymentAllows((String) r.get("provider")));
            out.add(m);
        }
        return out;
    }

    private static ProviderCatalog.Spec specOrNull(String kind, String provider) {
        try {
            return ProviderCatalog.spec(ProviderKind.valueOf(kind), provider);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ changes
    @Transactional
    ApprovalRequest propose(Input in) {
        if (in == null || in.kind() == null || in.provider() == null) throw ApiException.invalid("kind and provider are required");
        ProviderCatalog.Spec spec = specOrNull(in.kind(), in.provider().toUpperCase(Locale.ROOT));
        if (spec == null) throw ApiException.invalid("there is no provider " + in.provider() + " for " + in.kind());
        if (!deploymentAllows(spec.code())) {
            throw ApiException.conflict("provider " + spec.code() + " is not enabled in this deployment"
                    + " (corebanking.integration.providers-enabled)");
        }
        Map<String, String> settings = in.settings() == null ? Map.of() : in.settings();
        Map<String, String> given = in.secrets() == null ? Map.of() : in.secrets();
        for (Map.Entry<String, String> e : settings.entrySet()) {
            if (!spec.settings().contains(e.getKey())) throw ApiException.invalid("unknown setting '" + e.getKey() + "' for " + spec.code());
            if (e.getValue() == null || e.getValue().length() > 500) throw ApiException.invalid("setting '" + e.getKey() + "' is empty or too long");
        }
        for (Map.Entry<String, String> e : given.entrySet()) {
            if (!spec.secrets().contains(e.getKey())) throw ApiException.invalid("unknown secret '" + e.getKey() + "' for " + spec.code());
            if (e.getValue() == null || e.getValue().length() < 8 || e.getValue().length() > 500) {
                throw ApiException.invalid("secret '" + e.getKey() + "' must be 8 to 500 characters");
            }
        }
        if (spec.code().equals(GenericHttpSmsSender.CODE) && settings.get("url") != null) {
            try {
                EndpointGuard.checkUrl(settings.get("url"), EndpointGuard.DEFAULT_PORTS);
            } catch (EndpointGuard.BlockedException e) {
                throw ApiException.invalid("url: " + e.getMessage());
            }
        }
        Map<String, String> current = currentSecrets(spec.kind().name(), spec.code());
        for (String required : spec.requiredSecrets()) {
            if (!given.containsKey(required) && !current.containsKey(required)) throw ApiException.invalid("secret '" + required + "' is required");
        }
        Map<String, Object> hints = new TreeMap<>();
        current.forEach((k, v) -> hints.put(k, Secrets.last4(v)));
        given.forEach((k, v) -> hints.put(k, Secrets.last4(v)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", spec.kind().name());
        payload.put("provider", spec.code());
        payload.put("settings", new TreeMap<>(settings));
        payload.put("secretsChanged", given.keySet().stream().sorted().toList());
        payload.put("secretHints", hints);
        payload.put("verified", spec.verified());
        payload.put("note", spec.note());
        payload.put("sealed", Base64.getEncoder().encodeToString(secrets.seal(json.write(new TreeMap<>(given)), PAYLOAD_AAD)));
        Map<String, Object> before = list(false).stream().filter(m -> spec.kind().name().equals(m.get("kind"))).findFirst().orElse(null);
        return approvals.propose(ENTITY, before == null ? "CREATE" : "UPDATE", spec.kind().name(), payload,
                before == null ? null : json.toMap(before), null, null, null);
    }

    @Transactional
    ApprovalRequest proposeDeactivation(String kind) {
        if (jdbc.queryForList("SELECT 1 FROM integration.provider_config WHERE kind = ? AND status = 'ACTIVE'", kind).isEmpty()) {
            throw ApiException.notFound("active provider for " + kind);
        }
        return approvals.propose(ENTITY, "DEACTIVATE", kind, Map.of("kind", kind), null, null, null, null);
    }

    String apply(ApprovalRequest r) {
        String kind = String.valueOf(r.payload().get("kind"));
        String user = CurrentUser.username();
        if ("DEACTIVATE".equals(r.action())) {
            jdbc.update("UPDATE integration.provider_config SET status = 'INACTIVE', updated_by = ?, updated_at = now() WHERE kind = ? AND status = 'ACTIVE'",
                    user, kind);
            audit.record(user, "PROVIDER_DEACTIVATE", ENTITY, kind, Map.of("approvalId", r.id().toString()));
            return kind;
        }
        String provider = String.valueOf(r.payload().get("provider"));
        if (!deploymentAllows(provider)) throw ApiException.conflict("provider " + provider + " is not enabled in this deployment");
        Map<String, String> merged = new TreeMap<>(currentSecrets(kind, provider));
        String sealed = (String) r.payload().get("sealed");
        if (sealed != null) {
            json.readMap(secrets.open(Base64.getDecoder().decode(sealed), PAYLOAD_AAD)).forEach((k, v) -> merged.put(k, String.valueOf(v)));
        }
        Map<String, Object> hints = new TreeMap<>();
        merged.forEach((k, v) -> hints.put(k, Secrets.last4(v)));
        Integer version = jdbc.queryForObject("SELECT coalesce(max(version), 0) + 1 FROM integration.provider_config WHERE kind = ?", Integer.class, kind);
        jdbc.update("UPDATE integration.provider_config SET status = 'INACTIVE', updated_by = ?, updated_at = now() WHERE kind = ? AND status = 'ACTIVE'",
                user, kind);
        jdbc.update("""
                INSERT INTO integration.provider_config (id, kind, provider, settings, secrets_cipher, secret_hints, version, approval_id, updated_by)
                VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?)
                """, UUID.randomUUID(), kind, provider, json.write(r.payload().get("settings")),
                merged.isEmpty() ? null : secrets.seal(json.write(merged), ROW_AAD), json.write(hints), version, r.id(), r.maker());
        audit.record(user, "PROVIDER_CONFIG", ENTITY, kind, Map.of("provider", provider, "version", String.valueOf(version),
                "approvalId", r.id().toString()));
        return kind + " v" + version;
    }

    /** Secrets of the active configuration when it is for the same provider (they carry over a change). */
    private Map<String, String> currentSecrets(String kind, String provider) {
        List<byte[]> rows = jdbc.query("SELECT secrets_cipher FROM integration.provider_config WHERE kind = ? AND provider = ? AND status = 'ACTIVE'",
                (rs, i) -> rs.getBytes(1), kind, provider);
        Map<String, String> out = new TreeMap<>();
        if (!rows.isEmpty() && rows.get(0) != null) {
            json.readMap(secrets.open(rows.get(0), ROW_AAD)).forEach((k, v) -> out.put(k, String.valueOf(v)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ registry
    private record Resolved(String code, ProviderSettings settings) {}

    /** The active configuration of a kind, or null when the tenant has none (or the deployment no longer allows it). */
    private Resolved resolve(ProviderKind kind) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT provider, settings::text AS settings, secrets_cipher FROM integration.provider_config WHERE kind = ? AND status = 'ACTIVE'",
                kind.name());
        if (rows.isEmpty()) return null;
        String code = (String) rows.get(0).get("provider");
        if (!deploymentAllows(code) || specOrNull(kind.name(), code) == null) return null;
        Map<String, String> settings = new TreeMap<>();
        json.readMap((String) rows.get(0).get("settings")).forEach((k, v) -> settings.put(k, String.valueOf(v)));
        Map<String, String> sec = new TreeMap<>();
        byte[] cipher = (byte[]) rows.get(0).get("secrets_cipher");
        if (cipher != null) json.readMap(secrets.open(cipher, ROW_AAD)).forEach((k, v) -> sec.put(k, String.valueOf(v)));
        return new Resolved(code, new ProviderSettings(settings, sec));
    }

    Active<PayoutGateway> payout() {
        Resolved r = resolve(ProviderKind.PAYOUT);
        return r == null ? null : new Active<>(r.code(), ProviderCatalog.payout(r.code(), r.settings(), http), r.settings());
    }

    Active<CollectionGateway> collection() {
        Resolved r = resolve(ProviderKind.COLLECTION);
        return r == null ? null : new Active<>(r.code(), ProviderCatalog.collection(r.code(), r.settings(), http), r.settings());
    }

    Active<MandateProvider> mandate() {
        Resolved r = resolve(ProviderKind.MANDATE);
        return r == null ? null : new Active<>(r.code(), ProviderCatalog.mandate(r.code()), r.settings());
    }

    Active<SmsSender> sms() {
        Resolved r = resolve(ProviderKind.SMS);
        // the generic gateway's URL is tenant-supplied: it goes through the SSRF guard on every call
        return r == null ? null : new Active<>(r.code(), ProviderCatalog.sms(r.code(), r.settings(), http.guarded()), r.settings());
    }

    Active<EmailSender> email() {
        Resolved r = resolve(ProviderKind.EMAIL);
        if (r == null) return null;
        return new Active<>(r.code(), ProviderCatalog.SMTP.equals(r.code()) ? smtp : ProviderCatalog.email(r.code()), r.settings());
    }

    /** The parser of callbacks for the kind, only when {@code provider} is the tenant's active provider of that kind. */
    Active<InboundWebhookParser> inbound(ProviderKind kind, String provider) {
        Resolved r = resolve(kind);
        if (r == null || !r.code().equals(provider)) return null;
        return new Active<>(r.code(), ProviderCatalog.inbound(kind, r.code(), r.settings(), http), r.settings());
    }
}
