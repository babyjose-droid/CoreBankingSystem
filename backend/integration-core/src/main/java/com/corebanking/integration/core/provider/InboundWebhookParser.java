package com.corebanking.integration.core.provider;

import java.util.Map;

/**
 * Verifies and reads one provider's callbacks. Verification runs over the raw bytes of the request body, exactly
 * as received: re-serialising parsed JSON would change the bytes and break (or worse, weaken) the check.
 */
public interface InboundWebhookParser {

    /**
     * @param headers    request headers with lower-case names
     * @param rawBody    the body as received
     * @param nowSeconds the receiver's clock, for timestamp tolerance where the provider signs a timestamp
     * @throws WebhookRejectedException when the signature does not verify or the body cannot be read
     */
    InboundEvent parse(Map<String, String> headers, byte[] rawBody, ProviderSettings config, long nowSeconds);
}
