package com.corebanking.lending.engine;

import static com.corebanking.lending.engine.Life.assertAmortises;
import static com.corebanking.lending.engine.Life.assertClosedAndReconciled;
import static com.corebanking.lending.engine.Life.bd;
import static com.corebanking.lending.engine.Life.eq;
import static com.corebanking.lending.engine.Life.params;
import static com.corebanking.lending.engine.Life.sum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Method 18 (each tranche repaid as its own bullet, interest monthly on the total outstanding) and the elapsed-tenure
 * rate table (rate by month of the loan, applied at day-end like a reset: EMI changes, tenure kept).
 */
class TrancheBulletAndRateStepTest {

    static final LocalDate OPEN = LocalDate.of(2026, 6, 30);

    static LoanTerms trancheBullet(String sanctioned, int months) {
        return new LoanTerms(bd(sanctioned), bd("12"), months, OPEN, null, RepaymentMethod.TRANCHE_BULLET, 0, null, DayCount.ACTUAL_365,
                null, false, null);
    }

    // ------------------------------------------------------------------------------------------------ tranche bullet
    @Test
    void each_tranche_is_repaid_on_its_own_maturity_with_interest_monthly_on_the_total() {
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.open(params("12", List.of()), trancheBullet("300000", 12), bd("100000"), false, OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        assertEquals(LocalDate.of(2027, 6, 30), a.tranches().get(0).maturity(), "the first tranche matures at the loan's tenor");

        Life.runPaying(a, gl, OPEN, LocalDate.of(2026, 7, 19));
        gl.post(a.drawTranche(bd("120000"), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 12, 31)).lots());
        Life.runPaying(a, gl, LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 9));
        gl.post(a.drawTranche(bd("80000"), LocalDate.of(2026, 8, 10), LocalDate.of(2027, 3, 31)).lots());

        List<Instalment> f = a.futureSchedule();
        eq("300000", sum(f, Instalment::principal), "every tranche is in the schedule");
        for (Instalment r : f) {
            BigDecimal expected = switch (r.dueDate().toString()) {
                case "2026-12-31" -> bd("120000");
                case "2027-03-31" -> bd("80000");
                case "2027-06-30" -> bd("100000");
                default -> BigDecimal.ZERO;
            };
            eq(expected.toPlainString(), r.principal(), "principal due on " + r.dueDate());
            assertTrue(r.interest().signum() > 0, "interest every month: " + r.dueDate());
        }
        assertEquals(LocalDate.of(2027, 6, 30), f.get(f.size() - 1).dueDate(), "the loan ends with the last maturity");

        Life.runPaying(a, gl, LocalDate.of(2026, 8, 10), LocalDate.of(2027, 1, 1));
        eq("180000", a.principalOutstanding(), "the second tranche was repaid on its maturity");
        Life.payOnTimeUntilClosed(a, gl, LocalDate.of(2027, 1, 2));
        assertClosedAndReconciled(a, gl, "tranche bullet");

        // interest = balance x days at 12% (ACTUAL/365), rounded per demand: within a rupee per demand
        BigDecimal expected = interest("100000", OPEN, LocalDate.of(2026, 7, 20))
                .add(interest("220000", LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 10)))
                .add(interest("300000", LocalDate.of(2026, 8, 10), LocalDate.of(2026, 12, 31)))
                .add(interest("180000", LocalDate.of(2026, 12, 31), LocalDate.of(2027, 3, 31)))
                .add(interest("100000", LocalDate.of(2027, 3, 31), LocalDate.of(2027, 6, 30)));
        BigDecimal diff = gl.cr("4101").subtract(expected).abs();
        assertTrue(diff.compareTo(BigDecimal.valueOf(a.demands().size())) <= 0, "interest " + gl.cr("4101") + " vs " + expected);
    }

    private static BigDecimal interest(String balance, LocalDate from, LocalDate to) {
        return bd(balance).multiply(bd("12")).multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to)))
                .divide(bd("36500"), 2, RoundingMode.HALF_UP);
    }

    @Test
    void a_tranche_needs_a_maturity_after_the_next_due_date_and_part_prepayment_is_refused() {
        var book = LoanAccount.open(params("12", List.of()), trancheBullet("300000", 12), bd("100000"), false, OPEN);
        LoanAccount a = book.account();
        Life.runEod(a, new Life.Gl(), OPEN, LocalDate.of(2026, 7, 19));
        LocalDate day = LocalDate.of(2026, 7, 20);
        assertThrows(IllegalArgumentException.class, () -> a.drawTranche(bd("50000"), day), "no maturity");
        assertThrows(IllegalArgumentException.class, () -> a.drawTranche(bd("50000"), day, LocalDate.of(2026, 7, 31)), "on the next due date");
        var sim = a.simulateTranche(bd("50000"), day, LocalDate.of(2026, 10, 15));
        assertTrue(sim.scheduleAfter().stream().anyMatch(r -> r.dueDate().equals(LocalDate.of(2026, 10, 15))
                && r.principal().compareTo(bd("50000")) == 0), "a maturity between due dates is its own row");
        eq("100000", a.principalOutstanding(), "a simulation changes nothing");
        assertThrows(IllegalStateException.class, () -> a.prepay(bd("10000"), LoanAccount.PrepaymentMode.REDUCE_EMI, day));
    }

    @Test
    void tranche_bullet_with_interest_at_maturity() {
        Life.Gl gl = new Life.Gl();
        LoanTerms t = new LoanTerms(bd("200000"), bd("12"), 6, OPEN, null, RepaymentMethod.TRANCHE_BULLET, 0, null, DayCount.ACTUAL_365,
                null, false, LoanTerms.Options.NONE.withInterestAtMaturity(true));
        var book = LoanAccount.open(params("12", List.of()), t, bd("100000"), false, OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        assertEquals(1, a.futureSchedule().size(), "one row: the bullet with its interest");
        Life.runPaying(a, gl, OPEN, LocalDate.of(2026, 7, 31));
        gl.post(a.drawTranche(bd("100000"), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 31)).lots());
        assertEquals(2, a.futureSchedule().size(), "a row for each maturity");
        Life.payOnTimeUntilClosed(a, gl, LocalDate.of(2026, 8, 1));
        assertClosedAndReconciled(a, gl, "tranche bullet, interest at maturity");
    }

    // ------------------------------------------------------------------------------------------------ elapsed-tenure steps
    static LoanTerms stepped() {
        return new LoanTerms(bd("120000"), bd("10"), 12, OPEN, null, RepaymentMethod.EQUATED, 0, null, DayCount.ACTUAL_365, null, false,
                LoanTerms.Options.NONE.withRateSteps(List.of(new LoanTerms.RateStep(1, bd("10")), new LoanTerms.RateStep(7, bd("12")))));
    }

    @Test
    void the_disclosed_schedule_shows_every_step() {
        List<Instalment> rows = ScheduleBuilder.build(stepped());
        assertAmortises(rows, bd("120000"), "stepped");
        BigDecimal first = rows.get(1).instalment();
        BigDecimal later = rows.get(7).instalment();
        for (int i = 1; i < 6; i++) eq(first.toPlainString(), rows.get(i).instalment(), "months 2-6 at 10%");
        for (int i = 6; i < 11; i++) eq(later.toPlainString(), rows.get(i).instalment(), "months 7-11 at 12%");
        assertTrue(later.compareTo(first) > 0, "the EMI rises with the step, tenure kept");
        assertThrows(IllegalArgumentException.class, () -> new LoanTerms(bd("120000"), bd("11"), 12, OPEN, null, RepaymentMethod.EQUATED, 0,
                null, DayCount.ACTUAL_365, null, false, stepped().options()), "the loan's rate is the first step's");
    }

    @Test
    void the_day_end_applies_the_step_as_disclosed_and_the_loan_closes_reconciled() {
        Life.Gl gl = new Life.Gl();
        List<Instalment> disclosed = ScheduleBuilder.build(stepped());
        var book = LoanAccount.disburse(params("10", List.of()), stepped(), OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        a.drainRateChanges();
        LocalDate sixth = disclosed.get(5).dueDate();
        Life.runPaying(a, gl, OPEN, sixth.minusDays(1));
        eq("10", a.ratePercent(), "10% for the first six months");
        assertTrue(a.drainRateChanges().isEmpty(), "no step before month 7");
        Life.runPaying(a, gl, sixth, sixth);
        eq("12", a.ratePercent(), "12% from the sixth due date (month 7)");
        List<LoanAccount.RateChange> changes = a.drainRateChanges();
        assertEquals(1, changes.size(), "one step");
        assertEquals(LoanAccount.RateCause.TENURE_STEP, changes.get(0).cause());
        assertEquals(6, a.futureSchedule().size(), "tenure kept");
        // paid on time, the rebuilt schedule is the disclosed one
        for (int i = 0; i < 6; i++) {
            assertTrue(a.futureSchedule().get(i).instalment().subtract(disclosed.get(6 + i).instalment()).abs().compareTo(BigDecimal.ONE) <= 0,
                    "row " + (7 + i) + " as disclosed");
        }
        Life.payOnTimeUntilClosed(a, gl, sixth.plusDays(1));
        assertClosedAndReconciled(a, gl, "stepped");
    }
}
