package com.corebanking.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;

/**
 * Foreclosure quote. The reference system failed here ("Infinite or NaN"); this version guards every
 * division and rejects inconsistent input with a clear message instead.
 */
public final class PreclosureCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PreclosureCalculator() {}

    public record Quote(BigDecimal principalOutstanding, BigDecimal overdueDues, BigDecimal brokenPeriodInterest,
                        BigDecimal charge, BigDecimal chargeTax, BigDecimal total) {}

    /**
     * @param lastInterestDate date up to which interest has been demanded (last due date or disbursal)
     * @param chargePercent    foreclosure charge % on principal outstanding (0 for floating-rate
     *                         individual loans per RBI directions — the product decides)
     */
    public static Quote quote(BigDecimal principalOutstanding, BigDecimal overdueDues, BigDecimal annualRatePercent,
                              LocalDate lastInterestDate, LocalDate closureDate, DayCount dayCount,
                              BigDecimal chargePercent, BigDecimal taxRatePercent, Rounding rounding) {
        if (principalOutstanding.signum() < 0 || overdueDues.signum() < 0) {
            throw new IllegalArgumentException("outstanding amounts cannot be negative");
        }
        if (closureDate.isBefore(lastInterestDate)) {
            throw new IllegalArgumentException("closure date is before last interest date");
        }
        BigDecimal bpi = rounding.apply(principalOutstanding.multiply(annualRatePercent.divide(HUNDRED, MC), MC)
                .multiply(dayCount.yearFraction(lastInterestDate, closureDate), MC));
        FeeCalculator.FeeBreakup charge = FeeCalculator.flat(
                principalOutstanding.multiply(chargePercent, MC).divide(HUNDRED, MC),
                taxRatePercent, FeeCalculator.TaxTreatment.EXCLUSIVE, rounding);
        BigDecimal total = principalOutstanding.add(overdueDues).add(bpi).add(charge.total());
        return new Quote(principalOutstanding, overdueDues, bpi, charge.fee(), charge.tax(), total);
    }
}
