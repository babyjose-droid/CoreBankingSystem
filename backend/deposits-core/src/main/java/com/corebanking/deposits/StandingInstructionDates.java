package com.corebanking.deposits;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * When a standing instruction (P4-5b) pays. A payment falls due on its date; one that could not be made (not enough
 * money, a freeze) is tried again each day for {@code retryDays} days more, then left. The dates looked at on a
 * business day are those from {@code today - retryDays} to {@code today}, never before the instruction starts.
 */
public final class StandingInstructionDates {

    private StandingInstructionDates() {}

    /** The first day a payment due on a date before it is no longer tried. */
    public static LocalDate windowStart(LocalDate start, LocalDate today, int retryDays) {
        LocalDate w = today.minusDays(Math.max(0, retryDays));
        return w.isBefore(start) ? start : w;
    }

    /** A transfer every {@code everyMonths} months from {@code start}: its dates in the window, oldest first. */
    public static List<LocalDate> transferDates(LocalDate start, int everyMonths, LocalDate end, LocalDate today, int retryDays) {
        if (everyMonths < 1) throw new IllegalArgumentException("everyMonths must be at least 1");
        LocalDate from = windowStart(start, today, retryDays);
        List<LocalDate> out = new ArrayList<>();
        for (int k = 0; ; k++) {
            LocalDate d = start.plusMonths((long) k * everyMonths);
            if (d.isAfter(today) || (end != null && d.isAfter(end))) break;
            if (!d.isBefore(from)) out.add(d);
        }
        return out;
    }

    /** Every date in the window, oldest first: a loan's dues are looked up for each. */
    public static List<LocalDate> window(LocalDate start, LocalDate today, int retryDays) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = windowStart(start, today, retryDays); !d.isAfter(today); d = d.plusDays(1)) out.add(d);
        return out;
    }

    /** Whether nothing more will fall due: past the end date and its retries. */
    public static boolean over(LocalDate end, LocalDate today, int retryDays) {
        return end != null && today.isAfter(end.plusDays(Math.max(0, retryDays)));
    }
}
