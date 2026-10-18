package com.corebanking.deposits;

/**
 * Topics the deposits module writes to the outbox (ADR-008), in the transaction of the change. No personal data:
 * the payee of a transfer is read back through {@link DepositOperations#transferOut} by the module that sends it.
 */
public final class DepositEvents {

    /**
     * A bank transfer out of a savings or current account was posted to be sent through the payment gateway (P4-2b):
     * movementId, accountId, accountNo, customerId, branch, amount (decimal string), businessDate.
     */
    public static final String TRANSFER_OUT = "deposit.transfer_out";

    /**
     * A term deposit is maturing within the notice period (P4-4): accountId, accountNo, customerId, maturityDate, amount
     * (the expected maturity value, decimal string), instruction. The messaging module tells the depositor (template
     * DEPOSIT_MATURITY).
     */
    public static final String MATURITY_NOTICE = "deposit.maturity_notice";

    private DepositEvents() {}
}
