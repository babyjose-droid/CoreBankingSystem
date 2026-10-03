package com.corebanking.lending;

/**
 * Topics the lending module writes to the outbox (ADR-008), in the transaction of the change. Payload fields:
 * {@code loanId, loanNo, customerId, externalRef, branch, businessDate} plus what the event adds (amounts as
 * decimal strings). No personal data.
 */
public final class LoanEvents {

    /** amount, netDisbursed, transactionId, trancheNo. One event per disbursement: the first and every later tranche. */
    public static final String DISBURSED = "loan.disbursed";
    /** The disbursement was taken back (a payout failed): reason, transactionId. */
    public static final String DISBURSEMENT_REVERSED = "loan.disbursement_reversed";
    /** amount, valueDate, transactionId, kind (REPAYMENT, PREPAYMENT, PRECLOSURE, CANCELLATION), channel, loanStatus. */
    public static final String PAYMENT_RECEIVED = "payment.received";
    /** closure (CLOSED or CANCELLED). */
    public static final String CLOSED = "loan.closed";
    /** assetClass, npaSince, dpd. */
    public static final String NPA = "loan.npa";
    /** A no-objection letter was issued for a closed loan. */
    public static final String NOC_ISSUED = "loan.noc_issued";
    /** rateBefore, rateAfter, emiBefore, emiAfter, remainingBefore, remainingAfter, option. */
    public static final String RATE_RESET = "loan.rate_reset";

    private LoanEvents() {}
}
