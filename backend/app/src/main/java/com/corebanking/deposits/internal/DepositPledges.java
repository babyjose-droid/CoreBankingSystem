package com.corebanking.deposits.internal;

import com.corebanking.deposits.DepositLoans;
import com.corebanking.lending.LoanOperations;
import com.corebanking.platform.ApiException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loans against term deposits (P4-5b): a deposit is pledged for a loan by a lien that names the loan, through a
 * checker. Before it is placed, the deposits pledged for the loan must cover it ({@link DepositLoans}): at a
 * deposit-taking NBFC at most 75% of their value, at a rate 2 points above theirs, after three months; at a bank on the
 * tenant's policy (settings {@code deposits.loan.max-percent} and {@code deposits.loan.spread-percent}). The pledge
 * keeps the deposit from being closed or paid out; it is released once the loan is closed or cancelled, by a staff
 * request or by the day-end. Setting a deposit off against a loan in default is not built (open item P4-22).
 */
@Service
public class DepositPledges {

    static final String PLEDGE = "PLEDGE";

    /** openapi.yaml#/components/schemas/DepositPledgeInput. */
    public record PledgeInput(UUID loanId, String reason) {}

    /** What a pledge request carries. */
    public record PledgeRequest(String accountId, String accountNo, String loanId, String loanNo, String amount, String reason) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final BusinessDays days;
    private final BranchScope scope;
    private final DepositRuleSets rules;
    private final LoanOperations loans;
    private final Json json;

    DepositPledges(JdbcTemplate jdbc, ApprovalService approvals, BusinessDays days, BranchScope scope, DepositRuleSets rules, LoanOperations loans,
                   Json json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.days = days;
        this.scope = scope;
        this.rules = rules;
        this.loans = loans;
        this.json = json;
    }

    /** A deposit as a pledge needs it. */
    private record Deposit(UUID id, String accountNo, String status, String branch, BigDecimal balance, BigDecimal rate, LocalDate start,
                           LocalDate maturity) {
        DepositLoans.Pledged pledged() {
            return new DepositLoans.Pledged(accountNo, balance, rate, start, maturity);
        }
    }

    private Deposit deposit(UUID id) {
        List<Deposit> rows = jdbc.query("""
                SELECT a.id, a.account_no, a.status, a.branch_code, coalesce(b.balance, 0)::numeric, t.rate_percent::numeric, t.start_date, t.maturity_date
                  FROM deposits.account a JOIN deposits.term_deposit t ON t.account_id = a.id
                  LEFT JOIN deposits.account_balance b ON b.account_id = a.id
                 WHERE a.id = ?
                """, (rs, i) -> new Deposit(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6).stripTrailingZeros(), rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class)), id);
        if (rows.isEmpty()) throw ApiException.notFound("term deposit " + id);
        return rows.get(0);
    }

    private DepositLoans.Terms terms() {
        return DepositLoans.terms(rules.current(), setting("deposits.loan.max-percent", "90"), setting("deposits.loan.spread-percent", "1"));
    }

    private BigDecimal setting(String key, String otherwise) {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = ?", String.class, key);
        return new BigDecimal(v.isEmpty() ? otherwise : v.get(0).trim());
    }

    /** The deposits already pledged for the loan (active pledges), other than {@code except}. */
    private List<Deposit> pledgedFor(UUID loanId, UUID except) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT account_id FROM deposits.account_hold WHERE loan_id = ? AND status = 'ACTIVE' AND account_id <> ? ORDER BY placed_on
                """, UUID.class, loanId, except);
        List<Deposit> out = new ArrayList<>();
        for (UUID id : ids) out.add(deposit(id));
        return out;
    }

    /** Why this deposit cannot be pledged for the loan today, with what the loan may be. */
    private Map<String, Object> check(Deposit d, LoanOperations.Terms loan) {
        List<String> problems = new ArrayList<>();
        if (!"ACTIVE".equals(d.status())) problems.add("deposit " + d.accountNo() + " is " + d.status() + "; an active deposit is pledged");
        if (!"SANCTIONED".equals(loan.status()) && !"ACTIVE".equals(loan.status())) {
            problems.add("loan " + loan.loanNo() + " is " + loan.status() + "; a deposit is pledged for a sanctioned or active loan");
        }
        if (jdbc.queryForList("SELECT 1 FROM deposits.account_holder WHERE account_id = ? AND customer_id = ?", d.id(), loan.customerId()).isEmpty()) {
            problems.add("deposit " + d.accountNo() + " is not held by the borrower of loan " + loan.loanNo());
        }
        List<Map<String, Object>> other = jdbc.queryForList("""
                SELECT l.loan_no FROM deposits.account_hold h JOIN lending.loan_account l ON l.id = h.loan_id
                 WHERE h.account_id = ? AND h.status = 'ACTIVE'
                """, d.id());
        if (!other.isEmpty()) problems.add("deposit " + d.accountNo() + " already secures loan " + other.get(0).get("loan_no"));
        List<Deposit> all = new ArrayList<>(pledgedFor(loan.loanId(), d.id()));
        all.add(d);
        List<DepositLoans.Pledged> pledged = all.stream().map(Deposit::pledged).toList();
        DepositLoans.Terms t = terms();
        LocalDate today = days.requireOpen();
        if ("ACTIVE".equals(d.status())) {
            problems.addAll(DepositLoans.problems(t, new DepositLoans.Loan(loan.loanNo(), loan.sanctioned(), loan.ratePercent(), loan.maturity()), pledged, today));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("depositId", d.id().toString());
        m.put("depositNo", d.accountNo());
        m.put("loanId", loan.loanId().toString());
        m.put("loanNo", loan.loanNo());
        m.put("loanStatus", loan.status());
        m.put("sanctioned", DepositRuleSets.plain(loan.sanctioned()));
        m.put("loanRatePercent", DepositRuleSets.plain(loan.ratePercent()));
        m.put("loanMaturity", loan.maturity());
        m.put("lien", DepositRuleSets.plain(d.balance()));
        m.put("maxPercent", DepositRuleSets.plain(t.maxPercent()));
        m.put("spreadPercent", DepositRuleSets.plain(t.spreadPercent()));
        m.put("lockInMonths", t.lockInMonths());
        m.put("maxLoan", DepositRuleSets.plain(DepositLoans.maxLoan(t, pledged)));
        m.put("minRatePercent", DepositRuleSets.plain(DepositLoans.minRate(t, pledged)));
        m.put("deposits", all.stream().map(x -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("depositNo", x.accountNo());
            e.put("value", DepositRuleSets.plain(x.balance()));
            e.put("ratePercent", DepositRuleSets.plain(x.rate()));
            e.put("maturityDate", x.maturity());
            e.put("thisOne", x.id().equals(d.id()));
            return e;
        }).toList());
        m.put("problems", problems.stream().distinct().toList());
        return m;
    }

    /** What a pledge of this deposit for the loan would be, and why it cannot be: nothing changes. */
    public Map<String, Object> quote(UUID depositId, UUID loanId) {
        if (loanId == null) throw ApiException.invalid("loanId is required");
        Deposit d = deposit(depositId);
        scope.requireRecord(d.branch(), "term deposit " + depositId);
        return check(d, loans.terms(loanId));
    }

    /** Proposes pledging the deposit for the loan: a lien of the deposit's balance that names the loan. */
    @Transactional
    public ApprovalRequest propose(UUID depositId, PledgeInput in, String idempotencyKey) {
        if (in == null || in.loanId() == null) throw ApiException.invalid("loanId is required");
        Deposit d = deposit(depositId);
        scope.requireRecord(d.branch(), "term deposit " + depositId);
        LoanOperations.Terms loan = loans.terms(in.loanId());
        @SuppressWarnings("unchecked")
        List<String> problems = new ArrayList<>((List<String>) check(d, loan).get("problems"));
        String reason = in.reason() == null ? null : in.reason().trim();
        if (reason == null || reason.isEmpty()) problems.add("a reason is required (the sanction letter or the loan agreement)");
        else if (reason.length() > 500) problems.add("the reason is at most 500 characters");
        DepositAccountService.refuseIfAny("the deposit cannot be pledged", problems);
        PledgeRequest p = new PledgeRequest(d.id().toString(), d.accountNo(), loan.loanId().toString(), loan.loanNo(), DepositRuleSets.plain(d.balance()), reason);
        return approvals.propose(DepositMoneyService.HOLD_ENTITY, PLEDGE, d.id().toString(), json.toMap(p), null, d.balance(), d.branch(), idempotencyKey);
    }

    /** Places the pledge; returns the hold id. The deposits are checked again on the day it is approved. */
    String apply(ApprovalRequest r) {
        PledgeRequest p = json.convert(r.payload(), PledgeRequest.class);
        Deposit d = deposit(UUID.fromString(p.accountId()));
        @SuppressWarnings("unchecked")
        List<String> problems = (List<String>) check(d, loans.terms(UUID.fromString(p.loanId()))).get("problems");
        DepositAccountService.refuseIfAny("the deposit cannot be pledged", problems);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deposits.account_hold (id, account_id, kind, amount, reason, reference, placed_on, placed_by, approval_id, loan_id)
                VALUES (?, ?, 'LIEN', ?, ?, ?, current_date, ?, ?, ?)
                """, id, d.id(), d.balance(), p.reason(), "Loan " + p.loanNo(), r.maker(), r.id(), UUID.fromString(p.loanId()));
        return id.toString();
    }

    /** The deposits pledged for a loan, active and released. */
    public List<Map<String, Object>> forLoan(UUID loanId) {
        LoanOperations.Terms loan = loans.terms(loanId);
        scope.requireRecord(loan.branch(), "loan " + loanId);
        return jdbc.query("""
                SELECT h.id::text, a.id::text, a.account_no, h.amount::numeric, h.status, h.placed_on, h.released_on, h.reason, t.rate_percent::numeric,
                       t.maturity_date, a.status
                  FROM deposits.account_hold h JOIN deposits.account a ON a.id = h.account_id JOIN deposits.term_deposit t ON t.account_id = a.id
                 WHERE h.loan_id = ? ORDER BY h.status = 'ACTIVE' DESC, h.placed_on
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("holdId", rs.getString(1));
                    m.put("depositId", rs.getString(2));
                    m.put("depositNo", rs.getString(3));
                    m.put("amount", DepositRuleSets.plain(rs.getBigDecimal(4)));
                    m.put("status", rs.getString(5));
                    m.put("placedOn", rs.getObject(6, LocalDate.class));
                    m.put("releasedOn", rs.getObject(7, LocalDate.class));
                    m.put("reason", rs.getString(8));
                    m.put("depositRatePercent", DepositRuleSets.plain(rs.getBigDecimal(9)));
                    m.put("depositMaturity", rs.getObject(10, LocalDate.class));
                    m.put("depositStatus", rs.getString(11));
                    return m;
                }, loanId);
    }

    /** Why a hold cannot be released by hand: a pledge whose loan is still open. */
    static List<String> releaseProblems(JdbcTemplate jdbc, UUID holdId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.loan_no, l.status FROM deposits.account_hold h JOIN lending.loan_account l ON l.id = h.loan_id WHERE h.id = ?
                """, holdId);
        if (rows.isEmpty()) return List.of();
        String status = (String) rows.get(0).get("status");
        if ("CLOSED".equals(status) || "CANCELLED".equals(status)) return List.of();
        return List.of("the deposit secures loan " + rows.get(0).get("loan_no") + ", which is " + status + ": the pledge is released when the loan is closed");
    }

    /** The day-end: pledges whose loan is closed or cancelled. */
    static List<String> releasable(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT h.id::text FROM deposits.account_hold h JOIN lending.loan_account l ON l.id = h.loan_id
                 WHERE h.status = 'ACTIVE' AND l.status IN ('CLOSED','CANCELLED') ORDER BY h.placed_on
                """, String.class);
    }

    static void release(JdbcTemplate jdbc, UUID holdId, String user) {
        jdbc.update("""
                UPDATE deposits.account_hold h SET status = 'RELEASED', released_on = current_date, released_by = ?,
                       release_reason = 'loan ' || l.loan_no || ' ' || lower(l.status)
                  FROM lending.loan_account l WHERE l.id = h.loan_id AND h.id = ? AND h.status = 'ACTIVE'
                """, user, holdId);
    }
}
