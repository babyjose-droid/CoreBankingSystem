package com.corebanking.calc;

import java.math.BigDecimal;
import java.math.MathContext;

/** Equated instalment (PMT). Pure function: no I/O, no clock. */
public final class EmiCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TWELVE_HUNDRED = BigDecimal.valueOf(1200);

    private EmiCalculator() {}

    /**
     * EMI = P·r / (1 − (1+r)^−n), r = annual rate % / 1200.
     *
     * @param principal         loan amount
     * @param annualRatePercent e.g. 18 for 18% p.a.
     * @param instalments       number of monthly instalments (≥ 1)
     */
    public static BigDecimal pmt(BigDecimal principal, BigDecimal annualRatePercent, int instalments, Rounding rounding) {
        if (instalments < 1) throw new IllegalArgumentException("instalments must be >= 1");
        if (principal.signum() <= 0) throw new IllegalArgumentException("principal must be > 0");
        if (annualRatePercent.signum() == 0) {
            return rounding.apply(principal.divide(BigDecimal.valueOf(instalments), MC));
        }
        BigDecimal r = annualRatePercent.divide(TWELVE_HUNDRED, MC);
        BigDecimal growth = BigDecimal.ONE.add(r).pow(instalments, MC);
        BigDecimal emi = principal.multiply(r, MC).multiply(growth, MC)
                .divide(growth.subtract(BigDecimal.ONE), MC);
        return rounding.apply(emi);
    }
}
