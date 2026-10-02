package com.corebanking.customer.web;

import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Associates and customer exposure (US-034): customer-level relationships (co-applicant, guarantor, nominee,
 * authorised signatory), the exposure view by role and the exposure limit. Changes go through maker-checker;
 * the rules themselves live in the database (V17) and are repeated here only to give the maker a clear 422.
 */
@Service
class AssociatesService {

    static final Set<String> TYPES = Set.of("CO_APPLICANT", "GUARANTOR", "NOMINEE", "AUTHORISED_SIGNATORY");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** openapi.yaml#/components/schemas/RelationshipInput. */
    record RelationshipInput(UUID relatedCustomerId, String relationType, UUID loanId, BigDecimal sharePercent) {}
    record RelationshipsRequest(List<RelationshipInput> relationships) {}
    record ExposureLimitInput(BigDecimal exposureLimit, String reason) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final CustomerService customers;
    private final BranchScope scope;

    AssociatesService(JdbcTemplate jdbc, ApprovalService approvals, CustomerService customers, BranchScope scope) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.customers = customers;
        this.scope = scope;
    }

    /**
     * Relationships the customer holds ("OUTGOING": their guarantors, nominees …) and those others hold with
     * them ("INCOMING": whom they guarantee). The other party's name is shown only inside the caller's branch scope.
     */
    List<Map<String, Object>> relationships(UUID customerId) {
        customers.visibleBranch(customerId);
        String u = scope.user();
        return jdbc.queryForList("""
                SELECT r.id, 'OUTGOING' AS direction, r.relation_type AS "relationType", o.id AS "relatedCustomerId",
                       o.customer_no AS "relatedCustomerNo",
                       CASE WHEN o.home_branch IN (SELECT branch_code FROM platform.visible_branches(?)) THEN o.display_name END AS "relatedCustomerName",
                       r.loan_id AS "loanId", r.share_percent::text AS "sharePercent", r.status, r.created_by AS "createdBy",
                       r.created_at AS "createdAt", r.ended_at AS "endedAt"
                  FROM customer.relationship r JOIN customer.customer o ON o.id = r.related_customer_id
                 WHERE r.customer_id = ?
                UNION ALL
                SELECT r.id, 'INCOMING', r.relation_type, o.id, o.customer_no,
                       CASE WHEN o.home_branch IN (SELECT branch_code FROM platform.visible_branches(?)) THEN o.display_name END,
                       r.loan_id, r.share_percent::text, r.status, r.created_by, r.created_at, r.ended_at
                  FROM customer.relationship r JOIN customer.customer o ON o.id = r.customer_id
                 WHERE r.related_customer_id = ?
                 ORDER BY 2 DESC, 9, 3, 5
                """, u, customerId, u, customerId);
    }

    /**
     * Proposes one or more relationships. Nominees are given as a complete set per account: on approval the set
     * replaces the account's current nominees, and its shares must total 100.
     */
    @Transactional
    ApprovalRequest propose(UUID customerId, RelationshipsRequest request, String idempotencyKey) {
        String branch = customers.visibleBranch(customerId);
        if (request == null || request.relationships() == null || request.relationships().isEmpty() || request.relationships().size() > 20) {
            throw ApiException.invalid("send 1 to 20 relationships");
        }
        Map<String, Object> self = jdbc.queryForMap("SELECT customer_no, customer_type FROM customer.customer WHERE id = ?", customerId);
        Map<String, BigDecimal> nomineeTotals = new HashMap<>();
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RelationshipInput in : request.relationships()) {
            if (in == null || in.relatedCustomerId() == null) throw ApiException.invalid("relatedCustomerId is required");
            if (in.relationType() == null || !TYPES.contains(in.relationType())) {
                throw ApiException.invalid("relationType must be CO_APPLICANT, GUARANTOR, NOMINEE or AUTHORISED_SIGNATORY");
            }
            if (in.relatedCustomerId().equals(customerId)) throw ApiException.invalid("a customer cannot be related to themselves");
            List<Map<String, Object>> related = jdbc.queryForList(
                    "SELECT customer_no, customer_type, status FROM customer.customer WHERE id = ?", in.relatedCustomerId());
            if (related.isEmpty()) throw ApiException.invalid("related customer " + in.relatedCustomerId() + " not found");
            String relatedNo = (String) related.get(0).get("customer_no");
            if (!"ACTIVE".equals(related.get(0).get("status"))) {
                throw ApiException.invalid("customer " + relatedNo + " is " + related.get(0).get("status") + "; only ACTIVE customers can be linked");
            }
            boolean nominee = "NOMINEE".equals(in.relationType());
            if (nominee != (in.sharePercent() != null)) throw ApiException.invalid("sharePercent is required for a nominee and only for a nominee");
            if (!nominee && in.loanId() != null) throw ApiException.invalid("loanId applies only to a nomination");
            if (nominee) {
                if (in.sharePercent().signum() <= 0 || in.sharePercent().compareTo(HUNDRED) > 0 || in.sharePercent().scale() > 2) {
                    throw ApiException.invalid("sharePercent must be above 0 and at most 100, with up to two decimals");
                }
                if (in.loanId() != null && jdbc.queryForList(
                        "SELECT 1 FROM lending.loan_account WHERE id = ? AND customer_id = ?", in.loanId(), customerId).isEmpty()) {
                    throw ApiException.invalid("the account named in a nomination must belong to the customer");
                }
                nomineeTotals.merge(String.valueOf(in.loanId()), in.sharePercent(), BigDecimal::add);
            } else if (!jdbc.queryForList("""
                    SELECT 1 FROM customer.relationship
                     WHERE customer_id = ? AND related_customer_id = ? AND relation_type = ? AND status = 'ACTIVE'
                    """, customerId, in.relatedCustomerId(), in.relationType()).isEmpty()) {
                throw ApiException.conflict("customer " + relatedNo + " is already " + in.relationType() + " of this customer");
            }
            if ("AUTHORISED_SIGNATORY".equals(in.relationType())
                    && (!"NON_INDIVIDUAL".equals(self.get("customer_type")) || !"INDIVIDUAL".equals(related.get(0).get("customer_type")))) {
                throw ApiException.invalid("an authorised signatory is an individual acting for a non-individual customer");
            }
            if (!seen.add(in.relatedCustomerId() + "|" + in.relationType() + "|" + in.loanId())) {
                throw ApiException.invalid("customer " + relatedNo + " appears twice as " + in.relationType());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("relatedCustomerId", in.relatedCustomerId().toString());
            row.put("relatedCustomerNo", relatedNo);
            row.put("relationType", in.relationType());
            row.put("loanId", in.loanId() == null ? null : in.loanId().toString());
            row.put("sharePercent", in.sharePercent() == null ? null : in.sharePercent().toPlainString());
            rows.add(row);
        }
        for (Map.Entry<String, BigDecimal> e : nomineeTotals.entrySet()) {
            if (e.getValue().compareTo(HUNDRED) != 0) {
                throw ApiException.invalid("nominee shares must total 100 percent for an account; they total " + e.getValue().toPlainString()
                        + " (send the complete set of nominees: it replaces the current one)");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId.toString());
        payload.put("customerNo", self.get("customer_no"));
        payload.put("relationships", rows);
        return approvals.propose("CUSTOMER_RELATIONSHIP", "CREATE", (String) self.get("customer_no"), payload, null, null, branch,
                idempotencyKey);
    }

    /** As borrower, co-applicant and guarantor, with the limit and the room left under it. */
    Map<String, Object> exposure(UUID customerId) {
        customers.visibleBranch(customerId);
        Map<String, Object> e = jdbc.queryForMap("SELECT * FROM customer.exposure WHERE customer_id = ?", customerId);
        BigDecimal limit = (BigDecimal) e.get("exposure_limit");
        BigDecimal borrower = (BigDecimal) e.get("as_borrower");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerId", customerId);
        m.put("exposureLimit", plain(limit));
        m.put("asBorrower", plain(borrower));
        m.put("asCoApplicant", plain((BigDecimal) e.get("as_co_applicant")));
        m.put("asGuarantor", plain((BigDecimal) e.get("as_guarantor")));
        m.put("loansAsBorrower", e.get("loans_as_borrower"));
        m.put("loansAsCoApplicant", e.get("loans_as_co_applicant"));
        m.put("loansAsGuarantor", e.get("loans_as_guarantor"));
        m.put("available", limit == null ? null : plain(limit.subtract(borrower).max(BigDecimal.ZERO)));
        return m;
    }

    /** Sets, changes or (with a null limit) removes the customer's exposure limit, through maker-checker. */
    @Transactional
    ApprovalRequest proposeExposureLimit(UUID customerId, ExposureLimitInput in) {
        String branch = customers.visibleBranch(customerId);
        if (in == null || in.reason() == null || in.reason().isBlank()) throw ApiException.invalid("a reason is required");
        if (in.exposureLimit() != null && in.exposureLimit().signum() < 0) throw ApiException.invalid("exposureLimit cannot be negative");
        Map<String, Object> cur = jdbc.queryForMap("SELECT customer_no, exposure_limit FROM customer.customer WHERE id = ?", customerId);
        if (Objects.equals(plain((BigDecimal) cur.get("exposure_limit")), plain(in.exposureLimit()))) {
            throw ApiException.conflict("the exposure limit is already " + (in.exposureLimit() == null ? "not set" : plain(in.exposureLimit())));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId.toString());
        payload.put("customerNo", cur.get("customer_no"));
        payload.put("exposureLimit", plain(in.exposureLimit()));
        payload.put("reason", in.reason().trim());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("exposureLimit", plain((BigDecimal) cur.get("exposure_limit")));
        return approvals.propose("CUSTOMER_EXPOSURE_LIMIT", cur.get("exposure_limit") == null ? "CREATE" : "UPDATE",
                (String) cur.get("customer_no"), payload, before, in.exposureLimit(), branch, null);
    }

    static String plain(BigDecimal v) {
        if (v == null) return null;
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
    }

    /** Writes approved relationships; a nominee set replaces the account's current nominees. */
    @Service
    static class RelationshipApplier implements ApprovalApplier {
        private final JdbcTemplate jdbc;

        RelationshipApplier(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override public String entityType() { return "CUSTOMER_RELATIONSHIP"; }

        @Override
        public String apply(ApprovalRequest r) {
            UUID customerId = UUID.fromString(String.valueOf(r.payload().get("customerId")));
            List<?> rows = r.payload().get("relationships") instanceof List<?> l ? l : List.of();
            Set<String> replaced = new HashSet<>();
            for (Object o : rows) {
                Map<?, ?> row = (Map<?, ?>) o;
                UUID loanId = row.get("loanId") == null ? null : UUID.fromString(String.valueOf(row.get("loanId")));
                if ("NOMINEE".equals(row.get("relationType")) && replaced.add(String.valueOf(loanId))) {
                    jdbc.update("""
                            UPDATE customer.relationship SET status = 'ENDED', ended_at = now(), ended_by = ?
                             WHERE customer_id = ? AND relation_type = 'NOMINEE' AND status = 'ACTIVE' AND loan_id IS NOT DISTINCT FROM ?
                            """, r.maker(), customerId, loanId);
                }
            }
            int written = 0;
            for (Object o : rows) {
                Map<?, ?> row = (Map<?, ?>) o;
                UUID related = UUID.fromString(String.valueOf(row.get("relatedCustomerId")));
                UUID loanId = row.get("loanId") == null ? null : UUID.fromString(String.valueOf(row.get("loanId")));
                Object share = row.get("sharePercent");
                // idempotent: a relationship that is already active (approved through another request) is left alone
                if (!jdbc.queryForList("""
                        SELECT 1 FROM customer.relationship
                         WHERE customer_id = ? AND related_customer_id = ? AND relation_type = ? AND status = 'ACTIVE'
                           AND loan_id IS NOT DISTINCT FROM ?
                        """, customerId, related, row.get("relationType"), loanId).isEmpty()) {
                    continue;
                }
                jdbc.update("""
                        INSERT INTO customer.relationship (customer_id, related_customer_id, relation_type, loan_id, share_percent,
                                                           approval_id, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, customerId, related, row.get("relationType"), loanId,
                        share == null ? null : new BigDecimal(String.valueOf(share)), r.id(), r.maker());
                written++;
            }
            return r.payload().get("customerNo") + ": " + written + " relationship(s)";
        }
    }

    /** Sets the approved exposure limit. Existing loans stay; the limit binds the next booking or disbursement. */
    @Service
    static class ExposureLimitApplier implements ApprovalApplier {
        private final JdbcTemplate jdbc;

        ExposureLimitApplier(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override public String entityType() { return "CUSTOMER_EXPOSURE_LIMIT"; }

        @Override
        public String apply(ApprovalRequest r) {
            Object limit = r.payload().get("exposureLimit");
            jdbc.update("UPDATE customer.customer SET exposure_limit = ?, version = version + 1 WHERE id = ?",
                    limit == null ? null : new BigDecimal(String.valueOf(limit)),
                    UUID.fromString(String.valueOf(r.payload().get("customerId"))));
            return String.valueOf(r.payload().get("customerNo"));
        }
    }
}
