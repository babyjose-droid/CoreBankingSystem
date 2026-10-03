package com.corebanking.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;

/** Day-count conventions. {@link #yearFraction} is what interest calculations use. */
public enum DayCount {
    /** Actual days / 365 always (Indian retail lending default). */
    ACTUAL_365,
    /** Actual days / 360. */
    ACTUAL_360,
    /** ISDA Actual/Actual: days in each calendar year divided by that year's length. */
    ACTUAL_ACTUAL,
    /** 30/360 US bond basis (the reference system's "30-360NA"). */
    THIRTY_360,
    /** 30E/360 European basis ("30-360E"): a 31st on either date counts as the 30th. */
    THIRTY_E_360,
    /** Actual days / 366. */
    ACTUAL_366,
    /** Actual days / 364 (52 weeks of 7 days: weekly collection loans). */
    ACTUAL_364,
    /** Actual days / 336 (12 periods of 28 days: four-weekly collection loans). */
    ACTUAL_336,
    /** Actual days / 372 (12 months of 31 days). */
    ACTUAL_372;

    static final MathContext MC = MathContext.DECIMAL128;

    public long days(LocalDate from, LocalDate to) {
        if (this == THIRTY_360) {
            int d1 = Math.min(from.getDayOfMonth(), 30);
            int d2 = to.getDayOfMonth();
            if (d2 == 31 && d1 == 30) d2 = 30;
            return 360L * (to.getYear() - from.getYear())
                    + 30L * (to.getMonthValue() - from.getMonthValue())
                    + (d2 - d1);
        }
        if (this == THIRTY_E_360) {
            int d1 = Math.min(from.getDayOfMonth(), 30);
            int d2 = Math.min(to.getDayOfMonth(), 30);
            return 360L * (to.getYear() - from.getYear())
                    + 30L * (to.getMonthValue() - from.getMonthValue())
                    + (d2 - d1);
        }
        return ChronoUnit.DAYS.between(from, to);
    }

    /** Days in the year under this convention; 0 for ACTUAL_ACTUAL, where it depends on the calendar year. */
    public int yearBasis() {
        return switch (this) {
            case ACTUAL_365 -> 365;
            case ACTUAL_360, THIRTY_360, THIRTY_E_360 -> 360;
            case ACTUAL_366 -> 366;
            case ACTUAL_364 -> 364;
            case ACTUAL_336 -> 336;
            case ACTUAL_372 -> 372;
            case ACTUAL_ACTUAL -> 0;
        };
    }

    public BigDecimal yearFraction(LocalDate from, LocalDate to) {
        if (this == ACTUAL_ACTUAL) return actualActual(from, to);
        return BigDecimal.valueOf(days(from, to)).divide(BigDecimal.valueOf(yearBasis()), MC);
    }

    private static BigDecimal actualActual(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) return actualActual(to, from).negate();
        BigDecimal total = BigDecimal.ZERO;
        LocalDate cursor = from;
        while (cursor.getYear() < to.getYear()) {
            LocalDate nextYear = LocalDate.of(cursor.getYear() + 1, 1, 1);
            total = total.add(BigDecimal.valueOf(ChronoUnit.DAYS.between(cursor, nextYear))
                    .divide(BigDecimal.valueOf(Year.of(cursor.getYear()).length()), MC));
            cursor = nextYear;
        }
        return total.add(BigDecimal.valueOf(ChronoUnit.DAYS.between(cursor, to))
                .divide(BigDecimal.valueOf(Year.of(cursor.getYear()).length()), MC));
    }
}
