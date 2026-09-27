package com.corebanking.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden values verified against the reference sandbox walkthrough (Sep 2026) and by independent
 * calculation. A failing golden test blocks merge. See docs/golden-values.md.
 */
class GoldenCalcTest {

    static BigDecimal bd(String s) { return new BigDecimal(s); }
    static void eq(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    static final LocalDate OPEN = LocalDate.of(2026, 6, 30);

    // ---- EMI ---------------------------------------------------------------------------------
    @Test void emi_100000_at_18_for_12() {
        eq("9168", EmiCalculator.pmt(bd("100000"), bd("18"), 12, Rounding.RUPEE_HALF_UP));
    }

    @Test void emi_after_10000_prepayment() {
        eq("8251", EmiCalculator.pmt(bd("90000"), bd("18"), 12, Rounding.RUPEE_HALF_UP));
    }

    @Test void emi_zero_rate_is_straight_line() {
        eq("8333", EmiCalculator.pmt(bd("100000"), BigDecimal.ZERO, 12, Rounding.RUPEE_HALF_UP));
    }

    @Test void emi_rejects_bad_input() {
        assertThrows(IllegalArgumentException.class, () -> EmiCalculator.pmt(bd("100000"), bd("18"), 0, Rounding.RUPEE_HALF_UP));
        assertThrows(IllegalArgumentException.class, () -> EmiCalculator.pmt(bd("0"), bd("18"), 12, Rounding.RUPEE_HALF_UP));
    }

    // ---- Due dates ---------------------------------------------------------------------------
    @Test void month_end_open_date_anchors_to_month_end() {
        LocalDate[] expected = {
            LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30),
            LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31),
            LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31),
            LocalDate.of(2027, 4, 30), LocalDate.of(2027, 5, 31), LocalDate.of(2027, 6, 30)};
        for (int i = 0; i < 12; i++) assertEquals(expected[i], ScheduleGenerator.dueDate(OPEN, i + 1));
    }

    @Test void mid_month_dates_are_derived_not_chained() {
        LocalDate open = LocalDate.of(2026, 1, 30);
        assertEquals(LocalDate.of(2026, 2, 28), ScheduleGenerator.dueDate(open, 1));
        assertEquals(LocalDate.of(2026, 3, 30), ScheduleGenerator.dueDate(open, 2));
    }

    // ---- Equated schedule --------------------------------------------------------------------
    @Test void schedule_matches_reference_rows_1_to_7() {
        var rows = ScheduleGenerator.equated(bd("100000"), bd("18"), 12, OPEN, DayCount.ACTUAL_365, Rounding.RUPEE_HALF_UP);
        String[] interest = {"1529", "1412", "1252", "1172", "1016", "926", "800"};
        String[] principal = {"7639", "7756", "7916", "7996", "8152", "8242", "8368"};
        String[] closing = {"92361", "84605", "76689", "68693", "60541", "52299", "43931"};
        for (int i = 0; i < 7; i++) {
            eq(interest[i], rows.get(i).interest());
            eq(principal[i], rows.get(i).principal());
            eq(closing[i], rows.get(i).closingBalance());
        }
    }

    /**
     * OPEN ITEM OI-01: the reference shows 606 (Feb) and 395 (Apr) where HALF_UP gives 607 and 396.
     * Until the rule is confirmed we pin our own behaviour so any change is deliberate.
     */
    @Test void schedule_rows_8_and_10_follow_half_up_pending_OI_01() {
        var rows = ScheduleGenerator.equated(bd("100000"), bd("18"), 12, OPEN, DayCount.ACTUAL_365, Rounding.RUPEE_HALF_UP);
        eq("607", rows.get(7).interest());
        assertEquals(28, rows.get(7).days());
    }

    @Test void schedule_invariants() {
        var rows = ScheduleGenerator.equated(bd("100000"), bd("18"), 12, OPEN, DayCount.ACTUAL_365, Rounding.RUPEE_HALF_UP);
        assertEquals(12, rows.size());
        BigDecimal principalSum = rows.stream().map(ScheduleGenerator.Instalment::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        eq("100000", principalSum);
        eq("0", rows.get(11).closingBalance());
        for (int i = 0; i < 11; i++) eq("9168", rows.get(i).instalment());
        for (var r : rows) eq(r.instalment().toPlainString(), r.interest().add(r.principal()));
    }

    // ---- Bullet ------------------------------------------------------------------------------
    @Test void bullet_with_extra_first_day() {
        // ₹5,000 at 638.75% for a 30-day loan, extra day on disbursement → 31 days → ₹2,712.53 → ₹2,713
        LocalDate d = LocalDate.of(2026, 6, 30);
        eq("2713", ScheduleGenerator.bulletTotalInterest(bd("5000"), bd("638.75"), d, d.plusDays(30),
                DayCount.ACTUAL_365, true, Rounding.RUPEE_HALF_UP));
        eq("2625", ScheduleGenerator.bulletTotalInterest(bd("5000"), bd("638.75"), d, d.plusDays(30),
                DayCount.ACTUAL_365, false, Rounding.RUPEE_HALF_UP));
    }

    // ---- Fees --------------------------------------------------------------------------------
    @Test void fee_plus_gst() {
        var f = FeeCalculator.flat(bd("750"), bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, Rounding.RUPEE_HALF_UP);
        eq("750", f.fee()); eq("135", f.tax()); eq("885", f.total());
        var g = FeeCalculator.flat(bd("300"), bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, Rounding.RUPEE_HALF_UP);
        eq("354", g.total());
    }

    @Test void fee_inclusive_and_caps() {
        var f = FeeCalculator.flat(bd("354"), bd("18"), FeeCalculator.TaxTreatment.INCLUSIVE, Rounding.RUPEE_HALF_UP);
        eq("300", f.fee()); eq("54", f.tax());
        var capped = FeeCalculator.percentage(bd("1000000"), bd("2"), bd("500"), bd("10000"), bd("18"),
                FeeCalculator.TaxTreatment.EXCLUSIVE, Rounding.RUPEE_HALF_UP);
        eq("10000", capped.fee());
        var floored = FeeCalculator.percentage(bd("10000"), bd("1"), bd("500"), null, bd("18"),
                FeeCalculator.TaxTreatment.EXCLUSIVE, Rounding.RUPEE_HALF_UP);
        eq("500", floored.fee());
    }

    // ---- APR ---------------------------------------------------------------------------------
    @Test void apr_irr_basic_matches_reference_18_58() {
        // Resolves former open item: reference APR = IRR on flat-EMI flows, fee taken EXCLUDING GST.
        List<BigDecimal> flows = new ArrayList<>();
        flows.add(bd("-99700"));          // 100000 − ₹300 fee (ex-GST)
        for (int i = 0; i < 12; i++) flows.add(bd("9168"));
        eq("18.58", AprCalculator.nominalAnnualIrr(flows, 12));
    }

    @Test void apr_with_gst_inclusive_fee_is_higher() {
        List<BigDecimal> flows = new ArrayList<>();
        flows.add(bd("-99646"));
        for (int i = 0; i < 12; i++) flows.add(bd("9168"));
        eq("18.68", AprCalculator.nominalAnnualIrr(flows, 12));
    }

    @Test void xirr_simple_one_year() {
        var flows = List.of(new AprCalculator.DatedFlow(LocalDate.of(2026, 1, 1), bd("-100000")),
                new AprCalculator.DatedFlow(LocalDate.of(2027, 1, 1), bd("110000")));
        eq("10.00", AprCalculator.xirr(flows));
    }

    // ---- Deposits ----------------------------------------------------------------------------
    @Test void fd_quarterly_compounding() {
        eq("116022.17", DepositCalculator.maturityCumulative(bd("100000"), bd("7.5"), 24, 4, Rounding.PAISE_HALF_UP));
    }

    @Test void fd_rate_is_base_plus_slab() {
        eq("14.5", DepositCalculator.effectiveRate(bd("7"), bd("7.5")));
    }

    // ---- Day count ---------------------------------------------------------------------------
    @Test void day_counts() {
        LocalDate a = LocalDate.of(2027, 12, 1), b = LocalDate.of(2028, 3, 1);
        assertEquals(91, DayCount.ACTUAL_365.days(a, b));
        assertEquals(90, DayCount.THIRTY_360.days(a, b));
        // 31/365 + 60/366
        BigDecimal aa = DayCount.ACTUAL_ACTUAL.yearFraction(a, b);
        BigDecimal expected = bd("31").divide(bd("365"), DayCount.MC).add(bd("60").divide(bd("366"), DayCount.MC));
        assertTrue(aa.subtract(expected).abs().compareTo(bd("1E-20")) < 0);
    }

    // ---- Preclosure --------------------------------------------------------------------------
    @Test void preclosure_quote_never_nan() {
        var q = PreclosureCalculator.quote(bd("43931"), BigDecimal.ZERO, bd("18"),
                LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 10), DayCount.ACTUAL_365,
                bd("2"), bd("18"), Rounding.RUPEE_HALF_UP);
        eq("217", q.brokenPeriodInterest());   // 43931 × 18% × 10/365 = 216.65
        eq("879", q.charge());                 // 2% of 43931 = 878.62
        eq("158", q.chargeTax());
        eq("45185", q.total());
        var zero = PreclosureCalculator.quote(BigDecimal.ZERO, BigDecimal.ZERO, bd("18"),
                LocalDate.of(2027, 1, 31), LocalDate.of(2027, 1, 31), DayCount.ACTUAL_365,
                BigDecimal.ZERO, bd("18"), Rounding.RUPEE_HALF_UP);
        eq("0", zero.total());
        assertThrows(IllegalArgumentException.class, () -> PreclosureCalculator.quote(bd("1"), BigDecimal.ZERO, bd("18"),
                LocalDate.of(2027, 2, 1), LocalDate.of(2027, 1, 1), DayCount.ACTUAL_365, BigDecimal.ZERO, bd("18"), Rounding.RUPEE_HALF_UP));
    }
}
