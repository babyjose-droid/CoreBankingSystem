package com.corebanking.platform.control;

import com.corebanking.audit.AuditLog;
import com.corebanking.kernel.SupportAccess;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.TenantDataSources;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Time-boxed support access to a tenant (US-007, ADR-016).
 * <ul>
 *   <li>A platform engineer <b>asks</b> on the control plane: tenant, reason, ticket, duration (15 minutes to
 *       8 hours), scope READ_ONLY. The request is written into the tenant's own database
 *       ({@code platform.support_access}) and its audit trail, and indexed in the control plane.</li>
 *   <li>The <b>tenant's admin decides</b> inside the tenant API ({@code support-access:approve}): approve, reject,
 *       and revoke at any time. The database sets the expiry from the approved duration; nobody can extend it.</li>
 *   <li>What the engineer may then do is decided on every request by the request filter from that record
 *       ({@code TenantFilter}, {@link SupportAccess}); this service only keeps the record.</li>
 * </ul>
 * The tenant-side methods run on a request of a tenant user (routed data source, the tenant's transaction).
 */
@Service
public class SupportAccessService {

    /** openapi.yaml#/components/schemas/SupportAccessRequest. */
    public record Request(String tenant, String reason, String ticket, Integer durationMinutes, String scope) {}

    private static final String UTC = "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'";
    private static final String COLUMNS = "id::text, engineer, reason, ticket, scope, duration_minutes,"
            + " to_char(requested_at AT TIME ZONE 'UTC', " + UTC + "), effective_status, decided_by,"
            + " to_char(decided_at AT TIME ZONE 'UTC', " + UTC + "), decision_note, to_char(expires_at AT TIME ZONE 'UTC', " + UTC + "),"
            + " revoked_by, to_char(revoked_at AT TIME ZONE 'UTC', " + UTC + "), revoke_reason";

    private final JdbcTemplate control;
    private final JdbcTemplate jdbc;
    private final TenantDataSources dataSources;
    private final AuditLog audit;

    public SupportAccessService(@Qualifier("controlJdbc") JdbcTemplate control, JdbcTemplate jdbc, TenantDataSources dataSources, AuditLog audit) {
        this.control = control;
        this.jdbc = jdbc;
        this.dataSources = dataSources;
        this.audit = audit;
    }

    // ------------------------------------------------------------------------------------------------ control plane
    /** The engineer asks for access. Nothing is granted by this call. */
    public Map<String, Object> request(Request r) {
        CurrentUser engineer = CurrentUser.get();
        if (r.tenant() == null || !r.tenant().matches("[a-z][a-z0-9-]{2,30}")) throw ApiException.invalid("tenant is required");
        if (r.reason() == null || r.reason().trim().length() < 10 || r.reason().trim().length() > 500) {
            throw ApiException.invalid("reason is required: 10 to 500 characters saying what you need to look at and why");
        }
        if (r.ticket() == null || !r.ticket().matches("[A-Za-z0-9][A-Za-z0-9._/#-]{1,39}")) {
            throw ApiException.invalid("ticket is required: the support ticket this access is for");
        }
        int minutes = r.durationMinutes() == null ? 60 : r.durationMinutes();
        if (minutes < SupportAccess.MIN_MINUTES || minutes > SupportAccess.MAX_MINUTES) {
            throw ApiException.invalid("durationMinutes must be between " + SupportAccess.MIN_MINUTES + " and " + SupportAccess.MAX_MINUTES + " (8 hours)");
        }
        if (r.scope() != null && !SupportAccess.READ_ONLY.equals(r.scope())) throw ApiException.invalid("scope must be READ_ONLY");
        if (!engineer.login().matches("[A-Za-z0-9._@-]{2,80}")) throw ApiException.invalid("your user name cannot be used for support access");
        List<Map<String, Object>> tenant = control.queryForList("SELECT id, status FROM control.tenant WHERE code = ?", r.tenant());
        if (tenant.isEmpty() || !"ACTIVE".equals(tenant.get(0).get("status"))) throw ApiException.notFound("tenant " + r.tenant());
        JdbcTemplate tenantJdbc;
        try {
            tenantJdbc = new JdbcTemplate(dataSources.of(r.tenant()));
        } catch (IllegalStateException e) {
            throw ApiException.conflict("tenant " + r.tenant() + " is not served by this instance yet");
        }
        Integer waiting = tenantJdbc.queryForObject("""
                SELECT count(*)::int FROM platform.support_access
                 WHERE engineer_subject = ? AND status = 'REQUESTED' AND requested_at > now() - interval '24 hours'
                """, Integer.class, engineer.subject());
        if (waiting != null && waiting >= 3) {
            throw ApiException.conflict("you already have " + waiting + " requests waiting for this tenant's admin");
        }
        UUID id = UUID.randomUUID();
        String ticket = r.ticket();
        String reason = r.reason().trim();
        tenantJdbc.update("""
                INSERT INTO platform.support_access (id, engineer_subject, engineer, reason, ticket, duration_minutes)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, engineer.subject(), engineer.login(), reason, ticket, minutes);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("engineer", engineer.login());
        detail.put("ticket", ticket);
        detail.put("durationMinutes", minutes);
        detail.put("reason", reason);
        TenantDataSources.runAs(r.tenant(), () -> audit.record(SupportAccess.login(engineer.login()), "SUPPORT_ACCESS_REQUESTED",
                "SUPPORT_ACCESS", id.toString(), detail));
        control.update("INSERT INTO control.support_request (id, tenant_id, engineer_subject, engineer, ticket, duration_minutes) VALUES (?, ?, ?, ?, ?, ?)",
                id, tenant.get(0).get("id"), engineer.subject(), engineer.login(), ticket, minutes);
        control.update("INSERT INTO control.operator_action (tenant_id, operator, action, reason) VALUES (?, ?, 'SUPPORT_ACCESS_REQUEST', ?)",
                tenant.get(0).get("id"), engineer.login(), ticket + ": " + reason);
        Map<String, Object> view = one(tenantJdbc, id);
        view.put("tenant", r.tenant());
        return view;
    }

    /** The engineer's own requests, newest first, with their state as the tenant's database has it now. Operators see all. */
    public List<Map<String, Object>> requests(String tenant) {
        CurrentUser user = CurrentUser.get();
        boolean all = user.has("platform:operator");
        List<Map<String, Object>> index = control.queryForList("""
                SELECT r.id, t.code FROM control.support_request r JOIN control.tenant t ON t.id = r.tenant_id
                 WHERE (? OR r.engineer_subject = ?) AND (?::text IS NULL OR t.code = ?)
                 ORDER BY r.requested_at DESC LIMIT 100
                """, all, user.subject(), tenant, tenant);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : index) {
            String code = (String) row.get("code");
            Map<String, Object> view;
            try {
                view = one(new JdbcTemplate(dataSources.of(code)), (UUID) row.get("id"));
            } catch (RuntimeException e) {
                view = new LinkedHashMap<>();
                view.put("id", String.valueOf(row.get("id")));
                view.put("status", "UNKNOWN");
            }
            view.put("tenant", code);
            out.add(view);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ tenant side
    /** Requests and grants of this tenant, newest first; {@code status} filters on the effective status. */
    public List<Map<String, Object>> list(String status) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform.support_access_status WHERE (?::text IS NULL OR effective_status = ?)"
                + " ORDER BY requested_at DESC LIMIT 200", SupportAccessService::view, status, status);
    }

    @Transactional
    public Map<String, Object> approve(UUID id, String note) {
        String user = tenantUser();
        change(jdbc.update("UPDATE platform.support_access SET status = 'APPROVED', decided_by = ?, decision_note = ? WHERE id = ? AND status = 'REQUESTED'",
                user, blankToNull(note), id), id, "approved");
        return decided(id, user, "SUPPORT_ACCESS_APPROVED", note);
    }

    @Transactional
    public Map<String, Object> reject(UUID id, String note) {
        if (note == null || note.isBlank()) throw ApiException.invalid("a note is required to reject a request");
        String user = tenantUser();
        change(jdbc.update("UPDATE platform.support_access SET status = 'REJECTED', decided_by = ?, decision_note = ? WHERE id = ? AND status = 'REQUESTED'",
                user, note.trim(), id), id, "rejected");
        return decided(id, user, "SUPPORT_ACCESS_REJECTED", note);
    }

    @Transactional
    public Map<String, Object> revoke(UUID id, String reason) {
        String user = tenantUser();
        change(jdbc.update("UPDATE platform.support_access SET status = 'REVOKED', revoked_by = ?, revoke_reason = ? WHERE id = ? AND status = 'APPROVED'",
                user, blankToNull(reason), id), id, "revoked");
        return decided(id, user, "SUPPORT_ACCESS_REVOKED", reason);
    }

    /** Only a tenant user decides; a support engineer reading the tenant can never approve access. */
    private static String tenantUser() {
        CurrentUser user = CurrentUser.get();
        if (user.isSupport() || user.isSystem()) throw ApiException.forbidden("support access is decided by a tenant user");
        return user.login();
    }

    private void change(int rows, UUID id, String verb) {
        if (rows == 1) return;
        List<String> status = jdbc.queryForList("SELECT effective_status FROM platform.support_access_status WHERE id = ?", String.class, id);
        if (status.isEmpty()) throw ApiException.notFound("support access " + id);
        throw ApiException.conflict("support access " + id + " is " + status.get(0) + " and cannot be " + verb);
    }

    private Map<String, Object> decided(UUID id, String user, String action, String note) {
        Map<String, Object> view = one(jdbc, id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("engineer", view.get("engineer"));
        detail.put("ticket", view.get("ticket"));
        detail.put("expiresAt", view.get("expiresAt"));
        detail.put("note", blankToNull(note));
        audit.record(user, action, "SUPPORT_ACCESS", id.toString(), detail);
        return view;
    }

    private static Map<String, Object> one(JdbcTemplate jdbc, UUID id) {
        List<Map<String, Object>> l = jdbc.query("SELECT " + COLUMNS + " FROM platform.support_access_status WHERE id = ?",
                SupportAccessService::view, id);
        if (l.isEmpty()) throw ApiException.notFound("support access " + id);
        return l.get(0);
    }

    /** openapi.yaml#/components/schemas/SupportAccess. */
    private static Map<String, Object> view(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString(1));
        m.put("engineer", rs.getString(2));
        m.put("reason", rs.getString(3));
        m.put("ticket", rs.getString(4));
        m.put("scope", rs.getString(5));
        m.put("durationMinutes", rs.getInt(6));
        m.put("requestedAt", rs.getString(7));
        m.put("status", rs.getString(8));
        m.put("decidedBy", rs.getString(9));
        m.put("decidedAt", rs.getString(10));
        m.put("decisionNote", rs.getString(11));
        m.put("expiresAt", rs.getString(12));
        m.put("revokedBy", rs.getString(13));
        m.put("revokedAt", rs.getString(14));
        m.put("revokeReason", rs.getString(15));
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
