package com.corebanking.deposits;

import java.time.LocalDate;

/**
 * A deposit tenure in calendar months plus days ("12 months", "46 days", "1 month 15 days"). It is always applied
 * to a start date, never converted to a day count, so "12 months" is a year whether or not it holds 29 February.
 */
public record Tenure(int months, int days) implements Comparable<Tenure> {

    public Tenure {
        if (months < 0 || days < 0) throw new IllegalArgumentException("tenure cannot be negative");
    }

    public static Tenure ofMonths(int months) { return new Tenure(months, 0); }
    public static Tenure ofDays(int days) { return new Tenure(0, days); }

    public LocalDate addTo(LocalDate start) {
        return start.plusMonths(months).plusDays(days);
    }

    /** True when a deposit from {@code start} to {@code maturity} has run for at least this tenure. */
    public boolean reachedBy(LocalDate start, LocalDate maturity) {
        return !maturity.isBefore(addTo(start));
    }

    /**
     * Order used to sort the steps of a rate table: a month counts as 31 days, so "364 days" sorts before
     * "12 months". Which step a deposit falls in is decided on its real dates ({@link #reachedBy}), not by this.
     */
    @Override public int compareTo(Tenure o) {
        return Integer.compare(months * 31 + days, o.months * 31 + o.days);
    }

    @Override public String toString() {
        if (months == 0) return days + " days";
        return days == 0 ? months + " months" : months + " months " + days + " days";
    }
}
