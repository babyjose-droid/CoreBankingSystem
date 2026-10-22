package com.corebanking.deposits.internal;

import com.corebanking.deposits.Cheques;
import com.corebanking.deposits.DepositPostings;
import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.ledger.PostingService;
import com.corebanking.ledger.TransactionLot;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cheques on savings and current accounts (P4-6). A cheque book is issued through a checker; a stop payment takes
 * effect at once (its revocation waits for a checker). Clearing is simulated: a cheque of ours presented by another
 * bank is paid (Dr the account, Cr inward clearing 2409) or returned with the reason the engine gives
 * ({@link Cheques}); another bank's cheque paid in by our customer waits in clearing and is credited (Dr outward
 * clearing 1207, Cr the account) when it clears, or returned. Settling the clearing heads with the clearing house is a
 * voucher, outside this module.
 */
@Service
public class DepositCheques {

    static final String ENTITY = "CHEQUE";
    static final String ISSUE_BOOK = "ISSUE_BOOK";
    static final String STOP = "STOP";
    static final String REVOKE_STOP = "REVOKE_STOP";

    /** openapi.yaml#/components/schemas/ChequeBookInput. */
    public record BookInput(Integer leaves) {}

    /** openapi.yaml#/components/schemas/ChequeStopInput: one leaf, or a run of leaves. */
    public record StopInput(Integer fromLeaf, Integer toLeaf, String reason) {}

    /** openapi.yaml#/components/schemas/ChequePresentmentInput: a cheque of ours presented in inward clearing. */
    public record PresentInput(String accountNo, Integer leafNo, BigDecimal amount, LocalDate chequeDate, String payee, String presentingBank) {}

    /** openapi.yaml#/components/schemas/ChequeDepositInput: another bank's cheque paid in. */
    public record LodgeInput(String chequeNo, String draweeIfsc, String drawerName, BigDecimal amount, LocalDate chequeDate) {}

    /** openapi.yaml#/components/schemas/ChequeSettleInput. */
    public record SettleInput(String outcome, String reason) {}

    /** What a cheque request carries. */
    public record Request(String accountId, String accountNo, Integer leaves, Integer fromLeaf, Integer toLeaf, String reason) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final PostingService postings;
    private final BusinessDays days;
    private final BranchScope scope;
    private final DepositMoneyService money;
    private final Json json;

    DepositCheques(JdbcTemplate jdbc, ApprovalService approvals, PostingService postings, BusinessDays days, BranchScope scope, DepositMoneyService money,
                   Json json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.postings = postings;
        this.days = days;
        this.scope = scope;
        this.money = money;
        this.json = json;
    }

    private String gl(String key, String otherwise) {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = ?", String.class, key);
        return v.isEmpty() ? otherwise : v.get(0).trim();
    }

    private int validMonths() {
        return Integer.parseInt(gl("deposits.cheque-valid-months", "3"));
    }

    private boolean inoperative(UUID accountId) {
        return !jdbc.queryForList("SELECT 1 FROM deposits.casa_account WHERE account_id = ? AND inoperative_since IS NOT NULL", accountId).isEmpty();
    }

    private boolean chequeFacility(UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT coalesce(p.cheque_facility, false) FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code WHERE a.id = ?
                """, Boolean.class, accountId));
    }

    // ================================================================================================ cheque books and stops

    @Transactional
    public ApprovalRequest proposeBook(UUID accountId, BookInput in, String idempotencyKey) {
        DepositMoneyService.Account a = money.account(accountId, DepositProductService.CASA, true);
        List<String> problems = new ArrayList<>();
        int leaves = in == null || in.leaves() == null ? 25 : in.leaves();
        if (!Set.of(10, 25, 50, 100).contains(leaves)) problems.add("a cheque book has 10, 25, 50 or 100 leaves");
        if (!chequeFacility(a.id())) problems.add("account " + a.accountNo() + ": its product has no cheque facility");
        if (!"ACTIVE".equals(a.status())) problems.add("account " + a.accountNo() + " is " + a.status());
        if (inoperative(a.id())) problems.add("account " + a.accountNo() + " is inoperative: reactivate it first");
        DepositAccountService.refuseIfAny("the cheque book cannot be issued", problems);
        Request r = new Request(a.id().toString(), a.accountNo(), leaves, null, null, null);
        return approvals.propose(ENTITY, ISSUE_BOOK, a.id().toString(), json.toMap(r), null, null, a.branch(), idempotencyKey);
    }

    /** Leaves {@code from}..{@code to} of the account, as they stand; every number in the run must be issued to it. */
    private List<Map<String, Object>> leaves(UUID accountId, int from, int to) {
        return jdbc.queryForList("SELECT leaf_no, status FROM deposits.cheque_leaf WHERE account_id = ? AND leaf_no BETWEEN ? AND ? ORDER BY leaf_no",
                accountId, from, to);
    }

    @Transactional
    public ApprovalRequest proposeStop(UUID accountId, StopInput in, String idempotencyKey) {
        DepositMoneyService.Account a = money.account(accountId, DepositProductService.CASA, true);
        List<String> problems = new ArrayList<>();
        Integer from = in == null ? null : in.fromLeaf();
        Integer to = in == null || in.toLeaf() == null ? from : in.toLeaf();
        String reason = in == null || in.reason() == null ? null : in.reason().trim();
        if (reason == null || reason.isEmpty()) problems.add("a reason is required (the drawer's request)");
        if (from == null) problems.add("give the cheque number to stop");
        else if (to < from || to - from >= 100) problems.add("stop one cheque or a run of at most 100");
        else {
            List<Map<String, Object>> rows = leaves(a.id(), from, to);
            if (rows.size() != to - from + 1) problems.add("cheque" + (from.equals(to) ? " " + from + " was" : "s " + from + " to " + to + " were") + " not all issued to account " + a.accountNo());
            for (Map<String, Object> r : rows) {
                if (!"UNUSED".equals(r.get("status"))) problems.add("cheque " + r.get("leaf_no") + " is " + String.valueOf(r.get("status")).toLowerCase());
            }
        }
        DepositAccountService.refuseIfAny("payment cannot be stopped", problems);
        Request r = new Request(a.id().toString(), a.accountNo(), null, from, to, reason);
        return approvals.propose(ENTITY, STOP, a.id().toString(), json.toMap(r), null, null, a.branch(), idempotencyKey);
    }

    @Transactional
    public ApprovalRequest proposeRevoke(UUID accountId, int leafNo, DepositMoneyService.ReasonInput in, String idempotencyKey) {
        DepositMoneyService.Account a = money.account(accountId, DepositProductService.CASA, true);
        List<String> problems = new ArrayList<>();
        List<Map<String, Object>> rows = leaves(a.id(), leafNo, leafNo);
        if (rows.isEmpty()) problems.add("cheque " + leafNo + " was not issued to account " + a.accountNo());
        else if (!"STOPPED".equals(rows.get(0).get("status"))) problems.add("payment of cheque " + leafNo + " is not stopped");
        String reason = in == null || in.reason() == null ? null : in.reason().trim();
        if (reason == null || reason.isEmpty()) problems.add("a reason is required");
        DepositAccountService.refuseIfAny("the stop cannot be revoked", problems);
        Request r = new Request(a.id().toString(), a.accountNo(), null, leafNo, leafNo, reason);
        return approvals.propose(ENTITY, REVOKE_STOP, a.id().toString(), json.toMap(r), null, null, a.branch(), idempotencyKey);
    }

    String apply(ApprovalRequest r) {
        Request q = json.convert(r.payload(), Request.class);
        UUID accountId = UUID.fromString(q.accountId());
        switch (r.action()) {
            case ISSUE_BOOK -> {
                // one book at a time takes a run of numbers
                jdbc.execute("SELECT pg_advisory_xact_lock(hashtext('deposits.cheque_leaf_no'))");
                int first = jdbc.queryForObject("SELECT nextval('deposits.cheque_leaf_no')::int", Integer.class);
                jdbc.queryForObject("SELECT setval('deposits.cheque_leaf_no', ? + ? - 1)", Long.class, first, q.leaves());
                UUID book = UUID.randomUUID();
                jdbc.update("INSERT INTO deposits.cheque_book (id, account_id, first_leaf, leaves, issued_on, issued_by, approval_id) VALUES (?, ?, ?, ?, current_date, ?, ?)",
                        book, accountId, first, q.leaves(), r.maker(), r.id());
                jdbc.update("""
                        INSERT INTO deposits.cheque_leaf (account_id, leaf_no, book_id) SELECT ?, g, ? FROM generate_series(?, ? + ? - 1) g
                        """, accountId, book, first, first, q.leaves());
                return jdbc.queryForObject("SELECT book_no FROM deposits.cheque_book WHERE id = ?", String.class, book);
            }
            case STOP -> {
                int n = jdbc.update("""
                        UPDATE deposits.cheque_leaf SET status = 'STOPPED', stopped_on = current_date, stop_reason = ?, stopped_by = ?, stop_approval_id = ?
                         WHERE account_id = ? AND leaf_no BETWEEN ? AND ? AND status = 'UNUSED'
                        """, q.reason(), r.maker(), r.id(), accountId, q.fromLeaf(), q.toLeaf());
                if (n != q.toLeaf() - q.fromLeaf() + 1) throw ApiException.conflict("a cheque in the run was paid or stopped meanwhile");
                return q.fromLeaf() + (q.toLeaf().equals(q.fromLeaf()) ? "" : "-" + q.toLeaf());
            }
            case REVOKE_STOP -> {
                int n = jdbc.update("UPDATE deposits.cheque_leaf SET status = 'UNUSED' WHERE account_id = ? AND leaf_no = ? AND status = 'STOPPED'",
                        accountId, q.fromLeaf());
                if (n == 0) throw ApiException.conflict("payment of cheque " + q.fromLeaf() + " is no longer stopped");
                return String.valueOf(q.fromLeaf());
            }
            default -> throw new IllegalStateException("unknown cheque action " + r.action());
        }
    }

    // ================================================================================================ inward clearing

    /** A cheque of ours presented by another bank: paid at once, or returned with the reason. Answers the presentment. */
    @Transactional
    public Map<String, Object> present(PresentInput in) {
        List<String> problems = new ArrayList<>();
        if (in == null) throw ApiException.invalid("the cheque is required");
        if (in.accountNo() == null || in.accountNo().isBlank()) problems.add("the account number on the cheque is required");
        if (in.leafNo() == null || in.leafNo() < 1 || in.leafNo() > 999999) problems.add("the cheque number is six digits");
        if (in.amount() == null || in.amount().signum() <= 0 || in.amount().stripTrailingZeros().scale() > 2) problems.add("the amount is above zero, to the paisa");
        if (in.chequeDate() == null) problems.add("the date on the cheque is required");
        String payee = trim(in.payee(), 200, "payee", problems);
        String bank = trim(in.presentingBank(), 100, "presentingBank", problems);
        DepositAccountService.refuseIfAny("the presentment is not complete", problems);
        LocalDate today = days.requireOpen();
        List<Map<String, Object>> acc = jdbc.queryForList("""
                SELECT a.id, a.status, a.branch_code, p.gl_liability, coalesce(b.balance, 0)::numeric AS balance, c.inoperative_since,
                       (SELECT coalesce(sum(h.amount), 0) FROM deposits.account_hold h WHERE h.account_id = a.id AND h.status = 'ACTIVE' AND h.kind = 'LIEN')::numeric AS liens,
                       EXISTS (SELECT 1 FROM deposits.account_hold h WHERE h.account_id = a.id AND h.status = 'ACTIVE' AND h.kind IN ('DEBIT_FREEZE','TOTAL_FREEZE')) AS frozen
                  FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code JOIN deposits.casa_account c ON c.account_id = a.id
                  LEFT JOIN deposits.account_balance b ON b.account_id = a.id
                 WHERE a.account_no = ? AND a.family = 'CASA' FOR UPDATE OF a
                """, in.accountNo().trim());
        UUID accountId = null;
        Optional<Cheques.ReturnReason> reason;
        Map<String, Object> a = acc.isEmpty() ? null : acc.get(0);
        if (a == null) {
            reason = Optional.of(Cheques.ReturnReason.NO_SUCH_ACCOUNT);
        } else {
            accountId = (UUID) a.get("id");
            List<String> leaf = jdbc.queryForList("SELECT status FROM deposits.cheque_leaf WHERE account_id = ? AND leaf_no = ? FOR UPDATE", String.class,
                    accountId, in.leafNo());
            BigDecimal balance = (BigDecimal) a.get("balance");
            BigDecimal available = balance.subtract((BigDecimal) a.get("liens")).max(BigDecimal.ZERO);
            reason = Cheques.decide(new Cheques.Presentment("ACTIVE".equals(a.get("status")), leaf.isEmpty() ? null : Cheques.Leaf.valueOf(leaf.get(0)),
                    in.chequeDate(), in.amount(), today, validMonths(), Boolean.TRUE.equals(a.get("frozen")), a.get("inoperative_since") != null, balance, available));
        }
        UUID id = UUID.randomUUID();
        UUID movement = null;
        if (reason.isEmpty()) {
            String accountNo = in.accountNo().trim();
            DepositBooks b = DepositBooks.request(jdbc, postings, today, CurrentUser.username(), null, CurrentUser.username());
            String inward = gl("deposits.gl.inward-clearing", "2409");
            Leg leg = new Leg((String) a.get("branch_code"), (String) a.get("gl_liability"), accountNo);
            TransactionLot lot = b.post().transfer(leg, new Leg(leg.branch(), inward, inward), in.amount(), "CHQ " + in.leafNo(), "Cheque " + in.leafNo() + " to " + payee);
            b.post(lot);
            movement = b.movement(accountId, "CHEQUE_PAID", "DEBIT", in.amount(), lot.id(), null, "Cheque " + in.leafNo() + " to " + payee + " (" + bank + ")",
                    String.valueOf(in.leafNo()));
        }
        jdbc.update("""
                INSERT INTO deposits.cheque_presentment (id, account_id, account_no, leaf_no, amount, cheque_date, payee, presenting_bank, presented_on, status,
                                                         return_reason, movement_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, current_date, ?, ?, ?, ?)
                """, id, accountId, in.accountNo().trim(), in.leafNo(), in.amount(), in.chequeDate(), payee, bank, reason.isEmpty() ? "PAID" : "RETURNED",
                reason.map(Enum::name).orElse(null), movement, CurrentUser.username());
        if (reason.isEmpty()) {
            jdbc.update("UPDATE deposits.cheque_leaf SET status = 'PAID', paid_on = current_date, presentment_id = ? WHERE account_id = ? AND leaf_no = ?",
                    id, accountId, in.leafNo());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("status", reason.isEmpty() ? "PAID" : "RETURNED");
        out.put("returnReason", reason.map(Enum::name).orElse(null));
        out.put("returnReasonText", reason.map(Cheques.ReturnReason::text).orElse(null));
        out.put("movementId", movement == null ? null : movement.toString());
        return out;
    }

    // ================================================================================================ outward clearing

    /** Another bank's cheque paid in: in clearing until it is settled. */
    @Transactional
    public Map<String, Object> lodge(UUID accountId, LodgeInput in) {
        DepositMoneyService.Account a = money.account(accountId, DepositProductService.CASA, true);
        List<String> problems = new ArrayList<>();
        if (in == null) throw ApiException.invalid("the cheque is required");
        if (!"ACTIVE".equals(a.status())) problems.add("account " + a.accountNo() + " is " + a.status());
        if (in.chequeNo() == null || !in.chequeNo().matches("^[0-9]{6}$")) problems.add("the cheque number is six digits");
        String ifsc = in.draweeIfsc() == null ? null : in.draweeIfsc().trim().toUpperCase();
        if (ifsc == null || !ifsc.matches("^[A-Z]{4}0[A-Z0-9]{6}$")) problems.add("the drawee bank's IFSC is 11 characters (4 letters, 0, 6 letters or digits)");
        String drawer = trim(in.drawerName(), 200, "drawerName", problems);
        if (in.amount() == null || in.amount().signum() <= 0 || in.amount().stripTrailingZeros().scale() > 2) problems.add("the amount is above zero, to the paisa");
        LocalDate today = days.requireOpen();
        if (in.chequeDate() == null) problems.add("the date on the cheque is required");
        else if (in.chequeDate().isAfter(today)) problems.add("the cheque is post-dated (" + in.chequeDate() + ")");
        else if (today.isAfter(in.chequeDate().plusMonths(validMonths()))) problems.add("the cheque is stale: dated more than " + validMonths() + " months ago");
        DepositAccountService.refuseIfAny("the cheque cannot be paid in", problems);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deposits.cheque_deposit (id, account_id, cheque_no, drawee_ifsc, drawer_name, amount, cheque_date, lodged_on, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, current_date, ?)
                """, id, a.id(), in.chequeNo(), ifsc, drawer, in.amount(), in.chequeDate(), CurrentUser.username());
        return deposit(id);
    }

    /** The clearing simulator: the cheque paid in clears (the account is credited) or comes back with a reason. */
    @Transactional
    public Map<String, Object> settle(UUID depositId, SettleInput in) {
        Map<String, Object> d = depositRow(depositId, true);
        DepositMoneyService.Account a = money.account((UUID) d.get("account_id"), DepositProductService.CASA, true);
        if (in == null || !Set.of("CLEARED", "RETURNED").contains(in.outcome())) throw ApiException.invalid("outcome is CLEARED or RETURNED");
        List<String> problems = new ArrayList<>();
        if (!"IN_CLEARING".equals(d.get("status"))) problems.add("cheque " + d.get("cheque_no") + " is already " + String.valueOf(d.get("status")).toLowerCase());
        String reason = "RETURNED".equals(in.outcome()) ? trim(in.reason(), 200, "reason", problems) : null;
        BigDecimal amount = (BigDecimal) d.get("amount");
        if ("CLEARED".equals(in.outcome())) {
            for (String p : money.creditProblems(a, amount)) problems.add("account " + a.accountNo() + ": " + p + " (return the cheque instead)");
        }
        DepositAccountService.refuseIfAny("the cheque cannot be settled", problems);
        LocalDate today = days.requireOpen();
        UUID movement = null;
        if ("CLEARED".equals(in.outcome())) {
            DepositBooks b = DepositBooks.request(jdbc, postings, today, CurrentUser.username(), null, CurrentUser.username());
            String outward = gl("deposits.gl.outward-clearing", "1207");
            TransactionLot lot = b.post().transfer(new Leg(a.branch(), outward, outward), a.leg(), amount, "CHQ " + d.get("cheque_no"),
                    "Cheque " + d.get("cheque_no") + " of " + d.get("drawer_name"));
            b.post(lot);
            movement = b.movement(a.id(), "CHEQUE_DEPOSIT", "CREDIT", amount, lot.id(), null,
                    "Cheque " + d.get("cheque_no") + " of " + d.get("drawer_name") + " (" + d.get("drawee_ifsc") + ")", (String) d.get("cheque_no"));
        }
        jdbc.update("""
                UPDATE deposits.cheque_deposit SET status = ?, settled_on = current_date, return_reason = ?, movement_id = ?, settled_by = ? WHERE id = ?
                """, in.outcome(), reason, movement, CurrentUser.username(), depositId);
        return deposit(depositId);
    }

    private Map<String, Object> depositRow(UUID id, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT d.*, a.account_no FROM deposits.cheque_deposit d JOIN deposits.account a ON a.id = d.account_id WHERE d.id = ?"
                + (lock ? " FOR UPDATE OF d" : ""), id);
        if (rows.isEmpty()) throw ApiException.notFound("cheque paid in " + id);
        return rows.get(0);
    }

    private Map<String, Object> deposit(UUID id) {
        return depositView(depositRow(id, false));
    }

    private Map<String, Object> depositView(Map<String, Object> d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.get("id").toString());
        m.put("accountId", d.get("account_id").toString());
        m.put("accountNo", d.get("account_no"));
        m.put("chequeNo", d.get("cheque_no"));
        m.put("draweeIfsc", d.get("drawee_ifsc"));
        m.put("drawerName", d.get("drawer_name"));
        m.put("amount", DepositRuleSets.plain((BigDecimal) d.get("amount")));
        m.put("chequeDate", String.valueOf(d.get("cheque_date")));
        m.put("lodgedOn", String.valueOf(d.get("lodged_on")));
        m.put("status", d.get("status"));
        m.put("settledOn", d.get("settled_on") == null ? null : String.valueOf(d.get("settled_on")));
        m.put("returnReason", d.get("return_reason"));
        return m;
    }

    /** Cheques paid in and waiting in clearing (all branches in scope), oldest first: what the clearing simulator settles. */
    public List<Map<String, Object>> inClearing() {
        return jdbc.queryForList("""
                SELECT d.*, a.account_no, a.branch_code FROM deposits.cheque_deposit d JOIN deposits.account a ON a.id = d.account_id
                 WHERE d.status = 'IN_CLEARING' ORDER BY d.lodged_on, d.created_at
                """).stream().filter(d -> scope.canSee((String) d.get("branch_code"))).map(this::depositView).toList();
    }

    // ================================================================================================ reading

    /** An account's cheque books with their leaves, the cheques of it presented, and the cheques paid into it. */
    public Map<String, Object> cheques(UUID accountId) {
        DepositMoneyService.Account a = money.account(accountId, DepositProductService.CASA, true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chequeFacility", chequeFacility(a.id()));
        out.put("books", jdbc.query("SELECT id, book_no, first_leaf, leaves, issued_on, issued_by FROM deposits.cheque_book WHERE account_id = ? ORDER BY first_leaf DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("bookNo", rs.getString(2));
                    m.put("firstLeaf", rs.getInt(3));
                    m.put("lastLeaf", rs.getInt(3) + rs.getInt(4) - 1);
                    m.put("leaves", rs.getInt(4));
                    m.put("issuedOn", rs.getObject(5, LocalDate.class));
                    m.put("issuedBy", rs.getString(6));
                    UUID book = rs.getObject(1, UUID.class);
                    m.put("unused", jdbc.queryForObject("SELECT count(*)::int FROM deposits.cheque_leaf WHERE book_id = ? AND status = 'UNUSED'", Integer.class, book));
                    return m;
                }, a.id()));
        out.put("leaves", jdbc.query("""
                SELECT leaf_no, status, stopped_on, stop_reason, paid_on FROM deposits.cheque_leaf WHERE account_id = ? AND status <> 'UNUSED' ORDER BY leaf_no DESC LIMIT 200
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("leafNo", rs.getInt(1));
                    m.put("status", rs.getString(2));
                    m.put("stoppedOn", rs.getObject(3, LocalDate.class));
                    m.put("stopReason", rs.getString(4));
                    m.put("paidOn", rs.getObject(5, LocalDate.class));
                    return m;
                }, a.id()));
        out.put("presented", jdbc.query("""
                SELECT id, leaf_no, amount, cheque_date, payee, presenting_bank, presented_on, status, return_reason
                  FROM deposits.cheque_presentment WHERE account_id = ? ORDER BY created_at DESC LIMIT 100
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("leafNo", rs.getInt(2));
                    m.put("amount", DepositRuleSets.plain(rs.getBigDecimal(3)));
                    m.put("chequeDate", rs.getObject(4, LocalDate.class));
                    m.put("payee", rs.getString(5));
                    m.put("presentingBank", rs.getString(6));
                    m.put("presentedOn", rs.getObject(7, LocalDate.class));
                    m.put("status", rs.getString(8));
                    m.put("returnReason", rs.getString(9));
                    m.put("returnReasonText", rs.getString(9) == null ? null : Cheques.ReturnReason.valueOf(rs.getString(9)).text());
                    return m;
                }, a.id()));
        out.put("paidIn", jdbc.queryForList("SELECT d.*, a.account_no FROM deposits.cheque_deposit d JOIN deposits.account a ON a.id = d.account_id WHERE d.account_id = ? ORDER BY d.created_at DESC LIMIT 100",
                a.id()).stream().map(this::depositView).toList());
        return out;
    }

    private static String trim(String v, int max, String field, List<String> problems) {
        String t = v == null ? null : v.trim();
        if (t == null || t.isEmpty()) {
            problems.add(field + " is required");
            return null;
        }
        if (t.length() > max) problems.add(field + " is at most " + max + " characters");
        return t;
    }

    /** Applies cheque requests once approved (a stop payment at once). */
    @Service
    static class Applier implements ApprovalApplier {
        private final DepositCheques cheques;

        Applier(@Lazy DepositCheques cheques) {
            this.cheques = cheques;
        }

        @Override public String entityType() { return ENTITY; }

        @Override public String apply(ApprovalRequest r) { return cheques.apply(r); }
    }
}
