package com.corebanking.integration.internal;

import com.corebanking.integration.core.provider.ProviderCatalog;
import com.corebanking.integration.core.provider.ProviderKind;
import com.corebanking.integration.core.provider.Simulator;
import com.corebanking.platform.DemoTenantSeeder;
import com.corebanking.platform.Json;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * LOCAL DEVELOPMENT ONLY (corebanking.bootstrap.demo-tenant=true): gives the demo tenant a working integration
 * set-up, so payouts, collections, mandates, NACH files and messages can be tried without first passing five
 * provider configurations through maker-checker. Only when the deployment enables the SIMULATOR, which moves no
 * money and sends nothing. A real tenant is configured through the API, with approval.
 */
@Component
@ConditionalOnProperty(name = "corebanking.bootstrap.demo-tenant", havingValue = "true")
class DemoIntegrationSeeder implements DemoTenantSeeder {

    private static final String ACTOR = "bootstrap";
    private static final Logger log = LoggerFactory.getLogger(DemoIntegrationSeeder.class);

    /** code, DLT template id, body — variables as in {@link MessagingService#EVENTS}. */
    private static final List<String[]> SMS_TEMPLATES = List.of(
            new String[] {"LOAN_DISBURSED", "100000000002",
                    "Your loan {{loan_no}} of Rs {{amount}} was disbursed on {{date}}. Rs {{net_amount}} is paid to your bank account."},
            new String[] {"PAYMENT_RECEIVED", "100000000003",
                    "We received Rs {{amount}} towards your loan {{loan_no}} on {{date}}. Thank you."});

    private final JdbcTemplate jdbc;
    private final ProviderConfigService providers;
    private final Secrets secrets;
    private final Json json;

    DemoIntegrationSeeder(JdbcTemplate jdbc, ProviderConfigService providers, Secrets secrets, Json json) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.secrets = secrets;
        this.json = json;
    }

    @Override
    public void seed() {
        if (!providers.deploymentAllows(Simulator.CODE)) return;
        int added = 0;
        for (ProviderKind kind : ProviderKind.values()) added += provider(kind);
        added += property("nach.sponsor-bank-code", "TEST0000001");
        added += property("nach.utility-code", "NACH00000000000001");
        for (String[] t : SMS_TEMPLATES) added += smsTemplate(t[0], t[1], t[2]);
        if (added > 0) log.warn("demo tenant: {} simulator integration settings added (local development only)", added);
    }

    /** The simulator as the kind's provider, unless the kind was ever configured (a developer's own set-up stays). */
    private int provider(ProviderKind kind) {
        if (!jdbc.queryForList("SELECT 1 FROM integration.provider_config WHERE kind = ?", kind.name()).isEmpty()) return 0;
        ProviderCatalog.Spec spec = ProviderCatalog.spec(kind, Simulator.CODE);
        Map<String, String> sealed = new TreeMap<>();
        // generated here, kept only as ciphertext: POST /integrations/simulator/callbacks signs with it server-side
        if (spec.secrets().contains(Simulator.SECRET)) sealed.put(Simulator.SECRET, randomSecret());
        Map<String, Object> hints = new TreeMap<>();
        sealed.forEach((k, v) -> hints.put(k, Secrets.last4(v)));
        Map<String, String> settings = new TreeMap<>();
        if (spec.settings().contains("fromAddress")) settings.put("fromAddress", "no-reply@demo-nbfc.invalid");
        return jdbc.update("""
                INSERT INTO integration.provider_config (id, kind, provider, settings, secrets_cipher, secret_hints, version, updated_by)
                VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, 1, ?)
                """, UUID.randomUUID(), kind.name(), Simulator.CODE, json.write(settings),
                sealed.isEmpty() ? null : secrets.seal(json.write(sealed), ProviderConfigService.ROW_AAD), json.write(hints), ACTOR);
    }

    private static String randomSecret() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private int property(String key, String value) {
        return jdbc.update("INSERT INTO platform.system_property (key, value, updated_by) VALUES (?, ?, ?) ON CONFLICT (key) DO NOTHING",
                key, value, ACTOR);
    }

    /** Version 1 with its history row, as an approved template has. The DLT ids are placeholders, not registrations. */
    private int smsTemplate(String code, String dltTemplateId, String body) {
        int n = jdbc.update("""
                INSERT INTO integration.message_template (code, channel, language, category, body, dlt_entity_id, dlt_template_id,
                                                          dlt_header, status, version, updated_by)
                VALUES (?, 'SMS', 'en', 'TRANSACTIONAL', ?, '100000000001', ?, 'CBDEMO', 'ACTIVE', 1, ?)
                ON CONFLICT (code, channel, language) DO NOTHING
                """, code, body, dltTemplateId, ACTOR);
        if (n == 0) return 0;
        Map<String, Object> snapshot = new TreeMap<>(Map.of("code", code, "channel", "SMS", "language", "en", "category", "TRANSACTIONAL",
                "body", body, "dltEntityId", "100000000001", "dltTemplateId", dltTemplateId, "dltHeader", "CBDEMO", "status", "ACTIVE"));
        jdbc.update("""
                INSERT INTO integration.message_template_history (code, channel, language, version, snapshot, changed_by)
                VALUES (?, 'SMS', 'en', 1, ?::jsonb, ?) ON CONFLICT DO NOTHING
                """, code, json.write(snapshot), ACTOR);
        return n;
    }
}
