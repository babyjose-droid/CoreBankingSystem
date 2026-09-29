package com.corebanking.lending.engine;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Everything needed to build a repayment schedule. The same terms drive the preview, the KFS and the booked
 * schedule (ADR-006).
 *
 * @param firstDueDate     optional; when null the first due date is one month after disbursal
 * @param moratoriumMonths leading instalments that carry interest only (EQUATED only)
 * @param balloon          principal left for the last instalment (EQUATED only); zero for none
 */
public record LoanTerms(BigDecimal principal, BigDecimal ratePercent, int tenorMonths, LocalDate disbursalDate,
                        LocalDate firstDueDate, RepaymentMethod method, int moratoriumMonths, BigDecimal balloon,
                        DayCount dayCount, Rounding rounding, boolean extraDayOnFirst) {

    public LoanTerms {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(ratePercent, "ratePercent");
        Objects.requireNonNull(disbursalDate, "disbursalDate");
        Objects.requireNonNull(method, "method");
        if (principal.signum() <= 0) throw new IllegalArgumentException("principal must be positive");
        if (ratePercent.signum() < 0) throw new IllegalArgumentException("rate cannot be negative");
        if (tenorMonths < 1 || tenorMonths > 480) throw new IllegalArgumentException("tenor must be 1..480 months");
        if (moratoriumMonths < 0 || moratoriumMonths >= tenorMonths) throw new IllegalArgumentException("moratorium must be shorter than the tenor");
        if (firstDueDate != null && !firstDueDate.isAfter(disbursalDate)) throw new IllegalArgumentException("first due date must be after disbursal");
        balloon = balloon == null ? BigDecimal.ZERO : balloon;
        if (balloon.signum() < 0 || balloon.compareTo(principal) >= 0) throw new IllegalArgumentException("balloon must be 0..principal");
        if (method != RepaymentMethod.EQUATED && (moratoriumMonths > 0 || balloon.signum() > 0)) {
            throw new IllegalArgumentException("moratorium and balloon apply to EQUATED loans only");
        }
        dayCount = dayCount == null ? DayCount.ACTUAL_365 : dayCount;
        rounding = rounding == null ? Rounding.RUPEE_HALF_UP : rounding;
    }

    /** Standard monthly EMI loan with no moratorium or balloon. */
    public static LoanTerms equated(BigDecimal principal, BigDecimal rate, int months, LocalDate disbursal) {
        return new LoanTerms(principal, rate, months, disbursal, null, RepaymentMethod.EQUATED, 0, BigDecimal.ZERO,
                DayCount.ACTUAL_365, Rounding.RUPEE_HALF_UP, false);
    }
}
