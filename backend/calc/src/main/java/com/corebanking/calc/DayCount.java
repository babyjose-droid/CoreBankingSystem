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
    /** 30/360 US bond basis. */
    THIRTY_360;

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
        return ChronoUnit.DAYS.between(from, to);
    }

    public BigDecimal yearFraction(LocalDate from, LocalDate to) {
        return switch (this) {
            case ACTUAL_365 -> BigDecimal.valueOf(days(from, to)).divide(BigDecimal.valueOf(365), MC);
            case ACTUAL_360, THIRTY_360 -> BigDecimal.valueOf(days(from, to)).divide(BigDecimal.valueOf(360), MC);
            case ACTUAL_ACTUAL -> actualActual(from, to);
        };
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
