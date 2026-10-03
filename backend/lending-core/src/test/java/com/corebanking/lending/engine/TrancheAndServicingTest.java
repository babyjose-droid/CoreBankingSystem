package com.corebanking.lending.engine;

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
import com.corebanking.calc.EmiCalculator;
import com.corebanking.calc.FeeCalculator;
import com.corebanking.calc.Rounding;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.engine.Delinquency.AssetClass;
import com.corebanking.lending.engine.LoanTerms.Options;
import com.corebanking.ledger.PostingLine;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P2-6: multi-tranche disbursement (US-050), sanctioned-amount and maturity amendments and the asset-class override
 * (US-059), simulations (US-060) and the GST credit note on a fee waiver.
 */
class TrancheAndServicingTest {

    static final LocalDate OPEN = LocalDate.of(2026, 6, 30);
    static final FeeRule PF = new FeeRule("PF", "Processing fee", FeeRule.Event.DISBURSEMENT, FeeRule.CalcType.FIXED, bd("1000"),
            null, null, null, null, bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, true);
    static final FeeRule TRANCHE_FEE = new FeeRule("TF", "Disbursement fee", FeeRule.Event.EVERY_DISBURSEMENT, FeeRule.CalcType.PERCENT,
            null, bd("0.5"), null, null, null, bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, true);
    static final FeeRule ADHOC = new FeeRule("DOC", "Document charge", FeeRule.Event.ADHOC, FeeRule.CalcType.FIXED, bd("500"),
            null, null, null, null, bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, false);

    static LoanTerms emi(String amount, String rate, int months) {
        return LoanTerms.equated(bd(amount), bd(rate), months, OPEN);
    }

    // ------------------------------------------------------------------------------------------------ tranches
    @Test
    void two_tranches_with_fees_reschedule_over_the_instalments_left() {
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.open(params("12", List.of(PF, TRANCHE_FEE)), emi("300000", "12", 24), bd("100000"), false, OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        // first tranche: processing fee on the sanctioned amount (1000 + 180), tranche fee 0.5% of 100000 (500 + 90)
        eq("98230", book.netDisbursal(), "net of tranche 1");
        eq("98230", gl.cr("1202"), "bank");
        eq("100000", a.principalOutstanding(), "principal = amount drawn");
        eq("300000", a.sanctioned(), "sanctioned");
        eq("200000", a.undrawn(), "undrawn");
        assertFalse(a.fullyDrawn());
        assertEquals(24, book.schedule().size());
        eq(EmiCalculator.pmt(bd("100000"), bd("12"), 24, Rounding.RUPEE_HALF_UP).toPlainString(), book.schedule().get(0).instalment(),
                "EMI on the amount drawn");
        assertThrows(IllegalArgumentException.class, () -> a.simulateTranche(bd("200001"), OPEN.plusDays(1)));

        // first instalment paid; second (final) tranche on 14-Aug, mid-period
        Life.runPaying(a, gl, OPEN, LocalDate.of(2026, 8, 13));
        LocalDate day = LocalDate.of(2026, 8, 14);
        BigDecimal accruedOld = a.accruedNotDemanded();
        BigDecimal balanceBefore = a.principalOutstanding();
        var sim = a.simulateTranche(bd("200000"), day);
        eq(balanceBefore.toPlainString(), a.principalOutstanding(), "a simulation changes nothing");
        assertEquals(1, a.tranches().size());
        var result = a.drawTranche(bd("200000"), day);
        gl.post(result.lots());
        eq("198820", sim.netDisbursal(), "tranche 2 net: 200000 − (1000 + 180); the processing fee is not charged again");
        eq("198820", a.tranches().get(1).net(), "simulation = posting");
        assertTrue(a.fullyDrawn());
        assertEquals(sim.scheduleAfter(), a.futureSchedule(), "simulated schedule = booked schedule");
        assertEquals(23, a.futureSchedule().size(), "the instalments left");
        eq(balanceBefore.add(bd("200000")).toPlainString(), a.principalOutstanding(), "principal");
        eq(a.principalOutstanding().toPlainString(), sum(a.futureSchedule(), Instalment::principal), "future principal = outstanding");
        assertEquals(LocalDate.of(2026, 8, 31), a.futureSchedule().get(0).dueDate(), "due dates unchanged");
        assertTrue(a.currentEmi().compareTo(bd("14000")) > 0, "EMI recomputed on the larger balance: " + a.currentEmi());
        // the next demand: interest accrued on the old balance + 17 days on the new balance
        Life.runEod(a, gl, day, LocalDate.of(2026, 8, 31));
        var demand = a.demands().get(1);
        BigDecimal onNew = a.principalOutstanding().multiply(bd("12")).multiply(bd("17")).divide(bd("36500"), 0, java.math.RoundingMode.HALF_UP);
        assertTrue(demand.interestDue().subtract(accruedOld).subtract(onNew).abs().compareTo(BigDecimal.ONE) <= 0,
                "blended interest " + demand.interestDue() + " = " + accruedOld + " + " + onNew);
        assertThrows(IllegalStateException.class, () -> a.drawTranche(bd("1"), LocalDate.of(2026, 9, 1)));    // fully disbursed
        Life.payOnTimeUntilClosed(a, gl, LocalDate.of(2026, 9, 1));
        assertClosedAndReconciled(a, gl, "two tranches");
        eq("297050", gl.cr("1202"), "bank paid both nets");
        eq("2500", gl.cr("4102"), "fee income: 1000 + 500 + 1000");
        eq("450", gl.cr("2201").add(gl.cr("2202")), "GST on the fees");
        assertEquals(24, a.demands().size(), "tenor unchanged");
    }

    @Test
    void no_tranche_while_dues_are_unpaid_and_not_for_every_method() {
        var book = LoanAccount.open(params("12", List.of()), emi("300000", "12", 24), bd("100000"), false, OPEN);
        LoanAccount a = book.account();
        Life.Gl gl = new Life.Gl();
        Life.runEod(a, gl, OPEN, LocalDate.of(2026, 8, 5));               // first instalment unpaid
        assertThrows(IllegalStateException.class, () -> a.drawTranche(bd("50000"), LocalDate.of(2026, 8, 6)));
        // a step loan cannot be partly disbursed
        LoanTerms step = new LoanTerms(bd("300000"), bd("12"), 24, OPEN, null, RepaymentMethod.STEP_EQUATED, 0, null, null, null, false,
                Options.NONE.withStep(bd("5"), 12));
        assertThrows(IllegalArgumentException.class, () -> LoanAccount.open(params("12", List.of()), step, bd("100000"), false, OPEN));
        assertThrows(IllegalArgumentException.class, () -> LoanAccount.open(params("12", List.of()), emi("300000", "12", 24), bd("300001"), false, OPEN));
        // but it can be disbursed in full
        assertTrue(LoanAccount.open(params("12", List.of()), step, bd("300000"), false, OPEN).account().fullyDrawn());
    }

    @Test
    void pre_emi_interest_until_the_final_tranche_then_emi_for_the_full_tenor() {
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.open(params("10", List.of()), emi("200000", "10", 12), bd("80000"), true, OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        assertTrue(a.preEmi());
        assertEquals(13, book.schedule().size(), "one pre-EMI instalment ahead, then the 12 EMIs");
        eq("0", book.schedule().get(0).principal(), "pre-EMI: interest only");
        eq("679", book.schedule().get(0).interest(), "80000 × 10% × 31/365");
        // two months pass without a further tranche: two interest-only demands, the EMIs keep moving out
        Life.runPaying(a, gl, OPEN, LocalDate.of(2026, 9, 9));
        assertEquals(2, a.demands().size());
        eq("0", a.demands().get(0).principalDue(), "pre-EMI demand 1");
        eq("0", a.demands().get(1).principalDue(), "pre-EMI demand 2");
        eq("80000", a.principalOutstanding(), "nothing repaid yet");
        assertEquals(13, a.futureSchedule().size(), "still one pre-EMI instalment and 12 EMIs ahead");
        eq("0", a.futureSchedule().get(0).principal(), "next instalment is interest only");

        // final tranche on 10-Sep: EMIs start on the next due date, for the full tenor
        LocalDate day = LocalDate.of(2026, 9, 10);
        gl.post(a.drawTranche(bd("120000"), day).lots());
        assertTrue(a.fullyDrawn());
        assertFalse(a.preEmi());
        assertEquals(12, a.futureSchedule().size(), "12 EMIs");
        assertEquals(LocalDate.of(2026, 9, 30), a.futureSchedule().get(0).dueDate());
        assertTrue(a.futureSchedule().get(0).principal().signum() > 0, "the first EMI repays principal");
        eq(EmiCalculator.pmt(bd("200000"), bd("10"), 12, Rounding.RUPEE_HALF_UP).toPlainString(), a.currentEmi(), "EMI on the full amount");
        Life.payOnTimeUntilClosed(a, gl, day);
        assertClosedAndReconciled(a, gl, "pre-EMI");
        assertEquals(14, a.demands().size(), "2 pre-EMI + 12 EMI demands");
    }

    @Test
    void cancelling_the_undrawn_amount_starts_the_emis_on_what_was_drawn() {
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.open(params("10", List.of()), emi("200000", "10", 12), bd("80000"), true, OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        Life.runPaying(a, gl, OPEN, LocalDate.of(2026, 8, 9));
        LocalDate day = LocalDate.of(2026, 8, 10);
        var r = a.cancelUndrawn(day);
        assertTrue(r.lots().isEmpty(), "an undrawn commitment is not on the balance sheet");
        eq("80000", a.sanctioned(), "sanctioned = drawn");
        assertTrue(a.fullyDrawn());
        assertEquals(12, a.futureSchedule().size());
        eq(EmiCalculator.pmt(bd("80000"), bd("10"), 12, Rounding.RUPEE_HALF_UP).toPlainString(), a.currentEmi(), "EMI on the amount drawn");
        assertThrows(IllegalStateException.class, () -> a.cancelUndrawn(day));
        assertThrows(IllegalStateException.class, () -> a.drawTranche(bd("1000"), day));
        Life.payOnTimeUntilClosed(a, gl, day);
        assertClosedAndReconciled(a, gl, "undrawn cancelled");
    }

    @Test
    void bullet_and_fixed_principal_loans_take_tranches_too() {
        for (RepaymentMethod m : List.of(RepaymentMethod.BULLET_TOTAL_INTEREST, RepaymentMethod.BULLET_PERIODIC_INTEREST, RepaymentMethod.FIXED_PRINCIPAL)) {
            Life.Gl gl = new Life.Gl();
            LoanTerms t = new LoanTerms(bd("200000"), bd("12"), 6, OPEN, null, m, 0, null, DayCount.ACTUAL_365, null, false, null);
            var book = LoanAccount.open(params("12", List.of()), t, bd("50000"), false, OPEN);
            gl.post(book.result().lots());
            LoanAccount a = book.account();
            Life.runPaying(a, gl, OPEN, LocalDate.of(2026, 7, 19));
            gl.post(a.drawTranche(bd("150000"), LocalDate.of(2026, 7, 20)).lots());
            eq("200000", a.principalOutstanding(), m + ": principal");
            eq("200000", sum(a.futureSchedule(), Instalment::principal), m + ": future principal");
            assertEquals(LocalDate.of(2026, 12, 31), a.futureSchedule().get(a.futureSchedule().size() - 1).dueDate(), m + ": maturity unchanged");
            Life.payOnTimeUntilClosed(a, gl, LocalDate.of(2026, 7, 20));
            assertClosedAndReconciled(a, gl, m + " in tranches");
            // interest = 50000 for 20 days + 200000 from then on (bullets), by the day count
            if (m == RepaymentMethod.BULLET_TOTAL_INTEREST) {
                BigDecimal expected = bd("50000").multiply(bd("12")).multiply(bd("20")).add(bd("200000").multiply(bd("12")).multiply(bd("164")))
                        .divide(bd("36500"), 0, java.math.RoundingMode.HALF_UP);
                assertTrue(gl.cr("4101").subtract(expected).abs().compareTo(BigDecimal.ONE) <= 0, "bullet interest " + gl.cr("4101") + " vs " + expected);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ sanctioned amount
    @Test
    void top_up_and_reduction_of_the_sanctioned_amount() {
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.disburse(params("12", List.of(TRANCHE_FEE)), emi("100000", "12", 12), OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        Life.runPaying(a, gl, OPEN, LocalDate.of(2026, 9, 14));
        LocalDate day = LocalDate.of(2026, 9, 15);
        assertThrows(IllegalArgumentException.class, () -> a.previewSanctionChange(bd("90000"), day));    // below the amount disbursed
        assertThrows(IllegalArgumentException.class, () -> a.previewSanctionChange(bd("100000"), day));   // no change
        var effect = a.previewSanctionChange(bd("150000"), day);
        assertTrue(effect.topUp());
        eq("50000", effect.undrawnAfter(), "the top-up is undrawn until disbursed");
        eq("100000", a.sanctioned(), "a preview changes nothing");
        BigDecimal emiBefore = a.currentEmi();
        List<Instalment> scheduleBefore = a.futureSchedule();
        assertTrue(a.changeSanction(bd("150000"), day).lots().isEmpty(), "no money moves on the amendment itself");
        assertEquals(scheduleBefore, a.futureSchedule(), "the schedule changes only when the top-up is disbursed");
        // amendments wait until the top-up is disbursed or cancelled
        assertThrows(IllegalStateException.class, () -> a.previewAmendment(Amendment.tenure(12, null), day));
        gl.post(a.drawTranche(bd("50000"), day).lots());
        assertEquals(scheduleBefore.size(), a.futureSchedule().size(), "same instalments left");
        assertTrue(a.currentEmi().compareTo(emiBefore) > 0, "higher EMI");
        eq("150000", a.disbursedAmount(), "disbursed in all");
        assertEquals(2, a.tranches().size());
        eq("49705", a.tranches().get(1).net(), "50000 − tranche fee 250 − GST 45");
        Life.payOnTimeUntilClosed(a, gl, day);
        assertClosedAndReconciled(a, gl, "top-up");

        // reduction: only the undrawn part
        var b = LoanAccount.open(params("12", List.of()), emi("300000", "12", 24), bd("100000"), false, OPEN).account();
        b.endOfDay(OPEN, null);
        LocalDate next = OPEN.plusDays(1);
        b.changeSanction(bd("250000"), next);
        eq("150000", b.undrawn(), "undrawn reduced");
        assertThrows(IllegalArgumentException.class, () -> b.changeSanction(bd("99999"), next));
        b.changeSanction(bd("100000"), next);
        assertTrue(b.fullyDrawn());

        // no top-up for a borrower in arrears
        var c = LoanAccount.disburse(params("12", List.of()), emi("100000", "12", 12), OPEN).account();
        for (LocalDate d = OPEN; !d.isAfter(LocalDate.of(2026, 8, 5)); d = d.plusDays(1)) c.endOfDay(d, null);
        assertEquals(AssetClass.SMA0, c.assetClass());
        assertThrows(IllegalStateException.class, () -> c.previewSanctionChange(bd("150000"), LocalDate.of(2026, 8, 6)));
    }

    // ------------------------------------------------------------------------------------------------ maturity
    @Test
    void maturity_change_is_a_tenure_change_to_a_date() {
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.disburse(params("18", List.of()), emi("100000", "18", 12), OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        Life.runPaying(a, gl, OPEN, LocalDate.of(2026, 9, 14));
        LocalDate day = LocalDate.of(2026, 9, 15);
        assertEquals(LocalDate.of(2027, 6, 30), a.futureSchedule().get(9).dueDate(), "maturity before");
        // extension to December 2027: 16 instalments from 30-Sep-2026
        var ext = a.previewAmendment(Amendment.maturity(LocalDate.of(2027, 12, 10), 36), day);
        var same = a.previewAmendment(Amendment.tenure(16, 36), day);
        assertEquals(16, ext.remainingAfter());
        assertEquals(LocalDate.of(2027, 12, 31), ext.maturityAfter(), "the instalment date in the month asked for");
        eq(same.emiAfter().toPlainString(), ext.emiAfter(), "same EMI as a tenure change to 16");
        assertEquals(same.scheduleAfter(), ext.scheduleAfter());
        assertEquals(Amendment.Kind.MATURITY_CHANGE, ext.kind());
        // reduction to March 2027: 7 instalments, higher EMI
        var red = a.previewAmendment(Amendment.maturity(LocalDate.of(2027, 3, 31), 36), day);
        assertEquals(7, red.remainingAfter());
        assertTrue(red.emiAfter().compareTo(red.emiBefore()) > 0);
        assertThrows(IllegalArgumentException.class, () -> a.previewAmendment(Amendment.maturity(LocalDate.of(2027, 6, 1), 36), day));   // unchanged
        assertThrows(IllegalArgumentException.class, () -> a.previewAmendment(Amendment.maturity(LocalDate.of(2026, 8, 31), 36), day));  // in the past
        assertThrows(IllegalArgumentException.class, () -> a.previewAmendment(Amendment.maturity(LocalDate.of(2029, 12, 31), 36), day)); // beyond product tenure
        assertThrows(IllegalArgumentException.class, () -> new Amendment(Amendment.Kind.MATURITY_CHANGE, null, null, null, null, null, null, null));
        gl.post(a.amend(Amendment.maturity(LocalDate.of(2027, 12, 10), 36), day).lots());
        assertEquals(16, a.futureSchedule().size());
        Life.payOnTimeUntilClosed(a, gl, day);
        assertClosedAndReconciled(a, gl, "maturity extended");
    }

    // ------------------------------------------------------------------------------------------------ asset class override
    @Test
    void asset_class_override_downgrades_or_holds_but_never_upgrades() {
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.disburse(params("18", List.of()), emi("100000", "18", 12), OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        Life.runPaying(a, gl, OPEN, LocalDate.of(2026, 8, 14));
        LocalDate day = LocalDate.of(2026, 8, 15);
        assertEquals(AssetClass.STANDARD, a.assetClass());
        LocalDate until = LocalDate.of(2026, 11, 15);
        assertThrows(IllegalArgumentException.class, () -> a.overrideAssetClass(AssetClass.STANDARD, until, day));   // not an NPA class
        assertThrows(IllegalArgumentException.class, () -> a.overrideAssetClass(AssetClass.SMA2, until, day));
        assertThrows(IllegalArgumentException.class, () -> a.overrideAssetClass(AssetClass.SUBSTANDARD, day, day));  // expiry must be later
        assertThrows(IllegalArgumentException.class, () -> a.overrideAssetClass(AssetClass.SUBSTANDARD, null, day));

        // downgrade a performing account: unrealised (accrued) income goes to suspense
        BigDecimal accrued = a.accruedNotDemanded();
        assertTrue(accrued.signum() > 0);
        BigDecimal incomeBefore = gl.cr("4101");
        gl.post(a.overrideAssetClass(AssetClass.SUBSTANDARD, until, day).lots());
        assertEquals(AssetClass.SUBSTANDARD, a.assetClass());
        assertEquals(day, a.npaSince());
        eq(accrued.toPlainString(), a.suspense(), "accrued interest moved to suspense");
        eq(accrued.toPlainString(), gl.cr("2305"), "suspense GL");
        eq(incomeBefore.subtract(accrued).toPlainString(), gl.cr("4101"), "income reversed");

        // the borrower keeps paying on time: the hold keeps the account NPA and interest goes to suspense
        Life.runPaying(a, gl, day, LocalDate.of(2026, 11, 15));
        assertEquals(AssetClass.SUBSTANDARD, a.assetClass(), "held although nothing is overdue");
        assertEquals(0, a.dpd());
        assertTrue(a.provisionHeld().compareTo(bd("5000")) > 0, "10% provision while sub-standard: " + a.provisionHeld());
        // a weaker or shorter override is itself an upgrade
        LocalDate mid = LocalDate.of(2026, 10, 1);
        assertThrows(IllegalStateException.class, () -> a.dryRun(mid, null, x -> x.overrideAssetClass(AssetClass.SUBSTANDARD, LocalDate.of(2026, 10, 31), mid)));
        // the day after the override expires the normal rule applies: no arrears, so the account is standard again
        Life.runPaying(a, gl, LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 16));
        assertEquals(AssetClass.STANDARD, a.assetClass(), "upgraded by the IRACP rule once the hold has expired");
        assertNull(a.npaSince());
        eq("0", gl.dr("2305"), "suspense released to income on upgrade");
        Life.payOnTimeUntilClosed(a, gl, LocalDate.of(2026, 11, 17));
        assertClosedAndReconciled(a, gl, "override then normal life");

        // an NPA cannot be made better: a doubtful account cannot be "overridden" to sub-standard
        var b = LoanAccount.disburse(params("18", List.of()), emi("100000", "18", 12), OPEN).account();
        b.endOfDay(OPEN, null);
        LocalDate d1 = OPEN.plusDays(1);
        b.overrideAssetClass(AssetClass.DOUBTFUL1, d1.plusDays(30), d1);
        assertEquals(AssetClass.DOUBTFUL1, b.assetClass());
        var refused = assertThrows(IllegalStateException.class, () -> b.overrideAssetClass(AssetClass.SUBSTANDARD, d1.plusDays(60), d1));
        assertTrue(refused.getMessage().contains("never upgrade"), refused.getMessage());
        // a further downgrade is allowed, and the hold only ever makes the class worse than the days-past-due rule
        b.overrideAssetClass(AssetClass.LOSS, d1.plusDays(60), d1);
        assertEquals(AssetClass.LOSS, b.assetClass());
        for (LocalDate d = d1; !d.isAfter(d1.plusDays(90)); d = d.plusDays(1)) b.endOfDay(d, null);
        assertEquals(AssetClass.LOSS, b.assetClass(), "loss is permanent, also after the expiry");
    }

    @Test
    void override_release_is_refused_while_arrears_exist_and_upgrades_only_at_day_end() {
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.disburse(params("18", List.of()), emi("100000", "18", 12), OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        Life.runPaying(a, gl, OPEN, LocalDate.of(2026, 8, 14));
        LocalDate day = LocalDate.of(2026, 8, 15);
        assertThrows(IllegalStateException.class, () -> a.releaseAssetClassOverride(day), "nothing to release");
        gl.post(a.overrideAssetClass(AssetClass.SUBSTANDARD, LocalDate.of(2027, 3, 31), day).lots());

        // an instalment falls due and is not paid: the un-mark is refused (RBI upgrade rule)
        LocalDate d = day;
        while (a.overdueAmount(d).signum() == 0) {
            gl.post(a.endOfDay(d, null).lots());
            d = d.plusDays(1);
        }
        LocalDate arrearsDay = d;
        var refused = assertThrows(IllegalStateException.class, () -> a.releaseAssetClassOverride(arrearsDay));
        assertTrue(refused.getMessage().contains("arrears"), refused.getMessage());
        assertEquals(AssetClass.SUBSTANDARD, a.assetClass());
        assertEquals(AssetClass.SUBSTANDARD, a.classFloor(), "a refused release changes nothing");

        // the arrears are paid: the release is allowed, posts nothing, and the day-end upgrades the account
        gl.post(a.pay(a.overdueAmount(arrearsDay), arrearsDay, arrearsDay, "Receipt").lots());
        LoanAccount.Result released = a.releaseAssetClassOverride(arrearsDay);
        assertTrue(released.lots().isEmpty(), "a release posts nothing");
        assertNull(a.classFloor());
        assertNull(a.classFloorUntil());
        assertEquals(AssetClass.SUBSTANDARD, a.assetClass(), "the class changes at day-end, not at the release");
        gl.post(a.endOfDay(arrearsDay, null).lots());
        assertEquals(AssetClass.STANDARD, a.assetClass(), "upgraded by the normal rule: no arrears");
        assertNull(a.npaSince());
        eq(gl.dr("2305").toPlainString(), gl.cr("2305"), "suspense fully released on upgrade");
        Life.payOnTimeUntilClosed(a, gl, arrearsDay.plusDays(1));
        assertClosedAndReconciled(a, gl, "override released");

        // LOSS cannot be un-marked
        var b = LoanAccount.disburse(params("18", List.of()), emi("100000", "18", 12), OPEN).account();
        b.endOfDay(OPEN, null);
        b.overrideAssetClass(AssetClass.LOSS, OPEN.plusDays(60), OPEN.plusDays(1));
        assertThrows(IllegalStateException.class, () -> b.releaseAssetClassOverride(OPEN.plusDays(1)));
    }

    // ------------------------------------------------------------------------------------------------ simulations
    @Test
    void simulations_use_the_posting_code_and_change_nothing() {
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.disburse(params("18", List.of(ADHOC)), emi("100000", "18", 12), OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        Life.runEod(a, gl, OPEN, LocalDate.of(2026, 7, 14));
        LocalDate today = LocalDate.of(2026, 7, 15);
        LoanAccount.Snapshot before = a.snapshot();

        // a receipt of 20000 on 20-Aug: the first instalment (due 31-Jul) with its penal charges, the rest an advance
        LocalDate on = LocalDate.of(2026, 8, 20);
        var sim = a.simulateReceipt(bd("20000"), on, Provisioning.starter());
        assertEquals(before, a.snapshot(), "nothing changed");
        eq("1529", sim.interest(), "interest of instalment 1");
        eq("7639", sim.principal(), "principal of instalment 1");
        assertTrue(sim.penal().signum() > 0, "penal charges for the days overdue: " + sim.penal());
        eq(bd("20000").subtract(bd("9168")).subtract(sim.penal()).toPlainString(), sim.advance(), "the rest is kept as an advance");
        eq("0", sim.duesAfter(), "nothing left overdue");
        assertEquals(0, sim.dpdAfter());
        assertEquals(AssetClass.STANDARD, sim.assetClassAfter());
        assertTrue(sim.duesBefore().compareTo(bd("9168")) > 0);
        assertEquals(sim.interest().add(sim.principal()).add(sim.penal()).add(sim.fees()),
                sim.allocations().stream().map(Appropriation.Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        // the same days for real give the same appropriation
        Life.runEod(a, gl, today, on.minusDays(1));
        BigDecimal penal = a.charges().stream().map(LoanAccount.ChargeRow::unpaid).reduce(BigDecimal.ZERO, BigDecimal::add);
        eq(penal.toPlainString(), sim.penal(), "penal as simulated");
        a.pay(bd("20000"), on, on, "Receipt");
        eq(sim.advance().toPlainString(), a.excess(), "advance as simulated");
        eq(sim.principalOutstandingAfter().toPlainString(), a.principalOutstanding(), "principal as simulated");

        // pre-closure simulated for a later date = the quote on that date
        LoanAccount.Snapshot s2 = a.snapshot();
        LocalDate later = LocalDate.of(2026, 9, 10);
        var quote = a.simulatePreclosure(later, Provisioning.starter());
        assertEquals(s2, a.snapshot());
        // prepayment simulation
        var pre = a.simulatePrepayment(bd("20000"), LoanAccount.PrepaymentMode.REDUCE_EMI, later, Provisioning.starter());
        assertEquals(s2, a.snapshot());
        assertTrue(pre.instalmentAfter().compareTo(pre.instalmentBefore()) < 0);
        assertEquals(pre.remainingBefore(), pre.remainingAfter());
        Life.runEod(a, gl, on, later.minusDays(1));
        eq(a.preclosureQuote(later).total().toPlainString(), quote.total(), "simulated pre-closure = quote on the day");
        BigDecimal outstanding = a.principalOutstanding();
        a.prepay(bd("20000"), LoanAccount.PrepaymentMode.REDUCE_EMI, later);
        eq(outstanding.subtract(bd("20000")).toPlainString(), pre.principalOutstandingAfter(), "prepayment as simulated");
        assertEquals(pre.scheduleAfter(), a.futureSchedule());
        // a simulation of something the loan does not allow reports why, and still changes nothing
        LoanAccount.Snapshot s3 = a.snapshot();
        assertThrows(IllegalArgumentException.class, () -> a.simulatePrepayment(bd("999999"), LoanAccount.PrepaymentMode.REDUCE_EMI, later.plusDays(5), null));
        assertThrows(IllegalStateException.class, () -> a.simulateTranche(bd("1000"), later));
        assertEquals(s3, a.snapshot());
    }

    // ------------------------------------------------------------------------------------------------ GST credit note
    static BigDecimal line(TransactionLot lot, String gl, PostingLine.Side side) {
        return lot.lines().stream().filter(l -> l.glCode().equals(gl) && l.side() == side).map(PostingLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void waiving_a_fee_with_gst_reverses_the_tax_by_credit_note() {
        Life.Gl gl = new Life.Gl();
        var book = LoanAccount.disburse(params("18", List.of(ADHOC)), emi("100000", "18", 12), OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        Life.runEod(a, gl, OPEN, OPEN);
        LocalDate day = OPEN.plusDays(1);
        gl.post(a.chargeFee("DOC", bd("0"), day).lots());
        eq("590", a.charges().get(0).amount(), "500 + 18% GST");
        eq("500", gl.cr("4102"), "fee income");
        eq("90", gl.cr("2201").add(gl.cr("2202")), "output GST");
        // part waiver of 236: taxable 200, CGST 18, SGST 18
        var part = a.waiveCharge("C1", bd("236"), day);
        TransactionLot lot = part.lots().get(0);
        assertEquals("WAIVER", lot.type());
        eq("200", line(lot, "4102", PostingLine.Side.DR), "fee income reduced by the taxable value");
        eq("18", line(lot, "2201", PostingLine.Side.DR), "CGST reduced");
        eq("18", line(lot, "2202", PostingLine.Side.DR), "SGST reduced");
        eq("236", line(lot, "1103", PostingLine.Side.CR), "receivable reduced");
        assertTrue(lot.lines().stream().anyMatch(l -> l.glCode().equals("1103") && l.narration().equals("FEE waiver C1")),
                "the receivable line names the charge for the credit note");
        gl.post(part.lots());
        gl.post(a.waiveCharge("C1", bd("354"), day).lots());
        eq("0", gl.cr("4102"), "no fee income left");
        eq("0", gl.cr("2201").add(gl.cr("2202")), "no output GST left");
        eq("0", gl.dr("1103"), "receivable cleared");

        // inter-state supply: IGST
        var inter = LoanAccount.disburse(new LoanAccount.Params("10010000000025", "HO", "32", "29", bd("18"), bd("24"), null, null, null, null, 3,
                BigDecimal.ZERO, null, List.of(ADHOC)), emi("100000", "18", 12), OPEN).account();
        inter.endOfDay(OPEN, null);
        inter.chargeFee("DOC", bd("0"), day);
        TransactionLot w = inter.waiveCharge("C1", bd("590"), day).lots().get(0);
        eq("90", line(w, "2203", PostingLine.Side.DR), "IGST reduced");
        eq("500", line(w, "4102", PostingLine.Side.DR), "taxable value");

        // after 30 November following the financial year of the invoice the tax can no longer be reduced (s.34(2))
        assertTrue(Gst.creditNoteInTime(LocalDate.of(2026, 7, 1), LocalDate.of(2027, 11, 30)));
        assertFalse(Gst.creditNoteInTime(LocalDate.of(2026, 7, 1), LocalDate.of(2027, 12, 1)));
        assertTrue(Gst.creditNoteInTime(LocalDate.of(2027, 3, 31), LocalDate.of(2027, 11, 30)));
        assertTrue(Gst.creditNoteInTime(LocalDate.of(2027, 4, 1), LocalDate.of(2028, 11, 30)));
        var late = LoanAccount.disburse(params("18", List.of(ADHOC)), emi("100000", "18", 36), OPEN).account();
        late.endOfDay(OPEN, null);
        late.chargeFee("DOC", bd("0"), day);
        TransactionLot l = late.waiveCharge("C1", bd("590"), LocalDate.of(2027, 12, 1)).lots().get(0);
        eq("590", line(l, "4102", PostingLine.Side.DR), "out of time: the whole waiver is borne as income foregone");
        eq("0", line(l, "2201", PostingLine.Side.DR), "tax stays paid");
        // penal charges carry no GST: unchanged
        Gst.Inclusive parts = Gst.unbundle(bd("118"), bd("18"), "32", "32");
        eq("100", parts.taxable(), "unbundle");
        eq("18", parts.tax().total(), "tax");
    }
}
