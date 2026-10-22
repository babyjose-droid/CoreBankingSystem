package com.corebanking.deposits;

import java.time.LocalDate;

/**
 * Where a savings or current account stands for want of use (P4-6; a bank's rule set: RBI, inoperative accounts and
 * unclaimed deposits, 1-Jan-2024). Counted from the last movement made by or for the customer (or the opening date):
 * after {@code reviewMonths} the bank reviews it and tells the customer; after {@code inoperativeMonths} it is
 * inoperative (credits come in, nothing goes out until it is reactivated with the KYC updated); after
 * {@code unclaimedYears} its balance goes to the unclaimed deposits head for the Depositor Education and Awareness Fund.
 */
public final class Dormancy {

    public enum Stage { OPERATIVE, REVIEW, INOPERATIVE, UNCLAIMED }

    public record Rules(int reviewMonths, int inoperativeMonths, int unclaimedYears) {}

    private Dormancy() {}

    public static Stage stage(Rules r, LocalDate lastOperated, LocalDate today) {
        if (!today.isBefore(lastOperated.plusYears(r.unclaimedYears()))) return Stage.UNCLAIMED;
        if (!today.isBefore(lastOperated.plusMonths(r.inoperativeMonths()))) return Stage.INOPERATIVE;
        if (!today.isBefore(lastOperated.plusMonths(r.reviewMonths()))) return Stage.REVIEW;
        return Stage.OPERATIVE;
    }

    /** The day the account becomes inoperative if nothing happens meanwhile. */
    public static LocalDate inoperativeOn(Rules r, LocalDate lastOperated) {
        return lastOperated.plusMonths(r.inoperativeMonths());
    }

    /** The day its balance becomes unclaimed if nothing happens meanwhile. */
    public static LocalDate unclaimedOn(Rules r, LocalDate lastOperated) {
        return lastOperated.plusYears(r.unclaimedYears());
    }
}
