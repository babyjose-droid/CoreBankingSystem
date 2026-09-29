package com.corebanking.lending.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.FeeCalculator;
import com.corebanking.calc.Rounding;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.engine.Appropriation.Component;
import com.corebanking.lending.engine.Delinquency.AssetClass;
import com.corebanking.ledger.PostingLine;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class LendingEngineTest {

    static BigDecimal bd(String s) { return new BigDecimal(s); }
    static void eq(String e, BigDecimal a) { assertEquals(0, bd(e).compareTo(a), "expected " + e + " but was " + a); }
    static final LocalDate OPEN = LocalDate.of(2026, 6, 30);
    static BigDecimal sum(List<Instalment> rows) {
        return rows.stream().map(Instalment::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---- schedules ---------------------------------------------------------------------------
    @Test void equated_matches_phase1_golden_schedule() {
        var rows = ScheduleBuilder.build(LoanTerms.equated(bd("100000"), bd("18"), 12, OPEN));
        eq("9168", rows.get(0).instalment());
        eq("1529", rows.get(0).interest());
        eq("100000", sum(rows));
        eq("0", rows.get(11).closingBalance());
    }

    @Test void moratorium_rows_are_interest_only_then_equated() {
        var t = new LoanTerms(bd("100000"), bd("18"), 12, OPEN, null, RepaymentMethod.EQUATED, 3, BigDecimal.ZERO,
                DayCount.ACTUAL_365, Rounding.RUPEE_HALF_UP, false);
        var rows = ScheduleBuilder.build(t);
        for (int i = 0; i < 3; i++) eq("0", rows.get(i).principal());
        eq("100000", rows.get(3).openingBalance());
        eq(EmiStr(t), rows.get(4).instalment());
        eq("100000", sum(rows));
    }

    static String EmiStr(LoanTerms t) { return ScheduleBuilder.emi(t).toPlainString(); }

    @Test void balloon_leaves_the_residual_for_the_last_instalment() {
        var t = new LoanTerms(bd("100000"), bd("12"), 12, OPEN, null, RepaymentMethod.EQUATED, 0, bd("40000"),
                DayCount.ACTUAL_365, Rounding.RUPEE_HALF_UP, false);
        var rows = ScheduleBuilder.build(t);
        eq("5731", ScheduleBuilder.emi(t));                                  // vs 8,885 without the balloon
        assertTrue(rows.get(11).principal().compareTo(bd("40000")) > 0, "last instalment carries the balloon");
        eq("100000", sum(rows));
    }

    @Test void fixed_principal_and_bullets() {
        var fp = ScheduleBuilder.build(new LoanTerms(bd("120000"), bd("12"), 12, OPEN, null, RepaymentMethod.FIXED_PRINCIPAL, 0,
                null, null, null, false));
        eq("10000", fp.get(0).principal());
        assertTrue(fp.get(0).interest().compareTo(fp.get(11).interest()) > 0, "interest falls as principal falls");
        eq("120000", sum(fp));

        var bullet = ScheduleBuilder.build(new LoanTerms(bd("5000"), bd("638.75"), 1, OPEN, OPEN.plusDays(30),
                RepaymentMethod.BULLET_TOTAL_INTEREST, 0, null, null, null, true));
        assertEquals(1, bullet.size());
        eq("2713", bullet.get(0).interest());                   // Phase 1 golden value
        eq("7713", bullet.get(0).instalment());

        var periodic = ScheduleBuilder.build(new LoanTerms(bd("100000"), bd("12"), 6, OPEN, null,
                RepaymentMethod.BULLET_PERIODIC_INTEREST, 0, null, null, null, false));
        eq("0", periodic.get(4).principal());
        eq("100000", periodic.get(5).principal());
    }

    @Test void first_due_date_override_anchors_later_dates() {
        var t = new LoanTerms(bd("50000"), bd("24"), 3, LocalDate.of(2026, 6, 20), LocalDate.of(2026, 8, 5),
                RepaymentMethod.EQUATED, 0, null, null, null, false);
        var rows = ScheduleBuilder.build(t);
        assertEquals(LocalDate.of(2026, 8, 5), rows.get(0).dueDate());
        assertEquals(LocalDate.of(2026, 9, 5), rows.get(1).dueDate());
        assertEquals(46, rows.get(0).days());                   // broken period interest sits in the first instalment
        eq("50000", sum(rows));
    }

    @Test void invalid_terms_are_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new LoanTerms(bd("100"), bd("10"), 6, OPEN, null,
                RepaymentMethod.FIXED_PRINCIPAL, 2, null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new LoanTerms(bd("100"), bd("10"), 6, OPEN, OPEN,
                RepaymentMethod.EQUATED, 0, null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> LoanTerms.equated(bd("0"), bd("10"), 6, OPEN));
    }

    // ---- appropriation -----------------------------------------------------------------------
    static final List<Appropriation.Due> DUES = List.of(
            new Appropriation.Due("D1", LocalDate.of(2026, 7, 31), Component.INTEREST, bd("1529")),
            new Appropriation.Due("D1", LocalDate.of(2026, 7, 31), Component.PRINCIPAL, bd("7639")),
            new Appropriation.Due("D1", LocalDate.of(2026, 7, 31), Component.PENAL, bd("50")),
            new Appropriation.Due("D2", LocalDate.of(2026, 8, 31), Component.INTEREST, bd("1412")),
            new Appropriation.Due("D2", LocalDate.of(2026, 8, 31), Component.PRINCIPAL, bd("7756")),
            new Appropriation.Due("C1", LocalDate.of(2026, 8, 10), Component.FEE, bd("590")));

    @Test void by_demand_settles_oldest_demand_first() {
        var r = Appropriation.allocate(DUES, bd("10000"), Appropriation.DEFAULT_SEQUENCE, Appropriation.Mode.BY_DEMAND);
        eq("1529", r.allocations().get(0).amount());
        eq("7639", r.allocations().get(1).amount());
        eq("50", r.allocations().get(2).amount());
        eq("590", r.allocations().get(3).amount());          // charge C1 dated 10-Aug before D2
        eq("192", r.allocations().get(4).amount());          // rest to D2 interest
        eq("0", r.excess());
    }

    @Test void by_component_clears_interest_everywhere_first_and_keeps_excess() {
        var r = Appropriation.allocate(DUES, bd("20000"), Appropriation.DEFAULT_SEQUENCE, Appropriation.Mode.BY_COMPONENT);
        eq("2941", r.total(Component.INTEREST));
        eq("15395", r.total(Component.PRINCIPAL));
        eq("50", r.total(Component.PENAL));
        eq("590", r.total(Component.FEE));
        eq("1024", r.excess());
        assertThrows(IllegalArgumentException.class, () -> Appropriation.allocate(DUES, bd("1"),
                List.of(Component.FEE, Component.INTEREST), Appropriation.Mode.BY_DEMAND));
    }

    // ---- DPD / NPA ---------------------------------------------------------------------------
    @Test void dpd_and_sma_follow_the_rbi_example() {
        LocalDate due = LocalDate.of(2021, 3, 31);
        assertEquals(1, Delinquency.dpd(due, due));
        assertEquals(AssetClass.SMA0, Delinquency.classify(due, 1, AssetClass.STANDARD, null, true).assetClass());
        LocalDate d30 = LocalDate.of(2021, 4, 30);
        assertEquals(AssetClass.SMA1, Delinquency.classify(d30, Delinquency.dpd(d30, due), AssetClass.SMA0, null, true).assetClass());
        LocalDate d60 = LocalDate.of(2021, 5, 30);
        assertEquals(AssetClass.SMA2, Delinquency.classify(d60, Delinquency.dpd(d60, due), AssetClass.SMA1, null, true).assetClass());
        LocalDate d90 = LocalDate.of(2021, 6, 29);
        var npa = Delinquency.classify(d90, Delinquency.dpd(d90, due), AssetClass.SMA2, null, true);
        assertEquals(AssetClass.SUBSTANDARD, npa.assetClass());
        assertEquals(d90, npa.npaSince());
        assertEquals(0, Delinquency.dpd(due, null));
        assertEquals(0, Delinquency.dpd(due.minusDays(1), due));
    }

    @Test void npa_upgrades_only_when_all_arrears_are_paid_and_ages_into_doubtful() {
        LocalDate npaSince = LocalDate.of(2025, 1, 10);
        // paid most arrears, DPD now 20, but arrears remain → still NPA
        var still = Delinquency.classify(LocalDate.of(2025, 3, 1), 20, AssetClass.SUBSTANDARD, npaSince, true);
        assertEquals(AssetClass.SUBSTANDARD, still.assetClass());
        var upgraded = Delinquency.classify(LocalDate.of(2025, 3, 2), 0, AssetClass.SUBSTANDARD, npaSince, false);
        assertEquals(AssetClass.STANDARD, upgraded.assetClass());
        assertEquals(AssetClass.DOUBTFUL1, Delinquency.classify(LocalDate.of(2026, 1, 10), 400, AssetClass.SUBSTANDARD, npaSince, true).assetClass());
        assertEquals(AssetClass.DOUBTFUL2, Delinquency.classify(LocalDate.of(2027, 1, 10), 800, AssetClass.DOUBTFUL1, npaSince, true).assetClass());
        assertEquals(AssetClass.DOUBTFUL3, Delinquency.classify(LocalDate.of(2029, 1, 10), 1500, AssetClass.DOUBTFUL2, npaSince, true).assetClass());
        assertEquals(AssetClass.LOSS, Delinquency.classify(LocalDate.of(2029, 1, 10), 0, AssetClass.LOSS, npaSince, false).assetClass());
        assertEquals(AssetClass.SUBSTANDARD, Delinquency.worst(List.of(AssetClass.STANDARD, AssetClass.SUBSTANDARD, AssetClass.SMA2)));
    }

    // ---- daily charges ---------------------------------------------------------------------------
    @Test void daily_interest_and_penal() {
        eq("49.32", DailyCharges.interestForDay(bd("100000"), bd("18"), OPEN, DayCount.ACTUAL_365));
        eq("0.00", DailyCharges.interestForDay(BigDecimal.ZERO, bd("18"), OPEN, DayCount.ACTUAL_365));
        eq("5.02", DailyCharges.penalForDay(bd("9168"), bd("20"), OPEN, DayCount.ACTUAL_365));
        eq("-0.23", DailyCharges.trueUp(bd("1529"), bd("1529.23")));
    }

    // ---- fees and GST ------------------------------------------------------------------------
    @Test void processing_fee_with_intra_and_inter_state_gst() {
        var rule = new FeeRule("PF", "Processing fee", FeeRule.Event.DISBURSEMENT, FeeRule.CalcType.PERCENT, null, bd("0.75"),
                null, bd("500"), bd("10000"), bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, true);
        var intra = rule.compute(bd("100000"), "32", "32", Rounding.PAISE_HALF_UP);
        eq("750", intra.fee());
        eq("67.50", intra.gst().cgst());
        eq("67.50", intra.gst().sgst());
        eq("885", intra.total());
        var inter = rule.compute(bd("100000"), "32", "27", Rounding.PAISE_HALF_UP);
        eq("135", inter.gst().igst());
        eq("0", inter.gst().cgst());
        eq("500", rule.compute(bd("10000"), "32", "32", Rounding.PAISE_HALF_UP).fee());      // minimum applies
        var odd = Gst.split(bd("0.03"), "32", "32");
        eq("0.03", odd.total());
    }

    @Test void slab_fee() {
        var rule = new FeeRule("BNC", "Bounce charge", FeeRule.Event.BOUNCE, FeeRule.CalcType.SLAB, null, null,
                List.of(new FeeRule.Slab(bd("0"), bd("10000"), bd("300")), new FeeRule.Slab(bd("10000.01"), bd("99999999"), bd("500"))),
                null, null, bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, false);
        eq("300", rule.compute(bd("9168"), "32", "32", Rounding.PAISE_HALF_UP).fee());
        eq("590", rule.compute(bd("25000"), "32", "32", Rounding.PAISE_HALF_UP).total());
    }

    // ---- provisioning and cancellation -----------------------------------------------------
    @Test void provisioning_by_class_and_security() {
        var r = Provisioning.starter();
        eq("400.00", Provisioning.required(bd("100000"), BigDecimal.ZERO, AssetClass.SMA2, r));
        eq("10000.00", Provisioning.required(bd("100000"), BigDecimal.ZERO, AssetClass.SUBSTANDARD, r));
        eq("100000.00", Provisioning.required(bd("100000"), BigDecimal.ZERO, AssetClass.DOUBTFUL1, r));   // unsecured doubtful
        eq("36000.00", Provisioning.required(bd("100000"), bd("80000"), AssetClass.DOUBTFUL1, r));      // 20% × 80k + 100% × 20k
    }

    @Test void cooling_off_cancellation() {
        LocalDate d = LocalDate.of(2026, 6, 30);
        assertTrue(Cancellation.withinCoolingOff(d, 3, d.plusDays(3)));
        assertFalse(Cancellation.withinCoolingOff(d, 3, d.plusDays(4)));
        var q = Cancellation.quote(bd("100000"), bd("18.58"), d, d.plusDays(2), DayCount.ACTUAL_365);
        assertEquals(2, q.daysUsed());
        eq("102", q.interestForDaysUsed());
        eq("100102", q.total());
    }

    // ---- postings ------------------------------------------------------------------------
    static void assertBalancedPerBranch(TransactionLot lot) {
        Map<String, BigDecimal> net = new TreeMap<>();
        for (PostingLine l : lot.lines()) net.merge(l.branch(), l.signed(), BigDecimal::add);
        net.values().forEach(v -> assertEquals(0, v.signum()));
    }

    static final LoanPostings P = new LoanPostings(LoanPostings.GlMap.starter(), "HO", "10010000000017", OPEN);

    @Test void disbursement_books_gross_principal_net_payout_fee_and_gst() {
        var pf = new FeeRule("PF", "Processing fee", FeeRule.Event.DISBURSEMENT, FeeRule.CalcType.FIXED, bd("750"), null,
                null, null, null, bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, true).compute(bd("100000"), "32", "32", Rounding.PAISE_HALF_UP);
        var lot = P.disbursement(bd("100000"), List.of(pf), OPEN);
        assertBalancedPerBranch(lot);
        var bank = lot.lines().stream().filter(l -> l.glCode().equals("1202")).findFirst().orElseThrow();
        eq("99115", bank.amount());
        assertEquals(5, lot.lines().size());
    }

    @Test void repayment_npa_realises_interest_from_suspense() {
        var split = Appropriation.allocate(DUES, bd("10000"), Appropriation.DEFAULT_SEQUENCE, Appropriation.Mode.BY_DEMAND);
        var normal = P.repayment(bd("10000"), split, false, OPEN, "EMI");
        assertBalancedPerBranch(normal);
        var npa = P.repayment(bd("10000"), split, true, OPEN, "EMI");
        assertBalancedPerBranch(npa);
        assertTrue(npa.lines().stream().anyMatch(l -> l.glCode().equals("2305") && l.side() == PostingLine.Side.DR));
    }

    @Test void accrual_penal_waiver_provision_lots_balance() {
        assertBalancedPerBranch(P.accrual(bd("49.32"), false, OPEN));
        assertBalancedPerBranch(P.accrual(bd("-0.23"), false, OPEN));
        assertTrue(P.accrual(bd("49.32"), true, OPEN).lines().stream().anyMatch(l -> l.glCode().equals("2305")));
        assertBalancedPerBranch(P.penal(bd("5.02"), false, OPEN));
        assertBalancedPerBranch(P.npaIncomeReversal(bd("1529"), bd("50")));
        assertBalancedPerBranch(P.waiver(Component.PENAL, bd("50"), false));
        assertThrows(IllegalArgumentException.class, () -> P.waiver(Component.PRINCIPAL, bd("1"), false));
        assertBalancedPerBranch(P.provision(bd("400")));
        assertBalancedPerBranch(P.provision(bd("-150")));
        assertBalancedPerBranch(P.principalPrepayment(bd("10000"), OPEN));
        assertBalancedPerBranch(P.excessRefund(bd("1024")));
    }
}
