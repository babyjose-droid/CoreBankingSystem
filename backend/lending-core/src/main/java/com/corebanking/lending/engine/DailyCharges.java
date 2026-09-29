package com.corebanking.lending.engine;

import com.corebanking.calc.DayCount;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Daily interest accrual and penal charges (US-078, US-081).
 * Penal charges follow RBI's 18-Aug-2023 circular: a charge on the overdue amount, never added to the rate,
 * never compounded — the base excludes earlier penal charges and fees.
 */
public final class DailyCharges {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private DailyCharges() {}

    /** Interest for one day ({@code day} to day+1) on the principal outstanding, in paise. */
    public static BigDecimal interestForDay(BigDecimal principalOutstanding, BigDecimal ratePercent, LocalDate day, DayCount dayCount) {
        if (principalOutstanding.signum() <= 0 || ratePercent.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return principalOutstanding.multiply(ratePercent.divide(HUNDRED, MC), MC)
                .multiply(dayCount.yearFraction(day, day.plusDays(1)), MC).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Penal charge for one day.
     *
     * @param overduePrincipalAndInterest unpaid principal and interest that are past due (no fees, no penal)
     */
    public static BigDecimal penalForDay(BigDecimal overduePrincipalAndInterest, BigDecimal penalRatePercent, LocalDate day, DayCount dayCount) {
        if (overduePrincipalAndInterest.signum() <= 0 || penalRatePercent == null || penalRatePercent.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return overduePrincipalAndInterest.multiply(penalRatePercent.divide(HUNDRED, MC), MC)
                .multiply(dayCount.yearFraction(day, day.plusDays(1)), MC).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Interest to accrue on the day a demand falls due so that total accrual for the period equals the
     * scheduled (rupee-rounded) interest: the true-up can be negative by a few paise.
     */
    public static BigDecimal trueUp(BigDecimal scheduledInterest, BigDecimal accruedSoFarForPeriod) {
        return scheduledInterest.subtract(accruedSoFarForPeriod);
    }
}
