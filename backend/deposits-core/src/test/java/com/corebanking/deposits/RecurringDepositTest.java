package com.corebanking.deposits;

import static com.corebanking.deposits.Fixtures.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import com.corebanking.deposits.DepositPostings.FundingSource;
import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.ledger.PostingLine;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * Recurring deposits (P4-5): the monthly product with quarterly compounding on time, on instalments paid late, at day-end
 * month by month, on a premature closure, the late charge, the booking quote and the instalment posting. Expected figures
 * worked out with Python decimal arithmetic, not with this code.
 */
class RecurringDepositTest {

    static final LocalDate START = LocalDate.of(2026, 6, 25);
    static final Rounding R = Rounding.PAISE_HALF_UP;
    static final BigDecimal INST = bd("1000");
    static final IntFunction<BigDecimal> ON_TIME = m -> INST.multiply(BigDecimal.valueOf(m));

    static void eq(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " got " + actual);
    }

    @Test
    void onTimeMonthlyProductQuarterlyCompounding() {
        List<RecurringDeposit.Month> ms = RecurringDeposit.onTime(START, 12, INST, bd("7"), 3, R);
        assertEquals(12, ms.size());
        List<String> credits = ms.stream().filter(m -> m.credited().signum() > 0).map(m -> m.credited().toPlainString()).toList();
        assertEquals(List.of("35.00", "88.11", "142.15", "197.14"), credits);
        eq("12462.40", RecurringDeposit.maturityValue(INST, bd("7"), 12, 3, R));
        eq("462.40", RecurringDeposit.totalInterest(INST, bd("7"), 12, 3, R));
        eq("7.11", RecurringDeposit.annualisedYield(INST, bd("7"), 12, 3, R));
        assertEquals(LocalDate.of(2026, 9, 25), ms.get(2).to());
        List<TermDeposit.Period> ps = RecurringDeposit.periods(START, 12, INST, bd("7"), 3, R);
        assertEquals(4, ps.size());
        eq("462.40", ps.stream().map(TermDeposit.Period::interest).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(LocalDate.of(2027, 6, 25), ps.get(3).to());
    }

    @Test
    void aLateInstalmentEarnsFromTheMonthAfter() {
        // instalment 2 is paid in month 3: month 2 holds 1,000 only
        IntFunction<BigDecimal> held = m -> m == 2 ? INST : INST.multiply(BigDecimal.valueOf(m));
        List<RecurringDeposit.Month> ms = RecurringDeposit.months(START, 12, bd("7"), 3, R, 6, held);
        eq("1000", ms.get(1).balance());
        eq("29.17", ms.get(2).credited());
        eq("4029.17", ms.get(3).balance());
        eq("88.01", ms.get(5).credited());
        eq("117.18", ms.get(5).earned());
    }

    @Test
    void theDayEndWorksMonthsOutOnceAndBooksWhatIsEarned() {
        RecurringDeposit.State s = RecurringDeposit.State.starting();
        RecurringDeposit.Result first = RecurringDeposit.dayEnd(START, 12, bd("7"), 3, R, s, LocalDate.of(2026, 7, 24), ON_TIME);
        assertTrue(first.events().isEmpty(), "no month has ended");
        List<InterestDayEnd.Event> all = new ArrayList<>();
        for (LocalDate d = START; !d.isAfter(LocalDate.of(2027, 6, 25)); d = d.plusDays(1)) {
            RecurringDeposit.Result r = RecurringDeposit.dayEnd(START, 12, bd("7"), 3, R, s, d, ON_TIME);
            all.addAll(r.events());
            s = r.state();
        }
        assertEquals(12, s.monthsDone());
        eq("462.40", s.booked());
        eq("462.40", s.credited());
        BigDecimal accrued = all.stream().filter(e -> e instanceof InterestDayEnd.Accrual).map(e -> ((InterestDayEnd.Accrual) e).amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        eq("462.40", accrued);
        List<InterestDayEnd.Credit> credits = all.stream().filter(e -> e instanceof InterestDayEnd.Credit).map(e -> (InterestDayEnd.Credit) e).toList();
        assertEquals(4, credits.size());
        assertEquals(START, credits.get(0).periodFrom());
        assertEquals(LocalDate.of(2026, 9, 25), credits.get(0).periodEnd());
        assertEquals(LocalDate.of(2026, 9, 25), credits.get(1).periodFrom());
        eq("35.00", credits.get(0).amount());
        // a re-run of the last day produces nothing
        assertTrue(RecurringDeposit.dayEnd(START, 12, bd("7"), 3, R, s, LocalDate.of(2027, 6, 25), ON_TIME).events().isEmpty());
    }

    @Test
    void aPrematureClosurePaysWholeMonthsAtTheRateAllowed() {
        eq("117.34", RecurringDeposit.interestFor(START, 12, bd("5"), 3, R, LocalDate.of(2027, 2, 10), ON_TIME));
        assertEquals(7, RecurringDeposit.monthsRun(START, 12, LocalDate.of(2027, 2, 10)));
        eq("0", RecurringDeposit.interestFor(START, 12, bd("5"), 3, R, LocalDate.of(2026, 7, 20), ON_TIME));
        eq("0", RecurringDeposit.interestFor(START, 12, BigDecimal.ZERO, 3, R, LocalDate.of(2027, 2, 10), ON_TIME));
    }

    @Test
    void lateChargePerHundredPerMonthOrPart() {
        eq("30.00", RecurringDeposit.latePenalty(INST, LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 30), bd("1.5"), R));
        eq("15.00", RecurringDeposit.latePenalty(INST, LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 26), bd("1.5"), R));
        eq("0", RecurringDeposit.latePenalty(INST, LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 25), bd("1.5"), R));
    }

    static TermDepositProduct.Settings rd(Tenure min, Tenure max) {
        return new TermDepositProduct.Settings(TermDepositProduct.Type.RD, bd("500"), null, min, max, 3, DayCount.ACTUAL_365, R,
                List.of(new DepositRateTable.Cell(bd("0"), new Tenure(6, 0), bd("6.5")), new DepositRateTable.Cell(bd("0"), new Tenure(12, 0), bd("7"))),
                List.of(), true, bd("1"), 0, true);
    }

    @Test
    void aRecurringDepositProductRunsForWholeMonths() {
        assertTrue(TermDepositProduct.problems(Fixtures.bank(), rd(new Tenure(6, 0), new Tenure(60, 0))).isEmpty());
        assertTrue(TermDepositProduct.problems(Fixtures.bank(), rd(new Tenure(6, 0), new Tenure(60, 15)))
                .contains("a recurring deposit runs for whole months: its tenures have no days"));
        assertTrue(rd(new Tenure(6, 0), new Tenure(60, 0)).cumulative());
    }

    @Test
    void theBookingQuoteOfARecurringDeposit() {
        TermDepositBooking.Request req = new TermDepositBooking.Request(INST, new Tenure(12, 0), START, Set.of(), 0, true, LocalDate.of(1980, 1, 1), true);
        TermDepositBooking.Quote q = TermDepositBooking.quote(Fixtures.bank(), rd(new Tenure(6, 0), new Tenure(60, 0)), req);
        eq("7", q.rate().ratePercent());
        eq("462.40", q.totalInterest());
        eq("12462.40", q.maturityValue());
        assertEquals(LocalDate.of(2027, 6, 25), q.maturity());
        assertEquals(4, q.periods().size());
        TermDepositBooking.Request days = new TermDepositBooking.Request(INST, new Tenure(12, 10), START, Set.of(), 0, true, LocalDate.of(1980, 1, 1), true);
        assertTrue(TermDepositBooking.problems(Fixtures.bank(), rd(new Tenure(6, 0), new Tenure(60, 0)), days).contains("a recurring deposit runs for a whole number of months"));
    }

    @Test
    void anInstalmentWithItsLateChargeFromASavingsAccount() {
        DepositPostings post = new DepositPostings(DepositPostings.Heads.starter(), LocalDate.of(2026, 9, 30));
        Leg rd = new Leg("HO", "2405", "30010000000045");
        Leg sb = new Leg("MUM", "2401", "20010000000013");
        TransactionLot lot = post.rdInstalment(rd, INST, bd("30"), "4107", FundingSource.ACCOUNT, sb, "RD-2", 3);
        PostingLine debit = lot.lines().stream().filter(l -> l.account().equals("20010000000013")).findFirst().orElseThrow();
        assertEquals(PostingLine.Side.DR, debit.side());
        eq("1030", debit.amount());
        eq("1000", lot.lines().stream().filter(l -> l.account().equals("30010000000045")).findFirst().orElseThrow().amount());
        eq("30", lot.lines().stream().filter(l -> l.glCode().equals("4107")).findFirst().orElseThrow().amount());
        assertTrue(lot.lines().stream().anyMatch(l -> l.glCode().equals("1900")), "inter-branch legs between MUM and HO");
        TransactionLot cash = post.rdInstalment(rd, INST, BigDecimal.ZERO, "4107", FundingSource.CASH, null, "SLIP", 1);
        assertEquals(2, cash.lines().size());
    }
}
