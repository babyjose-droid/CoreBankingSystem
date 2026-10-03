package com.corebanking.platform;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The transactional outbox (ADR-008). A module that changes something other systems care about writes the event
 * here <b>with the same connection and in the same transaction</b> as the change, so the event exists if and only
 * if the change was committed. The integration module's relay reads the rows in order and fans them out (signed
 * webhooks, messages, payouts); delivery is at least once, so consumers are idempotent.
 * <p>
 * Payloads carry identifiers and amounts, never personal data: no names, PAN, Aadhaar, mobile numbers or bank
 * account numbers.
 */
@Component
public class Outbox {

    private final Json json;

    public Outbox(Json json) {
        this.json = json;
    }

    /**
     * @param jdbc        the JdbcTemplate of the surrounding transaction (request thread: the routed one; end of
     *                    day: the tenant's own)
     * @param topic       event type, e.g. {@code loan.disbursed}
     * @param aggregateId what the event is about, e.g. the loan number
     */
    public void publish(JdbcTemplate jdbc, String topic, String aggregateId, Map<String, ?> payload) {
        jdbc.update("INSERT INTO platform.outbox (topic, aggregate_id, payload) VALUES (?, ?, ?::jsonb)",
                topic, aggregateId, json.write(payload));
    }
}
