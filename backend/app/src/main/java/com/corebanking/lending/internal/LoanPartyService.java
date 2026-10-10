package com.corebanking.lending.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.ApprovalView;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release of a co-applicant or guarantor from a loan (V26). The release goes through maker-checker (entity
 * LOAN_PARTY_RELEASE): one checker, or two when the loan is stressed - in an SMA or NPA class - because releasing a
 * guarantor from a loan that is going bad weakens the lender's recovery. It takes effect on the business date of the
 * approval: from then the party is out of customer.exposure and of joint reporting. The row is kept, with who released
 * the party, when and why. The borrower cannot be released.
 */
@Service
public class LoanPartyService {

    static final String ENTITY = "LOAN_PARTY_RELEASE";
    /** Asset classes that count as stressed: any class other than STANDARD. */
    static final Set<String> SETTLED_CLASSES = Set.of("STANDARD");

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final BusinessDays days;
    private final AuditLog audit;

    LoanPartyService(JdbcTemplate jdbc, ApprovalService approvals, BusinessDays days, AuditLog audit) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.days = days;
        this.audit = audit;
    }

    /** The approval action: two checkers for a stressed loan, one otherwise (rows of platform.approval_rule, V26). */
    static String actionFor(String assetClass) {
        return SETTLED_CLASSES.contains(assetClass) ? "RELEASE" : "RELEASE_STRESSED";
    }

    @Transactional
    public Map<String, Object> propose(UUID loanId, UUID partyId, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.invalid("a reason is required");
        if (reason.length() > 500) throw ApiException.invalid("reason is at most 500 characters");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.loan_no, l.status, l.asset_class, p.role, p.released_on, c.customer_no
                  FROM lending.loan_account l
                  JOIN lending.loan_party p ON p.loan_id = l.id AND p.customer_id = ?
                  JOIN customer.customer c ON c.id = p.customer_id
                 WHERE l.id = ?
                """, partyId, loanId);
        if (rows.isEmpty()) throw ApiException.notFound("party " + partyId + " on loan " + loanId);
        Map<String, Object> r = rows.get(0);
        String role = String.valueOf(r.get("role"));
        if ("BORROWER".equals(role)) throw ApiException.invalid("the borrower cannot be released from the loan");
        if (r.get("released_on") != null) throw ApiException.conflict("this party was already released on " + r.get("released_on"));
        String status = String.valueOf(r.get("status"));
        if (!Set.of("SANCTIONED", "ACTIVE", "FROZEN").contains(status)) {
            throw ApiException.conflict("loan " + r.get("loan_no") + " is " + status + ": a party can be released from a sanctioned or running loan only");
        }
        String loanNo = String.valueOf(r.get("loan_no"));
        String key = loanNo + "/" + r.get("customer_no");
        if (!jdbc.queryForList("SELECT 1 FROM platform.approval_request WHERE entity_type = ? AND entity_id = ? AND status = 'PENDING'", ENTITY, key).isEmpty()) {
            throw ApiException.conflict("a release of this party is already awaiting approval");
        }
        String assetClass = String.valueOf(r.get("asset_class"));
        String action = actionFor(assetClass);
        LocalDate bd = days.current().businessDate();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loanId.toString());
        payload.put("loanNo", loanNo);
        payload.put("customerId", partyId.toString());
        payload.put("customerNo", r.get("customer_no"));
        payload.put("role", role);
        payload.put("reason", reason.trim());
        payload.put("assetClass", assetClass);
        payload.put("proposedOn", bd.toString());
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("role", role);
        current.put("releasedOn", null);
        return ApprovalView.of(approvals.propose(ENTITY, action, key, payload, current, null, branchOf(loanId), null));
    }

    private String branchOf(UUID loanId) {
        List<String> b = jdbc.queryForList("SELECT branch_code FROM lending.loan_account WHERE id = ?", String.class, loanId);
        return b.isEmpty() ? null : b.get(0);
    }

    /** Applied by the last checker. Effective on the business date (the day must be open, like every posting change). */
    String apply(ApprovalRequest r) {
        LocalDate bd = days.requireOpen();
        UUID loanId = UUID.fromString(String.valueOf(r.payload().get("loanId")));
        UUID partyId = UUID.fromString(String.valueOf(r.payload().get("customerId")));
        String reason = String.valueOf(r.payload().get("reason"));
        int n = jdbc.update("""
                UPDATE lending.loan_party SET released_on = ?, released_by = ?, release_reason = ?, release_approval_id = ?
                 WHERE loan_id = ? AND customer_id = ? AND released_on IS NULL AND role <> 'BORROWER'
                """, bd, r.maker(), reason, r.id(), loanId, partyId);
        if (n == 0) throw ApiException.conflict("the party was already released, or is the borrower");
        audit.record(CurrentUser.username(), "LOAN_PARTY_RELEASED", "LOAN", String.valueOf(r.payload().get("loanNo")),
                Map.of("customerNo", String.valueOf(r.payload().get("customerNo")), "role", String.valueOf(r.payload().get("role")),
                        "releasedOn", bd.toString(), "approvalId", r.id().toString()));
        return r.entityId();
    }

    @Service
    static class Applier implements ApprovalApplier {
        private final LoanPartyService parties;

        Applier(@Lazy LoanPartyService parties) {
            this.parties = parties;
        }

        @Override public String entityType() { return ENTITY; }

        @Override public String apply(ApprovalRequest r) { return parties.apply(r); }
    }
}
