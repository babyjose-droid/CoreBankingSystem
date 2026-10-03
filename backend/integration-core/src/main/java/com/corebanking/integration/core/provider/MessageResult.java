package com.corebanking.integration.core.provider;

/**
 * Outcome of handing a message to an SMS or e-mail provider.
 *
 * @param accepted  the provider took the message (delivery to the handset or inbox is reported separately)
 * @param retryable when not accepted: true for a temporary refusal
 */
public record MessageResult(boolean accepted, String providerRef, String error, boolean retryable) {

    public static MessageResult accepted(String providerRef) {
        return new MessageResult(true, providerRef, null, false);
    }

    public static MessageResult refused(String error, boolean retryable) {
        return new MessageResult(false, null, error, retryable);
    }
}
