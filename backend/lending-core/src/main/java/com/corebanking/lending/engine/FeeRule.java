package com.corebanking.lending.engine;

import com.corebanking.calc.FeeCalculator;
import com.corebanking.calc.Rounding;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Objects;

/**
 * A product fee (US-040): fixed, percentage or slab, with min/max caps, GST inclusive or exclusive, raised
 * automatically on an event. Amounts are computed with {@link FeeCalculator} and split into CGST/SGST or IGST
 * by {@link Gst}.
 */
public record FeeRule(String code, String name, Event event, CalcType calcType, BigDecimal amount, BigDecimal percent,
                      List<Slab> slabs, BigDecimal min, BigDecimal max, BigDecimal gstRatePercent,
                      FeeCalculator.TaxTreatment taxTreatment, boolean deductFromDisbursal) {

    public enum Event { DISBURSEMENT, PRECLOSURE, PART_PREPAYMENT, BOUNCE, LATE_PAYMENT, CANCELLATION, ADHOC }
    public enum CalcType { FIXED, PERCENT, SLAB }

    /** Base in [from, to] → fixed fee. */
    public record Slab(BigDecimal from, BigDecimal to, BigDecimal fee) {}

    public FeeRule {
        Objects.requireNonNull(code);
        Objects.requireNonNull(event);
        Objects.requireNonNull(calcType);
        gstRatePercent = gstRatePercent == null ? BigDecimal.ZERO : gstRatePercent;
        taxTreatment = taxTreatment == null ? FeeCalculator.TaxTreatment.EXCLUSIVE : taxTreatment;
        slabs = slabs == null ? List.of() : List.copyOf(slabs);
        if (calcType == CalcType.FIXED && amount == null) throw new IllegalArgumentException(code + ": FIXED fee needs an amount");
        if (calcType == CalcType.PERCENT && percent == null) throw new IllegalArgumentException(code + ": PERCENT fee needs a percent");
        if (calcType == CalcType.SLAB && slabs.isEmpty()) throw new IllegalArgumentException(code + ": SLAB fee needs slabs");
    }

    /** Fee before tax for a base amount (sanctioned amount, outstanding principal, overdue amount …). */
    public BigDecimal feeBeforeCaps(BigDecimal base) {
        return switch (calcType) {
            case FIXED -> amount;
            case PERCENT -> base.multiply(percent, MathContext.DECIMAL128).divide(BigDecimal.valueOf(100), MathContext.DECIMAL128);
            case SLAB -> slabs.stream().filter(s -> base.compareTo(s.from()) >= 0 && base.compareTo(s.to()) <= 0)
                    .map(Slab::fee).findFirst().orElse(BigDecimal.ZERO);
        };
    }

    public Charge compute(BigDecimal base, String supplierState, String recipientState, Rounding rounding) {
        BigDecimal fee = feeBeforeCaps(base);
        if (min != null && fee.compareTo(min) < 0) fee = min;
        if (max != null && fee.compareTo(max) > 0) fee = max;
        FeeCalculator.FeeBreakup b = FeeCalculator.flat(fee, gstRatePercent, taxTreatment, rounding);
        return new Charge(code, name, b.fee(), Gst.split(b.tax(), supplierState, recipientState), b.total());
    }

    /** A computed fee with its GST split. */
    public record Charge(String code, String name, BigDecimal fee, Gst.Split gst, BigDecimal total) {}
}
