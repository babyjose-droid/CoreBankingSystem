package com.corebanking.integration.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.customer.CustomerContacts;
import com.corebanking.eod.EodStepProvider;
import com.corebanking.integration.core.MessageTemplate;
import com.corebanking.integration.core.RetrySchedule;
import com.corebanking.integration.core.WebhookEvents;
import com.corebanking.integration.core.provider.EmailSender;
import com.corebanking.integration.core.provider.MessageResult;
import com.corebanking.integration.core.provider.SmsSender;
import com.corebanking.kernel.EodEngine;
import com.corebanking.kernel.Masking;
import com.corebanking.lending.LoanEvents;
import com.corebanking.lending.LoanOperations;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.Json;
import com.corebanking.platform.TenantDataSources;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SMS and e-mail to borrowers (US-123).
 * <ul>
 *   <li><b>Templates</b> per tenant, through maker-checker (entity MESSAGE_TEMPLATE). A template belongs to one of
 *       the events below and may use only that event's placeholders. An SMS template carries the DLT entity id,
 *       header and content template id (TRAI TCCCPR 2018); {@code dltForm} in the API shows the text as it has
 *       to be registered with the operator.</li>
 *   <li><b>Rendering</b> is strict ({@link MessageTemplate}): a missing value or an over-long one stops the
 *       message; it is logged as suppressed, never sent half-filled.</li>
 *   <li><b>Consent</b>: {@code integration.message_allowed} — transactional messages about the customer's own
 *       loan go out under legitimate use; service messages respect the channel opt-out; promotional ones also
 *       need a current MARKETING consent ({@code customer.has_consent}).</li>
 *   <li><b>Sending</b> is from the outbox, with retries, under the tenant's rate limit
 *       ({@code notify.rate-limit-per-minute}); what is over the limit waits, it is not dropped.</li>
 *   <li>The <b>delivery log</b> keeps the recipient and the text encrypted and shows the recipient masked.</li>
 * </ul>
 * Events: LOAN_DISBURSED, PAYMENT_RECEIVED, PAYMENT_BOUNCED, DUE_REMINDER (T-n days, at end of day), NOC_ISSUED,
 * RATE_RESET (RBI 18-Aug-2023: the reset and its effect on EMI and tenure are communicated to the borrower; the
 * options open to the borrower are part of the lender's template text).
 */
@Service
class MessagingService implements OutboxConsumer, EodStepProvider {

    static final String ENTITY = "MESSAGE_TEMPLATE";
    private static final String RECIPIENT_AAD = "integration.message.recipient";
    private static final String BODY_AAD = "integration.message.body";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final Logger log = LoggerFactory.getLogger(MessagingService.class);

    /** Template codes and the placeholders each may use. */
    static final Map<String, Set<String>> EVENTS = Map.of(
            "LOAN_DISBURSED", Set.of("name", "loan_no", "amount", "net_amount", "date"),
            "PAYMENT_RECEIVED", Set.of("name", "loan_no", "amount", "date"),
            "PAYMENT_BOUNCED", Set.of("name", "loan_no", "amount", "due_date", "reason"),
            "DUE_REMINDER", Set.of("name", "loan_no", "amount", "due_date"),
            "NOC_ISSUED", Set.of("name", "loan_no", "date"),
            "RATE_RESET", Set.of("name", "loan_no", "old_rate", "new_rate", "old_emi", "new_emi", "old_tenure", "new_tenure"));

    private static final Map<String, String> TOPICS = Map.of(
            LoanEvents.DISBURSED, "LOAN_DISBURSED", LoanEvents.PAYMENT_RECEIVED, "PAYMENT_RECEIVED",
            WebhookEvents.PAYMENT_BOUNCED, "PAYMENT_BOUNCED", LoanEvents.NOC_ISSUED, "NOC_ISSUED", LoanEvents.RATE_RESET, "RATE_RESET");

    /** openapi.yaml#/components/schemas/MessageTemplateInput. */
    record Input(String code, String channel, String language, String category, String subject, String body, String dltEntityId,
                 String dltTemplateId, String dltHeader, String status) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final ProviderConfigService providers;
    private final CustomerContacts contacts;
    private final LoanOperations loans;
    private final Secrets secrets;
    private final TenantProps props;
    private final AuditLog audit;
    private final Json json;

    MessagingService(JdbcTemplate jdbc, ApprovalService approvals, ProviderConfigService providers, CustomerContacts contacts,
                     LoanOperations loans, Secrets secrets, TenantProps props, AuditLog audit, Json json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.providers = providers;
        this.contacts = contacts;
        this.loans = loans;
        this.secrets = secrets;
        this.props = props;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------------------------------------ templates
    @Transactional
    ApprovalRequest propose(Input in) {
        if (in == null || in.code() == null || !EVENTS.containsKey(in.code())) {
            throw ApiException.invalid("code must be one of " + EVENTS.keySet().stream().sorted().toList());
        }
        if (in.channel() == null || !List.of("SMS", "EMAIL").contains(in.channel())) throw ApiException.invalid("channel must be SMS or EMAIL");
        String language = in.language() == null ? "en" : in.language();
        if (!language.matches("[a-z]{2}")) throw ApiException.invalid("language is a two-letter code");
        String category = in.category() == null ? "TRANSACTIONAL" : in.category();
        if (!List.of("TRANSACTIONAL", "SERVICE", "PROMOTIONAL").contains(category)) throw ApiException.invalid("category must be TRANSACTIONAL, SERVICE or PROMOTIONAL");
        String status = in.status() == null ? "ACTIVE" : in.status();
        if (!List.of("ACTIVE", "RETIRED").contains(status)) throw ApiException.invalid("status must be ACTIVE or RETIRED");
        Set<String> allowed = EVENTS.get(in.code());
        try {
            for (String p : MessageTemplate.placeholders(in.body())) {
                if (!allowed.contains(p)) throw ApiException.invalid("placeholder '" + p + "' is not available for " + in.code() + "; use " + allowed.stream().sorted().toList());
            }
            if ("EMAIL".equals(in.channel())) {
                if (in.subject() == null || in.subject().isBlank() || in.subject().length() > 200) throw ApiException.invalid("subject is required for e-mail (at most 200 characters)");
                for (String p : MessageTemplate.placeholders(in.subject())) {
                    if (!allowed.contains(p)) throw ApiException.invalid("placeholder '" + p + "' is not available for " + in.code());
                }
            }
        } catch (MessageTemplate.TemplateException e) {
            throw ApiException.invalid(e.getMessage());
        }
        if ("SMS".equals(in.channel())) {
            if (in.dltEntityId() == null || !in.dltEntityId().matches("[0-9]{6,25}") || in.dltTemplateId() == null
                    || !in.dltTemplateId().matches("[0-9]{6,25}") || in.dltHeader() == null || !in.dltHeader().matches("[A-Za-z0-9]{3,11}")) {
                throw ApiException.invalid("an SMS template needs dltEntityId and dltTemplateId (digits) and dltHeader (the registered sender id)");
            }
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", in.code());
        p.put("channel", in.channel());
        p.put("language", language);
        p.put("category", category);
        p.put("subject", "EMAIL".equals(in.channel()) ? in.subject().trim() : null);
        p.put("body", in.body());
        p.put("dltEntityId", "SMS".equals(in.channel()) ? in.dltEntityId() : null);
        p.put("dltTemplateId", "SMS".equals(in.channel()) ? in.dltTemplateId() : null);
        p.put("dltHeader", "SMS".equals(in.channel()) ? in.dltHeader() : null);
        p.put("status", status);
        List<Map<String, Object>> current = templates().stream()
                .filter(t -> in.code().equals(t.get("code")) && in.channel().equals(t.get("channel")) && language.equals(t.get("language"))).toList();
        return approvals.propose(ENTITY, current.isEmpty() ? "CREATE" : "UPDATE", in.code() + "/" + in.channel() + "/" + language, p,
                current.isEmpty() ? null : current.get(0), null, null, null);
    }

    String apply(ApprovalRequest r) {
        Map<String, Object> p = r.payload();
        Integer version = jdbc.queryForObject("SELECT coalesce(max(version), 0) + 1 FROM integration.message_template_history WHERE code = ? AND channel = ? AND language = ?",
                Integer.class, p.get("code"), p.get("channel"), p.get("language"));
        jdbc.update("""
                INSERT INTO integration.message_template (code, channel, language, category, subject, body, dlt_entity_id, dlt_template_id,
                                                          dlt_header, status, version, approval_id, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (code, channel, language) DO UPDATE SET category = EXCLUDED.category, subject = EXCLUDED.subject, body = EXCLUDED.body,
                    dlt_entity_id = EXCLUDED.dlt_entity_id, dlt_template_id = EXCLUDED.dlt_template_id, dlt_header = EXCLUDED.dlt_header,
                    status = EXCLUDED.status, version = EXCLUDED.version, approval_id = EXCLUDED.approval_id, updated_by = EXCLUDED.updated_by,
                    updated_at = now()
                """, p.get("code"), p.get("channel"), p.get("language"), p.get("category"), p.get("subject"), p.get("body"), p.get("dltEntityId"),
                p.get("dltTemplateId"), p.get("dltHeader"), p.get("status"), version, r.id(), r.maker());
        jdbc.update("INSERT INTO integration.message_template_history (code, channel, language, version, snapshot, changed_by, approval_id) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)",
                p.get("code"), p.get("channel"), p.get("language"), version, json.write(p), r.maker(), r.id());
        audit.record(CurrentUser.username(), "MESSAGE_TEMPLATE", ENTITY, String.valueOf(r.entityId()), Map.of("version", String.valueOf(version)));
        return r.entityId() + " v" + version;
    }

    List<Map<String, Object>> templates() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT code, channel, language, category, subject, body, dlt_entity_id AS "dltEntityId", dlt_template_id AS "dltTemplateId",
                       dlt_header AS "dltHeader", status, version, updated_by AS "updatedBy", updated_at AS "updatedAt"
                  FROM integration.message_template ORDER BY code, channel, language
                """);
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("dltForm", "SMS".equals(r.get("channel")) ? MessageTemplate.dltForm((String) r.get("body")) : null);
            return m;
        }).toList();
    }

    Map<String, Object> variables() {
        Map<String, Object> m = new java.util.TreeMap<>();
        EVENTS.forEach((k, v) -> m.put(k, v.stream().sorted().toList()));
        return m;
    }

    // ------------------------------------------------------------------------------------------------ queueing
    private static String money(Object v) {
        return v == null ? "" : new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String date(Object v) {
        return v == null ? "" : DATE.format(LocalDate.parse(String.valueOf(v)));
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    @Override
    public void on(long outboxId, String topic, String aggregateId, Map<String, Object> p, OffsetDateTime at) {
        String code = TOPICS.get(topic);
        if (code == null || p.get("customerId") == null || p.get("loanId") == null) return;
        Map<String, String> v = new LinkedHashMap<>();
        v.put("loan_no", text(p.get("loanNo")));
        switch (code) {
            case "LOAN_DISBURSED" -> {
                v.put("amount", money(p.get("amount")));
                v.put("net_amount", money(p.get("netDisbursed")));
                v.put("date", date(p.get("businessDate")));
            }
            case "PAYMENT_RECEIVED" -> {
                v.put("amount", money(p.get("amount")));
                v.put("date", date(p.get("valueDate")));
            }
            case "PAYMENT_BOUNCED" -> {
                v.put("amount", money(p.get("amount")));
                v.put("due_date", date(p.get("dueDate")));
                v.put("reason", text(p.get("returnReason")));
            }
            case "NOC_ISSUED" -> v.put("date", date(p.get("businessDate")));
            default -> {
                v.put("old_rate", text(p.get("rateBefore")));
                v.put("new_rate", text(p.get("rateAfter")));
                v.put("old_emi", money(p.get("emiBefore")));
                v.put("new_emi", money(p.get("emiAfter")));
                v.put("old_tenure", text(p.get("remainingBefore")));
                v.put("new_tenure", text(p.get("remainingAfter")));
            }
        }
        // a letter is announced once per loan, however often it is printed; other events once per outbox row
        String key = "NOC_ISSUED".equals(code) ? "noc:" + p.get("loanId") : topic + ":" + outboxId;
        enqueue(code, UUID.fromString(String.valueOf(p.get("customerId"))), UUID.fromString(String.valueOf(p.get("loanId"))), key, v);
    }

    /** Queues one message per active template (channel) of the code. Safe to call twice for the same event key. */
    void enqueue(String code, UUID customerId, UUID loanId, String eventKey, Map<String, String> values) {
        List<Map<String, Object>> templates = jdbc.queryForList(
                "SELECT * FROM integration.message_template WHERE code = ? AND status = 'ACTIVE' AND language = 'en'", code);
        if (templates.isEmpty()) return;
        CustomerContacts.Contact c = contacts.of(customerId);
        for (Map<String, Object> t : templates) {
            String channel = (String) t.get("channel");
            if (!jdbc.queryForList("SELECT 1 FROM integration.message WHERE template_code = ? AND channel = ? AND event_key = ?", code, channel, eventKey).isEmpty()) {
                continue;
            }
            boolean sms = "SMS".equals(channel);
            int limit = sms ? MessageTemplate.SMS_VALUE_LIMIT : MessageTemplate.EMAIL_VALUE_LIMIT;
            String recipient = sms ? c.mobile() : c.email();
            String suppress = jdbc.queryForObject("SELECT integration.message_allowed(?, ?, ?)", String.class, customerId, channel, t.get("category"));
            String body = null;
            String error = null;
            if (suppress == null && (recipient == null || recipient.isBlank() || !"ACTIVE".equals(c.status()))) suppress = "NO_RECIPIENT";
            if (suppress == null) {
                try {
                    Map<String, String> all = new LinkedHashMap<>(values);
                    String name = c.displayName() == null ? "" : c.displayName().replaceAll("[\\p{Cntrl}{}]", " ").trim();
                    all.put("name", name.length() > limit ? name.substring(0, limit) : name);
                    String text = (String) t.get("body");
                    body = MessageTemplate.render(text, only(all, MessageTemplate.placeholders(text)), limit);
                    if (!sms) {
                        String subject = (String) t.get("subject");
                        body = MessageTemplate.render(subject, only(all, MessageTemplate.placeholders(subject)), limit) + "\n" + body;
                    }
                } catch (MessageTemplate.TemplateException e) {
                    suppress = "RENDER_ERROR";
                    error = e.getMessage();
                }
            }
            jdbc.update("""
                    INSERT INTO integration.message (id, template_code, channel, language, category, customer_id, loan_id, event_key,
                        recipient_cipher, recipient_masked, body_cipher, status, suppress_reason, last_error, next_attempt_at)
                    VALUES (?, ?, ?, 'en', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END)
                    ON CONFLICT (template_code, channel, event_key) DO NOTHING
                    """, UUID.randomUUID(), code, channel, t.get("category"), customerId, loanId, eventKey,
                    suppress == null ? secrets.seal(recipient, RECIPIENT_AAD) : null,
                    recipient == null ? null : sms ? Masking.mobile(recipient) : Masking.email(recipient),
                    suppress == null ? secrets.seal(body, BODY_AAD) : null, suppress == null ? "QUEUED" : "SUPPRESSED", suppress, error, suppress == null);
        }
    }

    /** The values the text actually uses: an event may carry more than a given template needs. */
    private static Map<String, String> only(Map<String, String> all, Set<String> used) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : used) {
            if (all.containsKey(k)) m.put(k, all.get(k));
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------------ reminders
    @Override
    public int order() {
        return 310;
    }

    @Override
    public List<EodEngine.Step> steps(JdbcTemplate tenantJdbc, String tenant) {
        return List.of(new EodEngine.ItemStep() {
            @Override public String name() { return "Due reminders"; }

            @Override public List<String> items(EodEngine.Context ctx) { return List.of("due-reminders"); }

            @Override
            public void process(EodEngine.Context ctx, String item) {
                TenantDataSources.runAs(tenant, () -> remind(ctx.businessDate()));
            }
        });
    }

    /** Queues a reminder for every instalment due {@code notify.due-reminder.lead-days} days after the business date. */
    void remind(LocalDate businessDate) {
        LocalDate due = businessDate.plusDays(props.number("notify.due-reminder.lead-days", 3, 0, 15));
        for (LoanOperations.Due d : loans.duesOn(jdbc, due)) {
            try {
                enqueue("DUE_REMINDER", d.customerId(), d.loanId(), "due:" + d.loanId() + ":" + d.dueDate(),
                        Map.of("loan_no", d.loanNo(), "amount", money(d.amount()), "due_date", DATE.format(d.dueDate())));
            } catch (RuntimeException e) {
                log.warn("due reminder not queued for a loan: {}", e.getClass().getSimpleName());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ sending
    private record Claim(UUID id, String channel, String code, byte[] recipient, byte[] body, int attempts) {}

    void send() {
        int limit = props.number("notify.rate-limit-per-minute", 60, 1, 6000);
        Integer recent = jdbc.queryForObject("SELECT count(*) FROM integration.message WHERE sent_at > now() - interval '1 minute'", Integer.class);
        int budget = Math.min(50, limit - (recent == null ? 0 : recent));
        if (budget <= 0) return;                         // over the limit: the queue waits for the next minute
        List<Claim> claims = jdbc.query("""
                UPDATE integration.message SET next_attempt_at = now() + interval '3 minutes', attempts = attempts + 1
                 WHERE id IN (SELECT id FROM integration.message WHERE next_attempt_at <= now() AND status = 'QUEUED'
                               ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING id, channel, template_code, recipient_cipher, body_cipher, attempts
                """, (rs, i) -> new Claim(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBytes(4), rs.getBytes(5), rs.getInt(6)),
                budget);
        RetrySchedule schedule = RetrySchedule.PROVIDER.withMaxAttempts(props.number("notify.max-attempts", 5, 1, 20));
        for (Claim c : claims) {
            MessageResult r;
            String provider = null;
            try {
                String to = secrets.open(c.recipient(), RECIPIENT_AAD);
                String body = secrets.open(c.body(), BODY_AAD);
                if ("SMS".equals(c.channel())) {
                    ProviderConfigService.Active<SmsSender> sms = providers.sms();
                    if (sms == null) {
                        r = MessageResult.refused("no SMS provider is active", true);
                    } else {
                        provider = sms.code();
                        Map<String, Object> t = jdbc.queryForMap(
                                "SELECT dlt_entity_id, dlt_template_id, dlt_header FROM integration.message_template WHERE code = ? AND channel = 'SMS' AND language = 'en'",
                                c.code());
                        r = sms.port().send(new SmsSender.Sms(to, body, (String) t.get("dlt_entity_id"), (String) t.get("dlt_template_id"),
                                (String) t.get("dlt_header"), c.id().toString()));
                    }
                } else {
                    ProviderConfigService.Active<EmailSender> email = providers.email();
                    if (email == null) {
                        r = MessageResult.refused("no e-mail provider is active", true);
                    } else {
                        provider = email.code();
                        int nl = body.indexOf('\n');
                        r = email.port().send(new EmailSender.Email(to, body.substring(0, nl), body.substring(nl + 1),
                                email.settings().setting("fromAddress", null), c.id().toString()));
                    }
                }
            } catch (RuntimeException e) {
                r = MessageResult.refused(e.getClass().getSimpleName(), true);
            }
            if (r.accepted()) {
                jdbc.update("UPDATE integration.message SET status = 'SENT', provider = ?, provider_ref = ?, next_attempt_at = NULL, last_error = NULL, sent_at = now() WHERE id = ?",
                        provider, r.providerRef(), c.id());
            } else if (!r.retryable() || schedule.exhausted(c.attempts())) {
                jdbc.update("UPDATE integration.message SET status = 'FAILED', provider = ?, next_attempt_at = NULL, last_error = ? WHERE id = ?",
                        provider, r.error(), c.id());
            } else {
                jdbc.update("UPDATE integration.message SET next_attempt_at = now() + make_interval(secs => ?), last_error = ? WHERE id = ?",
                        schedule.delaySeconds(c.attempts(), ThreadLocalRandom.current().nextDouble()), r.error(), c.id());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ log, opt-out
    /** The delivery log: who (masked), what template, what happened. Never the text. */
    List<Map<String, Object>> log(UUID customerId, UUID loanId, String status) {
        return jdbc.queryForList("""
                SELECT m.id, m.template_code AS "templateCode", m.channel, m.category, m.customer_id AS "customerId", m.loan_id AS "loanId",
                       m.recipient_masked AS "recipientMasked", m.status, m.suppress_reason AS "suppressReason", m.provider,
                       m.provider_ref AS "providerRef", m.attempts, m.last_error AS "lastError", m.created_at AS "createdAt", m.sent_at AS "sentAt"
                  FROM integration.message m
                 WHERE (?::uuid IS NULL OR m.customer_id = ?) AND (?::uuid IS NULL OR m.loan_id = ?) AND (?::text IS NULL OR m.status = ?)
                   AND (m.loan_id IS NULL OR EXISTS (SELECT 1 FROM lending.loan_account l WHERE l.id = m.loan_id
                                                       AND l.branch_code IN (SELECT branch_code FROM platform.visible_branches(?))))
                 ORDER BY m.created_at DESC LIMIT 500
                """, customerId, customerId, loanId, loanId, status, status, CurrentUser.username());
    }

    @Transactional
    Map<String, Object> optOut(UUID customerId, String channel, boolean optOut, String source) {
        if (channel == null || !List.of("SMS", "EMAIL").contains(channel)) throw ApiException.invalid("channel must be SMS or EMAIL");
        contacts.of(customerId);
        if (optOut) {
            jdbc.update("INSERT INTO integration.message_opt_out (customer_id, channel, source, recorded_by) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    customerId, channel, source == null || source.isBlank() ? "STAFF" : source.trim(), CurrentUser.username());
        } else {
            jdbc.update("DELETE FROM integration.message_opt_out WHERE customer_id = ? AND channel = ?", customerId, channel);
        }
        audit.record(CurrentUser.username(), optOut ? "MESSAGE_OPT_OUT" : "MESSAGE_OPT_IN", "CUSTOMER", customerId.toString(), Map.of("channel", channel));
        return Map.of("customerId", customerId, "optedOut", jdbc.queryForList(
                "SELECT channel FROM integration.message_opt_out WHERE customer_id = ? ORDER BY channel", String.class, customerId));
    }

    @Component
    static class Task implements IntegrationTask {
        private final MessagingService messaging;

        Task(@Lazy MessagingService messaging) {
            this.messaging = messaging;
        }

        @Override public String name() { return "message-send"; }

        @Override public void run() { messaging.send(); }
    }

    @Component
    static class Applier implements ApprovalApplier {
        private final MessagingService messaging;

        Applier(@Lazy MessagingService messaging) {
            this.messaging = messaging;
        }

        @Override public String entityType() { return ENTITY; }

        @Override public String apply(ApprovalRequest r) { return messaging.apply(r); }
    }
}
