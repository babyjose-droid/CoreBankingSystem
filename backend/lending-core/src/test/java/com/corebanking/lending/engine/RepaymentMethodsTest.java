package com.corebanking.lending.engine;

import static com.corebanking.lending.engine.Life.assertAmortises;
import static com.corebanking.lending.engine.Life.assertClosedAndReconciled;
import static com.corebanking.lending.engine.Life.bd;
import static com.corebanking.lending.engine.Life.eq;
import static com.corebanking.lending.engine.Life.params;
import static com.corebanking.lending.engine.Life.sum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.FeeCalculator;
import com.corebanking.calc.RateSolver;
import com.corebanking.calc.Rounding;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.engine.LoanTerms.BpiMode;
import com.corebanking.lending.engine.LoanTerms.CustomRow;
import com.corebanking.lending.engine.LoanTerms.InterestBasis;
import com.corebanking.lending.engine.LoanTerms.Options;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P2-6 (US-039, US-054): every repayment method, frequency, interest basis and broken-period mode. For each: the
 * principal adds up, the last instalment absorbs rounding, interest equals the day-count (or periodic) formula, the
 * APR is the IRR of the actual flows, and a whole life paid on time reconciles to the general ledger.
 */
class RepaymentMethodsTest {

    static final LocalDate OPEN = LocalDate.of(2026, 6, 30);
    static final LocalDate JUL1 = LocalDate.of(2026, 7, 1);

    static LoanTerms terms(String principal, String rate, int periods, LocalDate open, LocalDate firstDue, RepaymentMethod m, Options o) {
        return new LoanTerms(bd(principal), bd(rate), periods, open, firstDue, m, 0, BigDecimal.ZERO, DayCount.ACTUAL_365,
                Rounding.RUPEE_HALF_UP, false, o);
    }

    /** Interest of every row = round(opening balance × rate × actual days / 365). */
    static void assertDayCountInterest(List<Instalment> rows, String rate, LocalDate open, String what) {
        LocalDate prev = open;
        for (Instalment i : rows) {
            BigDecimal expected = i.openingBalance().multiply(bd(rate)).multiply(BigDecimal.valueOf(DayCount.ACTUAL_365.days(prev, i.dueDate())))
                    .divide(bd("36500"), 0, RoundingMode.HALF_UP);
            eq(expected.toPlainString(), i.interest(), what + ": interest of " + i.number());
            assertEquals(DayCount.ACTUAL_365.days(prev, i.dueDate()), i.days(), what + ": days of " + i.number());
            prev = i.dueDate();
        }
    }

    static LoanAccount lifecycle(LoanTerms t, List<FeeRule> fees, String what) {
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.disburse(params(t.ratePercent().toPlainString(), fees), t, t.disbursalDate());
        gl.post(book.result().lots());
        Life.payOnTimeUntilClosed(book.account(), gl, t.disbursalDate());
        assertClosedAndReconciled(book.account(), gl, what);
        eq(ScheduleBuilder.plan(t).totalInterest().toPlainString(), gl.cr("4101"), what + ": income = scheduled interest");
        return book.account();
    }

    // ------------------------------------------------------------------------------------------------ frequencies
    @Test
    void weekly_equated_micro_loan_golden() {
        LoanTerms t = terms("30000", "24", 50, JUL1, null, RepaymentMethod.EQUATED, Options.of(Frequency.WEEKLY));
        var plan = ScheduleBuilder.plan(t);
        var rows = plan.schedule();
        assertEquals(50, rows.size());
        eq("673", plan.emi(), "weekly EMI = PMT(24%/52, 50)");
        assertEquals(LocalDate.of(2026, 7, 8), rows.get(0).dueDate());
        eq("138", rows.get(0).interest(), "first week interest 30000 × 24% × 7/365");
        eq("535", rows.get(0).principal(), "first week principal");
        assertEquals(LocalDate.of(2027, 6, 16), rows.get(49).dueDate());
        eq("672", rows.get(49).principal(), "last instalment clears the balance");
        eq("3", rows.get(49).interest(), "last interest");
        eq("675", rows.get(49).instalment(), "last instalment absorbs rounding");
        eq("3652", plan.totalInterest(), "total interest");
        assertAmortises(rows, bd("30000"), "weekly");
        assertDayCountInterest(rows, "24", JUL1, "weekly");
        eq("23.93", Apr.of(t, plan, BigDecimal.ZERO), "APR: weekly IRR × 52 (52 weeks are 364 days, so just under 24%)");
        lifecycle(t, List.of(), "weekly");
    }

    @Test
    void every_method_at_every_frequency_amortises_with_day_count_interest() {
        for (Frequency f : Frequency.values()) {
            int n = f == Frequency.DAILY ? 30 : f == Frequency.YEARLY ? 3 : 8;
            for (RepaymentMethod m : List.of(RepaymentMethod.EQUATED, RepaymentMethod.FIXED_PRINCIPAL,
                    RepaymentMethod.BULLET_PERIODIC_INTEREST, RepaymentMethod.BULLET_TOTAL_INTEREST)) {
                String what = m + " " + f;
                LoanTerms t = terms("200000", "10", n, OPEN, null, m, Options.of(f));
                var plan = ScheduleBuilder.plan(t);
                var rows = plan.schedule();
                assertEquals(m == RepaymentMethod.BULLET_TOTAL_INTEREST ? 1 : n, rows.size(), what + ": rows");
                assertAmortises(rows, bd("200000"), what);
                assertDayCountInterest(rows, "10", OPEN, what);
                assertEquals(f.plus(OPEN, n), rows.get(rows.size() - 1).dueDate(), what + ": maturity");
                eq("10", plan.accrualRatePercent(), what + ": accrual rate");
                if (m == RepaymentMethod.EQUATED) {
                    for (int i = 0; i < n - 1; i++) eq(plan.emi().toPlainString(), rows.get(i).instalment(), what + ": level instalment " + (i + 1));
                    // the EMI uses rate / periods per year, the interest actual days: the last instalment takes the difference
                    assertTrue(rows.get(n - 1).instalment().subtract(plan.emi()).abs().compareTo(plan.emi().multiply(bd("0.05"))) <= 0,
                            what + ": last instalment close to the EMI: " + rows.get(n - 1).instalment() + " vs " + plan.emi());
                } else {
                    assertNull(plan.emi());
                }
                if (m == RepaymentMethod.BULLET_PERIODIC_INTEREST) {
                    for (int i = 0; i < n - 1; i++) eq("0", rows.get(i).principal(), what + ": interest only");
                }
            }
        }
        eq("27893", ScheduleBuilder.emi(terms("200000", "10", 8, OPEN, null, RepaymentMethod.EQUATED, Options.of(Frequency.QUARTERLY))),
                "quarterly EMI = PMT(10%/4, 8)");
        // month-end anchoring holds for every month-based frequency
        var q = ScheduleBuilder.build(terms("200000", "10", 4, LocalDate.of(2026, 11, 30), null, RepaymentMethod.EQUATED, Options.of(Frequency.QUARTERLY)));
        assertEquals(LocalDate.of(2027, 2, 28), q.get(0).dueDate());
        assertEquals(LocalDate.of(2027, 5, 31), q.get(1).dueDate());
        assertEquals(LocalDate.of(2027, 11, 30), q.get(3).dueDate());
    }

    @Test
    void whole_life_reconciles_for_each_frequency_and_method() {
        lifecycle(terms("5000", "36", 30, JUL1, null, RepaymentMethod.EQUATED, Options.of(Frequency.DAILY)), List.of(), "daily collection");
        lifecycle(terms("50000", "20", 26, JUL1, null, RepaymentMethod.EQUATED, Options.of(Frequency.FORTNIGHTLY)), List.of(), "fortnightly");
        lifecycle(terms("200000", "10", 8, OPEN, null, RepaymentMethod.EQUATED, Options.of(Frequency.QUARTERLY)), List.of(), "quarterly EMI");
        lifecycle(terms("200000", "10", 4, OPEN, null, RepaymentMethod.FIXED_PRINCIPAL, Options.of(Frequency.HALF_YEARLY)), List.of(), "half-yearly fixed principal");
        lifecycle(terms("200000", "10", 2, OPEN, null, RepaymentMethod.BULLET_PERIODIC_INTEREST, Options.of(Frequency.YEARLY)), List.of(), "yearly interest, bullet principal");
        // the reference product MB01: a 30-day bullet with the extra first day (FSD 7.5.1)
        LoanTerms mb01 = new LoanTerms(bd("5000"), bd("638.75"), 30, LocalDate.of(2026, 6, 15), null, RepaymentMethod.BULLET_TOTAL_INTEREST, 0,
                BigDecimal.ZERO, DayCount.ACTUAL_ACTUAL, Rounding.RUPEE_HALF_UP, true, Options.of(Frequency.DAILY));
        var rows = ScheduleBuilder.build(mb01);
        assertEquals(LocalDate.of(2026, 7, 15), rows.get(0).dueDate());
        eq("7713", rows.get(0).instalment(), "MB01 demand: 5000 + 5000 × 638.75% × 31/365");
    }

    @Test
    void moratorium_and_balloon_work_at_other_frequencies() {
        LoanTerms t = new LoanTerms(bd("400000"), bd("12"), 12, OPEN, null, RepaymentMethod.EQUATED, 2, bd("100000"), DayCount.ACTUAL_365,
                Rounding.RUPEE_HALF_UP, false, Options.of(Frequency.QUARTERLY));
        var plan = ScheduleBuilder.plan(t);
        var rows = plan.schedule();
        assertAmortises(rows, bd("400000"), "quarterly with moratorium and balloon");
        eq("0", rows.get(0).principal(), "moratorium quarter 1");
        eq("0", rows.get(1).principal(), "moratorium quarter 2");
        assertTrue(rows.get(11).principal().compareTo(bd("100000")) > 0, "balloon in the last instalment");
        // PV of the 10 instalments and the balloon at 3% a quarter equals the principal (within rounding)
        double pv = 0;
        for (int k = 1; k <= 10; k++) pv += plan.emi().doubleValue() / Math.pow(1.03, k);
        pv += 100000 / Math.pow(1.03, 10);
        assertTrue(Math.abs(pv - 400000) < 10, "EMI solves the balloon equation: " + pv);
        lifecycle(t, List.of(), "quarterly with moratorium and balloon");
    }

    // ------------------------------------------------------------------------------------------------ step EMI
    @Test
    void step_up_and_step_down_instalments() {
        LoanTerms up = terms("100000", "12", 24, OPEN, null, RepaymentMethod.STEP_EQUATED, Options.NONE.withStep(bd("10"), 12));
        var plan = ScheduleBuilder.plan(up);
        var rows = plan.schedule();
        eq("4496", plan.emi(), "first-year instalment");
        for (int i = 0; i < 12; i++) eq("4496", rows.get(i).instalment(), "year 1 instalment " + (i + 1));
        for (int i = 12; i < 23; i++) eq("4946", rows.get(i).instalment(), "year 2 instalment " + (i + 1) + " = 4496 × 1.10");
        assertTrue(rows.get(23).instalment().subtract(bd("4946")).abs().compareTo(bd("99")) <= 0,
                "last instalment absorbs rounding and the day-count difference: " + rows.get(23).instalment());
        assertAmortises(rows, bd("100000"), "step-up");
        assertDayCountInterest(rows, "12", OPEN, "step-up");
        double apr = Apr.of(up, plan, BigDecimal.ZERO).doubleValue();
        assertTrue(Math.abs(Life.npv(100000, rows, apr, 12)) < 15, "APR is the IRR of the stepped flows");
        assertTrue(Math.abs(apr - 12) < 0.15, "and close to the contract rate: " + apr);
        lifecycle(up, List.of(), "step-up");

        LoanTerms down = terms("120000", "12", 12, OPEN, null, RepaymentMethod.STEP_EQUATED, Options.NONE.withStep(bd("-10"), 6));
        var d = ScheduleBuilder.build(down);
        eq("11205", d.get(0).instalment(), "first half");
        eq("10085", d.get(6).instalment(), "second half = 11205 × 0.90 (unrounded base)");
        assertAmortises(d, bd("120000"), "step-down");
        lifecycle(down, List.of(), "step-down");

        // steps so steep that an early instalment would not cover its interest are refused (no negative amortisation)
        LoanTerms steep = terms("1000000", "24", 120, OPEN, null, RepaymentMethod.STEP_EQUATED, Options.NONE.withStep(bd("100"), 12));
        assertThrows(IllegalArgumentException.class, () -> ScheduleBuilder.build(steep));
        assertThrows(IllegalArgumentException.class, () -> terms("1000", "12", 12, OPEN, null, RepaymentMethod.STEP_EQUATED, Options.NONE));
        assertThrows(IllegalArgumentException.class, () -> terms("1000", "12", 12, OPEN, null, RepaymentMethod.EQUATED, Options.NONE.withStep(bd("5"), 3)));
    }

    // ------------------------------------------------------------------------------------------------ broken-period interest
    @Test
    void broken_period_interest_modes() {
        LocalDate firstDue = LocalDate.of(2026, 8, 5);          // regular period starts 5-Jul: 5 broken days from 30-Jun
        // NONE: a normal EMI whose interest covers all 36 days
        LoanTerms none = terms("100000", "18", 12, OPEN, firstDue, RepaymentMethod.EQUATED, Options.NONE);
        var n = ScheduleBuilder.plan(none);
        eq("1775", n.schedule().get(0).interest(), "36 days' interest");
        eq("9168", n.schedule().get(0).instalment(), "the EMI");
        eq("0", n.brokenPeriodInterest(), "no separate BPI");
        assertAmortises(n.schedule(), bd("100000"), "BPI none");

        // ADD_TO_FIRST_INSTALMENT: EMI + interest of the 5 broken days; principal as in a standard month
        LoanTerms add = terms("100000", "18", 12, OPEN, firstDue, RepaymentMethod.EQUATED, Options.NONE.withBpi(BpiMode.ADD_TO_FIRST_INSTALMENT));
        var a = ScheduleBuilder.plan(add);
        eq("247", a.brokenPeriodInterest(), "BPI = 100000 × 18% × 5/365 = 246.58");
        eq("1775", a.schedule().get(0).interest(), "first interest = 36 days");
        eq("7639", a.schedule().get(0).principal(), "principal = EMI − 31 days' interest (1529)");
        eq("9414", a.schedule().get(0).instalment(), "EMI 9168 + 246 for the broken days");
        eq("9168", a.schedule().get(1).instalment(), "then the EMI");
        assertEquals(12, a.schedule().size());
        assertAmortises(a.schedule(), bd("100000"), "BPI in first instalment");
        assertDayCountInterest(a.schedule(), "18", OPEN, "BPI in first instalment");
        assertFalse(Apr.evenlySpaced(add, a));
        double aprAdd = Apr.of(add, a, BigDecimal.ZERO).doubleValue();
        assertTrue(Math.abs(Life.xnpv(OPEN, 100000, a.schedule(), 0, aprAdd)) < 5, "APR is the XIRR of the dated flows");
        lifecycle(add, List.of(), "BPI in first instalment");

        // SEPARATE_DEMAND: an interest-only demand on 5-Jul, then twelve EMIs
        LoanTerms sep = terms("100000", "18", 12, OPEN, firstDue, RepaymentMethod.EQUATED, Options.NONE.withBpi(BpiMode.SEPARATE_DEMAND));
        var s = ScheduleBuilder.plan(sep);
        assertEquals(13, s.schedule().size());
        assertEquals(LocalDate.of(2026, 7, 5), s.schedule().get(0).dueDate());
        eq("247", s.schedule().get(0).instalment(), "BPI demand");
        eq("0", s.schedule().get(0).principal(), "interest only");
        eq("1529", s.schedule().get(1).interest(), "then a standard 31-day period");
        eq("9168", s.schedule().get(1).instalment(), "EMI");
        assertFalse(s.bpiDeducted());
        assertAmortises(s.schedule(), bd("100000"), "BPI separate");
        assertDayCountInterest(s.schedule(), "18", OPEN, "BPI separate");
        lifecycle(sep, List.of(), "BPI separate");

        // DEDUCT_AT_DISBURSAL: the same rows, the BPI taken from the payout and set against its demand on 5-Jul
        LoanTerms ded = terms("100000", "18", 12, OPEN, firstDue, RepaymentMethod.EQUATED, Options.NONE.withBpi(BpiMode.DEDUCT_AT_DISBURSAL));
        var d = ScheduleBuilder.plan(ded);
        assertTrue(d.bpiDeducted());
        assertEquals(s.schedule(), d.schedule());
        FeeRule pf = new FeeRule("PF", "Processing fee", FeeRule.Event.DISBURSEMENT, FeeRule.CalcType.FIXED, bd("750"), null, null, null, null,
                bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, true);
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.disburse(params("18", List.of(pf)), ded, OPEN);
        gl.post(book.result().lots());
        eq("98868", book.netDisbursal(), "net = 100000 − 885 fee and GST − 247 BPI");
        eq("247", book.interestDeducted(), "BPI deducted");
        eq("98868", gl.cr("1202"), "bank pays the net amount");
        eq("247", gl.cr("2302"), "BPI held as an advance, not yet income");
        eq("0", gl.cr("4101"), "no interest income on day 0");
        LoanAccount acc = book.account();
        eq("247", acc.interestInAdvance(), "held on the account");
        Life.runEod(acc, gl, OPEN, LocalDate.of(2026, 7, 5));
        eq("0", acc.interestInAdvance(), "used on the demand date");
        eq("0", gl.dr("2302"), "advance GL cleared");
        eq("0", acc.overdueAmount(LocalDate.of(2026, 7, 5)), "the BPI demand is settled: nothing overdue");
        assertEquals(0, acc.dpd());
        eq("247", acc.demands().get(0).interestPaid(), "demand 1 paid from the deduction");
        // income for the broken days is earned by the daily accrual; 5-Jul's own accrual belongs to the next period
        eq("296.32", gl.cr("4101"), "247 for the broken period + one day (49.32) of the first regular period");
        Life.payOnTimeUntilClosed(acc, gl, LocalDate.of(2026, 7, 6));
        assertClosedAndReconciled(acc, gl, "BPI deducted");
        eq(d.totalInterest().toPlainString(), gl.cr("4101"), "income = scheduled interest incl. BPI");
        double aprDed = Apr.of(ded, d, bd("750")).doubleValue();
        assertTrue(Math.abs(Life.xnpv(OPEN, 100000 - 750 - 247, d.schedule(), 1, aprDed)) < 5, "APR: net of fee and BPI out, twelve EMIs in");
        assertTrue(aprDed > Apr.of(sep, s, bd("750")).doubleValue(), "paying the BPI upfront costs slightly more than paying it on 5-Jul");

        // no broken period (standard first due date): every mode gives the plain schedule
        var plain = ScheduleBuilder.build(terms("100000", "18", 12, OPEN, null, RepaymentMethod.EQUATED, Options.NONE));
        var std = ScheduleBuilder.plan(terms("100000", "18", 12, OPEN, LocalDate.of(2026, 7, 31), RepaymentMethod.EQUATED,
                Options.NONE.withBpi(BpiMode.DEDUCT_AT_DISBURSAL)));
        assertEquals(plain, std.schedule());
        assertFalse(std.bpiDeducted());
        // other methods carry BPI too
        var fp = ScheduleBuilder.plan(terms("120000", "12", 12, OPEN, firstDue, RepaymentMethod.FIXED_PRINCIPAL, Options.NONE.withBpi(BpiMode.SEPARATE_DEMAND)));
        assertEquals(13, fp.schedule().size());
        eq("197", fp.schedule().get(0).interest(), "120000 × 12% × 5/365");
        assertAmortises(fp.schedule(), bd("120000"), "fixed principal with BPI");
    }

    @Test
    void cancellation_and_preclosure_give_back_interest_deducted_in_advance() {
        LocalDate firstDue = LocalDate.of(2026, 8, 5);
        LoanTerms ded = terms("100000", "18", 12, OPEN, firstDue, RepaymentMethod.EQUATED, Options.NONE.withBpi(BpiMode.DEDUCT_AT_DISBURSAL));
        for (boolean cancel : new boolean[] {true, false}) {
            Life.Gl gl = new Life.Gl();
            var book = LoanAccount.disburse(params("18", List.of()), ded, OPEN);
            gl.post(book.result().lots());
            LoanAccount a = book.account();
            Life.runEod(a, gl, OPEN, OPEN.plusDays(1));                 // two days' interest: 98.63 → 99
            LocalDate day = OPEN.plusDays(2);
            if (cancel) {
                eq("99852", a.cancellationAmount(), "principal + 99 interest − 247 already deducted");
                gl.post(a.cancel(bd("99852"), day).lots());
                assertEquals(LoanAccount.Status.CANCELLED, a.status());
            } else {
                var q = a.preclosureQuote(day);
                eq("99852", q.total(), "pre-closure nets the deducted interest");
                gl.post(a.preclose(q.total(), day).lots());
                assertEquals(LoanAccount.Status.CLOSED, a.status());
            }
            eq("0", gl.dr("1101"), "principal GL");
            eq("0", gl.dr("1102"), "interest receivable GL");
            eq("0", gl.dr("2302"), "advance GL");
            eq("99", gl.cr("4101"), "income = interest for the days used");
            eq("99852", gl.dr("1203"), "bank receipt");
        }
    }

    // ------------------------------------------------------------------------------------------------ rate bases
    @Test
    void flat_rate_loan_amortises_at_its_effective_rate() {
        LoanTerms flat = terms("60000", "12", 12, OPEN, null, RepaymentMethod.EQUATED, Options.NONE.withBasis(InterestBasis.FLAT));
        var plan = ScheduleBuilder.plan(flat);
        var rows = plan.schedule();
        eq("5600", plan.emi(), "(60000 + 60000 × 12% × 1 year) / 12");
        for (Instalment i : rows) eq("5600", i.instalment(), "level instalment " + i.number());
        eq("7200", plan.totalInterest(), "flat interest exactly");
        assertAmortises(rows, bd("60000"), "flat");
        eq("21.457184", plan.accrualRatePercent(), "equivalent reducing rate");
        eq("21.457184", RateSolver.flatToEffective(bd("60000"), bd("12"), 12, 12), "RateSolver agrees");
        eq("1073", rows.get(0).interest(), "first interest = 60000 × 21.457184% / 12 (front-loaded, not 600)");
        assertTrue(rows.get(11).interest().compareTo(bd("110")) < 0, "interest declines with the balance");
        eq("21.46", Apr.of(flat, plan, BigDecimal.ZERO), "APR shows the reducing rate, not the 12% flat");
        LoanAccount a = lifecycle(flat, List.of(), "flat");
        eq("21.457184", a.ratePercent(), "the account accrues at the effective rate");
        eq("7200", a.demands().stream().map(LoanAccount.DemandRow::interestDue).reduce(BigDecimal.ZERO, BigDecimal::add), "interest demanded");

        // weekly flat micro-loan: 10000 at 20% flat for 26 weeks
        LoanTerms weekly = terms("10000", "20", 26, JUL1, null, RepaymentMethod.EQUATED, new Options(Frequency.WEEKLY, InterestBasis.FLAT, null, null, null, null, null, null));
        var w = ScheduleBuilder.plan(weekly);
        eq("423", w.emi(), "(10000 + 1000) / 26");
        eq("1000", w.totalInterest(), "10000 × 20% × 26/52");
        assertAmortises(w.schedule(), bd("10000"), "weekly flat");
        assertTrue(w.accrualRatePercent().compareTo(bd("36")) > 0 && w.accrualRatePercent().compareTo(bd("39")) < 0, "about 37.5% reducing: " + w.accrualRatePercent());
        lifecycle(weekly, List.of(), "weekly flat");

        assertThrows(IllegalArgumentException.class, () -> terms("60000", "12", 12, OPEN, null, RepaymentMethod.FIXED_PRINCIPAL, Options.NONE.withBasis(InterestBasis.FLAT)));
        assertThrows(IllegalArgumentException.class, () -> terms("60000", "12", 12, OPEN, LocalDate.of(2026, 8, 5), RepaymentMethod.EQUATED, Options.NONE.withBasis(InterestBasis.FLAT)));
        // a flat loan with a later first due date collects the broken days at the effective rate, on top of the flat interest
        var bpi = ScheduleBuilder.plan(terms("60000", "12", 12, OPEN, LocalDate.of(2026, 8, 5), RepaymentMethod.EQUATED,
                new Options(null, InterestBasis.FLAT, BpiMode.ADD_TO_FIRST_INSTALMENT, null, null, null, null, null)));
        eq("176", bpi.brokenPeriodInterest(), "60000 × 21.457184% × 5/365");
        eq("7376", bpi.totalInterest(), "flat interest + BPI");
        eq("5776", bpi.schedule().get(0).instalment(), "first instalment carries the BPI");
    }

    @Test
    void periodic_reducing_charges_the_same_rate_every_period() {
        LoanTerms monthly = terms("100000", "12", 12, OPEN, null, RepaymentMethod.EQUATED, Options.NONE.withBasis(InterestBasis.PERIODIC_REDUCING));
        var rows = ScheduleBuilder.build(monthly);
        eq("1000", rows.get(0).interest(), "100000 × 12% / 12, whatever the month's length");
        BigDecimal bal = bd("100000");
        for (Instalment i : rows) {
            eq(bal.multiply(bd("0.01")).setScale(0, RoundingMode.HALF_UP).toPlainString(), i.interest(), "monthly reducing interest of " + i.number());
            bal = i.closingBalance();
        }
        assertAmortises(rows, bd("100000"), "monthly reducing");
        eq("12.00", Apr.of(monthly, ScheduleBuilder.plan(monthly), BigDecimal.ZERO), "APR = the contract rate when there are no fees");
        lifecycle(monthly, List.of(), "monthly reducing");
        // daily reducing differs: February costs less than July
        var daily = ScheduleBuilder.build(terms("100000", "12", 12, OPEN, null, RepaymentMethod.EQUATED, Options.NONE));
        eq("1019", daily.get(0).interest(), "31 days");
    }

    @Test
    void rate_from_a_given_instalment_and_from_a_future_value() {
        LoanTerms t = terms("100000", "0", 12, OPEN, null, RepaymentMethod.EQUATED, Options.NONE.withFixedInstalment(bd("9168")));
        var plan = ScheduleBuilder.plan(t);
        eq("18.000015", plan.accrualRatePercent(), "rate implied by 12 instalments of 9168");
        eq("9168", plan.emi(), "the instalment given");
        eq("1500", plan.schedule().get(0).interest(), "100000 × 18.000015% / 12");
        assertAmortises(plan.schedule(), bd("100000"), "given instalment");
        for (int i = 0; i < 11; i++) eq("9168", plan.schedule().get(i).instalment(), "instalment " + (i + 1));
        lifecycle(t, List.of(), "given instalment");
        assertThrows(IllegalArgumentException.class, () -> ScheduleBuilder.plan(
                terms("100000", "0", 12, OPEN, null, RepaymentMethod.EQUATED, Options.NONE.withFixedInstalment(bd("8000")))));

        LocalDate d = LocalDate.of(2026, 7, 1);
        BigDecimal rate = RateSolver.simpleAnnualFromFutureValue(bd("5000"), bd("5500"), d, d.plusDays(30), DayCount.ACTUAL_365);
        eq("121.666667", rate, "(5500/5000 − 1) × 365/30");
        var bullet = ScheduleBuilder.build(new LoanTerms(bd("5000"), rate, 30, d, null, RepaymentMethod.BULLET_TOTAL_INTEREST, 0, null,
                DayCount.ACTUAL_365, null, false, Options.of(Frequency.DAILY)));
        eq("5500", bullet.get(0).instalment(), "the bullet repays the future value");
        assertThrows(IllegalArgumentException.class, () -> RateSolver.simpleAnnualFromFutureValue(bd("5000"), bd("4000"), d, d.plusDays(30), DayCount.ACTUAL_365));
    }

    // ------------------------------------------------------------------------------------------------ principal patterns
    @Test
    void fixed_principal_with_interest_and_principal_at_different_intervals() {
        LoanTerms t = terms("120000", "12", 12, OPEN, null, RepaymentMethod.FIXED_PRINCIPAL, Options.NONE.withPrincipalEvery(3));
        var rows = ScheduleBuilder.build(t);
        for (Instalment i : rows) {
            eq(i.number() % 3 == 0 ? "30000" : "0", i.principal(), "principal every third month: " + i.number());
            assertTrue(i.interest().signum() > 0, "interest every month");
        }
        assertAmortises(rows, bd("120000"), "differing interval");
        assertDayCountInterest(rows, "12", OPEN, "differing interval");
        lifecycle(t, List.of(), "differing interval");
        assertThrows(IllegalArgumentException.class, () -> terms("120000", "12", 10, OPEN, null, RepaymentMethod.FIXED_PRINCIPAL, Options.NONE.withPrincipalEvery(3)));
        assertThrows(IllegalArgumentException.class, () -> terms("120000", "12", 12, OPEN, null, RepaymentMethod.EQUATED, Options.NONE.withPrincipalEvery(3)));
    }

    /** "Periodic Fixed Principal And No Interest" (also with a differing interval): the same method at a zero rate. */
    @Test
    void fixed_principal_without_interest() {
        LoanTerms t = terms("120000", "0", 12, OPEN, null, RepaymentMethod.FIXED_PRINCIPAL, Options.NONE);
        var rows = ScheduleBuilder.build(t);
        assertEquals(12, rows.size());
        for (Instalment i : rows) {
            eq("10000", i.principal(), "principal of " + i.number());
            eq("0", i.interest(), "no interest on " + i.number());
            eq("10000", i.instalment(), "instalment " + i.number());
        }
        assertAmortises(rows, bd("120000"), "no interest");
        eq("0", Apr.of(t, ScheduleBuilder.plan(t), BigDecimal.ZERO), "APR of an interest-free loan without fees");
        LoanAccount a = lifecycle(t, List.of(), "no interest");
        eq("0", a.suspense(), "nothing accrued");

        var quarterly = ScheduleBuilder.build(terms("120000", "0", 12, OPEN, null, RepaymentMethod.FIXED_PRINCIPAL, Options.NONE.withPrincipalEvery(3)));
        for (Instalment i : quarterly) eq(i.number() % 3 == 0 ? "30000" : "0", i.instalment(), "instalment " + i.number());
    }

    @Test
    void structured_schedule_with_assigned_principal() {
        // a seasonal (harvest) loan: nothing in the lean months, principal after each harvest
        List<CustomRow> custom = List.of(new CustomRow(LocalDate.of(2026, 10, 15), bd("0")),
                new CustomRow(LocalDate.of(2026, 12, 20), bd("120000")), new CustomRow(LocalDate.of(2027, 4, 30), bd("30000")),
                new CustomRow(LocalDate.of(2027, 12, 20), bd("150000")));
        LoanTerms t = terms("300000", "11", 4, OPEN, null, RepaymentMethod.STRUCTURED, Options.NONE.withCustomRows(custom));
        var plan = ScheduleBuilder.plan(t);
        var rows = plan.schedule();
        assertEquals(4, rows.size());
        eq("9674", rows.get(0).interest(), "300000 × 11% × 107/365");
        eq("9674", rows.get(0).instalment(), "interest only in October");
        eq("120000", rows.get(1).principal(), "assigned principal");
        assertAmortises(rows, bd("300000"), "structured");
        assertDayCountInterest(rows, "11", OPEN, "structured");
        double apr = Apr.of(t, plan, BigDecimal.ZERO).doubleValue();
        assertTrue(Math.abs(Life.xnpv(OPEN, 300000, rows, 0, apr)) < 5, "APR is the XIRR");
        lifecycle(t, List.of(), "structured");

        assertThrows(IllegalArgumentException.class, () -> terms("300000", "11", 4, OPEN, null, RepaymentMethod.STRUCTURED, Options.NONE));
        assertThrows(IllegalArgumentException.class, () -> terms("300001", "11", 4, OPEN, null, RepaymentMethod.STRUCTURED, Options.NONE.withCustomRows(custom)));
        assertThrows(IllegalArgumentException.class, () -> terms("300000", "11", 3, OPEN, null, RepaymentMethod.STRUCTURED, Options.NONE.withCustomRows(custom)));
        List<CustomRow> unordered = List.of(custom.get(1), custom.get(0), custom.get(2), custom.get(3));
        assertThrows(IllegalArgumentException.class, () -> terms("300000", "11", 4, OPEN, null, RepaymentMethod.STRUCTURED, Options.NONE.withCustomRows(unordered)));
        assertThrows(IllegalArgumentException.class, () -> terms("300000", "11", 4, OPEN, null, RepaymentMethod.EQUATED, Options.NONE.withCustomRows(custom)));
    }

    // ------------------------------------------------------------------------------------------------ prepayment on the new shapes
    @Test
    void part_prepayment_keeps_the_method_and_frequency() {
        // weekly EMI, reduce EMI
        LoanTerms weekly = terms("30000", "24", 50, JUL1, null, RepaymentMethod.EQUATED, Options.of(Frequency.WEEKLY));
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.disburse(params("24", List.of()), weekly, JUL1);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        Life.runPaying(a, gl, JUL1, LocalDate.of(2026, 8, 9));
        LocalDate day = LocalDate.of(2026, 8, 10);
        if (a.overdueAmount(day).signum() > 0) gl.post(a.pay(a.overdueAmount(day), day, day, "Instalment").lots());
        int left = a.futureSchedule().size();
        gl.post(a.prepay(bd("10000"), LoanAccount.PrepaymentMode.REDUCE_EMI, day).lots());
        assertEquals(left, a.futureSchedule().size(), "same number of weeks");
        assertEquals(7, (int) java.time.temporal.ChronoUnit.DAYS.between(a.futureSchedule().get(0).dueDate(), a.futureSchedule().get(1).dueDate()), "still weekly");
        assertTrue(a.currentEmi().compareTo(bd("673")) < 0, "lower weekly instalment");
        eq(a.principalOutstanding().toPlainString(), sum(a.futureSchedule(), Instalment::principal), "future principal = outstanding");
        Life.payOnTimeUntilClosed(a, gl, day);
        assertClosedAndReconciled(a, gl, "weekly with prepayment");

        // weekly EMI, reduce tenure
        gl = new Life.Gl();
        book = LoanAccount.disburse(params("24", List.of()), weekly, JUL1);
        gl.post(book.result().lots());
        a = book.account();
        Life.runPaying(a, gl, JUL1, LocalDate.of(2026, 8, 9));
        if (a.overdueAmount(day).signum() > 0) gl.post(a.pay(a.overdueAmount(day), day, day, "Instalment").lots());
        gl.post(a.prepay(bd("10000"), LoanAccount.PrepaymentMode.REDUCE_TENURE, day).lots());
        assertTrue(a.futureSchedule().size() < left, "fewer weeks");
        eq("673", a.futureSchedule().get(1).instalment(), "same weekly instalment");
        Life.payOnTimeUntilClosed(a, gl, day);
        assertClosedAndReconciled(a, gl, "weekly with reduce-tenure prepayment");

        // flat-rate loan: continues on its equivalent reducing rate
        LoanTerms flat = terms("60000", "12", 12, OPEN, null, RepaymentMethod.EQUATED, Options.NONE.withBasis(InterestBasis.FLAT));
        gl = new Life.Gl();
        book = LoanAccount.disburse(params("12", List.of()), flat, OPEN);
        gl.post(book.result().lots());
        LoanAccount f = book.account();
        Life.runPaying(f, gl, OPEN, LocalDate.of(2026, 9, 14));
        LocalDate sep15 = LocalDate.of(2026, 9, 15);
        assertThrows(IllegalStateException.class, () -> f.prepay(bd("20000"), LoanAccount.PrepaymentMode.REDUCE_TENURE, sep15));
        eq("0", f.charges().stream().map(LoanAccount.ChargeRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add), "a refused prepayment charges nothing");
        gl.post(f.prepay(bd("20000"), LoanAccount.PrepaymentMode.REDUCE_EMI, sep15).lots());
        assertTrue(f.currentEmi().compareTo(bd("5600")) < 0, "lower instalment");
        eq("21.457184", f.ratePercent(), "rate unchanged");
        Life.payOnTimeUntilClosed(f, gl, sep15);
        assertClosedAndReconciled(f, gl, "flat with prepayment");
        assertTrue(gl.cr("4101").compareTo(bd("7200")) < 0, "less interest than the flat total after prepaying");

        // bullet with periodic interest: the interest instalments fall, the dates stay
        LoanTerms bullet = terms("200000", "10", 4, OPEN, null, RepaymentMethod.BULLET_PERIODIC_INTEREST, Options.of(Frequency.QUARTERLY));
        gl = new Life.Gl();
        book = LoanAccount.disburse(params("10", List.of()), bullet, OPEN);
        gl.post(book.result().lots());
        LoanAccount b = book.account();
        Life.runEod(b, gl, OPEN, LocalDate.of(2026, 8, 9));
        gl.post(b.prepay(bd("50000"), LoanAccount.PrepaymentMode.REDUCE_EMI, LocalDate.of(2026, 8, 10)).lots());
        assertEquals(4, b.futureSchedule().size());
        eq("150000", b.futureSchedule().get(3).principal(), "bullet principal after prepayment");
        eq("0", b.futureSchedule().get(0).principal(), "still interest only");
        Life.payOnTimeUntilClosed(b, gl, LocalDate.of(2026, 8, 10));
        assertClosedAndReconciled(b, gl, "bullet with prepayment");

        // structured schedules are not re-planned by a prepayment
        List<CustomRow> custom = List.of(new CustomRow(LocalDate.of(2026, 12, 20), bd("120000")), new CustomRow(LocalDate.of(2027, 12, 20), bd("180000")));
        var s = LoanAccount.disburse(params("11", List.of()), terms("300000", "11", 2, OPEN, null, RepaymentMethod.STRUCTURED,
                Options.NONE.withCustomRows(custom)), OPEN).account();
        s.endOfDay(OPEN, null);
        assertThrows(IllegalStateException.class, () -> s.prepay(bd("1000"), LoanAccount.PrepaymentMode.REDUCE_EMI, OPEN.plusDays(1)));
        // amendments and restructures stay with monthly EMI loans
        var w = LoanAccount.disburse(params("24", List.of()), weekly, JUL1).account();
        w.endOfDay(JUL1, null);
        assertThrows(IllegalStateException.class, () -> w.previewAmendment(Amendment.tenure(40, null), JUL1.plusDays(1)));
        assertThrows(IllegalStateException.class, () -> w.simulateRestructure(
                new RestructureTerms(null, 60, 0, RestructureTerms.OverdueInterest.CAPITALISE, null, "x"), JUL1.plusDays(1)));
    }
}
