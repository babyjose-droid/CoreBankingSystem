package com.corebanking.lending.engine;

import java.time.LocalDate;

/**
 * How often instalments fall due (BR-LPR-02: weekly, fortnightly, monthly, quarterly, half-yearly, yearly; daily for
 * micro-loans with daily collection). A loan's tenor is counted in periods of its frequency.
 */
public enum Frequency {
    DAILY(365, 0, 1, 1100),
    WEEKLY(52, 0, 7, 520),
    FORTNIGHTLY(26, 0, 14, 480),
    MONTHLY(12, 1, 0, 480),
    QUARTERLY(4, 3, 0, 160),
    HALF_YEARLY(2, 6, 0, 80),
    YEARLY(1, 12, 0, 40);

    private final int periodsPerYear;
    private final int months;
    private final int days;
    private final int maxPeriods;

    Frequency(int periodsPerYear, int months, int days, int maxPeriods) {
        this.periodsPerYear = periodsPerYear;
        this.months = months;
        this.days = days;
        this.maxPeriods = maxPeriods;
    }

    public int periodsPerYear() { return periodsPerYear; }

    /** Longest tenor in periods (40 years; 3 years for daily, 10 for weekly collection). */
    public int maxPeriods() { return maxPeriods; }

    /**
     * {@code anchor} moved by {@code n} periods (negative = back). Month-based frequencies never chain: the date is
     * always derived from the anchor, and an anchor on the last day of its month gives month-end dates throughout.
     */
    public LocalDate plus(LocalDate anchor, int n) {
        if (months == 0) return anchor.plusDays((long) days * n);
        LocalDate d = anchor.plusMonths((long) months * n);
        boolean monthEnd = anchor.getDayOfMonth() == anchor.lengthOfMonth();
        return monthEnd ? d.withDayOfMonth(d.lengthOfMonth()) : d;
    }
}
