package com.corebanking.kernel;

import java.time.LocalDate;

/**
 * What happens to a posting that arrives while the business day is open, being closed, or stuck in a failed end of
 * day (US-111, ADR-015).
 * <ul>
 *   <li><b>Cut-off</b> is the moment end of day starts: the business day leaves OPEN.</li>
 *   <li>Before the cut-off everything is posted at once, on the business date.</li>
 *   <li>After the cut-off, a <b>receipt from a customer-facing channel</b> (the repayment API used by payment
 *       gateways, collection partners and the LOS, i.e. a straight-through client) is accepted and booked on the
 *       <b>next business date</b>, once that date has opened. Nothing else is: staff back-office postings and every
 *       other kind of transaction are refused until the day is open again.</li>
 *   <li>A deferred receipt is <b>valued on its posting date</b>. The date being closed has already had (or is
 *       having) its demands, penal charges and classification worked out, and a closed date never receives entries;
 *       giving the receipt the old value date would need those to be redone. The time the money was reported is
 *       kept on the receipt as evidence.</li>
 * </ul>
 */
public final class PostingWindow {

    public enum DayStatus { OPEN, EOD_RUNNING, EOD_FAILED, CLOSED }

    public enum Kind { REPAYMENT, PREPAYMENT, PRECLOSURE, CANCELLATION, DISBURSEMENT, FEE, WAIVER, REVERSAL, AMENDMENT, VOUCHER }

    public enum Decision { POST_NOW, DEFER_TO_NEXT_BUSINESS_DATE, REFUSE }

    /** The date whose books take the entries, and the date the money counts from. */
    public record Booking(LocalDate postingDate, LocalDate valueDate) {}

    private PostingWindow() {}

    /**
     * @param straightThrough the caller is a customer-facing channel (token permission {@code loan:stp}), not a
     *                        staff user at a screen
     */
    public static Decision decide(DayStatus status, Kind kind, boolean straightThrough) {
        if (status == DayStatus.OPEN) return Decision.POST_NOW;
        if ((status == DayStatus.EOD_RUNNING || status == DayStatus.EOD_FAILED) && kind == Kind.REPAYMENT && straightThrough) {
            return Decision.DEFER_TO_NEXT_BUSINESS_DATE;
        }
        return Decision.REFUSE;
    }

    /**
     * Posting date and value date for a receipt.
     *
     * @param businessDate       the current business date (the one being closed, after the cut-off)
     * @param nextBusinessDate   the next working day
     * @param requestedValueDate the value date the caller asked for, or null
     * @throws IllegalArgumentException when the value date asked for cannot be honoured
     */
    public static Booking booking(Decision decision, LocalDate businessDate, LocalDate nextBusinessDate, LocalDate requestedValueDate) {
        switch (decision) {
            case POST_NOW -> {
                if (requestedValueDate != null && requestedValueDate.isAfter(businessDate)) {
                    throw new IllegalArgumentException("the value date cannot be after the business date " + businessDate);
                }
                return new Booking(businessDate, requestedValueDate == null ? businessDate : requestedValueDate);
            }
            case DEFER_TO_NEXT_BUSINESS_DATE -> {
                if (!nextBusinessDate.isAfter(businessDate)) throw new IllegalArgumentException("the next business date must be after " + businessDate);
                if (requestedValueDate != null && !requestedValueDate.equals(nextBusinessDate)) {
                    throw new IllegalArgumentException("end of day for " + businessDate + " has started: the receipt will be booked and valued on "
                            + nextBusinessDate + "; send it without a value date");
                }
                return new Booking(nextBusinessDate, nextBusinessDate);
            }
            default -> throw new IllegalArgumentException("the business day is not open; try after end of day");
        }
    }
}
