package com.corebanking.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.integration.core.Lifecycle.CollectionOrder;
import com.corebanking.integration.core.Lifecycle.Decision;
import com.corebanking.integration.core.Lifecycle.Delivery;
import com.corebanking.integration.core.Lifecycle.Mandate;
import com.corebanking.integration.core.Lifecycle.Payout;
import com.corebanking.integration.core.Lifecycle.Presentation;
import org.junit.jupiter.api.Test;

class LifecycleTest {

    @Test
    void payout_happy_path() {
        assertEquals(Decision.APPLY, Lifecycle.decide(Payout.INITIATED, Payout.SENT));
        assertEquals(Decision.APPLY, Lifecycle.decide(Payout.SENT, Payout.SUCCESS));
        assertEquals(Decision.APPLY, Lifecycle.decide(Payout.SUCCESS, Payout.RETURNED));
        assertEquals(Decision.APPLY, Lifecycle.decide(Payout.SENT, Payout.FAILED));
        assertEquals(Decision.APPLY, Lifecycle.decide(Payout.SENT, Payout.RETURNED));
    }

    @Test
    void a_webhook_may_overtake_the_api_response() {
        assertEquals(Decision.APPLY, Lifecycle.decide(Payout.INITIATED, Payout.SUCCESS));
        assertEquals(Decision.APPLY, Lifecycle.decide(Payout.INITIATED, Payout.FAILED));
        assertEquals(Decision.STALE, Lifecycle.decide(Payout.SUCCESS, Payout.SENT));
        assertEquals(Decision.STALE, Lifecycle.decide(Payout.FAILED, Payout.SENT));
    }

    @Test
    void repeats_are_duplicates() {
        for (Payout p : Payout.values()) assertEquals(Decision.DUPLICATE, Lifecycle.decide(p, p));
        for (Mandate m : Mandate.values()) assertEquals(Decision.DUPLICATE, Lifecycle.decide(m, m));
        for (Presentation p : Presentation.values()) assertEquals(Decision.DUPLICATE, Lifecycle.decide(p, p));
    }

    @Test
    void contradicting_outcomes_are_conflicts_never_applied() {
        assertEquals(Decision.CONFLICT, Lifecycle.decide(Payout.SUCCESS, Payout.FAILED));
        assertEquals(Decision.CONFLICT, Lifecycle.decide(Payout.FAILED, Payout.SUCCESS));
        assertEquals(Decision.CONFLICT, Lifecycle.decide(Payout.RETURNED, Payout.SUCCESS));
        assertEquals(Decision.CONFLICT, Lifecycle.decide(Payout.CANCELLED, Payout.SUCCESS));
        assertEquals(Decision.CONFLICT, Lifecycle.decide(Payout.ON_HOLD, Payout.SUCCESS));
        assertTrue(Payout.FAILED.terminal() && Payout.RETURNED.terminal() && Payout.CANCELLED.terminal());
        assertFalse(Payout.SUCCESS.terminal());           // a credited payout can still be returned
    }

    @Test
    void on_hold_payouts_resume_or_are_cancelled() {
        assertEquals(Decision.APPLY, Lifecycle.decide(Payout.INITIATED, Payout.ON_HOLD));
        assertEquals(Decision.APPLY, Lifecycle.decide(Payout.ON_HOLD, Payout.INITIATED));
        assertEquals(Decision.APPLY, Lifecycle.decide(Payout.ON_HOLD, Payout.CANCELLED));
    }

    @Test
    void a_payment_on_a_failed_or_expired_link_still_counts() {
        assertEquals(Decision.APPLY, Lifecycle.decide(CollectionOrder.CREATED, CollectionOrder.PAID));
        assertEquals(Decision.APPLY, Lifecycle.decide(CollectionOrder.EXPIRED, CollectionOrder.PAID));
        assertEquals(Decision.APPLY, Lifecycle.decide(CollectionOrder.FAILED, CollectionOrder.PAID));
        assertEquals(Decision.STALE, Lifecycle.decide(CollectionOrder.PAID, CollectionOrder.FAILED));
        assertEquals(Decision.STALE, Lifecycle.decide(CollectionOrder.PAID, CollectionOrder.EXPIRED));
        assertEquals(Decision.CONFLICT, Lifecycle.decide(CollectionOrder.CANCELLED, CollectionOrder.PAID));
    }

    @Test
    void mandate_lifecycle() {
        assertEquals(Decision.APPLY, Lifecycle.decide(Mandate.DRAFT, Mandate.SUBMITTED));
        assertEquals(Decision.APPLY, Lifecycle.decide(Mandate.SUBMITTED, Mandate.ACTIVE));
        assertEquals(Decision.APPLY, Lifecycle.decide(Mandate.SUBMITTED, Mandate.REJECTED));
        assertEquals(Decision.APPLY, Lifecycle.decide(Mandate.ACTIVE, Mandate.SUSPENDED));
        assertEquals(Decision.APPLY, Lifecycle.decide(Mandate.SUSPENDED, Mandate.ACTIVE));
        assertEquals(Decision.APPLY, Lifecycle.decide(Mandate.ACTIVE, Mandate.CANCELLED));
        assertEquals(Decision.APPLY, Lifecycle.decide(Mandate.ACTIVE, Mandate.EXPIRED));
        assertEquals(Decision.STALE, Lifecycle.decide(Mandate.ACTIVE, Mandate.SUBMITTED));
        assertEquals(Decision.CONFLICT, Lifecycle.decide(Mandate.REJECTED, Mandate.ACTIVE));
        assertEquals(Decision.CONFLICT, Lifecycle.decide(Mandate.CANCELLED, Mandate.ACTIVE));
        assertEquals(Decision.CONFLICT, Lifecycle.decide(Mandate.DRAFT, Mandate.ACTIVE));
        assertTrue(Mandate.ACTIVE.live());
        assertFalse(Mandate.SUSPENDED.live());
    }

    @Test
    void a_presentation_gets_one_outcome() {
        assertEquals(Decision.APPLY, Lifecycle.decide(Presentation.GENERATED, Presentation.SUCCESS));
        assertEquals(Decision.APPLY, Lifecycle.decide(Presentation.GENERATED, Presentation.BOUNCED));
        assertEquals(Decision.CONFLICT, Lifecycle.decide(Presentation.SUCCESS, Presentation.BOUNCED));
        assertEquals(Decision.CONFLICT, Lifecycle.decide(Presentation.BOUNCED, Presentation.SUCCESS));
        assertEquals(Decision.CONFLICT, Lifecycle.decide(Presentation.WITHDRAWN, Presentation.SUCCESS));
    }

    @Test
    void deliveries_retry_die_and_can_be_replayed() {
        assertEquals(Decision.APPLY, Lifecycle.decide(Delivery.PENDING, Delivery.DELIVERED));
        assertEquals(Decision.APPLY, Lifecycle.decide(Delivery.PENDING, Delivery.RETRY));
        assertEquals(Decision.APPLY, Lifecycle.decide(Delivery.RETRY, Delivery.RETRY));
        assertEquals(Decision.APPLY, Lifecycle.decide(Delivery.RETRY, Delivery.DEAD));
        assertEquals(Decision.APPLY, Lifecycle.decide(Delivery.DEAD, Delivery.PENDING));
        assertEquals(Decision.CONFLICT, Lifecycle.decide(Delivery.DELIVERED, Delivery.PENDING));
        assertEquals(Decision.DUPLICATE, Lifecycle.decide(Delivery.DELIVERED, Delivery.DELIVERED));
    }
}
