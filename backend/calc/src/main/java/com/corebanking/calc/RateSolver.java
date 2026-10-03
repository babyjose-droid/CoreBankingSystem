package com.corebanking.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Converts between the ways a rate can be quoted (the reference system's "rate bases") and the rate the engine
 * accrues at, which is always a nominal annual rate on the reducing balance.
 *
 * <ul>
 *   <li><b>Flat rate</b> ("Simple Interest Rate Flat"): interest = principal × flat rate × tenor, spread equally over
 *       the instalments. {@link #flatToEffective} gives the reducing-balance rate with the same instalments; this is
 *       the rate disclosed in the KFS and used for accrual.</li>
 *   <li><b>Tenure, amount and instalment</b>: the rate is whatever makes the given instalment repay the amount
 *       ({@link #fromInstalment}).</li>
 *   <li><b>Simple annual rate from future value</b>: the rate that grows the amount to the agreed maturity value by
 *       simple interest ({@link #simpleAnnualFromFutureValue}).</li>
 * </ul>
 * Rates are returned as % per annum with 6 decimal places (the reference system's rate precision).
 */
public final class RateSolver {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    public static final int RATE_SCALE = 6;

    private RateSolver() {}

    /** Total interest of a flat-rate loan: principal × flat % × periods / periods per year (unrounded). */
    public static BigDecimal flatInterest(BigDecimal principal, BigDecimal flatRatePercent, int periods, int periodsPerYear) {
        return principal.multiply(flatRatePercent, MC).multiply(BigDecimal.valueOf(periods), MC)
                .divide(HUNDRED.multiply(BigDecimal.valueOf(periodsPerYear)), MC);
    }

    /**
     * Periodic rate (a fraction, not %) at which {@code periods} equal payments of {@code instalment} repay
     * {@code principal}. Bisection: monotone and never diverges. Zero when the instalments only return the principal.
     */
    public static BigDecimal periodicRate(BigDecimal principal, BigDecimal instalment, int periods) {
        if (principal.signum() <= 0) throw new IllegalArgumentException("principal must be > 0");
        if (periods < 1) throw new IllegalArgumentException("periods must be >= 1");
        BigDecimal total = instalment.multiply(BigDecimal.valueOf(periods));
        if (total.compareTo(principal) < 0) {
            throw new IllegalArgumentException("instalments of " + instalment.toPlainString() + " do not repay " + principal.toPlainString());
        }
        if (total.compareTo(principal) == 0) return BigDecimal.ZERO;
        double p = principal.doubleValue();
        double e = instalment.doubleValue();
        double lo = 0;
        double hi = 1;
        while (pv(e, periods, hi) > p && hi < 1e6) hi *= 2;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (pv(e, periods, mid) > p) lo = mid; else hi = mid;
        }
        return new BigDecimal((lo + hi) / 2, MathContext.DECIMAL64);
    }

    private static double pv(double instalment, int periods, double r) {
        if (r == 0) return instalment * periods;
        return instalment * (1 - Math.pow(1 + r, -periods)) / r;
    }

    /** Nominal annual rate % (periodic rate × periods per year) of a level-instalment loan. */
    public static BigDecimal fromInstalment(BigDecimal principal, BigDecimal instalment, int periods, int periodsPerYear) {
        return annual(periodicRate(principal, instalment, periods), periodsPerYear);
    }

    /** Reducing-balance nominal annual rate % equivalent to a flat rate. */
    public static BigDecimal flatToEffective(BigDecimal principal, BigDecimal flatRatePercent, int periods, int periodsPerYear) {
        BigDecimal total = principal.add(flatInterest(principal, flatRatePercent, periods, periodsPerYear));
        return fromInstalment(principal, total.divide(BigDecimal.valueOf(periods), MC), periods, periodsPerYear);
    }

    public static BigDecimal annual(BigDecimal periodicRate, int periodsPerYear) {
        return periodicRate.multiply(BigDecimal.valueOf(periodsPerYear), MC).multiply(HUNDRED, MC).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** Simple annual rate % such that principal × (1 + rate × year fraction) = future value. */
    public static BigDecimal simpleAnnualFromFutureValue(BigDecimal principal, BigDecimal futureValue, LocalDate from, LocalDate to,
                                                         DayCount dayCount) {
        if (principal.signum() <= 0) throw new IllegalArgumentException("principal must be > 0");
        if (futureValue.compareTo(principal) < 0) throw new IllegalArgumentException("future value cannot be below the principal");
        BigDecimal years = dayCount.yearFraction(from, to);
        if (years.signum() <= 0) throw new IllegalArgumentException("maturity must be after the start date");
        return futureValue.subtract(principal).divide(principal, MC).divide(years, MC).multiply(HUNDRED, MC)
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }
}
