package com.corebanking.integration.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.integration.core.Lifecycle;
import com.corebanking.integration.core.RetrySchedule;
import com.corebanking.integration.core.WebhookEvents;
import com.corebanking.integration.core.provider.InboundEvent;
import com.corebanking.integration.core.provider.MandateProvider;
import com.corebanking.integration.core.provider.ProviderException;
import com.corebanking.kernel.Csv;
import com.corebanking.lending.LoanOperations;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.Json;
import com.corebanking.platform.Outbox;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The e-mandate register (US-070): one mandate per loan in registration or in force, with its UMRN, limits,
 * validity, sponsor bank and utility codes. The debit account is stored encrypted and shown masked.
 * <p>
 * Registration status arrives three ways, all through the same lifecycle check: a provider callback, a status
 * poll, or — where the sponsor bank reports registrations in a file or by mail — an operator's update or CSV
 * upload ({@code mandate_ref,status,umrn,reject_code,reject_reason}).
 */
@Service
class MandateService {

    static final String ACCOUNT_AAD = "integration.mandate.account";
    static final String NAME_AAD = "integration.mandate.holder_name";

    /** openapi.yaml#/components/schemas/MandateInput. */
    record Input(String holderName, String accountNumber, String ifsc, String accountType, BigDecimal maxAmount, String frequency,
                 LocalDate startDate, LocalDate endDate, String sponsorBankCode, String utilityCode) {}

    /** openapi.yaml#/components/schemas/MandateStatusUpdate. */
    record StatusUpdate(String status, String umrn, String rejectCode, String rejectReason) {}

    private final JdbcTemplate jdbc;
    private final Secrets secrets;
    private final ProviderConfigService providers;
    private final LoanOperations loans;
    private final BranchScope scope;
    private final TenantProps props;
    private final Outbox outbox;
    private final AuditLog audit;
    private final Json json;
    private final TransactionTemplate tx;

    MandateService(JdbcTemplate jdbc, Secrets secrets, ProviderConfigService providers, LoanOperations loans, BranchScope scope,
                   TenantProps props, Outbox outbox, AuditLog audit, Json json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.providers = providers;
        this.loans = loans;
        this.scope = scope;
        this.props = props;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    @Transactional
    Map<String, Object> register(UUID loanId, Input in) {
        LoanOperations.LoanRef loan = loans.find(loanId);
        scope.requireRecord(loan.branch(), "loan " + loanId);
        if (in == null) throw ApiException.invalid("the mandate details are required");
        if (in.holderName() == null || in.holderName().isBlank() || in.holderName().length() > 100) throw ApiException.invalid("holderName is required");
        String account = in.accountNumber() == null ? "" : in.accountNumber().replace(" ", "");
        if (!account.matches("[A-Za-z0-9]{6,35}")) throw ApiException.invalid("accountNumber must be 6 to 35 letters or digits");
        String ifsc = in.ifsc() == null ? "" : in.ifsc().trim().toUpperCase(Locale.ROOT);
        if (!ifsc.matches("[A-Z]{4}0[A-Z0-9]{6}")) throw ApiException.invalid("ifsc is not a valid IFSC");
        String type = in.accountType() == null ? "SB" : in.accountType();
        if (!List.of("SB", "CA", "CC", "OT").contains(type)) throw ApiException.invalid("accountType must be SB, CA, CC or OT");
        if (in.maxAmount() == null || in.maxAmount().signum() <= 0) throw ApiException.invalid("maxAmount must be positive");
        String frequency = in.frequency() == null ? "MONTHLY" : in.frequency();
        if (!List.of("MONTHLY", "QUARTERLY", "HALF_YEARLY", "YEARLY", "AS_PRESENTED").contains(frequency)) {
            throw ApiException.invalid("frequency must be MONTHLY, QUARTERLY, HALF_YEARLY, YEARLY or AS_PRESENTED");
        }
        if (in.startDate() == null) throw ApiException.invalid("startDate is required");
        if (in.endDate() != null && !in.endDate().isAfter(in.startDate())) throw ApiException.invalid("endDate must be after startDate");
        String sponsor = in.sponsorBankCode() != null ? in.sponsorBankCode() : props.text("nach.sponsor-bank-code", null);
        String utility = in.utilityCode() != null ? in.utilityCode() : props.text("nach.utility-code", null);
        if (sponsor == null || !sponsor.matches("[A-Z0-9]{1,11}") || utility == null || !utility.matches("[A-Z0-9]{1,18}")) {
            throw ApiException.invalid("sponsorBankCode and utilityCode are required (or set nach.sponsor-bank-code and nach.utility-code)");
        }
        if (List.of("CLOSED", "CANCELLED", "WRITTEN_OFF").contains(loan.status())) throw ApiException.conflict("loan is " + loan.status());
        if (!jdbc.queryForList("SELECT 1 FROM integration.mandate WHERE loan_id = ? AND status IN ('DRAFT','SUBMITTED','ACTIVE','SUSPENDED')", loanId).isEmpty()) {
            throw ApiException.conflict("the loan already has a mandate in registration or in force; cancel it first");
        }
        Integer seq = jdbc.queryForObject("SELECT count(*) + 1 FROM integration.mandate WHERE loan_id = ?", Integer.class, loanId);
        UUID id = UUID.randomUUID();
        String ref = "MD" + loan.loanNo() + "N" + seq;
        jdbc.update("""
                INSERT INTO integration.mandate (id, mandate_ref, loan_id, loan_no, customer_id, branch_code, max_amount, frequency, start_date,
                    end_date, holder_name_cipher, account_cipher, account_last4, ifsc, account_type, sponsor_bank_code, utility_code, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ref, loanId, loan.loanNo(), loan.customerId(), loan.branch(), in.maxAmount(), frequency, in.startDate(), in.endDate(),
                secrets.seal(in.holderName().trim(), NAME_AAD), secrets.seal(account, ACCOUNT_AAD), Secrets.last4(account), ifsc, type,
                sponsor, utility, CurrentUser.username());
        event(id, null, "DRAFT", "API", Map.of());
        audit.record(CurrentUser.username(), "MANDATE_REGISTER", "MANDATE", ref, Map.of("loanNo", loan.loanNo(), "accountLast4", Secrets.last4(account)));
        return one(id);
    }

    private void event(UUID id, String from, String to, String source, Map<String, ?> detail) {
        jdbc.update("INSERT INTO integration.mandate_event (mandate_id, from_status, to_status, source, actor, detail) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                id, from, to, source, CurrentUser.username(), json.write(detail));
    }

    /** Applies a status in its own transaction; returns what was done. */
    Lifecycle.Decision move(UUID id, Lifecycle.Mandate to, String umrn, String rejectCode, String rejectReason, String source) {
        return tx.execute(s -> {
            Map<String, Object> m = jdbc.queryForMap("SELECT status, umrn, mandate_ref, loan_id, loan_no, customer_id, account_last4 FROM integration.mandate WHERE id = ? FOR UPDATE", id);
            Lifecycle.Mandate from = Lifecycle.Mandate.valueOf((String) m.get("status"));
            Lifecycle.Decision d = Lifecycle.decide(from, to);
            if (d != Lifecycle.Decision.APPLY) {
                if (d == Lifecycle.Decision.CONFLICT) event(id, from.name(), from.name(), source, Map.of("conflict", to.name()));
                return d;
            }
            String newUmrn = m.get("umrn") != null ? (String) m.get("umrn") : umrn;
            if (to == Lifecycle.Mandate.ACTIVE && (newUmrn == null || !newUmrn.matches("[A-Z0-9]{20}"))) {
                throw ApiException.invalid("an active mandate needs its UMRN (20 letters or digits)");
            }
            boolean waiting = to == Lifecycle.Mandate.SUBMITTED;
            jdbc.update("""
                    UPDATE integration.mandate SET status = ?, umrn = ?, reject_code = ?, reject_reason = ?, attempts = 0, last_error = NULL,
                           next_attempt_at = CASE WHEN ? THEN now() + interval '5 minutes' END, updated_at = now() WHERE id = ?
                    """, to.name(), newUmrn, rejectCode, rejectReason, waiting, id);
            event(id, from.name(), to.name(), source, Map.of("rejectCode", String.valueOf(rejectCode)));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("mandateRef", m.get("mandate_ref"));
            p.put("loanId", String.valueOf(m.get("loan_id")));
            p.put("loanNo", m.get("loan_no"));
            p.put("customerId", String.valueOf(m.get("customer_id")));
            p.put("status", to.name());
            p.put("previousStatus", from.name());
            p.put("umrn", newUmrn);
            p.put("rejectCode", rejectCode);
            p.put("rejectReason", rejectReason);
            p.put("debitAccountMasked", "XXXXXXXX" + m.get("account_last4"));
            outbox.publish(jdbc, WebhookEvents.MANDATE_STATUS, (String) m.get("mandate_ref"), p);
            return d;
        });
    }

    String applyInbound(InboundEvent e) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM integration.mandate WHERE mandate_ref = ? OR (?::text IS NOT NULL AND provider_ref = ?)",
                UUID.class, e.reference(), e.providerRef(), e.providerRef());
        if (ids.isEmpty()) return "IGNORED: no mandate with this reference";
        Lifecycle.Mandate to;
        try {
            to = Lifecycle.Mandate.valueOf(e.status());
        } catch (IllegalArgumentException x) {
            return "IGNORED: unknown mandate status";
        }
        return move(ids.get(0), to, e.umrn(), e.reasonCode(), e.reason(), "WEBHOOK").name();
    }

    /** Operations (or the sponsor bank's report): set the status by hand. */
    Map<String, Object> update(UUID id, StatusUpdate u, String source) {
        one(id);
        if (u == null || u.status() == null) throw ApiException.invalid("status is required");
        Lifecycle.Mandate to;
        try {
            to = Lifecycle.Mandate.valueOf(u.status());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("unknown mandate status " + u.status());
        }
        Lifecycle.Decision d = move(id, to, u.umrn() == null ? null : u.umrn().trim().toUpperCase(Locale.ROOT), u.rejectCode(), u.rejectReason(), source);
        if (d == Lifecycle.Decision.CONFLICT || d == Lifecycle.Decision.STALE) throw ApiException.conflict("the mandate cannot become " + to + " from its current status");
        audit.record(CurrentUser.username(), "MANDATE_STATUS", "MANDATE", id.toString(), Map.of("status", to.name(), "source", source));
        return one(id);
    }

    /** Registration statuses from a file: every row is applied on its own and reported. */
    List<Map<String, Object>> upload(String csv) {
        Csv.Table t;
        try {
            t = Csv.parse(csv, List.of("mandate_ref", "status"), 5000);
        } catch (Csv.CsvException e) {
            throw ApiException.invalid(e.getMessage());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Csv.Row r : t.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("line", r.line());
            row.put("mandateRef", r.get("mandate_ref"));
            try {
                List<UUID> ids = jdbc.queryForList("SELECT id FROM integration.mandate WHERE mandate_ref = ? AND branch_code" + BranchScope.SQL_VISIBLE,
                        UUID.class, r.get("mandate_ref"), scope.user());
                if (ids.isEmpty()) throw ApiException.notFound("mandate");
                update(ids.get(0), new StatusUpdate(r.get("status"), t.header().contains("umrn") ? r.get("umrn") : null,
                        t.header().contains("reject_code") ? r.get("reject_code") : null,
                        t.header().contains("reject_reason") ? r.get("reject_reason") : null), "FILE");
                row.put("ok", true);
            } catch (ApiException e) {
                row.put("ok", false);
                row.put("error", e.getMessage());
            }
            out.add(row);
        }
        return out;
    }

    /** Sends DRAFT mandates to the provider and polls SUBMITTED ones. Without a provider they wait for an update by hand. */
    void work() {
        ProviderConfigService.Active<MandateProvider> provider = providers.mandate();
        if (provider == null) return;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                UPDATE integration.mandate SET next_attempt_at = now() + interval '3 minutes', attempts = attempts + 1
                 WHERE id IN (SELECT id FROM integration.mandate WHERE next_attempt_at <= now() AND status IN ('DRAFT','SUBMITTED')
                               ORDER BY next_attempt_at LIMIT 20 FOR UPDATE SKIP LOCKED)
                RETURNING *
                """);
        for (Map<String, Object> m : rows) {
            UUID id = (UUID) m.get("id");
            int attempts = ((Number) m.get("attempts")).intValue();
            try {
                if ("DRAFT".equals(m.get("status"))) {
                    java.sql.Date end = (java.sql.Date) m.get("end_date");
                    MandateProvider.Submitted s = provider.port().register(new MandateProvider.Registration((String) m.get("mandate_ref"),
                            secrets.open((byte[]) m.get("holder_name_cipher"), NAME_AAD), secrets.open((byte[]) m.get("account_cipher"), ACCOUNT_AAD),
                            (String) m.get("ifsc"), (String) m.get("account_type"), (BigDecimal) m.get("max_amount"), (String) m.get("frequency"),
                            ((java.sql.Date) m.get("start_date")).toLocalDate(), end == null ? null : end.toLocalDate(),
                            (String) m.get("sponsor_bank_code"), (String) m.get("utility_code")));
                    jdbc.update("UPDATE integration.mandate SET provider = ?, provider_ref = ?, authentication_url = ? WHERE id = ?",
                            provider.code(), s.providerRef(), s.authenticationUrl(), id);
                    move(id, Lifecycle.Mandate.SUBMITTED, null, null, null, "API");
                } else {
                    MandateProvider.State st = provider.port().status((String) m.get("mandate_ref"), (String) m.get("provider_ref"));
                    if (move(id, st.status(), st.umrn(), st.rejectCode(), st.rejectReason(), "POLL") != Lifecycle.Decision.APPLY) {
                        later(id, attempts, RetrySchedule.POSTING, "still with the provider");
                    }
                }
            } catch (ProviderException e) {
                later(id, attempts, RetrySchedule.PROVIDER, e.getMessage());
            }
        }
    }

    private void later(UUID id, int attempts, RetrySchedule schedule, String why) {
        if (schedule.exhausted(attempts)) {
            jdbc.update("UPDATE integration.mandate SET next_attempt_at = NULL, last_error = ? WHERE id = ?", "gave up: " + why, id);
        } else {
            jdbc.update("UPDATE integration.mandate SET next_attempt_at = now() + make_interval(secs => ?), last_error = ? WHERE id = ?",
                    schedule.delaySeconds(attempts, ThreadLocalRandom.current().nextDouble()), why, id);
        }
    }

    private static final String SELECT = """
            SELECT m.id, m.mandate_ref AS "mandateRef", m.loan_id AS "loanId", m.loan_no AS "loanNo", m.umrn, m.status,
                   m.max_amount::text AS "maxAmount", m.frequency, m.start_date::text AS "startDate", m.end_date::text AS "endDate",
                   'XXXXXXXX' || m.account_last4 AS "debitAccountMasked", m.ifsc, m.account_type AS "accountType",
                   m.sponsor_bank_code AS "sponsorBankCode", m.utility_code AS "utilityCode", m.provider,
                   m.authentication_url AS "authenticationUrl", m.reject_code AS "rejectCode", m.reject_reason AS "rejectReason",
                   m.last_error AS "lastError", m.created_by AS "createdBy", m.created_at AS "createdAt", m.updated_at AS "updatedAt"
              FROM integration.mandate m WHERE m.branch_code IN (SELECT branch_code FROM platform.visible_branches(?))""";

    Map<String, Object> one(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " AND m.id = ?", scope.user(), id);
        if (rows.isEmpty()) throw ApiException.notFound("mandate " + id);
        Map<String, Object> m = new LinkedHashMap<>(rows.get(0));
        m.put("events", jdbc.queryForList(
                "SELECT at, from_status AS \"from\", to_status AS \"to\", source, actor FROM integration.mandate_event WHERE mandate_id = ? ORDER BY id", id));
        return m;
    }

    /** Mandates of a loan, the one in force first (US-070: mandate status on the loan). */
    List<Map<String, Object>> ofLoan(UUID loanId) {
        return jdbc.queryForList(SELECT + " AND m.loan_id = ? ORDER BY (m.status IN ('ACTIVE','SUSPENDED')) DESC, m.created_at DESC", scope.user(), loanId);
    }

    List<Map<String, Object>> search(String status) {
        return jdbc.queryForList(SELECT + " AND (?::text IS NULL OR m.status = ?) ORDER BY m.created_at DESC LIMIT 500", scope.user(), status, status);
    }

    @Component
    static class Task implements IntegrationTask {
        private final MandateService mandates;

        Task(@Lazy MandateService mandates) {
            this.mandates = mandates;
        }

        @Override public String name() { return "mandate-registration"; }

        @Override public void run() { mandates.work(); }
    }
}
