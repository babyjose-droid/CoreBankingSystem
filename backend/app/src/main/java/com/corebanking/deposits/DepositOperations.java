package com.corebanking.deposits;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What other modules may do to deposit accounts (the integration module: transfers out through the payment gateway
 * and money received through a gateway payment link). Everything goes through the same posting path and checks as
 * the staff API; the database checks of tenant V30 and V31 hold whatever the caller does.
 * <p>
 * None of these goes through maker-checker: a transfer out was approved when it was made, and money a gateway has
 * received is credited as it arrives. Each call posts on the open business date and refuses (409) when the books are
 * not open; the caller retries later.
 */
public interface DepositOperations {

    /**
     * A deposit account as another module needs it.
     *
     * @param family    CASA or TERM_DEPOSIT
     * @param principal a term deposit's principal; null for savings and current accounts
     * @param payoutTo  where a term deposit is paid out (CASA or BANK_ACCOUNT); null for savings and current accounts
     * @param payoutBankRecorded true when a term deposit paid out to a bank account has that account recorded
     */
    record AccountRef(UUID id, String accountNo, String family, String status, String branch, UUID customerId, BigDecimal principal,
                      String payoutTo, boolean payoutBankRecorded) {}

    /** An account at another bank, in clear: only for the call that sends the money. */
    record Payee(String holderName, String accountNumber, String ifsc) {}

    /**
     * A transfer out to be sent through the gateway.
     *
     * @param status SENT (posted, waiting for the bank), PAID or RETURNED
     */
    record TransferOut(UUID movementId, UUID accountId, String accountNo, UUID customerId, String branch, BigDecimal amount, Payee payee,
                       String status) {}

    /** @throws com.corebanking.platform.ApiException 404 when there is no such account */
    AccountRef find(UUID accountId);

    /** @throws com.corebanking.platform.ApiException 404 when the movement is not a transfer out through the gateway */
    TransferOut transferOut(UUID movementId);

    /**
     * The bank paid the transfer: the money moves from the in-transit head to the disbursement bank. Does nothing when
     * the transfer is already paid; refused (409) when it was returned.
     */
    void settleTransferOut(UUID movementId, String utr);

    /**
     * The transfer failed or the bank returned it: the money goes back to the account (the paid posting is reversed
     * first when there was one). Does nothing when it is already returned.
     */
    void returnTransferOut(UUID movementId, String reason);

    /**
     * Money a gateway received for an account: a savings or current account is credited (a bank transfer in), a booked
     * term deposit is funded (the amount must be its principal). Returns the id of the movement.
     *
     * @param reference the gateway's payment reference or the bank's UTR
     */
    UUID receiveFromGateway(UUID accountId, BigDecimal amount, String reference);

    /**
     * Records the bank account a booked term deposit is paid out to, before it is funded through a payment link (a
     * deposit paid out to a bank account is funded only with that account recorded). Replaces one recorded before.
     */
    void recordPayoutBankAccount(UUID depositId, Payee account);
}
