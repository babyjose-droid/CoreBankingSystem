package com.corebanking.platform;

import com.corebanking.audit.AuditLog;
import com.corebanking.kernel.ApprovalPolicy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generic maker-checker (US-022, US-023). Modules call {@link #propose}; a checker calls {@link #approve}, which
 * replays the change through the module's {@link ApprovalApplier} in the same transaction. The database
 * enforces maker ≠ checker, one decision per checker and no decisions on closed requests (V8). An approval of a
 * disbursement, waiver or voucher is refused when its amount is above the checker's role amount limit (US-021).
 */
@Service
public class ApprovalService {

    private final JdbcTemplate jdbc;
    private final Json json;
    private final AuditLog audit;
    private final Map<String, ApprovalApplier> appliers;
    private final AmountLimitService limits;

    public ApprovalService(JdbcTemplate jdbc, Json json, AuditLog audit, List<ApprovalApplier> appliers,
                           AmountLimitService limits) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.limits = limits;
        this.appliers = appliers.stream().collect(Collectors.toMap(ApprovalApplier::entityType, Function.identity()));
    }

    /** Records a proposed change. Returns the existing request when the maker repeats an idempotency key. */
    @Transactional
    public ApprovalRequest propose(String entityType, String action, String entityId, Map<String, Object> payload,
                                   Map<String, Object> currentState, BigDecimal amount, String branchCode,
                                   String idempotencyKey) {
        if (!appliers.containsKey(entityType)) throw new IllegalStateException("no applier for " + entityType);
        String maker = CurrentUser.username();
        if (idempotencyKey != null) {
            List<UUID> existing = jdbc.queryForList(
                    "SELECT id FROM platform.approval_request WHERE maker = ? AND idempotency_key = ?",
                    UUID.class, maker, idempotencyKey);
            if (!existing.isEmpty()) return get(existing.get(0));
        }
        int checkers = policy().checkersRequired(entityType, action, amount);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO platform.approval_request
                      (id, entity_type, entity_id, action, payload, current_state, maker, branch_code, amount,
                       checkers_required, idempotency_key)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                    """, id, entityType, entityId, action, json.write(payload), json.write(currentState), maker,
                    branchCode, amount, checkers, idempotencyKey);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("a request with this Idempotency-Key already exists");
        }
        audit.record(maker, "PROPOSE", entityType, entityId == null ? id.toString() : entityId,
                Map.of("approvalId", id.toString(), "action", action));
        ApprovalRequest request = get(id);
        if (checkers == 0) return applyNow(request, maker, "auto-approved by rule");
        return request;
    }

    @Transactional
    public ApprovalRequest approve(UUID id, String note) {
        ApprovalRequest r = lockPending(id);
        String checker = CurrentUser.username();
        Set<String> prior = new HashSet<>(jdbc.queryForList(
                "SELECT checker FROM platform.approval_decision WHERE request_id = ? AND decision = 'APPROVE'", String.class, id));
        ApprovalPolicy.Outcome outcome;
        try {
            outcome = ApprovalPolicy.approve(r.maker(), prior, checker, r.checkersRequired());
        } catch (ApprovalPolicy.ApprovalException e) {
            throw ApiException.conflict(e.getMessage());
        }
        // Role amount limit of the checker (US-021): every approval counts, also the first of two.
        String limitType = AmountLimitService.typeOf(r);
        if (limitType != null) limits.require(limitType, AmountLimitService.APPROVE, r.amount(), id.toString());
        jdbc.update("INSERT INTO platform.approval_decision (request_id, checker, decision, note) VALUES (?, ?, 'APPROVE', ?)",
                id, checker, note);
        if (outcome == ApprovalPolicy.Outcome.NEED_MORE_APPROVALS) {
            audit.record(checker, "APPROVE_PARTIAL", r.entityType(), r.entityId(), Map.of("approvalId", id.toString()));
            return get(id);
        }
        return applyNow(r, checker, note);
    }

    @Transactional
    public ApprovalRequest reject(UUID id, String note) {
        ApprovalRequest r = lockPending(id);
        String checker = CurrentUser.username();
        try {
            ApprovalPolicy.reject(r.maker(), checker, note);
        } catch (ApprovalPolicy.ApprovalException e) {
            throw ApiException.conflict(e.getMessage());
        }
        jdbc.update("INSERT INTO platform.approval_decision (request_id, checker, decision, note) VALUES (?, ?, 'REJECT', ?)",
                id, checker, note);
        jdbc.update("UPDATE platform.approval_request SET status = 'REJECTED', checker = ?, checked_at = now(), checker_note = ? WHERE id = ?",
                checker, note, id);
        audit.record(checker, "REJECT", r.entityType(), r.entityId(), Map.of("approvalId", id.toString(), "note", note));
        return get(id);
    }

    private ApprovalRequest applyNow(ApprovalRequest r, String checker, String note) {
        String ref = appliers.get(r.entityType()).apply(r);
        jdbc.update("""
                UPDATE platform.approval_request
                   SET status = 'APPROVED', checker = ?, checked_at = now(), checker_note = ?, applied_ref = ?
                 WHERE id = ?
                """, checker, note, ref, r.id());
        audit.record(checker, "APPROVE", r.entityType(), ref == null ? r.entityId() : ref,
                Map.of("approvalId", r.id().toString(), "action", r.action()));
        return get(r.id());
    }

    private ApprovalRequest lockPending(UUID id) {
        List<String> status = jdbc.queryForList("SELECT status FROM platform.approval_request WHERE id = ? FOR UPDATE", String.class, id);
        if (status.isEmpty()) throw ApiException.notFound("approval " + id);
        if (!"PENDING".equals(status.get(0))) throw ApiException.conflict("approval is already " + status.get(0));
        return get(id);
    }

    public ApprovalRequest get(UUID id) {
        List<ApprovalRequest> l = jdbc.query("SELECT * FROM platform.approval_queue WHERE id = ?", this::map, id);
        if (l.isEmpty()) throw ApiException.notFound("approval " + id);
        return l.get(0);
    }

    public List<ApprovalRequest> list(String status, String entityType) {
        return jdbc.query("""
                SELECT * FROM platform.approval_queue
                 WHERE (?::text IS NULL OR status = ?) AND (?::text IS NULL OR entity_type = ?)
                 ORDER BY made_at DESC LIMIT 500
                """, this::map, status, status, entityType, entityType);
    }

    private ApprovalPolicy policy() {
        List<ApprovalPolicy.Rule> rules = jdbc.query(
                "SELECT entity_type, action, min_amount, checkers_required FROM platform.approval_rule",
                (rs, i) -> new ApprovalPolicy.Rule(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getInt(4)));
        return new ApprovalPolicy(rules);
    }

    private ApprovalRequest map(ResultSet rs, int i) throws SQLException {
        return new ApprovalRequest(
                rs.getObject("id", UUID.class), rs.getString("entity_type"), rs.getString("entity_id"),
                rs.getString("action"), orEmpty(json.readMap(rs.getString("payload"))),
                json.readMap(rs.getString("current_state")), rs.getString("maker"),
                rs.getObject("made_at", OffsetDateTime.class), rs.getString("branch_code"), rs.getBigDecimal("amount"),
                rs.getInt("checkers_required"), rs.getString("status"), rs.getString("checker"),
                rs.getObject("checked_at", OffsetDateTime.class), rs.getString("checker_note"),
                rs.getString("applied_ref"), rs.getDouble("age_hours"), rs.getInt("approvals_so_far"));
    }

    private static Map<String, Object> orEmpty(Map<String, Object> m) {
        return m == null ? new HashMap<>() : m;
    }
}
