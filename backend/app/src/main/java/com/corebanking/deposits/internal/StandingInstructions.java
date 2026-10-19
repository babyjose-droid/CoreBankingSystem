package com.corebanking.deposits.internal;

import com.corebanking.deposits.StandingInstructionDates;
import com.corebanking.eod.EodListener;
import com.corebanking.lending.LoanOperations;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.JobHandler;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Standing instructions on savings and current accounts (P4-5b), given through a checker:
 * <ul>
 *   <li>TRANSFER: a fixed amount to another savings or current account every 1, 3, 6 or 12 months from the start date;</li>
 *   <li>LOAN_INSTALMENT: what is due on each instalment date of a loan of the account holder (the sweep); the account
 *       is debited and the loan takes the repayment, in one transaction;</li>
 *   <li>RD_INSTALMENT: each instalment of a recurring deposit on its due date, with its late charge when it is late.</li>
 * </ul>
 * They are carried out by the job STANDING_INSTRUCTIONS (every 15 minutes) and right after end of day opens the next
 * date: each payment due on a date is tried from that date, and again on each run for {@code deposits.si.retry-days}
 * days more (3), until it is made; a payment is made once (one DONE run per date), whole or not at all. Every attempt
 * that fails is recorded with the reason. An instruction ends by itself when its end date and its retries have passed,
 * the paying account is no longer active, the loan is closed or the recurring deposit has nothing more to pay.
 */
@Service
public class StandingInstructions implements JobHandler, EodListener {

    private static final Logger log = LoggerFactory.getLogger(StandingInstructions.class);
    static final String ENTITY = "STANDING_INSTRUCTION";
    static final String KIND = "STANDING_INSTRUCTIONS";
    static final Set<String> KINDS = Set.of("TRANSFER", "LOAN_INSTALMENT", "RD_INSTALMENT");

    /** openapi.yaml#/components/schemas/StandingInstructionInput. */
    public record InstructionInput(UUID fromAccountId, String kind, UUID toAccountId, UUID loanId, BigDecimal amount, Integer everyMonths,
                                   LocalDate startOn, LocalDate endOn, String narration) {}

    /** What a standing instruction request carries. {@code instructionId} and {@code reason}: a cancellation. */
    public record Request(String instructionId, String instructionNo, String fromAccountId, String fromAccountNo, String kind, String toAccountId,
                          String toAccountNo, String loanId, String loanNo, String amount, Integer everyMonths, String startOn, String endOn,
                          String narration, String reason) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final BusinessDays days;
    private final BranchScope scope;
    private final DepositMoneyService money;
    private final LoanOperations loans;
    private final Json json;

    StandingInstructions(JdbcTemplate jdbc, ApprovalService approvals, BusinessDays days, BranchScope scope, DepositMoneyService money,
                         LoanOperations loans, Json json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.days = days;
        this.scope = scope;
        this.money = money;
        this.loans = loans;
        this.json = json;
    }

    // ================================================================================================ giving and cancelling

    @Transactional
    public ApprovalRequest propose(InstructionInput in, String idempotencyKey) {
        if (in == null || in.kind() == null || !KINDS.contains(in.kind())) throw ApiException.invalid("kind is one of TRANSFER, LOAN_INSTALMENT, RD_INSTALMENT");
        if (in.fromAccountId() == null) throw ApiException.invalid("fromAccountId is required");
        DepositMoneyService.Account from = money.account(in.fromAccountId(), DepositProductService.CASA, true);
        List<String> problems = new ArrayList<>();
        LocalDate today = days.requireOpen();
        if (!"ACTIVE".equals(from.status())) problems.add("account " + from.accountNo() + " is " + from.status());
        LocalDate start = in.startOn() == null ? today : in.startOn();
        if (start.isBefore(today)) problems.add("the instruction starts on or after the business date (" + today + ")");
        if (in.endOn() != null && in.endOn().isBefore(start)) problems.add("the end date is before the start date");
        String narration = in.narration() == null || in.narration().isBlank() ? null : in.narration().trim();
        if (narration != null && narration.length() > 200) problems.add("the narration is at most 200 characters");
        DepositMoneyService.Account to = null;
        LoanOperations.Terms loan = null;
        switch (in.kind()) {
            case "TRANSFER" -> {
                if (in.toAccountId() == null) problems.add("name the account the money goes to");
                else {
                    to = money.account(in.toAccountId(), DepositProductService.CASA, false);
                    if (to.id().equals(from.id())) problems.add("an account does not pay itself");
                    else if (!"ACTIVE".equals(to.status())) problems.add("account " + to.accountNo() + " is " + to.status());
                }
                if (in.amount() == null || in.amount().signum() <= 0 || in.amount().stripTrailingZeros().scale() > 2) {
                    problems.add("the amount is above zero, to the paisa");
                }
                if (in.everyMonths() == null || !Set.of(1, 3, 6, 12).contains(in.everyMonths())) problems.add("everyMonths is 1, 3, 6 or 12");
                if (in.loanId() != null) problems.add("a transfer names no loan");
            }
            case "LOAN_INSTALMENT" -> {
                if (in.loanId() == null) problems.add("name the loan whose instalments are paid");
                else {
                    loan = loans.terms(in.loanId());
                    if (!Set.of("SANCTIONED", "ACTIVE").contains(loan.status())) problems.add("loan " + loan.loanNo() + " is " + loan.status());
                    if (!money.holds(from.id(), loan.customerId())) problems.add("account " + from.accountNo() + " is not held by the borrower of loan " + loan.loanNo());
                    if (active("loan_id = ?", loan.loanId())) problems.add("loan " + loan.loanNo() + " is already paid by a standing instruction");
                }
                if (in.amount() != null || in.everyMonths() != null || in.toAccountId() != null) {
                    problems.add("a loan instalment instruction pays what falls due: it has no amount, frequency or other account");
                }
            }
            default -> {
                if (in.toAccountId() == null) problems.add("name the recurring deposit");
                else {
                    to = money.account(in.toAccountId(), DepositProductService.TERM_DEPOSIT, false);
                    if (!RecurringDeposits.isRd(jdbc, to.id())) problems.add("deposit " + to.accountNo() + " is not a recurring deposit");
                    else if (!"ACTIVE".equals(to.status())) problems.add("recurring deposit " + to.accountNo() + " is " + to.status() + " (fund it first)");
                    if (jdbc.queryForList("""
                            SELECT 1 FROM deposits.account_holder x JOIN deposits.account_holder y ON y.customer_id = x.customer_id
                             WHERE x.account_id = ? AND y.account_id = ?
                            """, from.id(), to.id()).isEmpty()) {
                        problems.add("account " + from.accountNo() + " and deposit " + to.accountNo() + " have no holder in common");
                    }
                    if (active("to_account_id = ? AND kind = 'RD_INSTALMENT'", to.id())) problems.add("deposit " + to.accountNo() + " is already paid by a standing instruction");
                }
                if (in.amount() != null || in.everyMonths() != null || in.loanId() != null) {
                    problems.add("a recurring deposit instruction pays each instalment: it has no amount, frequency or loan");
                }
            }
        }
        DepositAccountService.refuseIfAny("the standing instruction cannot be given", problems);
        Request r = new Request(null, null, from.id().toString(), from.accountNo(), in.kind(), to == null ? null : to.id().toString(),
                to == null ? null : to.accountNo(), loan == null ? null : loan.loanId().toString(), loan == null ? null : loan.loanNo(),
                in.amount() == null ? null : DepositRuleSets.plain(in.amount()), in.everyMonths(), start.toString(),
                in.endOn() == null ? null : in.endOn().toString(), narration, null);
        return approvals.propose(ENTITY, "CREATE", from.id().toString(), json.toMap(r), null, in.amount(), from.branch(), idempotencyKey);
    }

    private boolean active(String where, UUID id) {
        return !jdbc.queryForList("SELECT 1 FROM deposits.standing_instruction WHERE status = 'ACTIVE' AND " + where, id).isEmpty();
    }

    @Transactional
    public ApprovalRequest proposeCancel(UUID id, DepositMoneyService.ReasonInput in, String idempotencyKey) {
        Map<String, Object> si = row(id);
        DepositMoneyService.Account from = money.account((UUID) si.get("from_account_id"), DepositProductService.CASA, true);
        List<String> problems = new ArrayList<>();
        if (!"ACTIVE".equals(si.get("status"))) problems.add("standing instruction " + si.get("instruction_no") + " is " + si.get("status"));
        String reason = in == null || in.reason() == null ? null : in.reason().trim();
        if (reason == null || reason.isEmpty()) problems.add("a reason is required");
        else if (reason.length() > 500) problems.add("the reason is at most 500 characters");
        DepositAccountService.refuseIfAny("the standing instruction cannot be cancelled", problems);
        Request r = new Request(id.toString(), (String) si.get("instruction_no"), from.id().toString(), from.accountNo(), (String) si.get("kind"),
                null, null, null, null, null, null, null, null, null, reason);
        return approvals.propose(ENTITY, "CANCEL", id.toString(), json.toMap(r), null, null, from.branch(), idempotencyKey);
    }

    String apply(ApprovalRequest r) {
        Request q = json.convert(r.payload(), Request.class);
        if ("CANCEL".equals(r.action())) {
            int n = jdbc.update("""
                    UPDATE deposits.standing_instruction SET status = 'CANCELLED', closed_on = current_date, closed_by = ?, close_reason = ?,
                           close_approval_id = ? WHERE id = ? AND status = 'ACTIVE'
                    """, r.maker(), q.reason(), r.id(), UUID.fromString(q.instructionId()));
            if (n == 0) throw ApiException.conflict("standing instruction " + q.instructionNo() + " is no longer active");
            return q.instructionNo();
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deposits.standing_instruction (id, from_account_id, kind, to_account_id, loan_id, amount, every_months, start_on, end_on,
                                                           narration, created_by, approval_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::date, ?::date, ?, ?, ?)
                """, id, UUID.fromString(q.fromAccountId()), q.kind(), q.toAccountId() == null ? null : UUID.fromString(q.toAccountId()),
                q.loanId() == null ? null : UUID.fromString(q.loanId()), q.amount() == null ? null : new BigDecimal(q.amount()), q.everyMonths(),
                q.startOn(), q.endOn(), q.narration(), r.maker(), r.id());
        return jdbc.queryForObject("SELECT instruction_no FROM deposits.standing_instruction WHERE id = ?", String.class, id);
    }

    // ================================================================================================ reading

    private static final String SELECT = """
            SELECT s.id, s.instruction_no, s.kind, s.from_account_id, f.account_no AS from_account_no, s.to_account_id, t.account_no AS to_account_no,
                   s.loan_id, l.loan_no, s.amount::numeric AS amount, s.every_months, s.start_on, s.end_on, s.narration, s.status, s.created_by,
                   s.created_at, s.closed_on, s.closed_by, s.close_reason, f.branch_code,
                   (SELECT max(r.for_date) FROM deposits.standing_instruction_run r WHERE r.instruction_id = s.id AND r.status = 'DONE') AS last_paid_for,
                   (SELECT count(*) FROM deposits.standing_instruction_run r WHERE r.instruction_id = s.id AND r.status = 'DONE') AS paid_count
              FROM deposits.standing_instruction s JOIN deposits.account f ON f.id = s.from_account_id
              LEFT JOIN deposits.account t ON t.id = s.to_account_id LEFT JOIN lending.loan_account l ON l.id = s.loan_id
            """;

    private Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " WHERE s.id = ?", id);
        if (rows.isEmpty()) throw ApiException.notFound("standing instruction " + id);
        return rows.get(0);
    }

    private Map<String, Object> view(Map<String, Object> s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.get("id").toString());
        m.put("instructionNo", s.get("instruction_no"));
        m.put("kind", s.get("kind"));
        m.put("fromAccountId", s.get("from_account_id").toString());
        m.put("fromAccountNo", s.get("from_account_no"));
        m.put("toAccountId", s.get("to_account_id") == null ? null : s.get("to_account_id").toString());
        m.put("toAccountNo", s.get("to_account_no"));
        m.put("loanId", s.get("loan_id") == null ? null : s.get("loan_id").toString());
        m.put("loanNo", s.get("loan_no"));
        m.put("amount", s.get("amount") == null ? null : DepositRuleSets.plain((BigDecimal) s.get("amount")));
        m.put("everyMonths", s.get("every_months") == null ? null : ((Number) s.get("every_months")).intValue());
        m.put("startOn", String.valueOf(s.get("start_on")));
        m.put("endOn", s.get("end_on") == null ? null : String.valueOf(s.get("end_on")));
        m.put("narration", s.get("narration"));
        m.put("status", s.get("status"));
        m.put("createdBy", s.get("created_by"));
        m.put("closedOn", s.get("closed_on") == null ? null : String.valueOf(s.get("closed_on")));
        m.put("closedBy", s.get("closed_by"));
        m.put("closeReason", s.get("close_reason"));
        m.put("lastPaidFor", s.get("last_paid_for") == null ? null : String.valueOf(s.get("last_paid_for")));
        m.put("paidCount", ((Number) s.get("paid_count")).intValue());
        return m;
    }

    /** The instructions an account pays, or that pay a loan; newest first. */
    public List<Map<String, Object>> list(UUID accountId, UUID loanId) {
        if (accountId == null && loanId == null) throw ApiException.invalid("give accountId or loanId");
        if (accountId != null) {
            DepositMoneyService.Account a = money.account(accountId, null, true);
            return jdbc.queryForList(SELECT + " WHERE s.from_account_id = ? OR s.to_account_id = ? ORDER BY s.status = 'ACTIVE' DESC, s.created_at DESC",
                    a.id(), a.id()).stream().map(this::view).toList();
        }
        LoanOperations.Terms loan = loans.terms(loanId);
        scope.requireRecord(loan.branch(), "loan " + loanId);
        return jdbc.queryForList(SELECT + " WHERE s.loan_id = ? ORDER BY s.status = 'ACTIVE' DESC, s.created_at DESC", loanId).stream().map(this::view).toList();
    }

    /** One instruction with its runs (the latest 100). */
    public Map<String, Object> get(UUID id) {
        Map<String, Object> s = row(id);
        scope.requireRecord((String) s.get("branch_code"), "standing instruction " + id);
        Map<String, Object> m = view(s);
        m.put("runs", jdbc.query("""
                SELECT for_date, business_date, status, amount::numeric, reason, attempts, movement_id::text, loan_txn_id::text, updated_at
                  FROM deposits.standing_instruction_run WHERE instruction_id = ? ORDER BY for_date DESC, business_date DESC LIMIT 100
                """, (rs, i) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("forDate", rs.getObject(1, LocalDate.class));
                    r.put("businessDate", rs.getObject(2, LocalDate.class));
                    r.put("status", rs.getString(3));
                    r.put("amount", rs.getBigDecimal(4) == null ? null : DepositRuleSets.plain(rs.getBigDecimal(4)));
                    r.put("reason", rs.getString(5));
                    r.put("attempts", rs.getInt(6));
                    r.put("movementId", rs.getString(7));
                    r.put("loanTxnId", rs.getString(8));
                    return r;
                }, id));
        return m;
    }

    // ================================================================================================ carrying out

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Result run(Context context) {
        return runDue();
    }

    @Override
    public void businessDateOpened(String tenant, LocalDate closed, LocalDate opened) {
        Result r = runDue();
        if (r.processed() + r.failed() > 0) log.info("after end of day for {}: {} standing instruction payments made on {}, {} failed", tenant, r.processed(), opened, r.failed());
    }

    /** Carries out every payment due and not yet made. Does nothing while the business day is not open. */
    public Result runDue() {
        BusinessDays.BusinessDay day = days.current();
        Map<String, Object> artifact = new LinkedHashMap<>();
        if (day == null || !"OPEN".equals(day.status())) {
            artifact.put("skipped", "the business day is not open");
            return new Result(0, 0, artifact);
        }
        LocalDate today = day.businessDate();
        int retry = retryDays();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        int paid = 0;
        int failed = 0;
        int ended = 0;
        for (UUID id : jdbc.queryForList("SELECT id FROM deposits.standing_instruction WHERE status = 'ACTIVE' AND start_on <= ? ORDER BY instruction_no",
                UUID.class, today)) {
            try {
                Tally t = runOne(tx, id, today, retry);
                paid += t.paid;
                failed += t.failed;
                ended += t.ended;
            } catch (RuntimeException e) {
                failed++;
                log.warn("standing instruction {} could not be looked at", id, e);
            }
        }
        artifact.put("businessDate", today.toString());
        artifact.put("ended", ended);
        return new Result(paid, failed, artifact);
    }

    private static final class Tally {
        int paid;
        int failed;
        int ended;
    }

    private int retryDays() {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = 'deposits.si.retry-days'", String.class);
        try {
            return v.isEmpty() ? 3 : Math.max(0, Integer.parseInt(v.get(0).trim()));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    private Tally runOne(TransactionTemplate tx, UUID id, LocalDate today, int retry) {
        Tally t = new Tally();
        Map<String, Object> s = row(id);
        String kind = (String) s.get("kind");
        LocalDate start = ((java.sql.Date) s.get("start_on")).toLocalDate();
        LocalDate end = s.get("end_on") == null ? null : ((java.sql.Date) s.get("end_on")).toLocalDate();
        String no = (String) s.get("instruction_no");
        DepositMoneyService.Account from = money.account((UUID) s.get("from_account_id"), DepositProductService.CASA, false);
        String endReason = null;
        if (!"ACTIVE".equals(from.status())) endReason = "account " + from.accountNo() + " is " + from.status();
        else if (StandingInstructionDates.over(end, today, retry)) endReason = "the end date " + end + " has passed";
        LoanOperations.Terms loan = null;
        DepositMoneyService.Account to = null;
        if (endReason == null && "LOAN_INSTALMENT".equals(kind)) {
            loan = loans.terms((UUID) s.get("loan_id"));
            if (Set.of("CLOSED", "CANCELLED", "WRITTEN_OFF").contains(loan.status())) endReason = "loan " + loan.loanNo() + " is " + loan.status();
        } else if (endReason == null) {
            to = money.account((UUID) s.get("to_account_id"), null, false);
            if ("RD_INSTALMENT".equals(kind) && (!"ACTIVE".equals(to.status()) || rdNext(to.id(), today) == null)) {
                endReason = "recurring deposit " + to.accountNo() + (!"ACTIVE".equals(to.status()) ? " is " + to.status() : " has every instalment paid");
            } else if ("TRANSFER".equals(kind) && !"ACTIVE".equals(to.status())) {
                endReason = "account " + to.accountNo() + " is " + to.status();
            }
        }
        if (endReason != null) {
            final String why = endReason;
            tx.executeWithoutResult(x -> jdbc.update("""
                    UPDATE deposits.standing_instruction SET status = 'ENDED', closed_on = current_date, closed_by = 'system', close_reason = ?
                     WHERE id = ? AND status = 'ACTIVE'
                    """, why, id));
            t.ended++;
            return t;
        }
        switch (kind) {
            case "TRANSFER" -> {
                BigDecimal amount = (BigDecimal) s.get("amount");
                final DepositMoneyService.Account target = to;
                for (LocalDate d : StandingInstructionDates.transferDates(start, ((Number) s.get("every_months")).intValue(), end, today, retry)) {
                    attempt(tx, t, id, no, d, amount, () -> money.siTransfer(from, target, amount, no,
                            s.get("narration") == null ? "Standing instruction " + no : (String) s.get("narration")), null);
                }
            }
            case "LOAN_INSTALMENT" -> {
                final LoanOperations.Terms l = loan;
                for (LocalDate d : StandingInstructionDates.window(start, today, retry)) {
                    if (done(id, d)) continue;
                    LoanOperations.Due due = loans.dueFor(jdbc, l.loanId(), d);
                    if (due == null || due.amount().signum() <= 0) continue;
                    BigDecimal amount = due.amount();
                    attempt(tx, t, id, no, d, amount, () -> money.siLoanRepayment(from, l, amount, no, "Instalment of " + d + ", loan " + l.loanNo()),
                            p -> loans.postRepayment(l.loanId(), amount, today, "SI", no).txnId());
                }
            }
            default -> {
                final DepositMoneyService.Account rd = to;
                LocalDate window = StandingInstructionDates.windowStart(start, today, retry);
                for (int guard = 0; guard < 24; guard++) {
                    Map<String, Object> next = rdNext(rd.id(), today);
                    if (next == null || !Boolean.TRUE.equals(next.get("payableNow"))) break;
                    LocalDate due = LocalDate.parse(String.valueOf(next.get("dueDate")));
                    if (due.isAfter(today) || due.isBefore(window) || done(id, due)) break;
                    BigDecimal total = new BigDecimal((String) next.get("total"));
                    if (!attempt(tx, t, id, no, due, total, () -> money.siRdInstalment(from, rd, no), null)) break;
                }
            }
        }
        return t;
    }

    private Map<String, Object> rdNext(UUID rd, LocalDate today) {
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) RecurringDeposits.schedule(jdbc, rd, today).get("next");
        return next;
    }

    private boolean done(UUID id, LocalDate d) {
        return !jdbc.queryForList("SELECT 1 FROM deposits.standing_instruction_run WHERE instruction_id = ? AND for_date = ? AND status = 'DONE'", id, d).isEmpty();
    }

    @FunctionalInterface
    private interface Pay {
        DepositMoneyService.Paid pay();
    }

    @FunctionalInterface
    private interface After {
        UUID then(DepositMoneyService.Paid p);
    }

    /**
     * One payment for one date, in its own transaction with the instruction locked: made whole, recorded DONE; or not at
     * all, the failure recorded with its reason. Returns whether it was made.
     */
    private boolean attempt(TransactionTemplate tx, Tally t, UUID id, String no, LocalDate forDate, BigDecimal amount, Pay pay, After after) {
        if (done(id, forDate)) return false;
        try {
            Boolean made = tx.execute(x -> {
                List<String> locked = jdbc.queryForList("SELECT status FROM deposits.standing_instruction WHERE id = ? FOR UPDATE SKIP LOCKED", String.class, id);
                if (locked.isEmpty() || !"ACTIVE".equals(locked.get(0)) || done(id, forDate)) return false;
                DepositMoneyService.Paid p = pay.pay();
                UUID loanTxn = after == null ? null : after.then(p);
                jdbc.update("""
                        INSERT INTO deposits.standing_instruction_run (instruction_id, for_date, business_date, status, amount, lot_id, movement_id, loan_txn_id)
                        VALUES (?, ?, current_date, 'DONE', ?, ?, ?, ?)
                        """, id, forDate, p.amount(), p.lotId(), p.movementId(), loanTxn);
                return true;
            });
            if (Boolean.TRUE.equals(made)) {
                t.paid++;
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            t.failed++;
            String reason = e instanceof ApiException ? e.getMessage() : e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            if (!(e instanceof ApiException)) log.warn("standing instruction {}: the payment for {} failed", no, forDate, e);
            final String why = reason.length() > 1000 ? reason.substring(0, 1000) : reason;
            tx.executeWithoutResult(x -> {
                int n = jdbc.update("""
                        UPDATE deposits.standing_instruction_run SET attempts = attempts + 1, reason = ?, updated_at = now()
                         WHERE instruction_id = ? AND for_date = ? AND status = 'FAILED' AND business_date = (SELECT business_date FROM platform.business_day WHERE id = 1)
                        """, why, id, forDate);
                if (n == 0) {
                    jdbc.update("""
                            INSERT INTO deposits.standing_instruction_run (instruction_id, for_date, business_date, status, amount, reason)
                            VALUES (?, ?, current_date, 'FAILED', ?, ?)
                            """, id, forDate, amount, why);
                }
            });
            return false;
        }
    }

    /** Applies standing instruction requests once a checker approves them. */
    @Service
    static class Applier implements ApprovalApplier {
        private final StandingInstructions instructions;

        Applier(@Lazy StandingInstructions instructions) {
            this.instructions = instructions;
        }

        @Override public String entityType() { return ENTITY; }

        @Override public String apply(ApprovalRequest r) { return instructions.apply(r); }
    }
}
