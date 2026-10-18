package com.corebanking.deposits.internal;

import com.corebanking.calc.Rounding;
import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.deposits.DepositRules;
import com.corebanking.deposits.InterestDayEnd;
import com.corebanking.deposits.InterestDayEnd.Accrual;
import com.corebanking.deposits.InterestDayEnd.Credit;
import com.corebanking.deposits.RecurringDeposit;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Recurring deposits (P4-5) as the day-end, the closing code and the views need them: whether an account is one, the
 * instalments it holds month by month, its interest at day-end, the figures of a deposit paid on time, and the record
 * of each instalment. The figures come from the engine ({@link RecurringDeposit}).
 */
final class RecurringDeposits {

    static final String RD = "RD";

    private RecurringDeposits() {}

    static boolean isRd(JdbcTemplate jdbc, UUID accountId) {
        return !jdbc.queryForList("""
                SELECT 1 FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code WHERE a.id = ? AND p.product_type = 'RD'
                """, accountId).isEmpty();
    }

    /** An instalment paid and not taken back. */
    record Paid(int number, LocalDate dueDate, LocalDate paidOn, BigDecimal amount, BigDecimal lateCharge, String source) {}

    static List<Paid> paid(JdbcTemplate jdbc, UUID accountId) {
        return jdbc.query("""
                SELECT instalment_no, due_date, paid_on, amount::numeric, late_charge::numeric, source FROM deposits.rd_instalment
                 WHERE account_id = ? AND NOT reversed ORDER BY instalment_no
                """, (rs, i) -> new Paid(rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class), rs.getBigDecimal(4).stripTrailingZeros(),
                        rs.getBigDecimal(5).stripTrailingZeros(), rs.getString(6)), accountId);
    }

    /**
     * For month m, the instalments held through it: those paid on or before the day it starts (the balance only grows,
     * so that is the lowest amount held during the month).
     */
    static IntFunction<BigDecimal> held(List<Paid> paid, LocalDate start) {
        return m -> {
            LocalDate from = start.plusMonths(m - 1L);
            BigDecimal total = BigDecimal.ZERO;
            for (Paid p : paid) if (!p.paidOn().isAfter(from)) total = total.add(p.amount());
            return total;
        };
    }

    /** What the deposit has paid in: the instalments held. */
    static BigDecimal paidIn(List<Paid> paid) {
        return paid.stream().map(Paid::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Interest earned at {@code rate} for the whole months run by {@code on}, on the instalments actually paid: what a premature closure pays. */
    static BigDecimal interestFor(JdbcTemplate jdbc, DepositClosing.Deposit d, BigDecimal rate, LocalDate on) {
        return RecurringDeposit.interestFor(d.start(), d.tenure().months(), rate, d.periodMonths(), d.rounding(), on, held(paid(jdbc, d.id()), d.start()));
    }

    /** The maturity value of a deposit whose instalments are all paid on time. */
    static BigDecimal maturityValue(BigDecimal instalment, BigDecimal rate, int months, int compounding, Rounding rounding) {
        return RecurringDeposit.maturityValue(instalment, rate, months, compounding, rounding);
    }

    /** The day-end of one recurring deposit: each month that has ended is booked, each compounding month and maturity credited, with tax. */
    static void dayEnd(DepositBooks b, DepositRules rules, LocalDate through, UUID id) {
        JdbcTemplate jdbc = b.jdbc();
        String payable = DepositEod.payableGl(jdbc);
        Map<String, Object> t = jdbc.queryForMap(DepositEod.TERMS + " FOR UPDATE OF a", id);
        LocalDate start = DepositEod.date(t.get("start_date"));
        int months = jdbc.queryForObject("SELECT tenure_months FROM deposits.term_deposit WHERE account_id = ?", Integer.class, id);
        List<Map<String, Object>> st = jdbc.queryForList("""
                SELECT booked::numeric AS booked, credited::numeric AS credited, periods_credited FROM deposits.interest_state WHERE account_id = ?
                """, id);
        RecurringDeposit.State state = st.isEmpty() ? RecurringDeposit.State.starting()
                : new RecurringDeposit.State(((Number) st.get(0).get("periods_credited")).intValue(), (BigDecimal) st.get(0).get("booked"),
                        (BigDecimal) st.get(0).get("credited"));
        BigDecimal rate = ((BigDecimal) t.get("rate_percent")).stripTrailingZeros();
        int compounding = ((Number) t.get("interest_period_months")).intValue();
        RecurringDeposit.Result r = RecurringDeposit.dayEnd(start, months, rate, compounding, Rounding.valueOf((String) t.get("rounding")), state, through,
                held(paid(jdbc, id), start));
        if (r.events().isEmpty() && r.state().equals(state)) return;
        String accountNo = (String) t.get("account_no");
        String branch = (String) t.get("branch_code");
        Leg leg = new Leg(branch, (String) t.get("gl_liability"), accountNo);
        LocalDate lastCredit = null;
        for (InterestDayEnd.Event e : r.events()) {
            if (e instanceof Accrual acc) {
                TransactionLot lot = b.post().interestAccrual(branch, (String) t.get("gl_interest_expense"), payable, accountNo, acc.amount(), accountNo);
                b.post(lot);
                b.event(id, "ACCRUAL", acc.from(), acc.to(), null, acc.amount(), lot.id(), null, "interest for the month to " + acc.to());
            } else if (e instanceof Credit c) {
                String narration = "Interest to month " + c.number() + " (" + c.periodFrom() + " to " + c.periodEnd().minusDays(1) + ")";
                TransactionLot lot = b.post().interestCredit(leg, payable, c.amount(), accountNo, narration);
                b.post(lot);
                UUID m = b.movement(id, "INTEREST_CREDIT", "CREDIT", c.amount(), lot.id(), null, narration, null);
                b.event(id, "CREDIT", c.periodFrom(), c.periodEnd(), c.number(), c.amount(), lot.id(), m, null);
                if (Boolean.TRUE.equals(t.get("tds_applicable"))) {
                    TaxAtSource.onCredit(b, rules, id, (UUID) t.get("customer_id"), leg, c.amount(), m, c.periodFrom(), c.periodEnd());
                }
                lastCredit = c.periodEnd();
            }
        }
        RecurringDeposit.State s = r.state();
        jdbc.update("""
                INSERT INTO deposits.interest_state (account_id, accrued_through, booked, accrued, credited, periods_credited, last_credit_on)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id) DO UPDATE SET accrued_through = EXCLUDED.accrued_through, booked = EXCLUDED.booked, accrued = EXCLUDED.accrued,
                       credited = EXCLUDED.credited, periods_credited = EXCLUDED.periods_credited,
                       last_credit_on = coalesce(EXCLUDED.last_credit_on, deposits.interest_state.last_credit_on), updated_at = now()
                """, id, start.plusMonths(s.monthsDone()).minusDays(1), s.booked(), s.booked().subtract(s.credited()), s.credited(), s.monthsDone(),
                lastCredit);
    }

    /** Records an instalment with the movement that paid it into the deposit. */
    static void record(JdbcTemplate jdbc, UUID accountId, int number, LocalDate dueDate, BigDecimal amount, BigDecimal lateCharge, LocalDate paidOn,
                       String source, UUID movementId, UUID lotId, UUID approvalId, String user) {
        jdbc.update("""
                INSERT INTO deposits.rd_instalment (account_id, instalment_no, due_date, amount, late_charge, paid_on, source, movement_id, lot_id,
                                                    approval_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, accountId, number, dueDate, amount, lateCharge, paidOn, source, movementId, lotId, approvalId, user);
    }

    /** The first instalment, recorded when a recurring deposit is funded (P4-2): due and paid on the start date. */
    static void recordFunding(JdbcTemplate jdbc, UUID accountId, BigDecimal amount, LocalDate today, String source, UUID movementId, UUID approvalId,
                              String user) {
        if (!isRd(jdbc, accountId)) return;
        UUID lot = jdbc.queryForObject("SELECT lot_id FROM deposits.movement WHERE id = ?", UUID.class, movementId);
        record(jdbc, accountId, 1, today, amount, BigDecimal.ZERO, today, source, movementId, lot, approvalId, user);
    }

    /** After the funding of a recurring deposit is reversed: its first instalment no longer counts. */
    static void fundingReversed(JdbcTemplate jdbc, UUID fundingMovement) {
        jdbc.update("UPDATE deposits.rd_instalment SET reversed = true WHERE movement_id = ? AND NOT reversed", fundingMovement);
    }

    /**
     * The instalment schedule of a funded recurring deposit as of {@code today}: each instalment with its due date and
     * status (PAID, OVERDUE, DUE today or later), and what paying the next one today takes, its late charge included.
     */
    static Map<String, Object> schedule(JdbcTemplate jdbc, UUID accountId, LocalDate today) {
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT t.principal::numeric AS instalment, t.tenure_months, t.start_date, t.maturity_date, t.rounding, p.rd_late_charge_per_100::numeric AS late,
                       a.status
                  FROM deposits.term_deposit t JOIN deposits.account a ON a.id = t.account_id JOIN deposits.product p ON p.code = a.product_code
                 WHERE t.account_id = ?
                """, accountId);
        BigDecimal instalment = ((BigDecimal) t.get("instalment")).stripTrailingZeros();
        int months = ((Number) t.get("tenure_months")).intValue();
        LocalDate start = DepositEod.date(t.get("start_date"));
        BigDecimal late = ((BigDecimal) t.get("late")).stripTrailingZeros();
        Rounding rounding = Rounding.valueOf((String) t.get("rounding"));
        List<Paid> paid = start == null ? List.of() : paid(jdbc, accountId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instalment", DepositRuleSets.plain(instalment));
        out.put("instalments", months);
        out.put("lateChargePer100", DepositRuleSets.plain(late));
        out.put("paidCount", paid.size());
        out.put("paidIn", DepositRuleSets.plain(paidIn(paid)));
        out.put("lateChargesPaid", DepositRuleSets.plain(paid.stream().map(Paid::lateCharge).reduce(BigDecimal.ZERO, BigDecimal::add)));
        List<Map<String, Object>> rows = new ArrayList<>();
        int overdue = 0;
        Map<String, Object> next = null;
        if (start != null) {
            for (RecurringDeposit.Instalment i : RecurringDeposit.schedule(start, months, instalment)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("number", i.number());
                row.put("dueDate", i.dueDate());
                Paid p = i.number() <= paid.size() ? paid.get(i.number() - 1) : null;
                row.put("amount", DepositRuleSets.plain(i.amount()));
                row.put("paidOn", p == null ? null : p.paidOn());
                row.put("lateCharge", p == null ? null : DepositRuleSets.plain(p.lateCharge()));
                row.put("source", p == null ? null : p.source());
                String status = p != null ? "PAID" : i.dueDate().isBefore(today) ? "OVERDUE" : "DUE";
                if ("OVERDUE".equals(status)) overdue++;
                row.put("status", status);
                rows.add(row);
                if (p == null && next == null) {
                    next = new LinkedHashMap<>();
                    BigDecimal charge = RecurringDeposit.latePenalty(i.amount(), i.dueDate(), today, late, rounding);
                    next.put("number", i.number());
                    next.put("dueDate", i.dueDate());
                    next.put("amount", DepositRuleSets.plain(i.amount()));
                    next.put("lateCharge", DepositRuleSets.plain(charge));
                    next.put("total", DepositRuleSets.plain(i.amount().add(charge)));
                    next.put("payableNow", "ACTIVE".equals(t.get("status")) && today.isBefore(DepositEod.date(t.get("maturity_date"))));
                }
            }
        }
        out.put("overdueCount", overdue);
        out.put("next", next);
        out.put("schedule", rows);
        return out;
    }
}
