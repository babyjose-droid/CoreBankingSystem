package com.corebanking.integration.core.provider;

/** What a provider is used for. A tenant has at most one active provider per kind. */
public enum ProviderKind {
    /** Paying out disbursements (US-051). */
    PAYOUT,
    /** Collecting repayments through a payment gateway (US-073). */
    COLLECTION,
    /** e-Mandate registration (US-070). */
    MANDATE,
    SMS,
    EMAIL
}
