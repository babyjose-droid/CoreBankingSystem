package com.corebanking.deposits;

import static com.corebanking.deposits.Fixtures.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import com.corebanking.deposits.InterestDayEnd.Accrual;
import com.corebanking.deposits.InterestDayEnd.Credit;
import com.corebanking.deposits.InterestDayEnd.DepositResult;
import com.corebanking.deposits.InterestDayEnd.DepositState;
import com.corebanking.deposits.InterestDayEnd.Event;
import com.corebanking.deposits.InterestDayEnd.SavingsResult;
import com.corebanking.deposits.InterestDayEnd.SavingsState;
import com.corebanking.deposits.SavingsInterest.Band;
import com.corebanking.deposits.SavingsInterest.BandMode;
import com.corebanking.ledger.PostingLine;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The day-end's interest (P4-3). Expected figures worked out independently with Python decimal arithmetic:
 * 1,00,000 at 2.75% earns 7.53424658 a day (8 places); 92 days = 693.15068536, credited 693.15.
 */
class InterestDayEndTest {

    static final SavingsInterest SAVINGS = new SavingsInterest(List.of(new Band(bd("0"), bd("2.75"))), BandMode.INCREMENTAL, DayCount.ACTUAL_365);
    static final LocalDate JUL1 = LocalDate.of(2026, 7, 1);

    static void eq(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    static BigDecimal accrued(List<Event> events) {
        return events.stream().filter(e -> e instanceof Accrual).map(e -> ((Accrual) e).amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static List<Credit> credits(List<Event> events) {
        return events.stream().filter(e -> e instanceof Credit).map(e -> (Credit) e).toList();
    }

    static SavingsResult run(SavingsState s, LocalDate through, Function<LocalDate, BigDecimal> balance, Rounding r) {
        return InterestDayEnd.savings(SAVINGS, s, through, balance, 3, 4, r);
    }

    @Test
    void savingsAccruesDailyAndCreditsTheQuarterInWhicheverChunksTheDaysCome() {
        Function<LocalDate, BigDecimal> flat = d -> bd("100000");
        SavingsResult day1 = run(SavingsState.startingOn(JUL1), JUL1, flat, Rounding.PAISE_HALF_UP);
        eq("7.53", accrued(day1.events()));
        eq("7.53424658", day1.state().exact());
        SavingsResult july = run(day1.state(), LocalDate.of(2026, 7, 31), flat, Rounding.PAISE_HALF_UP);
        eq("233.56", july.state().accrued());
        SavingsResult quarter = run(july.state(), LocalDate.of(2026, 9, 30), flat, Rounding.PAISE_HALF_UP);
        List<Credit> c = credits(quarter.events());
        assertEquals(1, c.size());
        eq("693.15", c.get(0).amount());
        assertEquals(JUL1, c.get(0).periodFrom());
        assertEquals(LocalDate.of(2026, 9, 30), c.get(0).periodEnd());
        // everything accrued over the three runs is what was credited: the payable head is back to zero
        eq("693.15", accrued(day1.events()).add(accrued(july.events())).add(accrued(quarter.events())));
        eq("0", quarter.state().accrued());
        assertEquals(LocalDate.of(2026, 10, 1), quarter.state().periodFrom());

        // the same quarter in one run gives the same credit
        SavingsResult once = run(SavingsState.startingOn(JUL1), LocalDate.of(2026, 9, 30), flat, Rounding.PAISE_HALF_UP);
        eq("693.15", credits(once.events()).get(0).amount());
        eq("693.15", accrued(once.events()));
    }

    @Test
    void theCreditEarnsFromTheNextDayAndARerunDoesNothing() {
        LocalDate sep1 = LocalDate.of(2026, 9, 1);
        Function<LocalDate, BigDecimal> balance = d -> d.isBefore(sep1) ? bd("100000") : bd("50000");
        SavingsResult r = run(SavingsState.startingOn(JUL1), LocalDate.of(2026, 10, 3), balance, Rounding.PAISE_HALF_UP);
        eq("580.14", credits(r.events()).get(0).amount());
        // 1 to 3 October on 50,580.14 (11.43249741 for the three days)
        eq("11.43", r.state().accrued());
        eq("11.43249741", r.state().exact());
        SavingsResult again = run(r.state(), LocalDate.of(2026, 10, 3), balance, Rounding.PAISE_HALF_UP);
        assertTrue(again.events().isEmpty());
        assertEquals(r.state(), again.state());
    }

    @Test
    void aCreditRoundedToTheRupeeTrueUpsTheAccrualFirst() {
        SavingsResult r = run(SavingsState.startingOn(JUL1), LocalDate.of(2026, 9, 29), d -> bd("100000"), Rounding.RUPEE_HALF_UP);
        SavingsResult last = run(r.state(), LocalDate.of(2026, 9, 30), d -> bd("100000"), Rounding.RUPEE_HALF_UP);
        // 29 Sep: 91 days accrued to the paisa (685.62); 30 Sep: one more day, then true-up to the rupee credit of 693
        eq("685.62", r.state().accrued());
        eq("693", credits(last.events()).get(0).amount());
        eq("7.38", accrued(last.events()));
        assertTrue(last.events().get(0) instanceof Accrual && last.events().get(1) instanceof Credit, "true-up before the credit");
    }

    @Test
    void anEmptyAccountEarnsNothingAndPostsNothing() {
        SavingsResult r = run(SavingsState.startingOn(JUL1), LocalDate.of(2026, 9, 30), d -> BigDecimal.ZERO, Rounding.PAISE_HALF_UP);
        assertTrue(r.events().isEmpty());
        assertEquals(LocalDate.of(2026, 10, 1), r.state().periodFrom());
    }

    // ---- term deposits --------------------------------------------------------------------------------------------

    static final LocalDate APR1 = LocalDate.of(2026, 4, 1);
    static final TermDeposit.Terms CUMULATIVE = new TermDeposit.Terms(bd("100000"), bd("7.5"), APR1, LocalDate.of(2028, 4, 1), 3, true,
            DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP);

    @Test
    void aDepositCreditsEachQuarterOnItsCreditDateNotBefore() {
        DepositResult toJun30 = InterestDayEnd.deposit(CUMULATIVE, DepositState.startingOn(APR1), LocalDate.of(2026, 6, 30));
        eq("1875.00", accrued(toJun30.events()));
        assertTrue(credits(toJun30.events()).isEmpty(), "the first quarter is credited on 1 July");
        DepositResult jul1 = InterestDayEnd.deposit(CUMULATIVE, toJun30.state(), LocalDate.of(2026, 7, 1));
        List<Credit> c = credits(jul1.events());
        assertEquals(1, c.size());
        eq("1875.00", c.get(0).amount());
        assertEquals(1, c.get(0).number());
        assertEquals(1, jul1.state().periodsCredited());
        // 1 July itself earns on the compounded balance of 1,01,875
        assertTrue(accrued(jul1.events()).signum() > 0);
    }

    @Test
    void dayByDayTheDepositBooksExactlyWhatItCredits() {
        DepositState s = DepositState.startingOn(APR1);
        BigDecimal accrued = BigDecimal.ZERO;
        List<Credit> all = new ArrayList<>();
        for (LocalDate d = APR1; !d.isAfter(LocalDate.of(2028, 4, 3)); d = d.plusDays(1)) {
            DepositResult r = InterestDayEnd.deposit(CUMULATIVE, s, d);
            accrued = accrued.add(accrued(r.events()));
            all.addAll(credits(r.events()));
            s = r.state();
        }
        assertEquals(8, all.size());
        BigDecimal credited = all.stream().map(Credit::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        eq("16022.18", credited);
        eq("16022.18", accrued);
        eq("16022.18", s.booked());
        assertEquals(LocalDate.of(2028, 3, 31), s.accruedThrough(), "nothing accrues from the maturity date");
        assertEquals(LocalDate.of(2028, 4, 1), all.get(7).periodEnd());
    }

    @Test
    void aMonthlyPayoutDepositCreditsTheSameEveryMonth() {
        TermDeposit.Terms t = new TermDeposit.Terms(bd("200000"), bd("8"), LocalDate.of(2026, 4, 15), LocalDate.of(2027, 4, 15), 1, false,
                DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP);
        DepositResult r = InterestDayEnd.deposit(t, DepositState.startingOn(t.start()), LocalDate.of(2026, 7, 20));
        List<Credit> c = credits(r.events());
        assertEquals(3, c.size());
        for (Credit x : c) eq("1333.33", x.amount());
        assertEquals(LocalDate.of(2026, 7, 15), c.get(2).periodEnd());
    }

    // ---- posting rules ----------------------------------------------------------------------------------------------

    @Test
    void interestPostsAgainstTheExpenseAndPayableHeads() {
        DepositPostings post = new DepositPostings(DepositPostings.Heads.starter(), JUL1);
        TransactionLot accrual = post.interestAccrual("HO", "5108", "2404", "20010000000013", bd("7.53"), "20010000000013");
        assertEquals(DepositPostings.INTEREST_ACCRUAL, accrual.type());
        DepositMovementsTest.leg(accrual, "5108", "5108", PostingLine.Side.DR, "7.53");
        DepositMovementsTest.leg(accrual, "2404", "2404", PostingLine.Side.CR, "7.53");
        TransactionLot back = post.interestAccrual("HO", "5108", "2404", "20010000000013", bd("-0.15"), "20010000000013");
        DepositMovementsTest.leg(back, "2404", "2404", PostingLine.Side.DR, "0.15");
        DepositMovementsTest.leg(back, "5108", "5108", PostingLine.Side.CR, "0.15");
        TransactionLot credit = post.interestCredit(DepositMovementsTest.SB, "2404", bd("693.15"), "20010000000013", null);
        DepositMovementsTest.leg(credit, "2404", "2404", PostingLine.Side.DR, "693.15");
        DepositMovementsTest.leg(credit, DepositMovementsTest.SB.accountNo(), "2401", PostingLine.Side.CR, "693.15");
        TransactionLot payout = post.interestPayout(DepositMovementsTest.FD, DepositMovementsTest.CA_MUM, bd("1333.33"), "30010000000011");
        DepositMovementsTest.leg(payout, DepositMovementsTest.FD.accountNo(), "2403", PostingLine.Side.DR, "1333.33");
        DepositMovementsTest.leg(payout, DepositMovementsTest.CA_MUM.accountNo(), "2402", PostingLine.Side.CR, "1333.33");
        assertEquals(4, payout.lines().size(), "inter-branch legs between HO and MUM");
        TransactionLot tax = post.taxDeducted(DepositMovementsTest.FD, "2205", bd("6667"), "30010000000011", null);
        assertEquals(DepositPostings.TAX_DEDUCTED, tax.type());
        DepositMovementsTest.leg(tax, DepositMovementsTest.FD.accountNo(), "2403", PostingLine.Side.DR, "6667");
        DepositMovementsTest.leg(tax, "2205", "2205", PostingLine.Side.CR, "6667");
    }
}
