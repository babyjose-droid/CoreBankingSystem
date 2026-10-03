package com.corebanking.integration.internal;

import com.corebanking.platform.Json;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox relay (ADR-008). Events are taken in order, one transaction each: every {@link OutboxConsumer} writes
 * what follows from the event, and the event is marked published in the same transaction — so the fan-out happens
 * exactly once per event even if the relay crashes or runs on two instances.
 * <p>
 * SQS is not in between yet (no AWS SDK on the classpath): the relay hands events to the consumers in process.
 * An event that cannot be fanned out is retried on later ticks and holds back the events behind it, to keep the
 * order; after {@value #MAX_ATTEMPTS} attempts it is marked as skipped with its error, and logged, so that one bad
 * event cannot stop everything.
 */
@Component
class OutboxRelay implements IntegrationTask {

    static final int MAX_ATTEMPTS = 10;
    private static final int BATCH = 200;
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long id, String topic, String aggregateId, String payload, OffsetDateTime at) {}

    private final JdbcTemplate jdbc;
    private final Json json;
    private final List<OutboxConsumer> consumers;
    private final TransactionTemplate tx;

    OutboxRelay(JdbcTemplate jdbc, Json json, List<OutboxConsumer> consumers, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.consumers = consumers;
        this.tx = new TransactionTemplate(manager);
    }

    @Override public String name() { return "outbox-relay"; }

    @Override
    public void run() {
        for (int i = 0; i < BATCH; i++) {
            long[] failed = new long[] {0};
            Boolean more;
            try {
                more = tx.execute(status -> {
                    List<Row> rows = jdbc.query("""
                            SELECT id, topic, aggregate_id, payload::text, created_at FROM platform.outbox
                             WHERE published_at IS NULL ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                            """, (rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                    rs.getObject(5, OffsetDateTime.class)));
                    if (rows.isEmpty()) return Boolean.FALSE;
                    Row r = rows.get(0);
                    failed[0] = r.id();
                    Map<String, Object> payload = json.readMap(r.payload());
                    for (OutboxConsumer c : consumers) c.on(r.id(), r.topic(), r.aggregateId(), payload, r.at());
                    jdbc.update("UPDATE platform.outbox SET published_at = now() WHERE id = ?", r.id());
                    failed[0] = 0;
                    return Boolean.TRUE;
                });
            } catch (RuntimeException e) {
                if (failed[0] != 0) recordFailure(failed[0], e);
                return;                                    // keep the order: try this event again on the next tick
            }
            if (!Boolean.TRUE.equals(more)) return;
        }
    }

    private void recordFailure(long id, RuntimeException e) {
        String error = e.getClass().getSimpleName();
        jdbc.update("""
                UPDATE platform.outbox
                   SET relay_attempts = relay_attempts + 1, relay_error = ?,
                       published_at = CASE WHEN relay_attempts + 1 >= ? THEN now() ELSE published_at END
                 WHERE id = ? AND published_at IS NULL
                """, error, MAX_ATTEMPTS, id);
        log.error("outbox event {} could not be relayed ({}); it is skipped after {} attempts", id, error, MAX_ATTEMPTS);
    }
}
