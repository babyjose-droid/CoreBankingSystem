package com.corebanking.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * APR / IRR for the Key Fact Statement. Two bases:
 * <ul>
 *   <li>{@link #nominalAnnualIrr}: periodic IRR on equally spaced flows × periods per year
 *       ("IRR basic"). Reproduces the reference system's 18.58% on the golden loan when the
 *       processing fee is taken <b>excluding</b> GST.</li>
 *   <li>{@link #xirr}: effective annual rate on dated flows (Actual/365).</li>
 * </ul>
 * Solved by bisection: slow-ish but monotone and never diverges.
 */
public final class AprCalculator {
    private static final MathContext MC = MathContext.DECIMAL64;

    private AprCalculator() {}

    public record DatedFlow(LocalDate date, BigDecimal amount) {}

    /** @param flows flows[0] is the net disbursal (negative), then one flow per period. Result in % p.a., 2 dp. */
    public static BigDecimal nominalAnnualIrr(List<BigDecimal> flows, int periodsPerYear) {
        double lo = 0, hi = 1;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (npv(flows, mid) > 0) lo = mid; else hi = mid;
        }
        return BigDecimal.valueOf(lo * periodsPerYear * 100).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal xirr(List<DatedFlow> flows) {
        LocalDate t0 = flows.get(0).date();
        double lo = -0.99, hi = 10;
        for (int i = 0; i < 300; i++) {
            double mid = (lo + hi) / 2;
            double v = 0;
            for (DatedFlow f : flows) {
                v += f.amount().doubleValue() / Math.pow(1 + mid, ChronoUnit.DAYS.between(t0, f.date()) / 365.0);
            }
            if (v > 0) lo = mid; else hi = mid;
        }
        return BigDecimal.valueOf(lo * 100).round(MC).setScale(2, RoundingMode.HALF_UP);
    }

    private static double npv(List<BigDecimal> flows, double r) {
        double v = 0;
        for (int i = 0; i < flows.size(); i++) v += flows.get(i).doubleValue() / Math.pow(1 + r, i);
        return v;
    }
}
