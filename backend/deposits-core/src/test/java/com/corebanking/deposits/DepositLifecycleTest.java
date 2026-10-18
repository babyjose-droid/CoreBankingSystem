package com.corebanking.deposits;

import static com.corebanking.deposits.Fixtures.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import com.corebanking.deposits.DepositCharges.Charge;
import com.corebanking.deposits.DepositCharges.Schedule;
import com.corebanking.deposits.DepositCharges.Service;
import com.corebanking.deposits.DepositPostings.ChargeHeads;
import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.deposits.DepositPostings.PayTo;
import com.corebanking.deposits.InterestDayEnd.Accrual;
import com.corebanking.deposits.InterestDayEnd.Credit;
import com.corebanking.deposits.InterestDayEnd.SavingsResult;
import com.corebanking.deposits.InterestDayEnd.SavingsState;
import com.corebanking.deposits.SavingsInterest.Band;
import com.corebanking.deposits.SavingsInterest.BandMode;
import com.corebanking.ledger.PostingLine;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle (P4-4): charges with GST, the minimum balance, closure, maturity on a holiday and after it, and their
 * postings. Expected figures worked out by hand or with Python decimal arithmetic, not with this code.
 */
class DepositLifecycleTest {

    static final LocalDate JUL1 = LocalDate.of(2026, 7, 1);
    static final DepositPostings POST = new DepositPostings(DepositPostings.Heads.starter(), JUL1);
    static final Leg SB = new Leg("HO", "2401", "20010000000013");
    static final Leg SB_MUM = new Leg("MUM", "2401", "20010000000039");
    static final Leg FD = new Leg("HO", "2403", "30010000000011");
    static final Schedule SCHEDULE = new Schedule(bd("18"), bd("10"), bd("300"), bd("500"), 14, 365,
            List.of(new Service("DUP_STATEMENT", "Duplicate statement", bd("100"))));

    static void eq(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    static void leg(TransactionLot lot, String account, String gl, PostingLine.Side side, String amount) {
        PostingLine l = lot.lines().stream().filter(x -> x.account().equals(account) && x.glCode().equals(gl)).findFirst()
                .orElseThrow(() -> new AssertionError("no line " + account + " " + gl + " in " + lot.lines()));
        assertEquals(side, l.side(), account + " side");
        eq(amount, l.amount());
    }

    // ---- charges --------------------------------------------------------------------------------------------------

    @Test
    void aChargeWithinTheStateBearsCgstAndSgstAndAcrossStatesIgst() {
        Charge in = DepositCharges.charge("DUP_STATEMENT", "Duplicate statement", bd("100"), bd("18"), "32", "32");
        eq("9.00", in.tax().cgst());
        eq("9.00", in.tax().sgst());
        eq("0", in.tax().igst());
        eq("118.00", in.total());
        Charge across = DepositCharges.charge("DUP_STATEMENT", "Duplicate statement", bd("100"), bd("18"), "32", "27");
        eq("18.00", across.tax().igst());
        eq("0", across.tax().cgst());
        // no state on file for the depositor: the branch's own state
        assertEquals("32", DepositCharges.charge("X", "x", bd("100"), bd("18"), "32", null).placeOfSupply());
        // 33.33 x 18% = 5.9994 -> 6.00, split 3.00 and 3.00
        Charge odd = DepositCharges.charge("X", "x", bd("33.33"), bd("18"), "32", "32");
        eq("3.00", odd.tax().cgst());
        eq("3.00", odd.tax().sgst());
    }

    @Test
    void aChargeNeverTakesTheBalanceBelowZero() {
        Charge c = DepositCharges.charge("MIN_BALANCE", "Minimum balance charge", bd("100"), bd("18"), "32", "32");
        assertEquals(c, DepositCharges.fitTo(c, bd("500")).orElseThrow());
        // 59.00 available: taxable 50.00 and GST 9.00 inside it
        Charge cut = DepositCharges.fitTo(c, bd("59")).orElseThrow();
        eq("50.00", cut.taxable());
        eq("4.50", cut.tax().cgst());
        eq("4.50", cut.tax().sgst());
        eq("59.00", cut.total());
        assertTrue(DepositCharges.fitTo(c, BigDecimal.ZERO).isEmpty());
        assertTrue(DepositCharges.fitTo(c, bd("0.01")).isEmpty(), "a paisa holds no taxable value");
    }

    @Test
    void theMinimumBalanceChargeIsInProportionToTheShortfallAndCapped() {
        // 1,000 for ten days and 400 for twenty-one: (10,000 + 8,400) / 31 = 593.548... -> 593.55
        BigDecimal avg = DepositCharges.averageBalance(JUL1, LocalDate.of(2026, 7, 31), d -> d.getDayOfMonth() <= 10 ? bd("1000") : bd("400"));
        eq("593.55", avg);
        eq("406.45", DepositCharges.shortfall(bd("1000"), avg));
        eq("40.65", DepositCharges.minimumBalanceCharge(SCHEDULE, DepositCharges.shortfall(bd("1000"), avg)).orElseThrow());
        eq("300", DepositCharges.minimumBalanceCharge(SCHEDULE, bd("5000")).orElseThrow());
        assertTrue(DepositCharges.minimumBalanceCharge(SCHEDULE, BigDecimal.ZERO).isEmpty());
        assertTrue(DepositCharges.minimumBalanceCharge(Schedule.none(), bd("5000")).isEmpty());
        eq("0", DepositCharges.shortfall(bd("1000"), bd("1200")));
    }

    @Test
    void theClosureChargeIsOnlyBetweenTheFreePeriodAndItsLimit() {
        assertTrue(DepositCharges.closureCharge(SCHEDULE, JUL1, LocalDate.of(2026, 7, 14)).isEmpty(), "13 days: free");
        eq("500", DepositCharges.closureCharge(SCHEDULE, JUL1, LocalDate.of(2026, 7, 15)).orElseThrow());
        eq("500", DepositCharges.closureCharge(SCHEDULE, JUL1, LocalDate.of(2027, 7, 1)).orElseThrow());
        assertTrue(DepositCharges.closureCharge(SCHEDULE, JUL1, LocalDate.of(2027, 7, 2)).isEmpty(), "366 days: past the limit");
        assertTrue(DepositCharges.closureCharge(Schedule.none(), JUL1, LocalDate.of(2026, 9, 1)).isEmpty());
    }

    @Test
    void aScheduleIsCheckedBeforeItIsUsed() {
        assertTrue(DepositCharges.problems(SCHEDULE).isEmpty());
        assertTrue(DepositCharges.problems(null).isEmpty());
        List<String> p = DepositCharges.problems(new Schedule(bd("118"), bd("10"), null, bd("500"), null, null,
                List.of(new Service("MIN_BALANCE", "", bd("0")), new Service("DUP", "x", bd("1.005")), new Service("DUP", "y", bd("1")))));
        assertTrue(p.stream().anyMatch(x -> x.contains("GST rate")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("needs both")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("free period")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("not MIN_BALANCE")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("listed twice")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("DUP: the amount")), p.toString());
        assertEquals(Optional.empty(), SCHEDULE.service("NONE"));
    }

    // ---- maturity -------------------------------------------------------------------------------------------------

    @Test
    void aDepositMaturingOnAHolidayEarnsTheContractedRateToTheNextWorkingDay() {
        LocalDate sat = LocalDate.of(2026, 7, 4);
        LocalDate mon = LocalDate.of(2026, 7, 6);
        // 1,00,000 x 7% x 2 / 365 = 38.356... -> 38.36
        Maturity.Overdue o = Maturity.overdue(bd("100000"), bd("7"), sat, mon, mon, bd("2.75"), Rounding.PAISE_HALF_UP);
        assertEquals(2, o.days());
        eq("7", o.ratePercent());
        eq("38.36", o.interest());
        assertTrue(o.basis().contains("holiday"), o.basis());
        eq("0", Maturity.overdue(bd("100000"), bd("7"), mon, mon, mon, bd("2.75"), Rounding.PAISE_HALF_UP).interest());
    }

    @Test
    void aMaturedDepositPaidLateEarnsTheLowerOfTheTwoRatesOrNothing() {
        LocalDate due = LocalDate.of(2026, 7, 4);
        LocalDate paid = LocalDate.of(2026, 8, 5);
        // 32 days at 2.75%: 1,00,000 x 0.0275 x 32 / 365 = 241.0958... -> 241.10
        Maturity.Overdue bank = Maturity.overdue(bd("100000"), bd("7"), due, paid, LocalDate.of(2026, 7, 6), bd("2.75"), Rounding.PAISE_HALF_UP);
        eq("2.75", bank.ratePercent());
        eq("241.10", bank.interest());
        // a contracted rate below the overdue rate is kept: 2% for 32 days = 175.34
        eq("175.34", Maturity.overdue(bd("100000"), bd("2"), due, paid, LocalDate.of(2026, 7, 6), bd("2.75"), Rounding.PAISE_HALF_UP).interest());
        Maturity.Overdue nbfc = Maturity.overdue(bd("100000"), bd("7"), due, paid, LocalDate.of(2026, 7, 6), null, Rounding.PAISE_HALF_UP);
        eq("0", nbfc.interest());
        assertTrue(nbfc.basis().startsWith("no interest"), nbfc.basis());
    }

    @Test
    void theMaturityNoticeFallsDueTheGivenNumberOfDaysBefore() {
        LocalDate m = LocalDate.of(2026, 7, 31);
        assertFalse(Maturity.noticeDue(m, LocalDate.of(2026, 7, 16), 14));
        assertTrue(Maturity.noticeDue(m, LocalDate.of(2026, 7, 17), 14));
        assertTrue(Maturity.noticeDue(m, LocalDate.of(2026, 7, 30), 14));
        assertFalse(Maturity.noticeDue(m, m, 14), "on the day it is processed, not noticed");
    }

    // ---- savings interest on closure ------------------------------------------------------------------------------

    @Test
    void closingASavingsAccountCreditsTheOpenPeriodUpToTheDayBefore() {
        SavingsInterest si = new SavingsInterest(List.of(new Band(bd("0"), bd("2.75"))), BandMode.INCREMENTAL, DayCount.ACTUAL_365);
        // accrued 1 to 9 July on 1,00,000: 9 x 7.53424658 = 67.80821922, booked 67.81
        SavingsState s = new SavingsState(LocalDate.of(2026, 7, 9), bd("67.80821922"), bd("67.81"), JUL1);
        SavingsResult r = InterestDayEnd.savingsToClose(si, s, LocalDate.of(2026, 7, 15), d -> bd("100000"), 3, 4, Rounding.PAISE_HALF_UP);
        // 10 to 14 July accrued (14 days: 105.47945212 -> 105.48, so 37.67 more), then 105.48 credited for 1 to 14 July
        assertEquals(2, r.events().size(), r.events().toString());
        Accrual a = (Accrual) r.events().get(0);
        eq("37.67", a.amount());
        Credit c = (Credit) r.events().get(1);
        eq("105.48", c.amount());
        assertEquals(JUL1, c.periodFrom());
        assertEquals(LocalDate.of(2026, 7, 14), c.periodEnd());
        eq("0", r.state().accrued());
        // a product crediting to the rupee trues the accrual down first: 105 credited, 0.48 taken back
        SavingsResult rupee = InterestDayEnd.savingsToClose(si, s, LocalDate.of(2026, 7, 15), d -> bd("100000"), 3, 4, Rounding.RUPEE_HALF_UP);
        eq("-0.48", ((Accrual) rupee.events().get(1)).amount());
        eq("105", ((Credit) rupee.events().get(2)).amount());
        assertThrows(IllegalArgumentException.class,
                () -> InterestDayEnd.savingsToClose(si, s, LocalDate.of(2026, 7, 9), d -> bd("100000"), 3, 4, Rounding.PAISE_HALF_UP));
        // an account closed the day it was opened earns nothing
        SavingsResult none = InterestDayEnd.savingsToClose(si, SavingsState.startingOn(JUL1), JUL1, d -> bd("100000"), 3, 4, Rounding.PAISE_HALF_UP);
        assertTrue(none.events().isEmpty());
    }

    // ---- postings --------------------------------------------------------------------------------------------------

    @Test
    void aChargePostsTheAccountAgainstIncomeAndTheTaxHeads() {
        Charge c = DepositCharges.charge("DUP_STATEMENT", "Duplicate statement", bd("100"), bd("18"), "32", "32");
        TransactionLot lot = POST.charge(SB, ChargeHeads.starter(), c, "INV1");
        assertEquals(DepositPostings.CHARGE, lot.type());
        leg(lot, SB.accountNo(), "2401", PostingLine.Side.DR, "118.00");
        leg(lot, "4107", "4107", PostingLine.Side.CR, "100.00");
        leg(lot, "2201", "2201", PostingLine.Side.CR, "9.00");
        leg(lot, "2202", "2202", PostingLine.Side.CR, "9.00");
        assertEquals(4, lot.lines().size());
        TransactionLot igst = POST.charge(SB, ChargeHeads.starter(), DepositCharges.charge("X", "X", bd("100"), bd("18"), "32", "27"), "INV2");
        leg(igst, "2203", "2203", PostingLine.Side.CR, "18.00");
        assertEquals(3, igst.lines().size());
    }

    @Test
    void closureProceedsGoWhereTheyAreSent() {
        leg(POST.closurePayout(FD, bd("1000"), PayTo.CASH, null, "C1", null), "1201", "1201", PostingLine.Side.CR, "1000");
        leg(POST.closurePayout(FD, bd("1000"), PayTo.BANK_TRANSFER, null, "UTR1", null), "1202", "1202", PostingLine.Side.CR, "1000");
        leg(POST.closurePayout(FD, bd("1000"), PayTo.GATEWAY, null, "G1", null), "2408", "2408", PostingLine.Side.CR, "1000");
        TransactionLot toAccount = POST.closurePayout(FD, bd("1000"), PayTo.ACCOUNT, SB_MUM, "C2", null);
        assertEquals(DepositPostings.CLOSURE, toAccount.type());
        leg(toAccount, FD.accountNo(), "2403", PostingLine.Side.DR, "1000");
        leg(toAccount, SB_MUM.accountNo(), "2401", PostingLine.Side.CR, "1000");
        assertEquals(4, toAccount.lines().size(), "inter-branch legs between HO and MUM");
        assertThrows(IllegalArgumentException.class, () -> POST.closurePayout(FD, bd("1000"), PayTo.ACCOUNT, null, "C3", null));
        assertThrows(IllegalArgumentException.class, () -> POST.closurePayout(SB, bd("1000"), PayTo.ACCOUNT, SB, "C4", null));
    }

    @Test
    void recoveryAndRenewalPostings() {
        TransactionLot back = POST.interestRecovery(FD, "5107", bd("250"), FD.accountNo());
        leg(back, FD.accountNo(), "2403", PostingLine.Side.DR, "250");
        leg(back, "5107", "5107", PostingLine.Side.CR, "250");
        Leg renewed = new Leg("HO", "2403", "30010000000029");
        TransactionLot r = POST.renewal(FD, renewed, bd("107185.90"));
        assertEquals(DepositPostings.RENEWAL, r.type());
        leg(r, FD.accountNo(), "2403", PostingLine.Side.DR, "107185.90");
        leg(r, renewed.accountNo(), "2403", PostingLine.Side.CR, "107185.90");
    }
}
