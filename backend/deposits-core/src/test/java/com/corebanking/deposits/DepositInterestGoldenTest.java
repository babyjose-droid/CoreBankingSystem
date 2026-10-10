package com.corebanking.deposits;

import static com.corebanking.deposits.Fixtures.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import com.corebanking.deposits.SavingsInterest.Balance;
import com.corebanking.deposits.SavingsInterest.Band;
import com.corebanking.deposits.SavingsInterest.BandMode;
import com.corebanking.deposits.TermDeposit.Period;
import com.corebanking.deposits.TermDeposit.Terms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden values for deposit interest, each worked out independently (Python decimal arithmetic) before the engine
 * was run. A failing golden test blocks merge. See docs/golden-values.md G-12 to G-22.
 */
class DepositInterestGoldenTest {

    static void eq(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    static Terms terms(String principal, String rate, LocalDate start, LocalDate maturity, int periodMonths, boolean cumulative) {
        return new Terms(bd(principal), bd(rate), start, maturity, periodMonths, cumulative, DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP);
    }

    static final LocalDate APR1 = LocalDate.of(2026, 4, 1);

    // ---- term deposits ---------------------------------------------------------------------------------------

    @Test void cumulative_quarterly_24_months() {
        Terms t = terms("100000", "7.5", APR1, LocalDate.of(2028, 4, 1), 3, true);
        List<Period> p = TermDeposit.periods(t);
        assertEquals(8, p.size());
        eq("1875.00", p.get(0).interest());
        eq("1910.16", p.get(1).interest());
        eq("2135.38", p.get(7).interest());
        // G-09 gives 116022.17 without rounding; rounding each quarter's credit to the paisa gives one paisa more.
        eq("16022.18", TermDeposit.totalInterest(t));
        eq("116022.18", TermDeposit.maturityValue(t));
        eq("8.00", TermDeposit.annualisedYield(t));
    }

    @Test void monthly_payout_keeps_the_principal() {
        Terms t = terms("200000", "8", LocalDate.of(2026, 4, 15), LocalDate.of(2027, 4, 15), 1, false);
        List<Period> p = TermDeposit.periods(t);
        assertEquals(12, p.size());
        for (Period x : p) eq("1333.33", x.interest());
        eq("15999.96", TermDeposit.totalInterest(t));
        eq("200000", TermDeposit.maturityValue(t));
    }

    @Test void a_last_period_that_is_not_whole_earns_for_its_days() {
        LocalDate start = LocalDate.of(2027, 1, 10);
        Terms t = terms("50000", "7", start, start.plusDays(400), 3, true);
        List<Period> p = TermDeposit.periods(t);
        assertEquals(5, p.size());
        eq("875.00", p.get(0).interest());
        eq("921.75", p.get(3).interest());
        assertFalse(p.get(4).whole());
        assertEquals(LocalDate.of(2028, 1, 10), p.get(4).from());
        assertEquals(LocalDate.of(2028, 2, 14), p.get(4).to());
        eq("359.73", p.get(4).interest());              // 53592.95 x 7% x 35 / 365
        eq("3952.68", TermDeposit.totalInterest(t));
    }

    @Test void interest_once_at_maturity_is_simple_interest_on_days() {
        LocalDate start = LocalDate.of(2026, 10, 10);
        Terms t = terms("75000", "6.25", start, start.plusDays(91), 0, false);
        assertEquals(1, TermDeposit.periods(t).size());
        eq("1168.66", TermDeposit.totalInterest(t));    // 75000 x 6.25% x 91 / 365
    }

    @Test void accrual_meets_each_credit_exactly() {
        Terms t = terms("100000", "7.5", APR1, LocalDate.of(2028, 4, 1), 3, true);
        eq("0", TermDeposit.earnedBefore(t, APR1));
        eq("927.20", TermDeposit.earnedBefore(t, LocalDate.of(2026, 5, 16)));   // 45 of 91 days of 1875.00
        eq("1875.00", TermDeposit.earnedBefore(t, LocalDate.of(2026, 7, 1)));
        eq("2809.32", TermDeposit.earnedBefore(t, LocalDate.of(2026, 8, 15)));  // + 45 of 92 days of 1910.16
        eq("16022.18", TermDeposit.earnedBefore(t, LocalDate.of(2028, 4, 1)));
        eq("16022.18", TermDeposit.earnedBefore(t, LocalDate.of(2030, 1, 1)));  // nothing after maturity
    }

    @Test void daily_accrual_adds_up_to_the_total_and_never_goes_back() {
        Terms t = terms("123456.78", "8.35", LocalDate.of(2026, 11, 30), LocalDate.of(2028, 2, 29), 3, true);
        BigDecimal booked = BigDecimal.ZERO;
        for (LocalDate d = t.start(); d.isBefore(t.maturity()); d = d.plusDays(1)) {
            BigDecimal upTo = TermDeposit.earnedBefore(t, d.plusDays(1));
            assertTrue(upTo.compareTo(booked) >= 0, "accrual fell on " + d);
            booked = upTo;
        }
        eq(TermDeposit.totalInterest(t).toPlainString(), booked);
    }

    @Test void terms_are_validated() {
        assertThrows(IllegalArgumentException.class, () -> terms("0", "7", APR1, APR1.plusDays(30), 0, false));
        assertThrows(IllegalArgumentException.class, () -> terms("1000", "7", APR1, APR1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> terms("1000", "-1", APR1, APR1.plusDays(30), 0, false));
        assertThrows(IllegalArgumentException.class, () -> new Terms(bd("1000"), bd("7"), APR1, APR1.plusDays(30), 0, false,
                DayCount.THIRTY_360, Rounding.PAISE_HALF_UP));
    }

    // ---- savings ---------------------------------------------------------------------------------------------

    @Test void savings_whole_balance_and_incremental_bands() {
        var whole = new SavingsInterest(List.of(new Band(bd("0"), bd("3"))), BandMode.WHOLE_BALANCE, DayCount.ACTUAL_365);
        eq("20.54794521", whole.forOneDay(bd("250000")));
        var stepped = new SavingsInterest(List.of(new Band(bd("0"), bd("2.75")), new Band(bd("100000.01"), bd("3.5"))),
                BandMode.WHOLE_BALANCE, DayCount.ACTUAL_365);
        eq("23.97260274", stepped.forOneDay(bd("250000")));        // the whole balance at 3.5%
        eq("7.53424658", stepped.forOneDay(bd("100000")));         // at 2.75%
        var incremental = new SavingsInterest(List.of(new Band(bd("0"), bd("2.75")), new Band(bd("100000"), bd("3.5"))),
                BandMode.INCREMENTAL, DayCount.ACTUAL_365);
        eq("21.91780822", incremental.forOneDay(bd("250000")));    // 1,00,000 at 2.75% + 1,50,000 at 3.5%
        eq("0", incremental.forOneDay(bd("0")));
        eq("0", incremental.forOneDay(bd("-500")));
    }

    @Test void savings_interest_for_a_quarter_part() {
        var s = new SavingsInterest(List.of(new Band(bd("0"), bd("2.75")), new Band(bd("100000"), bd("3.5"))),
                BandMode.INCREMENTAL, DayCount.ACTUAL_365);
        BigDecimal accrued = s.forPeriod(List.of(
                new Balance(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 11), bd("40000")),
                new Balance(LocalDate.of(2026, 7, 11), LocalDate.of(2026, 7, 31), bd("250000"))));
        eq("468.49315070", accrued);
        eq("468.49", Rounding.PAISE_HALF_UP.apply(accrued));
    }

    @Test void a_bank_savings_product_has_one_rate_up_to_one_lakh() {
        var early = new SavingsInterest(List.of(new Band(bd("0"), bd("2.5")), new Band(bd("50000"), bd("3"))),
                BandMode.INCREMENTAL, DayCount.ACTUAL_365);
        assertEquals(DepositRules.SAVINGS_UNIFORM_RATE_UP_TO, early.check(Fixtures.bank()).get(0).rule());
        var atLimit = new SavingsInterest(List.of(new Band(bd("0"), bd("2.5")), new Band(bd("100000"), bd("3"))),
                BandMode.INCREMENTAL, DayCount.ACTUAL_365);
        assertTrue(atLimit.check(Fixtures.bank()).isEmpty(), "only the part above 1,00,000 earns the second rate");
        var wholeAtLimit = new SavingsInterest(List.of(new Band(bd("0"), bd("2.5")), new Band(bd("100000"), bd("3"))),
                BandMode.WHOLE_BALANCE, DayCount.ACTUAL_365);
        assertEquals(1, wholeAtLimit.check(Fixtures.bank()).size(), "a balance of exactly 1,00,000 would earn the second rate");
        var wholeAbove = new SavingsInterest(List.of(new Band(bd("0"), bd("2.5")), new Band(bd("100000.01"), bd("3"))),
                BandMode.WHOLE_BALANCE, DayCount.ACTUAL_365);
        assertTrue(wholeAbove.check(Fixtures.bank()).isEmpty());
        assertEquals(DepositRules.DEMAND_DEPOSITS_ALLOWED, atLimit.check(Fixtures.nbfc()).get(0).rule());
    }

    @Test void savings_bands_are_validated() {
        assertThrows(IllegalArgumentException.class, () -> new SavingsInterest(List.of(), BandMode.INCREMENTAL, DayCount.ACTUAL_365));
        assertThrows(IllegalArgumentException.class, () -> new SavingsInterest(List.of(new Band(bd("10"), bd("3"))),
                BandMode.INCREMENTAL, DayCount.ACTUAL_365));
        assertThrows(IllegalArgumentException.class, () -> new SavingsInterest(List.of(new Band(bd("0"), bd("3"))),
                BandMode.INCREMENTAL, DayCount.ACTUAL_ACTUAL));
    }

    // ---- recurring deposits ----------------------------------------------------------------------------------

    @Test void recurring_deposit_maturity_values() {
        eq("12462.40", RecurringDeposit.maturityValue(bd("1000"), bd("7"), 12, 3, Rounding.PAISE_HALF_UP));
        eq("129779.56", RecurringDeposit.maturityValue(bd("5000"), bd("7.5"), 24, 3, Rounding.PAISE_HALF_UP));
        eq("20603.27", RecurringDeposit.maturityValue(bd("2000"), bd("6.5"), 10, 3, Rounding.PAISE_HALF_UP));  // 10 months: a last month alone
        eq("12000.00", RecurringDeposit.maturityValue(bd("1000"), bd("0"), 12, 3, Rounding.PAISE_HALF_UP));
    }

    @Test void recurring_deposit_schedule_and_late_charge() {
        var s = RecurringDeposit.schedule(LocalDate.of(2026, 8, 31), 6, bd("1500"));
        assertEquals(6, s.size());
        assertEquals(LocalDate.of(2026, 9, 30), s.get(1).dueDate());
        assertEquals(LocalDate.of(2027, 1, 31), s.get(5).dueDate());      // back to the 31st after February
        assertEquals(LocalDate.of(2027, 2, 28), RecurringDeposit.maturityDate(LocalDate.of(2026, 8, 31), 6));
        LocalDate due = LocalDate.of(2026, 10, 5);
        eq("0", RecurringDeposit.latePenalty(bd("1500"), due, due, bd("1.50"), Rounding.PAISE_HALF_UP));
        eq("22.50", RecurringDeposit.latePenalty(bd("1500"), due, due.plusDays(1), bd("1.50"), Rounding.PAISE_HALF_UP));
        eq("22.50", RecurringDeposit.latePenalty(bd("1500"), due, LocalDate.of(2026, 11, 5), bd("1.50"), Rounding.PAISE_HALF_UP));
        eq("45.00", RecurringDeposit.latePenalty(bd("1500"), due, LocalDate.of(2026, 11, 6), bd("1.50"), Rounding.PAISE_HALF_UP));
    }
}
