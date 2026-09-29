package com.corebanking.lending.engine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;

/**
 * Days past due and asset classification per RBI's IRACP norms and the 12-Nov-2021 clarification (US-076, US-077).
 * <ul>
 *   <li>An amount is overdue if unpaid at the day-end of its due date; days past due count that day as day 1
 *       (RBI example: due 31-Mar, SMA-1 at day-end 30-Apr, SMA-2 at 30-May, NPA at 29-Jun).</li>
 *   <li>SMA-0 1–30, SMA-1 31–60, SMA-2 61–90 days; NPA above 90 days.</li>
 *   <li>NPA ageing from the NPA date: substandard below 12 months; doubtful D1 12–24, D2 24–48, D3 above 48 months.</li>
 *   <li>An NPA is upgraded to standard only when the entire arrears of interest and principal are paid
 *       (penal charges alone do not keep an account NPA).</li>
 * </ul>
 */
public final class Delinquency {

    public enum AssetClass {
        STANDARD, SMA0, SMA1, SMA2, SUBSTANDARD, DOUBTFUL1, DOUBTFUL2, DOUBTFUL3, LOSS;

        public boolean isNpa() {
            return ordinal() >= SUBSTANDARD.ordinal();
        }
    }

    public record Status(int dpd, AssetClass assetClass, LocalDate npaSince) {}

    private Delinquency() {}

    /** @param oldestUnpaidDue due date of the oldest demand still unpaid, or null when nothing is overdue */
    public static int dpd(LocalDate asOf, LocalDate oldestUnpaidDue) {
        if (oldestUnpaidDue == null || oldestUnpaidDue.isAfter(asOf)) return 0;
        return (int) ChronoUnit.DAYS.between(oldestUnpaidDue, asOf) + 1;
    }

    /**
     * Classification at day-end.
     *
     * @param previous          classification after the previous day-end
     * @param previousNpaSince  NPA date carried from before (null when not NPA)
     * @param arrearsOutstanding true while any overdue interest or principal (or a fee, per product policy) is unpaid
     */
    public static Status classify(LocalDate asOf, int dpd, AssetClass previous, LocalDate previousNpaSince,
                                  boolean arrearsOutstanding) {
        if (previous == AssetClass.LOSS) return new Status(dpd, AssetClass.LOSS, previousNpaSince);
        if (previous != null && previous.isNpa()) {
            if (!arrearsOutstanding) return new Status(dpd, AssetClass.STANDARD, null);    // upgrade only at zero arrears
            return new Status(dpd, npaAge(previousNpaSince, asOf), previousNpaSince);
        }
        if (dpd > 90) return new Status(dpd, AssetClass.SUBSTANDARD, asOf);
        if (dpd > 60) return new Status(dpd, AssetClass.SMA2, null);
        if (dpd > 30) return new Status(dpd, AssetClass.SMA1, null);
        if (dpd > 0) return new Status(dpd, AssetClass.SMA0, null);
        return new Status(0, AssetClass.STANDARD, null);
    }

    static AssetClass npaAge(LocalDate npaSince, LocalDate asOf) {
        long months = ChronoUnit.MONTHS.between(npaSince, asOf);
        if (months < 12) return AssetClass.SUBSTANDARD;
        if (months < 24) return AssetClass.DOUBTFUL1;
        if (months < 48) return AssetClass.DOUBTFUL2;
        return AssetClass.DOUBTFUL3;
    }

    /** Borrower-level classification (US-037): when one loan is NPA, all the borrower's loans are NPA. */
    public static AssetClass worst(Collection<AssetClass> classes) {
        AssetClass w = AssetClass.STANDARD;
        for (AssetClass c : classes) if (c.ordinal() > w.ordinal()) w = c;
        return w;
    }
}
