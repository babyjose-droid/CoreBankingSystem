package com.corebanking.integration.core.provider;

import com.corebanking.integration.core.Lifecycle;
import com.corebanking.kernel.Masking;
import java.math.BigDecimal;

/**
 * Paying a disbursement to the borrower's bank account (US-051). {@link #send} is idempotent on
 * {@link Request#reference()}: sending the same reference again must never move money twice — an adapter for a
 * provider that cannot guarantee this must ask for the status first.
 */
public interface PayoutGateway {

    /** The account to credit. Only ever held in memory in clear; stored encrypted and shown masked. */
    record Beneficiary(String holderName, String accountNumber, String ifsc) {
        @Override
        public String toString() {
            return "Beneficiary[ifsc=" + ifsc + ", account=" + Masking.account(accountNumber) + "]";
        }
    }

    enum Validity { VALID, INVALID, UNAVAILABLE }

    /**
     * Result of a beneficiary validation ("penny drop").
     *
     * @param nameAtBank the account holder's name as the bank returned it, when the provider gives it
     */
    record Validation(Validity validity, String nameAtBank, String providerRef, String reason) {}

    /** @param mode IMPS, NEFT or RTGS */
    record Request(String reference, BigDecimal amount, Beneficiary beneficiary, String mode, String narration) {}

    /** @param status SENT (accepted, outcome pending), SUCCESS, FAILED or RETURNED */
    record Result(Lifecycle.Payout status, String providerRef, String utr, String failureCode, String failureReason) {}

    Validation validate(Beneficiary beneficiary, String reference);

    Result send(Request request);

    /** The provider's current view of a payout we sent; used by polling. */
    Result status(String reference, String providerRef);
}
