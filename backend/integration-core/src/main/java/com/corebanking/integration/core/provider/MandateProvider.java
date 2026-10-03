package com.corebanking.integration.core.provider;

import com.corebanking.integration.core.Lifecycle;
import com.corebanking.kernel.Masking;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Registering an e-mandate (NACH) with the sponsor bank through a provider (US-070). */
public interface MandateProvider {

    record Registration(String reference, String holderName, String accountNumber, String ifsc, String accountType,
                        BigDecimal maxAmount, String frequency, LocalDate startDate, LocalDate endDate,
                        String sponsorBankCode, String utilityCode) {
        @Override
        public String toString() {
            return "Registration[" + reference + ", account=" + Masking.account(accountNumber) + "]";
        }
    }

    /** @param authenticationUrl where the customer authenticates the mandate (net banking, debit card, Aadhaar), if any */
    record Submitted(String providerRef, String authenticationUrl) {}

    /** @param status SUBMITTED, ACTIVE, REJECTED or CANCELLED */
    record State(Lifecycle.Mandate status, String umrn, String rejectCode, String rejectReason) {}

    Submitted register(Registration registration);

    State status(String reference, String providerRef);
}
