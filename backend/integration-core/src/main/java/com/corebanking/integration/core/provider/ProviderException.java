package com.corebanking.integration.core.provider;

/**
 * A provider call did not produce an answer we can act on.
 * <ul>
 *   <li>{@code retryable}: timeout, connection failure, HTTP 5xx or 429 — the outcome is unknown or the provider
 *       asked us to come back; the caller retries with the same reference (providers must treat our reference as
 *       an idempotency key) or asks for the status.</li>
 *   <li>not retryable: configuration is missing, the adapter does not support the call, or the answer could not
 *       be understood — a person has to look.</li>
 * </ul>
 * The message never contains secrets or account numbers.
 */
public final class ProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public ProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public static ProviderException unsupported(String what) {
        return new ProviderException(what + " is not supported by this adapter", false);
    }
}
