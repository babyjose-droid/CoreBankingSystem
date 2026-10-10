package com.corebanking.integration.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SimulatedPayoutOutcomeTest {

    @Test
    void a_payout_can_be_failed_before_it_is_credited_and_returned_after() {
        assertTrue(InboundWebhookService.outcomeAllowed("FAILED", "INITIATED"));
        assertTrue(InboundWebhookService.outcomeAllowed("FAILED", "SENT"));
        assertFalse(InboundWebhookService.outcomeAllowed("FAILED", "SUCCESS"), "a credited payout comes back as RETURNED");
        assertTrue(InboundWebhookService.outcomeAllowed("RETURNED", "SUCCESS"));
        assertTrue(InboundWebhookService.outcomeAllowed("RETURNED", "SENT"));
        assertFalse(InboundWebhookService.outcomeAllowed("RETURNED", "FAILED"));
        assertFalse(InboundWebhookService.outcomeAllowed("RETURNED", "INITIATED"));
        assertFalse(InboundWebhookService.outcomeAllowed("PAID", "SENT"));
    }
}
