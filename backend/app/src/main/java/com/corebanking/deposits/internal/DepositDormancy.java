package com.corebanking.deposits.internal;

import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.deposits.DepositRules;
import com.corebanking.deposits.Dormancy;
import com.corebanking.ledger.PostingService;
import com.corebanking.ledger.TransactionLot;
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
 * Inoperative accounts and unclaimed deposits (P4-6), under a bank's rule set ({@link Dormancy}): the day-end marks a
 * savings or current account inoperative after 24 months without a movement made by or for the customer; from then
 * it takes credits but nothing goes out and nothing is charged until it is reactivated (through a checker, with the
 * KYC updated). After 10 years its balance is transferred to the unclaimed deposits head (2407) for the Depositor
 * Education and Awareness Fund, as is a matured term deposit left unpaid for 10 years; a claimant is refunded
 * through a checker. Remitting the head to the Fund and claiming it back from the Fund are outside this module.
 * A deposit-taking NBFC's rule set has none of this (open item P4-23).
 */
@Service
public class DepositDormancy {

    static final String ENTITY = "DEPOSIT_DORMANCY";
    static final String REACTIVATE = "REACTIVATE";
    static final String CLAIM = "CLAIM";

    /** openapi.yaml#/components/schemas/ReactivationInput. */
    public record ReactivationInput(String kycReference, String reason) {}

    /** What a reactivation or a claim carries. */
    public record Request(String accountId, String accountNo, String family, String kycReference, String reason, String refund) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final PostingService postings;
    private final BusinessDays days;
    private final BranchScope scope;
    private final DepositRuleSets rules;
    private final DepositMoneyService money;
    private final Json json;

    DepositDormancy(JdbcTemplate jdbc, ApprovalService approvals, PostingService postings, BusinessDays days, BranchScope scope, DepositRuleSets rules,
                    DepositMoneyService money, Json json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.postings = postings;
        this.days = days;
        this.scope = scope;
        this.rules = rules;
        this.money = money;
        this.json = json;
    }

    /** The rules, or null when the rule set has none (a deposit-taking NBFC). */
    static Dormancy.Rules rulesOf(DepositRules r) {
        if (r.number(DepositRules.CASA_INOPERATIVE_AFTER_MONTHS).isEmpty() || r.number(DepositRules.UNCLAIMED_AFTER_YEARS).isEmpty()) return null;
        return new Dormancy.Rules(r.whole(DepositRules.CASA_REVIEW_AFTER_MONTHS).orElse(12), r.whole(DepositRules.CASA_INOPERATIVE_AFTER_MONTHS).get(),
                r.whole(DepositRules.UNCLAIMED_AFTER_YEARS).get());
    }

    static String unclaimedGl(JdbcTemplate jdbc) {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = 'deposits.gl.unclaimed'", String.class);
        return v.isEmpty() ? "2407" : v.get(0).trim();
    }

    // ================================================================================================ the day-end

    /** Savings and current accounts that have gone without a customer movement long enough to be inoperative. */
    static List<String> toMarkInoperative(JdbcTemplate jdbc, Dormancy.Rules r, LocalDate today) {
        return jdbc.queryForList("""
                SELECT a.id::text FROM deposits.account a JOIN deposits.casa_account c ON c.account_id = a.id
                 WHERE a.family = 'CASA' AND a.status = 'ACTIVE' AND c.inoperative_since IS NULL
                   AND coalesce(c.last_customer_txn_on, a.opened_on) <= (?::date - make_interval(months => ?))::date
                 ORDER BY a.account_no
                """, String.class, today.toString(), r.inoperativeMonths());
    }

    static void markInoperative(JdbcTemplate jdbc, UUID id, LocalDate today) {
        jdbc.update("UPDATE deposits.casa_account SET inoperative_since = ? WHERE account_id = ? AND inoperative_since IS NULL", today, id);
    }

    /** Accounts with a balance not operated or claimed for the unclaimed years: inoperative savings and current accounts, matured deposits unpaid. */
    static List<String> toTransferUnclaimed(JdbcTemplate jdbc, Dormancy.Rules r, LocalDate today) {
        return jdbc.queryForList("""
                SELECT a.id::text FROM deposits.account a JOIN deposits.account_balance b ON b.account_id = a.id
                  LEFT JOIN deposits.casa_account c ON c.account_id = a.id LEFT JOIN deposits.term_deposit t ON t.account_id = a.id
                 WHERE b.balance > 0
                   AND NOT EXISTS (SELECT 1 FROM deposits.unclaimed u WHERE u.account_id = a.id AND u.refunded_on IS NULL)
                   AND ((a.family = 'CASA' AND a.status = 'ACTIVE' AND c.inoperative_since IS NOT NULL
                         AND coalesce(c.last_customer_txn_on, a.opened_on) <= (?::date - make_interval(years => ?))::date)
                     OR (a.family = 'TERM_DEPOSIT' AND a.status = 'MATURED' AND t.maturity_date <= (?::date - make_interval(years => ?))::date))
                 ORDER BY a.account_no
                """, String.class, today.toString(), r.unclaimedYears(), today.toString(), r.unclaimedYears());
    }

    /** Transfers the whole balance to the unclaimed deposits head; a lien keeps it where it is (the lien holder decides). */
    static void transferUnclaimed(DepositBooks b, UUID id) {
        JdbcTemplate jdbc = b.jdbc();
        Map<String, Object> a = jdbc.queryForMap("""
                SELECT a.account_no, a.family, a.branch_code, a.opened_on, p.gl_liability, b.balance::numeric AS balance, c.last_customer_txn_on, t.maturity_date,
                       EXISTS (SELECT 1 FROM deposits.account_hold h WHERE h.account_id = a.id AND h.status = 'ACTIVE') AS held
                  FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code JOIN deposits.account_balance b ON b.account_id = a.id
                  LEFT JOIN deposits.casa_account c ON c.account_id = a.id LEFT JOIN deposits.term_deposit t ON t.account_id = a.id
                 WHERE a.id = ? FOR UPDATE OF a
                """, id);
        if (Boolean.TRUE.equals(a.get("held"))) return;
        BigDecimal balance = (BigDecimal) a.get("balance");
        if (balance.signum() <= 0) return;
        String gl = unclaimedGl(jdbc);
        String accountNo = (String) a.get("account_no");
        Leg leg = new Leg((String) a.get("branch_code"), (String) a.get("gl_liability"), accountNo);
        TransactionLot lot = b.post().transfer(leg, new Leg(leg.branch(), gl, gl), balance, accountNo, "Unclaimed: transferred for the DEA Fund");
        b.post(lot);
        UUID m = b.movement(id, "UNCLAIMED_TRANSFER", "DEBIT", balance, lot.id(), null, "Unclaimed balance transferred for the DEA Fund", null);
        LocalDate last = "CASA".equals(a.get("family"))
                ? DepositEod.date(a.get("last_customer_txn_on") == null ? a.get("opened_on") : a.get("last_customer_txn_on"))
                : DepositEod.date(a.get("maturity_date"));
        jdbc.update("INSERT INTO deposits.unclaimed (account_id, transferred_on, amount, movement_id, last_operated_on) VALUES (?, ?, ?, ?, ?)",
                id, b.today(), balance, m, last);
    }

    // ================================================================================================ the list

    /** Accounts under review, inoperative or unclaimed, as of the business date. */
    public Map<String, Object> list(String stage) {
        LocalDate today = days.requireOpen();
        Dormancy.Rules r = rulesOf(rules.current());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", today);
        out.put("applies", r != null);
        if (r == null) {
            out.put("accounts", List.of());
            return out;
        }
        out.put("reviewMonths", r.reviewMonths());
        out.put("inoperativeMonths", r.inoperativeMonths());
        out.put("unclaimedYears", r.unclaimedYears());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.account_no, a.family, a.product_code, a.branch_code, a.status, cu.customer_no, cu.display_name,
                       coalesce(b.balance, 0)::numeric AS balance, c.inoperative_since,
                       CASE a.family WHEN 'CASA' THEN coalesce(c.last_customer_txn_on, a.opened_on) ELSE t.maturity_date END AS last_operated,
                       u.transferred_on, u.amount::numeric AS unclaimed_amount
                  FROM deposits.account a JOIN customer.customer cu ON cu.id = a.customer_id
                  LEFT JOIN deposits.account_balance b ON b.account_id = a.id
                  LEFT JOIN deposits.casa_account c ON c.account_id = a.id LEFT JOIN deposits.term_deposit t ON t.account_id = a.id
                  LEFT JOIN deposits.unclaimed u ON u.account_id = a.id AND u.refunded_on IS NULL
                 WHERE (a.family = 'CASA' AND a.status = 'ACTIVE'
                        AND (c.inoperative_since IS NOT NULL OR coalesce(c.last_customer_txn_on, a.opened_on) <= (?::date - make_interval(months => ?))::date))
                    OR u.id IS NOT NULL
                 ORDER BY last_operated, a.account_no
                """, today.toString(), r.reviewMonths());
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (Map<String, Object> x : rows) {
            if (!scope.canSee((String) x.get("branch_code"))) continue;
            LocalDate last = DepositEod.date(x.get("last_operated"));
            String st = x.get("transferred_on") != null ? "UNCLAIMED" : x.get("inoperative_since") != null ? "INOPERATIVE" : Dormancy.stage(r, last, today).name();
            if ("UNCLAIMED".equals(st) && x.get("transferred_on") == null) st = "INOPERATIVE";   // the day-end transfers it
            if (stage != null && !stage.isBlank() && !stage.equals(st)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", x.get("id").toString());
            m.put("accountNo", x.get("account_no"));
            m.put("family", x.get("family"));
            m.put("productCode", x.get("product_code"));
            m.put("branchCode", x.get("branch_code"));
            m.put("customerNo", x.get("customer_no"));
            m.put("customerName", x.get("display_name"));
            m.put("balance", DepositRuleSets.plain((BigDecimal) x.get("balance")));
            m.put("lastOperated", last);
            m.put("stage", st);
            m.put("inoperativeSince", DepositEod.date(x.get("inoperative_since")));
            m.put("inoperativeOn", "CASA".equals(x.get("family")) ? Dormancy.inoperativeOn(r, last) : null);
            m.put("unclaimedOn", Dormancy.unclaimedOn(r, last));
            m.put("unclaimedTransferredOn", DepositEod.date(x.get("transferred_on")));
            m.put("unclaimedAmount", x.get("unclaimed_amount") == null ? null : DepositRuleSets.plain((BigDecimal) x.get("unclaimed_amount")));
            accounts.add(m);
        }
        out.put("accounts", accounts);
        return out;
    }

    // ================================================================================================ reactivation and claims

    private Map<String, Object> state(UUID id) {
        return jdbc.queryForMap("""
                SELECT a.account_no, a.family, a.status, c.inoperative_since, u.id AS unclaimed_id, u.amount::numeric AS unclaimed_amount
                  FROM deposits.account a LEFT JOIN deposits.casa_account c ON c.account_id = a.id
                  LEFT JOIN deposits.unclaimed u ON u.account_id = a.id AND u.refunded_on IS NULL WHERE a.id = ?
                """, id);
    }

    /** A savings or current account reactivated (and any unclaimed balance refunded), or a matured deposit's unclaimed balance refunded. */
    @Transactional
    public ApprovalRequest propose(UUID accountId, ReactivationInput in, String idempotencyKey) {
        DepositMoneyService.Account a = money.account(accountId, null, true);
        Map<String, Object> s = state(a.id());
        boolean casa = DepositProductService.CASA.equals(a.family());
        List<String> problems = new ArrayList<>();
        if (casa && s.get("inoperative_since") == null && s.get("unclaimed_id") == null) problems.add("account " + a.accountNo() + " is operative");
        if (!casa && s.get("unclaimed_id") == null) problems.add("deposit " + a.accountNo() + " has no unclaimed balance");
        String kyc = in == null || in.kycReference() == null ? null : in.kycReference().trim();
        String reason = in == null || in.reason() == null ? null : in.reason().trim();
        if (kyc == null || kyc.isEmpty()) problems.add("the KYC record updated (or the claim documents seen) is required");
        else if (kyc.length() > 100) problems.add("kycReference is at most 100 characters");
        if (reason == null || reason.isEmpty()) problems.add("a reason is required");
        else if (reason.length() > 500) problems.add("the reason is at most 500 characters");
        DepositAccountService.refuseIfAny(casa ? "the account cannot be reactivated" : "the claim cannot be paid", problems);
        String refund = s.get("unclaimed_amount") == null ? null : DepositRuleSets.plain((BigDecimal) s.get("unclaimed_amount"));
        Request r = new Request(a.id().toString(), a.accountNo(), a.family(), kyc, reason, refund);
        return approvals.propose(ENTITY, casa ? REACTIVATE : CLAIM, a.id().toString(), json.toMap(r), null,
                refund == null ? null : new BigDecimal(refund), a.branch(), idempotencyKey);
    }

    String apply(ApprovalRequest r) {
        Request q = json.convert(r.payload(), Request.class);
        UUID id = UUID.fromString(q.accountId());
        DepositMoneyService.Account a = money.account(id, null, false);
        Map<String, Object> s = state(id);
        LocalDate today = days.requireOpen();
        if (s.get("unclaimed_id") != null) {
            BigDecimal amount = (BigDecimal) s.get("unclaimed_amount");
            DepositBooks b = DepositBooks.request(jdbc, postings, today, r.maker(), r.id(), r.checker() == null ? r.maker() : r.checker());
            String gl = unclaimedGl(jdbc);
            TransactionLot lot = b.post().transfer(new Leg(a.branch(), gl, gl), a.leg(), amount, a.accountNo(), "Unclaimed balance refunded to the claimant");
            b.post(lot);
            UUID m = b.movement(id, "UNCLAIMED_REFUND", "CREDIT", amount, lot.id(), null, "Unclaimed balance refunded (" + q.kycReference() + ")", null);
            jdbc.update("""
                    UPDATE deposits.unclaimed SET refunded_on = ?, refund_movement_id = ?, refund_reference = ?, refund_approval_id = ? WHERE id = ?
                    """, today, m, q.kycReference(), r.id(), s.get("unclaimed_id"));
        }
        if (DepositProductService.CASA.equals(a.family())) {
            // reactivated: operated today, so the next day-end does not mark it again
            jdbc.update("UPDATE deposits.casa_account SET inoperative_since = NULL, last_customer_txn_on = ? WHERE account_id = ?", today, id);
        }
        return a.accountNo();
    }

    /** Applies reactivations and claims once a checker approves them. */
    @Service
    static class Applier implements ApprovalApplier {
        private final DepositDormancy dormancy;

        Applier(@Lazy DepositDormancy dormancy) {
            this.dormancy = dormancy;
        }

        @Override public String entityType() { return ENTITY; }

        @Override public String apply(ApprovalRequest r) { return dormancy.apply(r); }
    }
}
