package com.corebanking.customer.web;

import com.corebanking.audit.AuditLog;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.CurrentUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent and purpose records under the Digital Personal Data Protection Act 2023 (US-036).
 * <ul>
 *   <li>One record per customer and purpose: the lawful basis (CONSENT, s.6, or LEGITIMATE_USE, s.7), the notice
 *       version shown (s.5), the channel and an evidence reference, when it was given and when it lapses.</li>
 *   <li>A record is never edited or deleted. A withdrawal (s.6(4)) is recorded on the grant and in the
 *       append-only history, and takes effect at once.</li>
 *   <li>Withdrawing a purpose the lender needs to service a loan, while the customer is party to one, is recorded
 *       and flagged {@code retainedForLegalObligation}: the consent is gone, but the data is kept and used as far
 *       as law requires (s.6(6) leaves processing required or authorised by law in place, s.8(7) allows retention
 *       needed to comply with law — RBI and PMLA record-keeping, credit-information reporting). The database
 *       decides the flag.</li>
 * </ul>
 * This is the product's interpretation, to be confirmed by the lender's counsel; see docs/phase2-status.md.
 */
@Service
class ConsentService {

    static final Set<String> BASES = Set.of("CONSENT", "LEGITIMATE_USE");
    static final Set<String> CHANNELS = Set.of("BRANCH", "WEB", "MOBILE_APP", "API", "PAPER", "CALL_CENTRE");

    /** openapi.yaml#/components/schemas/ConsentInput. */
    record Input(String purpose, String lawfulBasis, String noticeVersion, String channel, String evidenceRef,
                 OffsetDateTime grantedAt, OffsetDateTime expiresAt) {}
    record Withdrawal(String reason) {}

    private final JdbcTemplate jdbc;
    private final CustomerService customers;
    private final AuditLog audit;

    ConsentService(JdbcTemplate jdbc, CustomerService customers, AuditLog audit) {
        this.jdbc = jdbc;
        this.customers = customers;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT id, customer_id, purpose, lawful_basis, notice_version, channel, evidence_ref, granted_at, expires_at,
                   withdrawn_at, withdrawal_reason, withdrawn_by, retained_for_legal_obligation, recorded_by, recorded_at,
                   CASE WHEN withdrawn_at IS NOT NULL THEN 'WITHDRAWN'
                        WHEN expires_at IS NOT NULL AND expires_at <= now() THEN 'EXPIRED' ELSE 'ACTIVE' END AS status
              FROM customer.consent""";

    List<Map<String, Object>> list(UUID customerId) {
        customers.visibleBranch(customerId);
        return jdbc.query(SELECT + " WHERE customer_id = ? ORDER BY granted_at DESC, id", ConsentService::row, customerId);
    }

    @Transactional
    Map<String, Object> record(UUID customerId, Input in) {
        customers.visibleBranch(customerId);
        if (in == null || in.purpose() == null || in.purpose().isBlank()) throw ApiException.invalid("purpose is required");
        String purpose = in.purpose().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (jdbc.queryForList("SELECT 1 FROM platform.enumeration WHERE enum_type = 'consent-purpose' AND code = ? AND active", purpose).isEmpty()) {
            throw ApiException.invalid("purpose " + in.purpose() + " is not an active consent purpose (enumeration consent-purpose)");
        }
        if (in.lawfulBasis() == null || !BASES.contains(in.lawfulBasis())) throw ApiException.invalid("lawfulBasis must be CONSENT or LEGITIMATE_USE");
        if (in.noticeVersion() == null || in.noticeVersion().isBlank()) throw ApiException.invalid("noticeVersion (the notice shown to the customer) is required");
        if (in.channel() == null || !CHANNELS.contains(in.channel())) {
            throw ApiException.invalid("channel must be one of " + CHANNELS.stream().sorted().toList());
        }
        if ("CONSENT".equals(in.lawfulBasis()) && (in.evidenceRef() == null || in.evidenceRef().isBlank())) {
            throw ApiException.invalid("evidenceRef is required for a consent: the reference that proves it was given (OTP, e-sign, form scan)");
        }
        OffsetDateTime granted = in.grantedAt() == null ? OffsetDateTime.now() : in.grantedAt();
        if (granted.isAfter(OffsetDateTime.now().plusMinutes(5))) throw ApiException.invalid("grantedAt cannot be in the future");
        if (in.expiresAt() != null && !in.expiresAt().isAfter(granted)) throw ApiException.invalid("expiresAt must be after grantedAt");
        if (!jdbc.queryForList("""
                SELECT 1 FROM customer.consent
                 WHERE customer_id = ? AND purpose = ? AND withdrawn_at IS NULL AND (expires_at IS NULL OR expires_at > now())
                """, customerId, purpose).isEmpty()) {
            throw ApiException.conflict("a record for " + purpose + " is already in force for this customer");
        }
        UUID id = UUID.randomUUID();
        String user = CurrentUser.username();
        jdbc.update("""
                INSERT INTO customer.consent (id, customer_id, purpose, lawful_basis, notice_version, channel, evidence_ref,
                                              granted_at, expires_at, recorded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, customerId, purpose, in.lawfulBasis(), in.noticeVersion().trim(), in.channel(),
                in.evidenceRef() == null || in.evidenceRef().isBlank() ? null : in.evidenceRef().trim(), granted, in.expiresAt(), user);
        audit.record(user, "CONSENT_RECORD", "CUSTOMER", customerId.toString(),
                Map.of("consentId", id.toString(), "purpose", purpose, "lawfulBasis", in.lawfulBasis()));
        return get(customerId, id);
    }

    @Transactional
    Map<String, Object> withdraw(UUID customerId, UUID consentId, Withdrawal w) {
        customers.visibleBranch(customerId);
        if (w == null || w.reason() == null || w.reason().isBlank()) throw ApiException.invalid("a reason is required");
        List<Map<String, Object>> cur = jdbc.queryForList(
                "SELECT lawful_basis, withdrawn_at FROM customer.consent WHERE id = ? AND customer_id = ? FOR UPDATE", consentId, customerId);
        if (cur.isEmpty()) throw ApiException.notFound("consent " + consentId);
        if (cur.get(0).get("withdrawn_at") != null) throw ApiException.conflict("this consent is already withdrawn");
        if (!"CONSENT".equals(cur.get(0).get("lawful_basis"))) {
            throw ApiException.conflict("a legitimate-use record is not a consent and cannot be withdrawn");
        }
        String user = CurrentUser.username();
        jdbc.update("UPDATE customer.consent SET withdrawn_at = now(), withdrawal_reason = ?, withdrawn_by = ? WHERE id = ?",
                w.reason().trim(), user, consentId);
        Map<String, Object> after = get(customerId, consentId);
        audit.record(user, "CONSENT_WITHDRAW", "CUSTOMER", customerId.toString(), Map.of("consentId", consentId.toString(),
                "purpose", String.valueOf(after.get("purpose")),
                "retainedForLegalObligation", String.valueOf(after.get("retainedForLegalObligation"))));
        return after;
    }

    private Map<String, Object> get(UUID customerId, UUID consentId) {
        List<Map<String, Object>> l = jdbc.query(SELECT + " WHERE id = ? AND customer_id = ?", ConsentService::row, consentId, customerId);
        if (l.isEmpty()) throw ApiException.notFound("consent " + consentId);
        return l.get(0);
    }

    private static Map<String, Object> row(ResultSet rs, int i) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id", UUID.class));
        m.put("customerId", rs.getObject("customer_id", UUID.class));
        m.put("purpose", rs.getString("purpose"));
        m.put("lawfulBasis", rs.getString("lawful_basis"));
        m.put("noticeVersion", rs.getString("notice_version"));
        m.put("channel", rs.getString("channel"));
        m.put("evidenceRef", rs.getString("evidence_ref"));
        m.put("grantedAt", rs.getObject("granted_at", OffsetDateTime.class));
        m.put("expiresAt", rs.getObject("expires_at", OffsetDateTime.class));
        m.put("status", rs.getString("status"));
        m.put("withdrawnAt", rs.getObject("withdrawn_at", OffsetDateTime.class));
        m.put("withdrawalReason", rs.getString("withdrawal_reason"));
        m.put("withdrawnBy", rs.getString("withdrawn_by"));
        m.put("retainedForLegalObligation", rs.getBoolean("retained_for_legal_obligation"));
        m.put("recordedBy", rs.getString("recorded_by"));
        m.put("recordedAt", rs.getObject("recorded_at", OffsetDateTime.class));
        return m;
    }
}
