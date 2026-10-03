package com.corebanking.lending.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.FeeCalculator;
import com.corebanking.calc.Rounding;
import com.corebanking.lending.engine.Delinquency.AssetClass;
import com.corebanking.ledger.PostingLine;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Whole-life scenarios: every posting is checked against a mini general ledger built from the lots. */
class LoanLifecycleTest {

    static BigDecimal bd(String s) { return new BigDecimal(s); }
    static void eq(String e, BigDecimal a, String what) { assertEquals(0, bd(e).compareTo(a), what + ": expected " + e + " but was " + a); }

    static final LocalDate OPEN = LocalDate.of(2026, 6, 30);
    static final FeeRule PF = new FeeRule("PF", "Processing fee", FeeRule.Event.DISBURSEMENT, FeeRule.CalcType.FIXED, bd("750"),
            null, null, null, null, bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, true);
    static final FeeRule FORECLOSE = new FeeRule("FC", "Foreclosure charge", FeeRule.Event.PRECLOSURE, FeeRule.CalcType.PERCENT,
            null, bd("2"), null, null, null, bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, false);

    static LoanAccount.Params params(List<FeeRule> fees) {
        return new LoanAccount.Params("10010000000017", "HO", "32", "32", bd("18"), bd("24"), null, null, null, null, 3,
                BigDecimal.ZERO, null, fees);
    }

    /** Net debit balance per GL code (and per loan-side account) from the lots. */
    static final class Gl {
        final Map<String, BigDecimal> net = new TreeMap<>();
        void post(List<TransactionLot> lots) {
            for (TransactionLot lot : lots) {
                BigDecimal total = BigDecimal.ZERO;
                for (PostingLine l : lot.lines()) {
                    net.merge(l.glCode(), l.signed(), BigDecimal::add);
                    total = total.add(l.signed());
                }
                assertEquals(0, total.signum(), "lot " + lot.type() + " unbalanced");
            }
        }
        BigDecimal dr(String gl) { return net.getOrDefault(gl, BigDecimal.ZERO); }
        BigDecimal cr(String gl) { return dr(gl).negate(); }
    }

    static void runEod(LoanAccount a, Gl gl, LocalDate from, LocalDate to) {
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) gl.post(a.endOfDay(d, Provisioning.starter()).lots());
    }

    @Test
    void performing_loan_paid_on_time_closes_with_exact_scheduled_interest() {
        Gl gl = new Gl();
        var book = LoanAccount.disburse(params(List.of(PF)), LoanTerms.equated(bd("100000"), bd("18"), 12, OPEN), OPEN);
        gl.post(book.result().lots());
        eq("99115", book.netDisbursal(), "net disbursal");
        LoanAccount a = book.account();
        LocalDate d = OPEN;
        BigDecimal scheduledInterest = book.schedule().stream().map(i -> i.interest()).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (var inst : book.schedule()) {
            runEod(a, gl, d, inst.dueDate());                   // demand raised at EOD of the due date
            assertEquals(1, a.dpd(), "SMA-0 at day-end of due date while unpaid");
            gl.post(a.pay(inst.instalment(), inst.dueDate().plusDays(1), inst.dueDate().plusDays(1), "EMI").lots());
            d = inst.dueDate().plusDays(1);
        }
        assertEquals(LoanAccount.Status.CLOSED, a.status());
        eq("1529", a.demands().get(0).interestDue(), "first demand interest");
        eq("0", gl.dr("1101"), "principal GL");
        eq("0", gl.dr("1102"), "interest receivable GL");
        eq(scheduledInterest.toPlainString(), gl.cr("4101"), "interest income = scheduled interest");
        eq("750", gl.cr("4102"), "fee income");
        eq("135", gl.cr("2201").add(gl.cr("2202")), "GST");
        eq("0", gl.dr("1109").add(gl.dr("5102")), "provision released on closure");
    }

    @Test
    void unpaid_loan_becomes_npa_moves_income_to_suspense_and_upgrades_after_full_payment() {
        Gl gl = new Gl();
        var book = LoanAccount.disburse(params(List.of()), LoanTerms.equated(bd("100000"), bd("18"), 12, OPEN), OPEN);
        gl.post(book.result().lots());
        LoanAccount a = book.account();
        LocalDate firstDue = LocalDate.of(2026, 7, 31);
        runEod(a, gl, OPEN, firstDue.plusDays(89));
        assertEquals(90, a.dpd());
        assertEquals(AssetClass.SMA2, a.assetClass());
        eq("0", gl.dr("2305"), "no suspense before NPA");
        runEod(a, gl, firstDue.plusDays(90), firstDue.plusDays(90));
        assertEquals(91, a.dpd());
        assertEquals(AssetClass.SUBSTANDARD, a.assetClass());
        assertTrue(a.suspense().signum() > 0);
        eq(a.suspense().toPlainString(), gl.cr("2305"), "suspense GL = unrealised interest and penal");
        assertTrue(gl.dr("1109").signum() < 0, "10% provision held on substandard");
        eq("10000.00", a.provisionHeld(), "substandard provision");

        // accrual while NPA goes to suspense, not income
        BigDecimal incomeBefore = gl.cr("4101");
        runEod(a, gl, firstDue.plusDays(91), firstDue.plusDays(95));
        eq(incomeBefore.toPlainString(), gl.cr("4101"), "no income accrued while NPA");

        // RBI (12-Nov-2021): upgrade only when the entire arrears of interest AND principal are paid
        LocalDate payDay = firstDue.plusDays(96);
        BigDecimal penalUnpaid = a.charges().stream().map(LoanAccount.ChargeRow::unpaid).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal part = a.overdueAmount(payDay).subtract(penalUnpaid).subtract(bd("1"));
        gl.post(a.pay(part, payDay, payDay, "Recovery").lots());
        assertEquals(AssetClass.SUBSTANDARD, a.assetClass(), "interest/principal still in arrears → stays NPA");
        gl.post(a.pay(a.overdueAmount(payDay), payDay, payDay, "Recovery").lots());
        assertEquals(AssetClass.STANDARD, a.assetClass(), "upgraded at zero arrears");
        eq("0", a.suspense(), "suspense cleared");
        eq("0", gl.dr("2305"), "suspense GL cleared");
        eq(a.principalOutstanding().toPlainString(), gl.dr("1101"), "principal GL = principal outstanding");
        eq(a.accruedNotDemanded().toPlainString(), gl.dr("1102"), "interest receivable GL = accrued not demanded");
    }

    @Test
    void part_prepayment_reduce_emi_and_reduce_tenure() {
        for (var mode : LoanAccount.PrepaymentMode.values()) {
            Gl gl = new Gl();
            var book = LoanAccount.disburse(params(List.of()), LoanTerms.equated(bd("100000"), bd("18"), 12, OPEN), OPEN);
            LoanAccount a = book.account();
            gl.post(book.result().lots());
            LocalDate d = OPEN;
            for (int i = 0; i < 3; i++) {
                var inst = book.schedule().get(i);
                runEod(a, gl, d, inst.dueDate());
                gl.post(a.pay(inst.instalment(), inst.dueDate().plusDays(1), inst.dueDate().plusDays(1), "EMI").lots());
                d = inst.dueDate().plusDays(1);
            }
            runEod(a, gl, d, LocalDate.of(2026, 10, 14));
            gl.post(a.prepay(bd("10000"), mode, LocalDate.of(2026, 10, 15)).lots());
            BigDecimal futurePrincipal = a.futureSchedule().stream().map(i -> i.principal()).reduce(BigDecimal.ZERO, BigDecimal::add);
            eq(a.principalOutstanding().toPlainString(), futurePrincipal, mode + ": future principal = outstanding");
            if (mode == LoanAccount.PrepaymentMode.REDUCE_EMI) {
                assertEquals(9, a.futureSchedule().size());
                assertTrue(a.futureSchedule().get(1).instalment().compareTo(bd("9168")) < 0, "lower EMI");
            } else {
                assertTrue(a.futureSchedule().size() < 9, "fewer instalments");
                eq("9168", a.futureSchedule().get(1).instalment(), "same EMI");
            }
            // next demand = interest carried on the old balance + interest on the new balance
            runEod(a, gl, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 31));
            var demand = a.demands().get(3);
            assertTrue(demand.interestDue().compareTo(bd("900")) > 0 && demand.interestDue().compareTo(bd("1172")) < 0,
                    "blended interest " + demand.interestDue());
            eq(a.accruedNotDemanded().add(a.demands().stream().map(LoanAccount.DemandRow::interestUnpaid)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)).toPlainString(), gl.dr("1102"), mode + ": receivable GL");
            assertThrows(IllegalStateException.class, () -> a.prepay(bd("5000"), mode, LocalDate.of(2026, 11, 1)), "dues first");
        }
    }

    @Test
    void preclosure_quote_equals_posting_and_closes_the_loan() {
        Gl gl = new Gl();
        var book = LoanAccount.disburse(params(List.of(PF, FORECLOSE)), LoanTerms.equated(bd("100000"), bd("18"), 12, OPEN), OPEN);
        LoanAccount a = book.account();
        gl.post(book.result().lots());
        LocalDate d = OPEN;
        for (int i = 0; i < 2; i++) {
            var inst = book.schedule().get(i);
            runEod(a, gl, d, inst.dueDate());
            gl.post(a.pay(inst.instalment(), inst.dueDate().plusDays(1), inst.dueDate().plusDays(1), "EMI").lots());
            d = inst.dueDate().plusDays(1);
        }
        LocalDate day = LocalDate.of(2026, 9, 15);
        runEod(a, gl, d, day.minusDays(1));
        var q = a.preclosureQuote(day);
        eq("84605", q.principal(), "principal after two EMIs");
        eq("1996.68", q.foreclosureFee().total(), "2% of 84,605 + 18% GST");
        assertThrows(IllegalArgumentException.class, () -> a.preclose(q.total().subtract(bd("1")), day));
        gl.post(a.preclose(q.total(), day).lots());
        assertEquals(LoanAccount.Status.CLOSED, a.status());
        eq("0", gl.dr("1101"), "principal GL");
        eq("0", gl.dr("1102"), "interest receivable GL");
        eq("0", gl.dr("1103"), "fee receivable GL");
        eq("1692.10", gl.cr("4104"), "the foreclosure charge is income of its own head");
        eq("750", gl.cr("4102"), "processing fee income holds the processing fee only");
    }

    @Test
    void foreclosure_charge_and_its_waiver_use_the_same_income_head() {
        var post = new LoanPostings(LoanPostings.GlMap.starter(), "HO", "10010000000017", OPEN);
        Gl gl = new Gl();
        gl.post(List.of(post.feeCharge(FORECLOSE.compute(bd("50000"), "32", "32", Rounding.PAISE_HALF_UP), OPEN, true)));
        eq("1000", gl.cr("4104"), "2% of 50,000");
        eq("0", gl.cr("4102"), "nothing to processing fee income");
        // waived with a credit note: taxable part off the same head, tax off the output tax
        gl.post(List.of(post.feeWaiver("C1", "Foreclosure charge", bd("590"), Gst.unbundle(bd("590"), bd("18"), "32", "32"), true)));
        eq("500", gl.cr("4104"), "taxable 500 waived");
        eq("90", gl.cr("2201").add(gl.cr("2202")), "GST left on the half not waived");
        // waived without a credit note (out of time): the whole amount off the same head
        gl.post(List.of(post.waiver(Appropriation.Component.FEE, bd("590"), false, true)));
        eq("-90", gl.cr("4104"), "income foregone, tax stays paid");
        eq("0", gl.cr("4102"), "processing fee income untouched");
        eq("0", gl.dr("1103"), "receivable cleared");

        // a map stored before the head existed (no foreclosureIncome) posts to the starter head
        var old = new LoanPostings.GlMap("1101", "1102", "1103", "1104", "4101", "4102", "4103", "2201", "2202", "2203",
                "1202", "1203", "2302", "2305", "5102", "1109", "5103", null);
        assertEquals("4104", old.foreclosureIncome());
        // other fees are not affected by the flag's default
        Gl other = new Gl();
        other.post(List.of(post.feeCharge(PF.compute(bd("100000"), "32", "32", Rounding.PAISE_HALF_UP), OPEN)));
        eq("750", other.cr("4102"), "processing fee");
        eq("0", other.cr("4104"), "not a foreclosure charge");
    }

    @Test
    void cooling_off_cancellation_and_frozen_accounts() {
        Gl gl = new Gl();
        var book = LoanAccount.disburse(params(List.of(PF)), LoanTerms.equated(bd("100000"), bd("18"), 12, OPEN), OPEN);
        LoanAccount a = book.account();
        gl.post(book.result().lots());
        runEod(a, gl, OPEN, OPEN.plusDays(1));
        eq("100099", a.cancellationAmount(), "principal + 2 days' interest (49.32 × 2 = 98.64 → 99)");
        a.freeze();
        assertThrows(IllegalStateException.class, () -> a.cancel(bd("100099"), OPEN.plusDays(2)));
        a.unfreeze();
        gl.post(a.cancel(bd("100099"), OPEN.plusDays(2)).lots());
        assertEquals(LoanAccount.Status.CANCELLED, a.status());
        eq("0", gl.dr("1101"), "principal GL");
        eq("750", gl.cr("4102"), "disclosed processing fee retained");

        var late = LoanAccount.disburse(params(List.of()), LoanTerms.equated(bd("100000"), bd("18"), 12, OPEN), OPEN).account();
        assertThrows(IllegalStateException.class, () -> late.cancel(late.cancellationAmount(), OPEN.plusDays(4)));
    }

    @Test
    void snapshot_restore_reverses_a_transaction_exactly() {
        var book = LoanAccount.disburse(params(List.of()), LoanTerms.equated(bd("100000"), bd("18"), 12, OPEN), OPEN);
        LoanAccount a = book.account();
        Gl gl = new Gl();
        runEod(a, gl, OPEN, LocalDate.of(2026, 7, 31));
        var before = a.snapshot();
        a.pay(bd("9168"), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), "EMI");
        eq("92361", a.principalOutstanding(), "after EMI");
        a.restore(before);
        eq("100000", a.principalOutstanding(), "after reversal");
        assertEquals(before, a.snapshot());
    }

    @Test
    void penal_charges_accrue_on_overdue_principal_and_interest_only() {
        var book = LoanAccount.disburse(params(List.of()), LoanTerms.equated(bd("100000"), bd("18"), 12, OPEN), OPEN);
        LoanAccount a = book.account();
        Gl gl = new Gl();
        runEod(a, gl, OPEN, LocalDate.of(2026, 8, 10));        // 10 days past the first due date
        BigDecimal penal = a.charges().stream().filter(c -> c.kind() == Appropriation.Component.PENAL)
                .map(LoanAccount.ChargeRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        eq("60.30", penal, "10 days × 9,168 × 24% / 365 = 6.03 per day");
        eq("60.30", gl.cr("4103"), "penal income, no GST");
        var waive = a.waiveCharge(a.charges().get(0).id(), bd("60.30"), LocalDate.of(2026, 8, 11));
        gl.post(waive.lots());
        eq("0", gl.dr("1104"), "penal receivable cleared by waiver");
    }
}
