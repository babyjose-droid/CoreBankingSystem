package com.corebanking.lending.engine;

import static com.corebanking.lending.engine.Life.assertClosedAndReconciled;
import static com.corebanking.lending.engine.Life.bd;
import static com.corebanking.lending.engine.Life.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.EmiCalculator;
import com.corebanking.calc.Rounding;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.engine.Amendment.RateResetOption;
import com.corebanking.lending.engine.LoanAccount.RateChange;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Automatic floating-rate reset at day-end (RBI 18-Aug-2023, D-14): benchmark + spread on the reset date, through the
 * amendment code; EMI or tenure as the borrower or the product chose, falling back to the EMI when a longer tenure is
 * not allowed; the next reset date always moves on; resets outside the product band are applied and flagged.
 */
class RateResetTest {

    static final LocalDate OPEN = LocalDate.of(2026, 6, 30);
    static final LocalDate RESET1 = LocalDate.of(2026, 9, 30);
    static final LocalDate RESET2 = LocalDate.of(2026, 12, 30);

    /** REPO-like history: 6.00% from 1-Jan-2026, then whatever the test records. */
    static final class Rates implements LoanAccount.BenchmarkRates {
        final TreeMap<LocalDate, BigDecimal> history = new TreeMap<>(Map.of(LocalDate.of(2026, 1, 1), bd("6")));

        Rates set(LocalDate from, String rate) {
            history.put(from, bd(rate));
            return this;
        }

        @Override public BigDecimal rateOn(String code, LocalDate date) {
            assertEquals("REPO", code);
            var e = history.floorEntry(date);
            return e == null ? null : e.getValue();
        }
    }

    /** 6% + 3% spread = 9%, reset quarterly, band 8..12%, at most 36 months; no penal charges. */
    static LoanAccount.Params params(RateResetOption defaultOption, Integer maxTenure) {
        return new LoanAccount.Params("10010000000017", "HO", "32", "32", bd("9"), null, null, null, null, null, 3, BigDecimal.ZERO,
                null, List.of(), new LoanAccount.FloatingRate("REPO", bd("3"), 3, defaultOption, maxTenure, bd("8"), bd("12")));
    }

    static LoanAccount book(LoanAccount.Params p, Rates rates, Life.Gl gl) {
        var b = LoanAccount.disburse(p, LoanTerms.equated(bd("200000"), bd("9"), 24, OPEN), OPEN);
        gl.post(b.result().lots());
        return b.account().useBenchmarks(rates);
    }

    @Test
    void reset_on_its_date_keeps_the_tenure_and_changes_the_emi() {
        Life.Gl gl = new Life.Gl();
        Rates rates = new Rates().set(LocalDate.of(2026, 8, 7), "6.5");
        LoanAccount a = book(params(RateResetOption.KEEP_TENURE_CHANGE_EMI, 36), rates, gl);
        assertEquals(RESET1, a.nextRateReset(), "first reset three months from disbursal");
        BigDecimal emiBooked = a.currentEmi();
        eq(EmiCalculator.pmt(bd("200000"), bd("9"), 24, Rounding.RUPEE_HALF_UP).toPlainString(), emiBooked, "EMI at 9%");

        Life.runPaying(a, gl, OPEN, RESET1.minusDays(1));
        assertTrue(a.drainRateChanges().isEmpty(), "nothing before the reset date, whatever the benchmark did");
        eq("9", a.ratePercent(), "rate until the reset date");

        Life.runPaying(a, gl, RESET1, RESET1);
        List<RateChange> changes = a.drainRateChanges();
        assertEquals(1, changes.size());
        RateChange c = changes.get(0);
        assertEquals(LoanAccount.RateCause.BENCHMARK_RESET, c.cause());
        assertEquals(List.of(RESET1), c.resetDates());
        eq("6.5", c.benchmarkRate(), "benchmark on the reset date");
        eq("9.5", c.rateAfter(), "benchmark + spread");
        eq("9.5", a.ratePercent(), "accrual rate from the reset date");
        assertEquals(RateResetOption.KEEP_TENURE_CHANGE_EMI, c.applied());
        assertNull(c.fallbackReason());
        assertFalse(c.outsideBand());
        assertEquals(RESET2, a.nextRateReset(), "next reset moved on");
        assertEquals(RESET2, c.nextReset());
        // instalment 3 fell due on the reset date: 21 instalments left, re-priced over the same tenure
        Amendment.Effect e = c.effect();
        assertEquals(21, e.remainingBefore());
        assertEquals(21, e.remainingAfter(), "tenure kept");
        BigDecimal balance = a.futureSchedule().get(0).openingBalance();
        eq(EmiCalculator.pmt(balance, bd("9.5"), 21, Rounding.RUPEE_HALF_UP).toPlainString(), e.emiAfter(), "EMI over the instalments left");
        assertTrue(e.emiAfter().compareTo(emiBooked) > 0, "a higher rate raises the EMI");
        eq(emiBooked.toPlainString(), e.emiBefore(), "EMI before");
        // golden: 200000 at 9% for 24 months, EMI 9137; after three instalments 176,955 at 9.5% over 21 months
        eq("9137", emiBooked, "golden EMI at 9%");
        eq("176955", balance, "golden balance at the reset");
        eq("9180", e.emiAfter(), "golden EMI at 9.5%");
        // the next demand is the new EMI at the new rate: accrual and schedule agree
        Life.runPaying(a, gl, RESET1.plusDays(1), LocalDate.of(2026, 10, 31));
        LoanAccount.DemandRow d4 = a.demands().get(3);
        eq("9180", d4.interestDue().add(d4.principalDue()), "instalment 4 is the new EMI");

        Life.payOnTimeUntilClosed(a, gl, LocalDate.of(2026, 11, 1));
        assertClosedAndReconciled(a, gl, "reset, EMI path");
        assertEquals(24, a.demands().size(), "tenure unchanged");
    }

    @Test
    void reset_keeping_the_emi_lengthens_the_tenure_within_the_maximum() {
        Life.Gl gl = new Life.Gl();
        Rates rates = new Rates().set(LocalDate.of(2026, 9, 1), "7");
        LoanAccount a = book(params(RateResetOption.KEEP_EMI_CHANGE_TENURE, 36), rates, gl);
        BigDecimal emi = a.currentEmi();
        Life.runPaying(a, gl, OPEN, RESET1);
        RateChange c = a.drainRateChanges().get(0);
        eq("10", c.rateAfter(), "7 + 3");
        assertEquals(RateResetOption.KEEP_EMI_CHANGE_TENURE, c.applied());
        assertNull(c.fallbackReason());
        eq(emi.toPlainString(), c.effect().emiAfter(), "EMI kept");
        assertTrue(c.effect().remainingAfter() > c.effect().remainingBefore(), "tenure lengthened");
        assertEquals(22, c.effect().remainingAfter(), "golden: one more instalment at 10%");
        // every instalment but the last is the same EMI, and none amortises negatively
        List<Instalment> f = a.futureSchedule();
        for (int i = 0; i < f.size() - 1; i++) {
            eq(emi.toPlainString(), f.get(i).instalment(), "EMI of " + f.get(i).number());
            assertTrue(f.get(i).principal().signum() > 0, "principal repaid in " + f.get(i).number());
        }
        Life.payOnTimeUntilClosed(a, gl, RESET1.plusDays(1));
        assertClosedAndReconciled(a, gl, "reset, tenure path");
        assertEquals(25, a.demands().size(), "24 booked + 1");
    }

    @Test
    void borrower_choice_overrides_the_product_default() {
        Life.Gl gl = new Life.Gl();
        LoanAccount a = book(params(RateResetOption.KEEP_TENURE_CHANGE_EMI, 36), new Rates().set(LocalDate.of(2026, 9, 1), "7"), gl);
        a.resetPreference(RateResetOption.KEEP_EMI_CHANGE_TENURE);
        Life.runPaying(a, gl, OPEN, RESET1);
        RateChange c = a.drainRateChanges().get(0);
        assertEquals(RateResetOption.KEEP_EMI_CHANGE_TENURE, c.requested());
        assertEquals(RateResetOption.KEEP_EMI_CHANGE_TENURE, c.applied());
        assertThrows(IllegalArgumentException.class, () -> a.resetPreference(RateResetOption.CHANGE_BOTH));
    }

    @Test
    void unchanged_benchmark_only_moves_the_reset_date() {
        Life.Gl gl = new Life.Gl();
        LoanAccount a = book(params(null, 36), new Rates(), gl);
        assertEquals(RateResetOption.KEEP_TENURE_CHANGE_EMI, a.params().floating().defaultOption(), "default: EMI changes, tenure kept");
        List<Instalment> before = a.futureSchedule();
        Life.runPaying(a, gl, OPEN, RESET1);
        RateChange c = a.drainRateChanges().get(0);
        assertFalse(c.changed(), "same rate: nothing re-scheduled");
        assertNull(c.effect());
        eq("9", c.rateAfter(), "rate kept");
        assertEquals(RESET2, a.nextRateReset(), "the date moves on all the same");
        assertEquals(before.subList(3, before.size()), a.futureSchedule(), "schedule untouched");
    }

    @Test
    void day_ends_across_two_reset_dates_reset_twice_and_a_late_state_catches_up_once() {
        Life.Gl gl = new Life.Gl();
        Rates rates = new Rates().set(LocalDate.of(2026, 9, 15), "6.25").set(LocalDate.of(2026, 12, 1), "5.75");
        LoanAccount a = book(params(RateResetOption.KEEP_TENURE_CHANGE_EMI, 36), rates, gl);
        Life.runPaying(a, gl, OPEN, RESET2);
        List<RateChange> changes = a.drainRateChanges();
        assertEquals(2, changes.size(), "one reset per reset date");
        eq("9.25", changes.get(0).rateAfter(), "6.25 + 3 on 30-Sep");
        eq("8.75", changes.get(1).rateAfter(), "5.75 + 3 on 30-Dec");
        assertEquals(LocalDate.of(2027, 3, 30), a.nextRateReset());

        // a loan whose reset dates passed without a reset (booked before automatic resets): one catch-up reset at the
        // benchmark of the latest date passed; interest already accrued is not re-rated
        Life.Gl gl2 = new Life.Gl();
        LoanAccount.Params fixed = params(RateResetOption.KEEP_TENURE_CHANGE_EMI, 36).withFloating(null);
        var b = LoanAccount.disburse(fixed, LoanTerms.equated(bd("200000"), bd("9"), 24, OPEN), OPEN);
        gl2.post(b.result().lots());
        LoanAccount legacy = b.account();
        Life.runPaying(legacy, gl2, OPEN, RESET2.minusDays(1));
        LoanAccount.Snapshot s = legacy.snapshot();
        LoanAccount.Snapshot behind = new LoanAccount.Snapshot(s.status(), s.disbursedOn(), s.disbursedAmount(), s.principalOutstanding(),
                s.futureSchedule(), s.demands(), s.charges(), s.accruedNotDemanded(), s.carriedInterest(), s.lastAccrualDate(), s.excess(),
                s.assetClass(), s.npaSince(), s.dpd(), s.suspense(), s.provisionHeld(), s.chargeSeq(), s.ratePercent(),
                s.capitalisedSuspense(), s.restructure(), s.terms(), s.sanctioned(), s.tranches(), s.preEmi(), s.interestInAdvance(),
                s.classFloor(), s.classFloorUntil(), new LoanAccount.RateResetState(RESET1, null, false, 1));
        LoanAccount late = LoanAccount.restore(a.params(), behind).useBenchmarks(rates);
        Life.runPaying(late, gl2, RESET2, RESET2);
        List<RateChange> caught = late.drainRateChanges();
        assertEquals(1, caught.size(), "one change");
        assertEquals(List.of(RESET1, RESET2), caught.get(0).resetDates(), "both dates covered");
        eq("8.75", caught.get(0).rateAfter(), "the benchmark of the latest date");
        assertEquals(LocalDate.of(2027, 3, 30), late.nextRateReset(), "next date after the day processed");
        Life.payOnTimeUntilClosed(late, gl2, RESET2.plusDays(1));
        assertClosedAndReconciled(late, gl2, "catch-up");
    }

    @Test
    void a_reset_outside_the_band_is_applied_and_flagged() {
        Life.Gl gl = new Life.Gl();
        Rates rates = new Rates().set(LocalDate.of(2026, 9, 1), "9.5");
        LoanAccount a = book(params(RateResetOption.KEEP_TENURE_CHANGE_EMI, 36), rates, gl);
        Life.runPaying(a, gl, OPEN, RESET1);
        RateChange c = a.drainRateChanges().get(0);
        eq("12.5", a.ratePercent(), "9.5 + 3 applied although the band ends at 12% (D-14)");
        assertTrue(c.outsideBand());
        assertTrue(a.rateReset().outsideBand(), "loan flagged");
        rates.set(LocalDate.of(2026, 12, 1), "8");
        Life.runPaying(a, gl, RESET1.plusDays(1), RESET2);
        assertFalse(a.drainRateChanges().get(0).outsideBand(), "11% is inside the band again");
        assertFalse(a.rateReset().outsideBand(), "flag cleared");
        Life.payOnTimeUntilClosed(a, gl, RESET2.plusDays(1));
        assertClosedAndReconciled(a, gl, "outside-band reset");
    }

    @Test
    void keeping_the_emi_falls_back_to_a_higher_emi_when_the_maximum_tenure_would_be_passed() {
        Life.Gl gl = new Life.Gl();
        LoanAccount a = book(params(RateResetOption.KEEP_EMI_CHANGE_TENURE, 24), new Rates().set(LocalDate.of(2026, 9, 1), "8"), gl);
        Life.runPaying(a, gl, OPEN, RESET1);
        RateChange c = a.drainRateChanges().get(0);
        assertEquals(RateResetOption.KEEP_EMI_CHANGE_TENURE, c.requested());
        assertEquals(RateResetOption.KEEP_TENURE_CHANGE_EMI, c.applied(), "fallback");
        assertNotNull(c.fallbackReason());
        assertTrue(c.fallbackReason().contains("maximum tenure"), c.fallbackReason());
        assertEquals(c.effect().remainingBefore(), c.effect().remainingAfter(), "tenure kept");
        assertTrue(c.effect().emiAfter().compareTo(c.effect().emiBefore()) > 0, "EMI raised instead");
        Life.payOnTimeUntilClosed(a, gl, RESET1.plusDays(1));
        assertClosedAndReconciled(a, gl, "fallback");
        assertEquals(24, a.demands().size());
    }

    @Test
    void a_rate_jump_the_emi_cannot_carry_falls_back_rather_than_amortise_negatively() {
        Life.Gl gl = new Life.Gl();
        LoanAccount a = book(params(RateResetOption.KEEP_EMI_CHANGE_TENURE, 480), new Rates().set(LocalDate.of(2026, 9, 1), "60"), gl);
        Life.runPaying(a, gl, OPEN, RESET1);
        RateChange c = a.drainRateChanges().get(0);
        assertEquals(RateResetOption.KEEP_TENURE_CHANGE_EMI, c.applied());
        assertTrue(c.fallbackReason().contains("negatively amortise"), c.fallbackReason());
    }

    @Test
    void switching_to_fixed_stops_the_resets() {
        Life.Gl gl = new Life.Gl();
        Rates rates = new Rates().set(LocalDate.of(2026, 9, 1), "7");
        LoanAccount a = book(params(RateResetOption.KEEP_TENURE_CHANGE_EMI, 36), rates, gl);
        Life.runPaying(a, gl, OPEN, LocalDate.of(2026, 8, 10));
        Amendment fixed = new Amendment(Amendment.Kind.SWITCH_TO_FIXED, bd("10.5"), RateResetOption.KEEP_TENURE_CHANGE_EMI, null, null,
                null, 36, "borrower asked for a fixed rate");
        a.amend(fixed, LocalDate.of(2026, 8, 11));
        eq("10.5", a.ratePercent(), "fixed rate");
        assertNull(a.nextRateReset(), "no more resets");
        assertEquals(LocalDate.of(2026, 8, 11), a.rateReset().fixedSince());
        Life.runPaying(a, gl, LocalDate.of(2026, 8, 11), RESET2);
        assertTrue(a.drainRateChanges().isEmpty(), "the reset dates pass without a reset");
        eq("10.5", a.ratePercent(), "rate unchanged");
        assertThrows(IllegalStateException.class, () -> a.previewAmendment(fixed, RESET2.plusDays(1)), "already fixed");
        Life.payOnTimeUntilClosed(a, gl, RESET2.plusDays(1));
        assertClosedAndReconciled(a, gl, "switched to fixed");
        // a fixed-rate loan cannot be "switched"
        LoanAccount plain = LoanAccount.disburse(Life.params("9", List.of()), LoanTerms.equated(bd("100000"), bd("9"), 12, OPEN), OPEN).account();
        assertThrows(IllegalStateException.class, () -> plain.previewAmendment(fixed, OPEN.plusDays(1)));
        assertNull(plain.nextRateReset());
    }

    @Test
    void a_bullet_loan_on_a_floating_rate_re_prices_its_interest_and_a_frozen_account_is_reset_when_unfrozen() {
        Life.Gl gl = new Life.Gl();
        Rates rates = new Rates().set(LocalDate.of(2026, 9, 1), "7");
        LoanTerms t = new LoanTerms(bd("100000"), bd("9"), 12, OPEN, null, RepaymentMethod.BULLET_PERIODIC_INTEREST, 0, BigDecimal.ZERO,
                DayCount.ACTUAL_365, Rounding.RUPEE_HALF_UP, false, LoanTerms.Options.NONE);
        var b = LoanAccount.disburse(params(RateResetOption.KEEP_EMI_CHANGE_TENURE, 36), t, OPEN);
        gl.post(b.result().lots());
        LoanAccount a = b.account().useBenchmarks(rates);
        Life.runPaying(a, gl, OPEN, RESET1.minusDays(1));
        a.freeze();
        Life.runEod(a, gl, RESET1, RESET1.plusDays(2));
        assertTrue(a.drainRateChanges().isEmpty(), "a frozen account changes nothing, its reset included");
        eq("9", a.ratePercent(), "rate kept while frozen");
        assertEquals(RESET1, a.nextRateReset(), "the reset date stays, in the past: the reset is held");
        a.unfreeze();
        Life.runEod(a, gl, RESET1.plusDays(3), RESET1.plusDays(3));
        RateChange c = a.drainRateChanges().get(0);
        eq("10", a.ratePercent(), "caught up at the first day-end after unfreezing");
        assertEquals(List.of(RESET1), c.resetDates());
        assertEquals(RESET2, a.nextRateReset());
        assertEquals(RateResetOption.KEEP_TENURE_CHANGE_EMI, c.applied(), "no EMI to keep");
        assertNotNull(c.fallbackReason());
        Instalment next = a.futureSchedule().get(0);
        eq("0", next.principal(), "principal still at maturity");
        Life.payOnTimeUntilClosed(a, gl, RESET1.plusDays(4));
        assertClosedAndReconciled(a, gl, "floating bullet");
    }

    @Test
    void a_reset_date_on_a_sunday_is_applied_by_the_day_end_that_closes_that_sunday() {
        // disbursed Saturday 27-Jun-2026: the first reset falls on Sunday 27-Sep-2026, closed by Saturday 26-Sep's day-end
        LocalDate open = LocalDate.of(2026, 6, 27);
        LocalDate saturday = LocalDate.of(2026, 9, 26);
        LocalDate sunday = LocalDate.of(2026, 9, 27);
        assertEquals(java.time.DayOfWeek.SUNDAY, sunday.getDayOfWeek());
        Rates rates = new Rates().set(sunday, "6.5");                    // a rate recorded from the reset date itself
        Life.Gl gl = new Life.Gl();
        var b = LoanAccount.disburse(params(RateResetOption.KEEP_TENURE_CHANGE_EMI, 36), LoanTerms.equated(bd("200000"), bd("9"), 24, open), open);
        gl.post(b.result().lots());
        LoanAccount a = b.account().useBenchmarks(rates);
        assertEquals(sunday, a.nextRateReset());
        Life.runPaying(a, gl, open, saturday.minusDays(1));
        // Saturday's day-end: Saturday, then Sunday, in Saturday's books
        gl.post(a.endOfDay(saturday, null, saturday).lots());
        assertTrue(a.drainRateChanges().isEmpty(), "nothing on Saturday");
        gl.post(a.endOfDay(sunday, null, saturday).lots());
        List<RateChange> changes = a.drainRateChanges();
        assertEquals(1, changes.size());
        assertEquals(sunday, changes.get(0).day(), "dated the reset date");
        eq("9.5", a.ratePercent(), "the benchmark in force on Sunday (6.5) + 3");
        assertEquals(LocalDate.of(2026, 12, 27), a.nextRateReset());
        Life.payOnTimeUntilClosed(a, gl, sunday.plusDays(1));
        assertClosedAndReconciled(a, gl, "Sunday reset");
    }

    @Test
    void whole_life_with_four_resets_reconciles_to_the_ledger() {
        Life.Gl gl = new Life.Gl();
        Rates rates = new Rates().set(LocalDate.of(2026, 9, 1), "6.5").set(LocalDate.of(2026, 12, 1), "7")
                .set(LocalDate.of(2027, 3, 1), "6").set(LocalDate.of(2027, 6, 1), "5.5");
        LoanAccount a = book(params(RateResetOption.KEEP_EMI_CHANGE_TENURE, 36), rates, gl);
        int resets = 0;
        LocalDate d = OPEN;
        while (a.status() != LoanAccount.Status.CLOSED) {
            Life.payDay(a, gl, d);
            for (RateChange c : a.drainRateChanges()) if (c.changed()) resets++;
            d = d.plusDays(1);
        }
        assertEquals(4, resets, "four benchmark moves, four resets");
        assertClosedAndReconciled(a, gl, "four resets");
    }

    @Test
    void floating_state_stored_before_v24_finds_its_next_reset_date() {
        Life.Gl gl = new Life.Gl();
        LoanAccount a = book(params(RateResetOption.KEEP_EMI_CHANGE_TENURE, 36), new Rates(), gl);
        Life.runPaying(a, gl, OPEN, LocalDate.of(2026, 10, 5));
        LoanAccount.Snapshot s = a.snapshot();
        LoanAccount.Snapshot old = new LoanAccount.Snapshot(s.status(), s.disbursedOn(), s.disbursedAmount(), s.principalOutstanding(),
                s.futureSchedule(), s.demands(), s.charges(), s.accruedNotDemanded(), s.carriedInterest(), s.lastAccrualDate(), s.excess(),
                s.assetClass(), s.npaSince(), s.dpd(), s.suspense(), s.provisionHeld(), s.chargeSeq(), s.ratePercent(),
                s.capitalisedSuspense(), s.restructure(), s.terms(), s.sanctioned(), s.tranches(), s.preEmi(), s.interestInAdvance(),
                s.classFloor(), s.classFloorUntil());
        assertEquals(RESET2, LoanAccount.restore(a.params(), old).nextRateReset());
    }
}
