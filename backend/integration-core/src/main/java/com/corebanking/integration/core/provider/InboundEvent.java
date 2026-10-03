package com.corebanking.integration.core.provider;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A provider callback after its signature has been verified, in our own terms.
 *
 * @param eventId     the provider's id for this notification (or for the payment), unique per provider: the key
 *                    of the replay store
 * @param reference   our reference (payout reference, order reference, mandate reference), when the provider echoes it
 * @param providerRef the provider's id of the payout, payment or mandate
 * @param status      for PAYOUT: SENT, SUCCESS, FAILED, RETURNED; for PAYMENT: PAID, FAILED; for MANDATE: ACTIVE,
 *                    REJECTED, CANCELLED
 * @param occurredAt  when it happened at the provider (payment time), when given
 */
public record InboundEvent(String eventId, Kind kind, String reference, String providerRef, String status,
                           BigDecimal amount, String utr, String method, Instant occurredAt, String reasonCode,
                           String reason, String umrn) {

    public enum Kind { PAYOUT, PAYMENT, MANDATE }

    public InboundEvent {
        if (eventId == null || eventId.isBlank() || eventId.length() > 200) throw new WebhookRejectedException("the event has no usable id");
        if (kind == null || status == null || status.isBlank()) throw new WebhookRejectedException("the event has no kind or status");
    }
}
