package com.corebanking.lending;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What other modules may do to loans (the integration module: gateway and NACH receipts, bounce charges, NACH
 * presentations, failed payouts). Everything goes through the same engine and posting path as the staff API.
 * Callers are responsible for their own idempotency: each call here posts.
 */
public interface LoanOperations {

    /** @param source CONSOLE (staff, maker-checker) or API (an LOS, straight through) */
    record LoanRef(UUID id, String loanNo, UUID customerId, String branch, String status, String source, String externalRef,
                   BigDecimal netDisbursed, LocalDate nextDueDate) {}

    record Posting(UUID txnId, LocalDate businessDate, String loanStatus) {}

    /** An amount falling due on a date: the instalment less any advance already held. */
    record Due(UUID loanId, String loanNo, UUID customerId, String branch, LocalDate dueDate, BigDecimal amount) {}

    /** @param charged false when the product has no fee for a bounce; {@code note} says so */
    record BounceCharge(boolean charged, String feeCode, String note) {}

    /** @throws com.corebanking.platform.ApiException 404 when there is no such loan */
    LoanRef find(UUID loanId);

    LoanRef findByNo(String loanNo);

    /**
     * Posts a receipt on the open business date. 409 when the books are not open or the loan cannot take it.
     *
     * @param channel   how the money came (PG, NACH), shown in the transaction's narration
     * @param reference the bank or gateway reference, shown in the narration
     */
    Posting postRepayment(UUID loanId, BigDecimal amount, LocalDate valueDate, String channel, String reference);

    /** Levies the product's bounce fee (the fee rule with event BOUNCE), with GST as the rule says. */
    BounceCharge chargeBounceFee(UUID loanId, BigDecimal bouncedAmount);

    /** Instalments of ACTIVE loans falling due on the date. Reads through the JdbcTemplate given (end of day). */
    List<Due> duesOn(JdbcTemplate jdbc, LocalDate dueDate);

    /** What is still unpaid of the demand (or coming instalment) of that date; null when nothing is. */
    Due dueFor(JdbcTemplate jdbc, UUID loanId, LocalDate dueDate);

    /**
     * Takes back a disbursement whose payout did not reach the borrower: reverses the disbursement's postings
     * (principal, deducted fees, GST) and the day-end postings since, and returns the loan to SANCTIONED so that
     * it can be disbursed again or left. Refused (409) once the loan has any other transaction.
     *
     * @return the loan number
     */
    String reverseDisbursement(UUID loanId, String reason, String user);
}
