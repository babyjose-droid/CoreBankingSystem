package com.corebanking.integration.core.nach;

import com.corebanking.integration.core.Lifecycle;
import java.time.LocalDate;
import java.util.function.Predicate;

/** Rules around NACH presentations that do not depend on a file layout (US-071, US-072). */
public final class NachRules {

    /**
     * Tenant policy for presenting again after a bounce.
     *
     * @param maxPresentations presentations per demand in total, the first one included (property
     *                         {@code nach.max-presentations-per-demand})
     * @param gapWorkingDays   working days between a bounce and the next settlement date (property
     *                         {@code nach.representation.gap-days})
     */
    public record Policy(int maxPresentations, int gapWorkingDays) {
        public Policy {
            if (maxPresentations < 1 || maxPresentations > 10) throw new IllegalArgumentException("maxPresentations must be 1 to 10");
            if (gapWorkingDays < 1 || gapWorkingDays > 30) throw new IllegalArgumentException("gapWorkingDays must be 1 to 30");
        }
    }

    /** @param reason why not, when {@code represent} is false */
    public record Representation(boolean represent, String reason) {}

    private NachRules() {}

    /**
     * Whether a bounced demand is presented again.
     *
     * @param presentationsMade how many times the demand has been presented, the bounced one included
     * @param representable     whether the return reason allows it (insufficient funds: yes; account closed: no)
     * @param mandate           the mandate's status now
     */
    public static Representation afterBounce(int presentationsMade, boolean representable, Lifecycle.Mandate mandate, Policy policy) {
        if (mandate != Lifecycle.Mandate.ACTIVE) return new Representation(false, "the mandate is " + mandate);
        if (!representable) return new Representation(false, "the return reason does not allow another presentation");
        if (presentationsMade >= policy.maxPresentations()) {
            return new Representation(false, "presented " + presentationsMade + " time(s): the limit is " + policy.maxPresentations());
        }
        return new Representation(true, null);
    }

    /** The date {@code n} working days after {@code from} (n = 0 gives {@code from} itself, working or not). */
    public static LocalDate workingDaysAfter(LocalDate from, int n, Predicate<LocalDate> isWorkingDay) {
        if (n < 0 || n > 60) throw new IllegalArgumentException("n must be 0 to 60");
        LocalDate d = from;
        for (int i = 0; i < n; i++) {
            int guard = 0;
            do {
                d = d.plusDays(1);
                if (++guard > 366) throw new IllegalStateException("no working day within a year after " + from);
            } while (!isWorkingDay.test(d));
        }
        return d;
    }

    /**
     * Whether a mandate covers a debit: the amount is within its maximum and the settlement date within its
     * validity. A debit above the maximum must never be presented — the bank would return it, and presenting it
     * breaches the mandate.
     */
    public static String coverage(java.math.BigDecimal amount, LocalDate settlementDate, java.math.BigDecimal maxAmount,
                                  LocalDate startDate, LocalDate endDate) {
        if (amount.compareTo(maxAmount) > 0) return "amount " + amount.toPlainString() + " is above the mandate's maximum " + maxAmount.toPlainString();
        if (settlementDate.isBefore(startDate)) return "the mandate starts on " + startDate;
        if (endDate != null && settlementDate.isAfter(endDate)) return "the mandate ended on " + endDate;
        return null;
    }
}
