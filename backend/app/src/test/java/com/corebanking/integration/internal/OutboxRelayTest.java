package com.corebanking.integration.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class OutboxRelayTest {

    @Test
    void a_database_failure_is_recorded_with_its_sqlstate_and_never_its_message() {
        var sql = new SQLException("ERROR: operator does not exist: text[] @> character varying[] CLAUDE-TEST-PAYLOAD", "42883");
        assertEquals("DataIntegrityViolationException 42883",
                OutboxRelay.describe(new DataIntegrityViolationException("statement and data", sql)));
    }

    @Test
    void other_failures_are_recorded_by_class_only() {
        assertEquals("IllegalStateException", OutboxRelay.describe(new IllegalStateException("CLAUDE-TEST-PAYLOAD")));
        assertEquals("IllegalStateException", OutboxRelay.describe(new IllegalStateException(new SQLException("no state"))));
    }
}
