package com.corebanking.integration.core;

import java.util.EnumSet;
import java.util.Set;

/**
 * Status lifecycles of the things we track with a partner. Status news arrives at least once and in any order (a
 * webhook can overtake the API response, a poll can repeat what a webhook said), so every change goes through
 * {@link #decide}: a repeat is ignored, a step backwards is ignored, a step the lifecycle does not have is
 * refused and flagged. The database enforces the same transitions (V20).
 */
public final class Lifecycle {

    public enum Decision {
        /** Move to the new status. */
        APPLY,
        /** Already there: the news is a duplicate. */
        DUPLICATE,
        /** The news is older than what we know (e.g. SENT arriving after SUCCESS). */
        STALE,
        /** Not a step this lifecycle has (e.g. FAILED after SUCCESS): keep the status and flag for operations. */
        CONFLICT
    }

    /** Payout of a disbursement (US-051). */
    public enum Payout {
        /** Created with the disbursement; not yet given to the gateway. */
        INITIATED,
        /** No usable beneficiary account; waits for operations. */
        ON_HOLD,
        /** Accepted by the gateway; outcome not known yet. */
        SENT,
        SUCCESS,
        FAILED,
        /** Credited and later returned by the beneficiary bank. */
        RETURNED,
        /** Withdrawn by operations before it was sent. */
        CANCELLED;

        public boolean terminal() {
            return this == FAILED || this == RETURNED || this == CANCELLED;
        }

        Set<Payout> next() {
            return switch (this) {
                case INITIATED -> EnumSet.of(ON_HOLD, SENT, SUCCESS, FAILED, CANCELLED);
                case ON_HOLD -> EnumSet.of(INITIATED, CANCELLED);
                case SENT -> EnumSet.of(SUCCESS, FAILED, RETURNED);
                case SUCCESS -> EnumSet.of(RETURNED);
                case FAILED, RETURNED, CANCELLED -> EnumSet.noneOf(Payout.class);
            };
        }
    }

    /** A payment link or order for a loan due (US-073). */
    public enum CollectionOrder {
        CREATED, PAID, FAILED, EXPIRED, CANCELLED;

        Set<CollectionOrder> next() {
            return switch (this) {
                // a payment can still arrive on a link the gateway reported as failed or that we expired: money wins
                case CREATED -> EnumSet.of(PAID, FAILED, EXPIRED, CANCELLED);
                case FAILED, EXPIRED -> EnumSet.of(PAID);
                case PAID, CANCELLED -> EnumSet.noneOf(CollectionOrder.class);
            };
        }
    }

    /** An e-mandate (US-070). */
    public enum Mandate {
        /** Captured; not yet sent for registration. */
        DRAFT,
        /** With the provider or sponsor bank; the customer may still have to authenticate. */
        SUBMITTED,
        ACTIVE,
        REJECTED,
        /** Presentations paused (by operations or after repeated bounces). */
        SUSPENDED,
        CANCELLED,
        EXPIRED;

        public boolean live() {
            return this == ACTIVE;
        }

        Set<Mandate> next() {
            return switch (this) {
                case DRAFT -> EnumSet.of(SUBMITTED, CANCELLED);
                case SUBMITTED -> EnumSet.of(ACTIVE, REJECTED, CANCELLED);
                case ACTIVE -> EnumSet.of(SUSPENDED, CANCELLED, EXPIRED);
                case SUSPENDED -> EnumSet.of(ACTIVE, CANCELLED, EXPIRED);
                case REJECTED, CANCELLED, EXPIRED -> EnumSet.noneOf(Mandate.class);
            };
        }
    }

    /** One debit presented under a mandate (US-071, US-072). */
    public enum Presentation {
        /** In a presentation file. */
        GENERATED,
        SUCCESS,
        BOUNCED,
        /** Taken out before the response (the due was paid another way). */
        WITHDRAWN;

        Set<Presentation> next() {
            return this == GENERATED ? EnumSet.of(SUCCESS, BOUNCED, WITHDRAWN) : EnumSet.noneOf(Presentation.class);
        }
    }

    /** Delivery of an outbound webhook or a message. */
    public enum Delivery {
        PENDING, RETRY, DELIVERED, DEAD;

        Set<Delivery> next() {
            return switch (this) {
                case PENDING, RETRY -> EnumSet.of(RETRY, DELIVERED, DEAD);
                case DEAD -> EnumSet.of(PENDING);              // replay
                case DELIVERED -> EnumSet.noneOf(Delivery.class);
            };
        }
    }

    private Lifecycle() {}

    public static Decision decide(Payout from, Payout to) {
        if (from == to) return Decision.DUPLICATE;
        if (from.next().contains(to)) return Decision.APPLY;
        return to == Payout.INITIATED || to == Payout.ON_HOLD || to == Payout.SENT ? Decision.STALE : Decision.CONFLICT;
    }

    public static Decision decide(CollectionOrder from, CollectionOrder to) {
        if (from == to) return Decision.DUPLICATE;
        if (from.next().contains(to)) return Decision.APPLY;
        return from == CollectionOrder.PAID ? Decision.STALE : Decision.CONFLICT;
    }

    public static Decision decide(Mandate from, Mandate to) {
        if (from == to) return Decision.DUPLICATE;
        if (from.next().contains(to)) return Decision.APPLY;
        return to == Mandate.SUBMITTED || to == Mandate.DRAFT ? Decision.STALE : Decision.CONFLICT;
    }

    public static Decision decide(Presentation from, Presentation to) {
        if (from == to) return Decision.DUPLICATE;
        return from.next().contains(to) ? Decision.APPLY : Decision.CONFLICT;
    }

    public static Decision decide(Delivery from, Delivery to) {
        if (from == to && from != Delivery.RETRY) return Decision.DUPLICATE;
        return from.next().contains(to) ? Decision.APPLY : Decision.CONFLICT;
    }
}
