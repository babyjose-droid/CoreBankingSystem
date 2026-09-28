package com.corebanking.audit;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tamper-evident audit trail (US-025). Rows are hash-chained by the database (V6); this service only appends
 * and reads. Detail must never contain unmasked personal data.
 */
@Service
public class AuditLog {

    public record Event(long id, OffsetDateTime at, String actor, String action, String entityType, String entityId,
                        String detailJson) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public AuditLog(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(String actor, String action, String entityType, String entityId, Map<String, ?> detail) {
        jdbc.update("INSERT INTO audit.event (actor, action, entity_type, entity_id, detail) VALUES (?, ?, ?, ?, ?::jsonb)",
                actor, action, entityType, entityId, detail == null ? null : mapper.writeValueAsString(detail));
    }

    public List<Event> recent(String entityType, String entityId, int limit) {
        return jdbc.query("""
                SELECT id, at, actor, action, entity_type, entity_id, detail::text FROM audit.event
                 WHERE (?::text IS NULL OR entity_type = ?) AND (?::text IS NULL OR entity_id = ?)
                 ORDER BY id DESC LIMIT ?
                """, (rs, i) -> new Event(rs.getLong(1),
                        rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant().atOffset(ZoneOffset.UTC),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                entityType, entityType, entityId, entityId, Math.min(Math.max(limit, 1), 1000));
    }

    /** @return id of the first row whose hash does not verify, or null when the chain is intact */
    public Long verifyChain() {
        return jdbc.queryForObject("SELECT audit.verify_chain()", Long.class);
    }

}
