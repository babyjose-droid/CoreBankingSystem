package com.corebanking.integration.core.provider;

/** A provider callback failed verification or could not be read. It is answered with 4xx and never processed. */
public final class WebhookRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WebhookRejectedException(String message) {
        super(message);
    }
}
