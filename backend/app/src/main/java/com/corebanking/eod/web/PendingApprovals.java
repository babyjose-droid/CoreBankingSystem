package com.corebanking.eod.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Approvals still pending whose effect is financial and dated: they stay pending through the end of day and, once
 * approved, apply on the business date of their approval, not on the day they were proposed (ADR-015). Day-end
 * does not wait for them; it warns. A tenant can choose to refuse the start instead (system property
 * {@code eod.block-on-pending-approvals=true}).
 */
final class PendingApprovals {

    /** Entity type and how a count of it reads ("2 vouchers"). Configuration changes (products, masters) are not dated. */
    static final Map<String, String[]> DATED = new LinkedHashMap<>();
    static {
        DATED.put("VOUCHER", new String[] {"voucher", "vouchers"});
        DATED.put("LOAN_DISBURSEMENT", new String[] {"disbursement", "disbursements"});
        DATED.put("LOAN_DISBURSEMENT_REVERSAL", new String[] {"disbursement reversal", "disbursement reversals"});
        DATED.put("LOAN_WAIVER", new String[] {"waiver", "waivers"});
        DATED.put("LOAN_REVERSAL", new String[] {"loan transaction reversal", "loan transaction reversals"});
        DATED.put("LOAN_AMENDMENT", new String[] {"loan amendment", "loan amendments"});
        DATED.put("LOAN_RESTRUCTURE", new String[] {"restructure", "restructures"});
        DATED.put("LOAN_SANCTION_CHANGE", new String[] {"sanction change", "sanction changes"});
        DATED.put("LOAN_NPA_OVERRIDE", new String[] {"NPA override", "NPA overrides"});
        DATED.put("LOAN_PARTY_RELEASE", new String[] {"guarantor or co-applicant release", "guarantor or co-applicant releases"});
    }

    static final String BLOCK_PROPERTY = "eod.block-on-pending-approvals";

    record Summary(int total, List<Map<String, Object>> byType, boolean blocking, String message) {
        Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", total);
            m.put("byType", byType);
            m.put("blocking", blocking);
            m.put("message", message);
            return m;
        }
    }

    private PendingApprovals() {}

    static Summary of(JdbcTemplate jdbc) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query("SELECT entity_type, count(*)::int FROM platform.approval_request WHERE status = 'PENDING' "
                        + "AND entity_type = ANY (?::text[]) GROUP BY entity_type",
                rs -> { counts.put(rs.getString(1), rs.getInt(2)); }, "{" + String.join(",", DATED.keySet()) + "}");
        List<Map<String, Object>> byType = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, String[]> e : DATED.entrySet()) {
            Integer n = counts.get(e.getKey());
            if (n == null || n == 0) continue;
            total += n;
            byType.add(Map.of("entityType", e.getKey(), "count", n));
            parts.add(n + " " + (n == 1 ? e.getValue()[0] : e.getValue()[1]));
        }
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = ?", String.class, BLOCK_PROPERTY);
        boolean block = !v.isEmpty() && "true".equalsIgnoreCase(v.get(0).trim());
        String message = total == 0 ? null
                : total + (total == 1 ? " approval is" : " approvals are") + " pending: " + String.join(", ", parts)
                        + (block ? " — end of day cannot start until they are approved or rejected (" + BLOCK_PROPERTY + ")"
                                 : " — they stay pending and will apply on the business date on which they are approved");
        return new Summary(total, byType, block && total > 0, message);
    }
}
