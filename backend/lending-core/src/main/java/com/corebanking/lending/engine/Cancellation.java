package com.corebanking.lending.engine;

import com.corebanking.calc.DayCount;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Exit in the cooling-off (look-up) period (US-053). Under RBI's digital lending rules the borrower repays the
 * principal and the proportionate APR-based cost for the days used, with no penalty; a disclosed one-time
 * processing fee may be retained.
 */
public final class Cancellation {

    public record Quote(BigDecimal principal, BigDecimal interestForDaysUsed, long daysUsed, BigDecimal total) {}

    private Cancellation() {}

    public static boolean withinCoolingOff(LocalDate disbursedOn, int coolingOffDays, LocalDate onDate) {
        return !onDate.isBefore(disbursedOn) && !onDate.isAfter(disbursedOn.plusDays(coolingOffDays));
    }

    public static Quote quote(BigDecimal principalDisbursed, BigDecimal aprPercent, LocalDate disbursedOn, LocalDate onDate,
                              DayCount dayCount) {
        long days = dayCount.days(disbursedOn, onDate);
        BigDecimal interest = principalDisbursed.multiply(aprPercent.divide(BigDecimal.valueOf(100), MathContext.DECIMAL128), MathContext.DECIMAL128)
                .multiply(dayCount.yearFraction(disbursedOn, onDate), MathContext.DECIMAL128).setScale(0, RoundingMode.HALF_UP);
        return new Quote(principalDisbursed, interest, days, principalDisbursed.add(interest));
    }
}
