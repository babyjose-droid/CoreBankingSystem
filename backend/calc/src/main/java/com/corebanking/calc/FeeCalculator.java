package com.corebanking.calc;

import java.math.BigDecimal;
import java.math.MathContext;

/** Fees and charges with GST. Percentage fees respect optional min/max caps before tax. */
public final class FeeCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private FeeCalculator() {}

    public enum TaxTreatment { EXCLUSIVE, INCLUSIVE }

    public record FeeBreakup(BigDecimal fee, BigDecimal tax, BigDecimal total) {}

    /** Flat fee. EXCLUSIVE: tax is added on top. INCLUSIVE: the amount already contains tax. */
    public static FeeBreakup flat(BigDecimal amount, BigDecimal taxRatePercent, TaxTreatment treatment, Rounding rounding) {
        BigDecimal t = taxRatePercent.divide(HUNDRED, MC);
        if (treatment == TaxTreatment.EXCLUSIVE) {
            BigDecimal fee = rounding.apply(amount);
            BigDecimal tax = rounding.apply(fee.multiply(t, MC));
            return new FeeBreakup(fee, tax, fee.add(tax));
        }
        BigDecimal total = rounding.apply(amount);
        BigDecimal fee = rounding.apply(total.divide(BigDecimal.ONE.add(t), MC));
        return new FeeBreakup(fee, total.subtract(fee), total);
    }

    /** Percentage fee on a base (e.g. sanctioned amount), clamped to [min, max] (nulls = no cap). */
    public static FeeBreakup percentage(BigDecimal base, BigDecimal feePercent, BigDecimal min, BigDecimal max,
                                        BigDecimal taxRatePercent, TaxTreatment treatment, Rounding rounding) {
        BigDecimal fee = base.multiply(feePercent, MC).divide(HUNDRED, MC);
        if (min != null && fee.compareTo(min) < 0) fee = min;
        if (max != null && fee.compareTo(max) > 0) fee = max;
        return flat(fee, taxRatePercent, treatment, rounding);
    }
}
