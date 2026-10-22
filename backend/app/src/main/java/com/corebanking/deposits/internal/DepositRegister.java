package com.corebanking.deposits.internal;

import com.corebanking.deposits.DepositRules;
import com.corebanking.deposits.NetOwnedFund;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The register of deposits and, for a deposit-taking NBFC, its net owned fund and the 1.5 × NOF cap (P4-6). The net
 * owned fund is recorded through a checker from each audited balance sheet; the latest governs. A funding and a
 * recurring deposit's instalment are refused when they would take public deposits past the cap
 * ({@link NetOwnedFund}); a deposit-taking NBFC with no net owned fund recorded takes no new deposit.
 */
@Service
public class DepositRegister {

    static final String ENTITY = "DEPOSIT_NOF";

    /** openapi.yaml#/components/schemas/NetOwnedFundInput. */
    public record NofInput(BigDecimal amount, LocalDate asOf, String reference) {}

    /** What a net owned fund request carries. */
    public record Request(String amount, String asOf, String reference) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final BusinessDays days;
    private final BranchScope scope;
    private final DepositRuleSets rules;
    private final Json json;

    DepositRegister(JdbcTemplate jdbc, ApprovalService approvals, BusinessDays days, BranchScope scope, DepositRuleSets rules, Json json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.days = days;
        this.scope = scope;
        this.rules = rules;
        this.json = json;
    }

    /** The multiple of the net owned fund public deposits are held to; null when the rule set has none. */
    static BigDecimal multiple(JdbcTemplate jdbc) {
        String kind = jdbc.queryForObject("SELECT deposits.rule_kind()", String.class);
        if (kind == null) return null;
        return new DepositRuleSets(jdbc).current().number(DepositRules.TD_DEPOSITS_TO_NOF).orElse(null);
    }

    /** The latest net owned fund recorded, or null. */
    static BigDecimal nof(JdbcTemplate jdbc) {
        List<BigDecimal> v = jdbc.queryForList("SELECT amount::numeric FROM deposits.net_owned_fund ORDER BY as_of DESC LIMIT 1", BigDecimal.class);
        return v.isEmpty() ? null : v.get(0);
    }

    static BigDecimal outstanding(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT deposits.public_deposits_outstanding()::numeric", BigDecimal.class);
    }

    /** Why a new deposit of {@code adding} cannot be accepted under the cap; empty when it can (or there is no cap). */
    static List<String> capProblems(JdbcTemplate jdbc, BigDecimal adding) {
        BigDecimal multiple = multiple(jdbc);
        if (multiple == null) return List.of();
        return NetOwnedFund.problems(multiple, nof(jdbc), outstanding(jdbc), adding);
    }

    // ================================================================================================ net owned fund

    @Transactional
    public ApprovalRequest proposeNof(NofInput in, String idempotencyKey) {
        List<String> problems = new ArrayList<>();
        if (multiple(jdbc) == null) problems.add("this institution's rule set holds deposits to no multiple of its net owned fund");
        if (in == null || in.amount() == null || in.amount().signum() <= 0 || in.amount().stripTrailingZeros().scale() > 2) problems.add("the amount is above zero, to the paisa");
        LocalDate today = days.requireOpen();
        if (in == null || in.asOf() == null) problems.add("the date of the audited balance sheet is required");
        else if (in.asOf().isAfter(today)) problems.add("the balance sheet date is after the business date");
        else if (!jdbc.queryForList("SELECT 1 FROM deposits.net_owned_fund WHERE as_of = ?", in.asOf()).isEmpty()) problems.add("a net owned fund is already recorded as of " + in.asOf());
        String ref = in == null || in.reference() == null ? null : in.reference().trim();
        if (ref == null || ref.isEmpty()) problems.add("the reference (the audited balance sheet or the auditor's certificate) is required");
        else if (ref.length() > 200) problems.add("the reference is at most 200 characters");
        DepositAccountService.refuseIfAny("the net owned fund cannot be recorded", problems);
        Request r = new Request(DepositRuleSets.plain(in.amount()), in.asOf().toString(), ref);
        return approvals.propose(ENTITY, "RECORD", in.asOf().toString(), json.toMap(r), null, in.amount(), null, idempotencyKey);
    }

    String apply(ApprovalRequest r) {
        Request q = json.convert(r.payload(), Request.class);
        jdbc.update("INSERT INTO deposits.net_owned_fund (as_of, amount, reference, recorded_by, approval_id) VALUES (?::date, ?, ?, ?, ?)",
                q.asOf(), new BigDecimal(q.amount()), q.reference(), r.maker(), r.id());
        return q.asOf();
    }

    /** The cap and where the institution stands against it, with the net owned funds recorded. */
    public Map<String, Object> nofView() {
        Map<String, Object> m = new LinkedHashMap<>();
        BigDecimal multiple = multiple(jdbc);
        BigDecimal nof = nof(jdbc);
        BigDecimal out = outstanding(jdbc);
        m.put("applies", multiple != null);
        m.put("multiple", multiple == null ? null : DepositRuleSets.plain(multiple));
        m.put("netOwnedFund", nof == null ? null : DepositRuleSets.plain(nof));
        m.put("outstanding", DepositRuleSets.plain(out));
        m.put("cap", multiple == null || nof == null ? null : DepositRuleSets.plain(NetOwnedFund.cap(multiple, nof)));
        m.put("headroom", multiple == null || nof == null ? null : DepositRuleSets.plain(NetOwnedFund.headroom(multiple, nof, out)));
        m.put("usedPercent", multiple == null || nof == null ? null
                : DepositRuleSets.plain(out.multiply(BigDecimal.valueOf(100)).divide(NetOwnedFund.cap(multiple, nof), 2, java.math.RoundingMode.HALF_UP)));
        m.put("history", jdbc.query("SELECT as_of, amount::numeric, reference, recorded_by FROM deposits.net_owned_fund ORDER BY as_of DESC", (rs, i) -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("asOf", rs.getObject(1, LocalDate.class));
            h.put("amount", DepositRuleSets.plain(rs.getBigDecimal(2)));
            h.put("reference", rs.getString(3));
            h.put("recordedBy", rs.getString(4));
            return h;
        }));
        return m;
    }

    // ================================================================================================ the register

    /** Every deposit funded and not closed, with its depositor and terms (the register of deposits), and the totals. */
    public Map<String, Object> register(String branch) {
        if (branch != null && !branch.isBlank()) scope.require(branch);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> x : jdbc.queryForList("""
                SELECT a.id, a.account_no, a.branch_code, a.status, a.product_code, p.product_type, cu.customer_no, cu.display_name,
                       (SELECT count(*) FROM deposits.account_holder h WHERE h.account_id = a.id) AS holders,
                       t.principal::numeric AS principal, t.rate_percent::numeric AS rate, t.tenure_months, t.tenure_days, t.start_date, t.maturity_date,
                       t.maturity_instruction, coalesce(b.balance, 0)::numeric AS balance, a.nomination_mode,
                       EXISTS (SELECT 1 FROM deposits.account_hold h WHERE h.account_id = a.id AND h.status = 'ACTIVE' AND h.kind = 'LIEN') AS lien
                  FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code JOIN deposits.term_deposit t ON t.account_id = a.id
                  JOIN customer.customer cu ON cu.id = a.customer_id LEFT JOIN deposits.account_balance b ON b.account_id = a.id
                 WHERE a.family = 'TERM_DEPOSIT' AND a.status IN ('ACTIVE','MATURED') AND (?::text IS NULL OR a.branch_code = ?::text)
                 ORDER BY t.start_date, a.account_no
                """, branch == null || branch.isBlank() ? null : branch, branch == null || branch.isBlank() ? null : branch)) {
            if (!scope.canSee((String) x.get("branch_code"))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", x.get("id").toString());
            m.put("accountNo", x.get("account_no"));
            m.put("branchCode", x.get("branch_code"));
            m.put("status", x.get("status"));
            m.put("productCode", x.get("product_code"));
            m.put("productType", x.get("product_type"));
            m.put("customerNo", x.get("customer_no"));
            m.put("depositorName", x.get("display_name"));
            m.put("holders", ((Number) x.get("holders")).intValue());
            m.put("principal", DepositRuleSets.plain((BigDecimal) x.get("principal")));
            m.put("ratePercent", DepositRuleSets.plain((BigDecimal) x.get("rate")));
            m.put("tenureMonths", ((Number) x.get("tenure_months")).intValue());
            m.put("tenureDays", ((Number) x.get("tenure_days")).intValue());
            m.put("startDate", DepositEod.date(x.get("start_date")));
            m.put("maturityDate", DepositEod.date(x.get("maturity_date")));
            m.put("maturityInstruction", x.get("maturity_instruction"));
            m.put("balance", DepositRuleSets.plain((BigDecimal) x.get("balance")));
            m.put("nominee", !"NONE".equals(x.get("nomination_mode")));
            m.put("lien", Boolean.TRUE.equals(x.get("lien")));
            rows.add(m);
            total = total.add((BigDecimal) x.get("balance"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", days.requireOpen());
        out.put("count", rows.size());
        out.put("total", DepositRuleSets.plain(total));
        out.put("netOwnedFund", nofView());
        out.put("deposits", rows);
        return out;
    }

    /** Applies net owned fund requests once a checker approves them. */
    @Service
    static class Applier implements ApprovalApplier {
        private final DepositRegister register;

        Applier(@Lazy DepositRegister register) {
            this.register = register;
        }

        @Override public String entityType() { return ENTITY; }

        @Override public String apply(ApprovalRequest r) { return register.apply(r); }
    }
}
