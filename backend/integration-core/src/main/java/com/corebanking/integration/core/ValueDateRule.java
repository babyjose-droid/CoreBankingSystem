package com.corebanking.integration.core;

import java.time.LocalDate;

/**
 * Value date of a repayment that arrives from outside (payment gateway, NACH): the day the borrower paid, as far
 * as the books allow it.
 * <ul>
 *   <li>Paid on the open business date or up to {@code maxBackValueDays} before it: value date = payment date.</li>
 *   <li>Paid on a calendar day after the open business date (the books have not been rolled yet): value date =
 *       the business date — a transaction cannot be valued in the future.</li>
 *   <li>Paid earlier than the back-value limit (a late or replayed notification): value date = the business date,
 *       and the receipt is flagged for operations to review.</li>
 * </ul>
 * The posting itself always carries the open business date; the engine applies the receipt to the dues as they
 * stand on that date.
 */
public final class ValueDateRule {

    /** @param adjusted true when the value date is not the payment date; {@code review} asks operations to look */
    public record Decision(LocalDate valueDate, boolean adjusted, boolean review, String note) {}

    private ValueDateRule() {}

    public static Decision decide(LocalDate paymentDate, LocalDate businessDate, int maxBackValueDays) {
        if (maxBackValueDays < 0 || maxBackValueDays > 31) throw new IllegalArgumentException("maxBackValueDays must be 0 to 31");
        if (paymentDate == null) return new Decision(businessDate, true, false, "no payment date given: valued on the business date");
        if (paymentDate.isAfter(businessDate)) {
            return new Decision(businessDate, true, false, "paid on " + paymentDate + ", after the open business date: valued on the business date");
        }
        if (paymentDate.isBefore(businessDate.minusDays(maxBackValueDays))) {
            return new Decision(businessDate, true, true, "paid on " + paymentDate + ", more than " + maxBackValueDays
                    + " day(s) before the business date: valued on the business date, review needed");
        }
        return new Decision(paymentDate, false, false, null);
    }
}
