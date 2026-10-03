package com.corebanking.integration.internal;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Something that reacts to an outbox event. Called by {@link OutboxRelay} inside the transaction that marks the
 * event as published, so a consumer only writes to the database (a delivery row, a payout instruction, a queued
 * message) and never calls another system. A consumer must tolerate seeing an event twice.
 */
interface OutboxConsumer {

    void on(long outboxId, String topic, String aggregateId, Map<String, Object> payload, OffsetDateTime at);
}
