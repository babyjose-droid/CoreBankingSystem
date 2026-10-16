package com.corebanking.deposits.internal;

import com.corebanking.deposits.DepositRules;
import com.corebanking.deposits.TdsOnInterest;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tax deducted at source on deposit interest (P4-3b): Form 121 declarations, through maker-checker, and a depositor's
 * tax record for a tax year (interest credited and tax deducted, per account and quarter, with the basis of each
 * deduction). The deduction itself is made by the day-end ({@link TaxAtSource}).
 */
@Service
public class DepositTaxService {

    static final String ENTITY = "TDS_DECLARATION";

    /** openapi.yaml#/components/schemas/TaxDeclarationInput. */
    public record DeclarationInput(UUID customerId, Integer taxYear, LocalDate receivedOn, String reference) {}

    /** What a declaration request carries. */
    public record Declaration(String customerId, String customerNo, int taxYear, String receivedOn, String reference, String declarationId,
                              String reason) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final BusinessDays days;
    private final BranchScope scope;
    private final DepositRuleSets rules;
    private final Json json;

    DepositTaxService(JdbcTemplate jdbc, ApprovalService approvals, BusinessDays days, BranchScope scope, DepositRuleSets rules, Json json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.days = days;
        this.scope = scope;
        this.rules = rules;
        this.json = json;
    }

    private Map<String, Object> customer(UUID id) {
        if (id == null) throw ApiException.invalid("customerId is required");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, customer_no, customer_type, home_branch, display_name, pan_hash IS NOT NULL AS pan FROM customer.customer WHERE id = ?
                """, id);
        if (rows.isEmpty()) throw ApiException.notFound("customer " + id);
        scope.requireRecord((String) rows.get(0).get("home_branch"), "customer " + id);
        return rows.get(0);
    }

    /** Proposes recording Form 121 for a depositor and tax year (the current one by default, or the next). */
    @Transactional
    public ApprovalRequest proposeDeclaration(DeclarationInput in, String idempotencyKey) {
        if (in == null) throw ApiException.invalid("the declaration is described in the body");
        Map<String, Object> c = customer(in.customerId());
        LocalDate today = days.requireOpen();
        int current = TdsOnInterest.taxYearStarting(today);
        int year = in.taxYear() == null ? current : in.taxYear();
        List<String> problems = new ArrayList<>();
        if (!"INDIVIDUAL".equals(c.get("customer_type"))) problems.add("Form 121 is a declaration by an individual");
        if (!Boolean.TRUE.equals(c.get("pan"))) problems.add("a declaration without a PAN is not valid; record the customer's PAN first");
        if (year != current && year != current + 1) {
            problems.add("a declaration is recorded for the current tax year (" + label(current) + ") or the next");
        }
        LocalDate received = in.receivedOn() == null ? today : in.receivedOn();
        if (received.isAfter(today)) problems.add("the date received is after the business date");
        String reference = in.reference() == null || in.reference().isBlank() ? null : in.reference().trim();
        if (reference != null && reference.length() > 60) problems.add("the reference is longer than 60 characters");
        if (!jdbc.queryForList("SELECT 1 FROM deposits.tds_declaration WHERE customer_id = ? AND tax_year = ? AND status = 'ACTIVE'",
                in.customerId(), year).isEmpty()) {
            problems.add("Form 121 for " + label(year) + " is already recorded for this customer");
        }
        DepositAccountService.refuseIfAny("the declaration cannot be recorded", problems);
        Declaration d = new Declaration(in.customerId().toString(), (String) c.get("customer_no"), year, received.toString(), reference, null, null);
        return approvals.propose(ENTITY, "RECORD", in.customerId().toString(), json.toMap(d), null, null, (String) c.get("home_branch"), idempotencyKey);
    }

    /** Proposes revoking a declaration, with a reason: tax is deducted again from the next credit (and caught up). */
    @Transactional
    public ApprovalRequest proposeRevoke(UUID declarationId, DepositMoneyService.ReasonInput in, String idempotencyKey) {
        Map<String, Object> d = declaration(declarationId);
        Map<String, Object> c = customer((UUID) d.get("customer_id"));
        List<String> problems = new ArrayList<>();
        if (!"ACTIVE".equals(d.get("status"))) problems.add("the declaration is already revoked");
        String reason = in == null || in.reason() == null || in.reason().isBlank() ? null : in.reason().trim();
        if (reason == null) problems.add("a reason is required");
        else if (reason.length() > 500) problems.add("the reason is longer than 500 characters");
        DepositAccountService.refuseIfAny("the declaration cannot be revoked", problems);
        Declaration r = new Declaration(String.valueOf(d.get("customer_id")), (String) c.get("customer_no"), ((Number) d.get("tax_year")).intValue(),
                String.valueOf(d.get("received_on")), (String) d.get("reference"), declarationId.toString(), reason);
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("taxYear", d.get("tax_year"));
        current.put("receivedOn", String.valueOf(d.get("received_on")));
        current.put("reference", d.get("reference"));
        return approvals.propose(ENTITY, "REVOKE", declarationId.toString(), json.toMap(r), current, null, (String) c.get("home_branch"), idempotencyKey);
    }

    String apply(ApprovalRequest r) {
        Declaration d = json.convert(r.payload(), Declaration.class);
        LocalDate today = days.requireOpen();
        if ("REVOKE".equals(r.action())) {
            int n = jdbc.update("""
                    UPDATE deposits.tds_declaration SET status = 'REVOKED', revoked_on = ?, revoked_by = ?, revoke_reason = ?, revoke_approval_id = ?
                     WHERE id = ? AND status = 'ACTIVE'
                    """, today, r.maker(), d.reason(), r.id(), UUID.fromString(d.declarationId()));
            if (n == 0) throw ApiException.conflict("the declaration is already revoked");
            return d.declarationId();
        }
        if (!jdbc.queryForList("SELECT 1 FROM deposits.tds_declaration WHERE customer_id = ?::uuid AND tax_year = ? AND status = 'ACTIVE'",
                d.customerId(), d.taxYear()).isEmpty()) {
            throw ApiException.conflict("Form 121 for " + label(d.taxYear()) + " is already recorded for this customer");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deposits.tds_declaration (id, customer_id, tax_year, reference, received_on, recorded_by, approval_id)
                VALUES (?, ?::uuid, ?, ?, ?::date, ?, ?)
                """, id, d.customerId(), d.taxYear(), d.reference(), d.receivedOn(), r.maker(), r.id());
        return id.toString();
    }

    private Map<String, Object> declaration(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, customer_id, tax_year, reference, received_on::text AS received_on, status FROM deposits.tds_declaration WHERE id = ?
                """, id);
        if (rows.isEmpty()) throw ApiException.notFound("tax declaration " + id);
        return rows.get(0);
    }

    /** A depositor's declarations, newest tax year first. */
    public List<Map<String, Object>> declarations(UUID customerId) {
        customer(customerId);
        return jdbc.queryForList("""
                SELECT id, tax_year AS "taxYear", form, reference, received_on::text AS "receivedOn", status, recorded_by AS "recordedBy",
                       revoked_on::text AS "revokedOn", revoked_by AS "revokedBy", revoke_reason AS "revokeReason", created_at AS "createdAt"
                  FROM deposits.tds_declaration WHERE customer_id = ? ORDER BY tax_year DESC, created_at DESC
                """, customerId);
    }

    /**
     * A depositor's tax record for a tax year: the payee (senior, PAN, declaration, threshold and rate that apply),
     * interest and tax by quarter and by account, and each credit with the basis of its deduction.
     */
    public Map<String, Object> summary(UUID customerId, Integer taxYear) {
        Map<String, Object> c = customer(customerId);
        LocalDate today = days.requireOpen();
        int year = taxYear == null ? TdsOnInterest.taxYearStarting(today) : taxYear;
        DepositRules r = rules.current();
        LocalDate asOf = LocalDate.of(year, 4, 1).isAfter(today) ? LocalDate.of(year, 4, 1) : today;
        TaxAtSource.Payee p = TaxAtSource.payee(jdbc, r, customerId, asOf.isAfter(LocalDate.of(year + 1, 3, 31)) ? LocalDate.of(year + 1, 3, 31) : asOf);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerId", customerId.toString());
        m.put("customerNo", c.get("customer_no"));
        m.put("name", c.get("display_name"));
        m.put("taxYear", year);
        m.put("taxYearLabel", label(year));
        m.put("senior", p.payee().seniorCitizen());
        m.put("panOnFile", p.payee().panOnFile());
        m.put("declarationId", p.declarationId() == null ? null : p.declarationId().toString());
        m.put("threshold", DepositRuleSets.plain(r.required(p.payee().seniorCitizen() ? DepositRules.TDS_THRESHOLD_SENIOR : DepositRules.TDS_THRESHOLD)));
        BigDecimal rate = r.required(DepositRules.TDS_RATE);
        m.put("ratePercent", DepositRuleSets.plain(p.payee().panOnFile() ? rate : rate.max(r.required(DepositRules.TDS_NO_PAN_RATE))));
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT coalesce(sum(interest), 0)::numeric AS interest, coalesce(sum(tax), 0)::numeric AS tax FROM deposits.tds_ledger
                 WHERE customer_id = ? AND tax_year = ?
                """, customerId, year);
        m.put("interest", DepositRuleSets.plain((BigDecimal) totals.get("interest")));
        m.put("tax", DepositRuleSets.plain((BigDecimal) totals.get("tax")));
        m.put("quarters", jdbc.query("""
                SELECT q, coalesce(sum(t.interest), 0)::numeric, coalesce(sum(t.tax), 0)::numeric
                  FROM generate_series(1, 4) q
                  LEFT JOIN deposits.tds_ledger t ON t.customer_id = ? AND t.tax_year = ?
                       AND ((extract(month FROM t.business_date)::int + 8) % 12) / 3 + 1 = q
                 GROUP BY q ORDER BY q
                """, (rs, i) -> {
                    Map<String, Object> q = new LinkedHashMap<>();
                    q.put("quarter", rs.getInt(1));
                    q.put("interest", DepositRuleSets.plain(rs.getBigDecimal(2)));
                    q.put("tax", DepositRuleSets.plain(rs.getBigDecimal(3)));
                    return q;
                }, customerId, year));
        m.put("accounts", jdbc.query("""
                SELECT a.id, a.account_no, a.family, sum(t.interest)::numeric, sum(t.tax)::numeric
                  FROM deposits.tds_ledger t JOIN deposits.account a ON a.id = t.account_id
                 WHERE t.customer_id = ? AND t.tax_year = ? GROUP BY a.id, a.account_no, a.family ORDER BY a.account_no
                """, (rs, i) -> {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("accountId", rs.getString(1));
                    a.put("accountNo", rs.getString(2));
                    a.put("family", rs.getString(3));
                    a.put("interest", DepositRuleSets.plain(rs.getBigDecimal(4)));
                    a.put("tax", DepositRuleSets.plain(rs.getBigDecimal(5)));
                    return a;
                }, customerId, year));
        m.put("credits", jdbc.query("""
                SELECT t.id, t.business_date::text, a.account_no, t.interest::numeric, t.tax::numeric, t.rate_percent::numeric, t.basis
                  FROM deposits.tds_ledger t JOIN deposits.account a ON a.id = t.account_id
                 WHERE t.customer_id = ? AND t.tax_year = ? ORDER BY t.id DESC LIMIT 500
                """, (rs, i) -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("id", rs.getLong(1));
                    e.put("businessDate", rs.getString(2));
                    e.put("accountNo", rs.getString(3));
                    e.put("interest", DepositRuleSets.plain(rs.getBigDecimal(4)));
                    e.put("tax", DepositRuleSets.plain(rs.getBigDecimal(5)));
                    e.put("ratePercent", rs.getBigDecimal(6) == null ? null : DepositRuleSets.plain(rs.getBigDecimal(6)));
                    e.put("basis", rs.getString(7));
                    return e;
                }, customerId, year));
        return m;
    }

    /** "2026-27" for the tax year starting 1 April 2026. */
    static String label(int year) {
        return year + "-" + String.valueOf(year + 1).substring(2);
    }

    @Service
    static class Applier implements ApprovalApplier {
        private final DepositTaxService tax;

        Applier(@Lazy DepositTaxService tax) {
            this.tax = tax;
        }

        @Override public String entityType() { return ENTITY; }

        @Override public String apply(ApprovalRequest r) { return tax.apply(r); }
    }
}
