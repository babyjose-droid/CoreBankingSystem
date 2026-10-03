package com.corebanking.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Day-count conventions of the reference system and the rate conversions behind its rate bases (US-039). */
class RateBasisTest {

    static BigDecimal bd(String s) { return new BigDecimal(s); }
    static void eq(String e, BigDecimal a, String what) { assertEquals(0, bd(e).compareTo(a), what + ": expected " + e + " but was " + a); }

    @Test
    void all_nine_day_count_conventions() {
        LocalDate a = LocalDate.of(2026, 1, 31);
        LocalDate b = LocalDate.of(2026, 3, 31);      // 59 actual days
        for (DayCount d : DayCount.values()) {
            BigDecimal interest = bd("100000").multiply(bd("0.12")).multiply(d.yearFraction(a, b)).setScale(2, RoundingMode.HALF_UP);
            String expected = switch (d) {
                case ACTUAL_365, ACTUAL_ACTUAL -> "1939.73";     // 59/365
                case ACTUAL_360 -> "1966.67";                    // 59/360
                case THIRTY_360, THIRTY_E_360 -> "2000.00";      // 60/360
                case ACTUAL_366 -> "1934.43";                    // 59/366
                case ACTUAL_364 -> "1945.05";                    // 59/364
                case ACTUAL_336 -> "2107.14";                    // 59/336
                case ACTUAL_372 -> "1903.23";                    // 59/372
            };
            eq(expected, interest, d.name());
        }
        // the two 30/360 bases differ when the end date is a 31st and the start is not the 30th or 31st
        LocalDate c = LocalDate.of(2026, 5, 15);
        LocalDate e = LocalDate.of(2026, 7, 31);
        assertEquals(76, DayCount.THIRTY_360.days(c, e), "US: the 31st stays");
        assertEquals(75, DayCount.THIRTY_E_360.days(c, e), "European: the 31st counts as the 30th");
        assertEquals(77, DayCount.ACTUAL_365.days(c, e));
        // Actual/Actual across a leap year boundary: 2027 is not a leap year, 2028 is
        eq("1.001377", DayCount.ACTUAL_ACTUAL.yearFraction(LocalDate.of(2027, 7, 1), LocalDate.of(2028, 7, 1)).setScale(6, RoundingMode.HALF_UP),
                "184 days of 2027 / 365 + 182 days of 2028 / 366");
        assertEquals(0, DayCount.ACTUAL_ACTUAL.yearBasis());
        assertEquals(364, DayCount.ACTUAL_364.yearBasis());
        // a week is exactly 1/52 of a year on Actual/364
        eq("0.0192308", DayCount.ACTUAL_364.yearFraction(a, a.plusDays(7)).setScale(7, RoundingMode.HALF_UP), "7/364 = 1/52");
    }

    @Test
    void flat_rate_and_given_instalment_convert_to_a_reducing_rate() {
        eq("7200", RateSolver.flatInterest(bd("60000"), bd("12"), 12, 12), "60000 × 12% × 1 year");
        eq("21.457184", RateSolver.flatToEffective(bd("60000"), bd("12"), 12, 12), "12% flat for a year is 21.46% reducing");
        eq("18.000015", RateSolver.fromInstalment(bd("100000"), bd("9168"), 12, 12), "the golden loan's EMI gives back 18%");
        // the rate found prices the instalments back to the principal
        BigDecimal r = RateSolver.periodicRate(bd("100000"), bd("9168"), 12);
        double pv = 0;
        for (int k = 1; k <= 12; k++) pv += 9168 / Math.pow(1 + r.doubleValue(), k);
        assertTrue(Math.abs(pv - 100000) < 0.01, "present value " + pv);
        eq("0", RateSolver.periodicRate(bd("12000"), bd("1000"), 12), "instalments that only return the principal: 0%");
        assertThrows(IllegalArgumentException.class, () -> RateSolver.periodicRate(bd("12000"), bd("999"), 12));
        // a very high rate still solves (the reference product charges 638.75% a year)
        BigDecimal high = RateSolver.fromInstalment(bd("5000"), bd("7713"), 1, 12);
        eq("651.120000", high, "one instalment of 7713 on 5000 in a month: 54.26% a month");
        // weekly: 10000 at 20% flat for 26 weeks
        BigDecimal weekly = RateSolver.flatToEffective(bd("10000"), bd("20"), 26, 52);
        assertTrue(weekly.compareTo(bd("37")) > 0 && weekly.compareTo(bd("38")) < 0, "about 37.5%: " + weekly);
    }

    @Test
    void simple_rate_from_a_future_value() {
        LocalDate d = LocalDate.of(2026, 7, 1);
        eq("121.666667", RateSolver.simpleAnnualFromFutureValue(bd("5000"), bd("5500"), d, d.plusDays(30), DayCount.ACTUAL_365), "10% in 30 days");
        eq("10.000000", RateSolver.simpleAnnualFromFutureValue(bd("100000"), bd("110000"), d, d.plusYears(1), DayCount.ACTUAL_365), "10% in a year");
        eq("0.000000", RateSolver.simpleAnnualFromFutureValue(bd("100000"), bd("100000"), d, d.plusYears(1), DayCount.ACTUAL_365), "no growth");
        assertThrows(IllegalArgumentException.class, () -> RateSolver.simpleAnnualFromFutureValue(bd("5000"), bd("5500"), d, d, DayCount.ACTUAL_365));
    }
}
