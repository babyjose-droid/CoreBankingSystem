package com.corebanking.calc;

import java.math.BigDecimal;
import java.math.MathContext;

/** Term deposit maths. */
public final class DepositCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private DepositCalculator() {}

    /**
     * Cumulative (re-investment) deposit maturity value: P × (1 + r/k)^(k·months/12).
     * {@code months} must be a multiple of 12/k (e.g. multiples of 3 for quarterly).
     */
    public static BigDecimal maturityCumulative(BigDecimal principal, BigDecimal annualRatePercent,
                                                int months, int compoundingsPerYear, Rounding rounding) {
        if ((months * compoundingsPerYear) % 12 != 0) {
            throw new IllegalArgumentException("tenor must be a whole number of compounding periods");
        }
        int periods = months * compoundingsPerYear / 12;
        BigDecimal r = annualRatePercent.divide(HUNDRED, MC).divide(BigDecimal.valueOf(compoundingsPerYear), MC);
        return rounding.apply(principal.multiply(BigDecimal.ONE.add(r).pow(periods, MC), MC));
    }

    /** Effective card rate = base rate + slab spread (additive, as observed in the reference system). */
    public static BigDecimal effectiveRate(BigDecimal baseRatePercent, BigDecimal slabSpreadPercent) {
        return baseRatePercent.add(slabSpreadPercent);
    }
}
