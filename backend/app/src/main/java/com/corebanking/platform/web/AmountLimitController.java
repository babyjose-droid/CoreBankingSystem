package com.corebanking.platform.web;

import com.corebanking.kernel.AmountLimits;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BusinessDays;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role amount limits per transaction type (US-021): list, and propose a new limit through maker-checker. A new
 * limit for a role and type takes over from its effective date; the earlier open-ended limit ends the day before.
 */
@RestController
@RequestMapping("/api/v1/amount-limits")
class AmountLimitController {

    /** openapi.yaml#/components/schemas/AmountLimitInput. */
    record Input(String roleName, String txnType, BigDecimal perTransactionMax, BigDecimal perDayMax, LocalDate effectiveFrom,
                 LocalDate effectiveTo) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final BusinessDays days;

    AmountLimitController(JdbcTemplate jdbc, ApprovalService approvals, BusinessDays days) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.days = days;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('limit:view')")
    List<Map<String, Object>> list(@RequestParam(required = false) String roleName, @RequestParam(required = false) String txnType,
                                   @RequestParam(defaultValue = "false") boolean currentOnly) {
        BusinessDays.BusinessDay bd = days.current();
        LocalDate today = bd == null ? LocalDate.now() : bd.businessDate();
        return jdbc.query("""
                SELECT id, role_name, txn_type, per_txn_max, per_day_max, effective_from, effective_to, created_by,
                       to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                       (effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)) AS in_force
                  FROM platform.amount_limit
                 WHERE (?::text IS NULL OR role_name = ?) AND (?::text IS NULL OR txn_type = ?)
                 ORDER BY role_name, txn_type, effective_from DESC
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("roleName", rs.getString(2));
                    m.put("txnType", rs.getString(3));
                    m.put("perTransactionMax", plain(rs.getBigDecimal(4)));
                    m.put("perDayMax", plain(rs.getBigDecimal(5)));
                    m.put("effectiveFrom", rs.getString(6));
                    m.put("effectiveTo", rs.getString(7));
                    m.put("createdBy", rs.getString(8));
                    m.put("createdAt", rs.getString(9));
                    m.put("inForce", rs.getBoolean(10));
                    return m;
                }, today, today, roleName, roleName, txnType, txnType)
                .stream().filter(m -> !currentOnly || Boolean.TRUE.equals(m.get("inForce"))).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('limit:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> propose(@RequestBody Input in) {
        if (in.roleName() == null || !in.roleName().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{1,63}")) {
            throw ApiException.invalid("roleName must be a role name of 2 to 64 letters, digits, _ . : or -");
        }
        if (in.txnType() == null || !AmountLimits.TYPES.contains(in.txnType())) {
            throw ApiException.invalid("txnType must be one of " + AmountLimits.TYPES.stream().sorted().toList());
        }
        if (in.perTransactionMax() == null || in.perTransactionMax().signum() < 0) {
            throw ApiException.invalid("perTransactionMax is required and cannot be negative");
        }
        if (in.perDayMax() != null && in.perDayMax().compareTo(in.perTransactionMax()) < 0) {
            throw ApiException.invalid("perDayMax cannot be below perTransactionMax");
        }
        if (in.effectiveFrom() == null) throw ApiException.invalid("effectiveFrom is required");
        if (in.effectiveTo() != null && in.effectiveTo().isBefore(in.effectiveFrom())) {
            throw ApiException.invalid("effectiveTo cannot be before effectiveFrom");
        }
        List<Map<String, Object>> current = jdbc.queryForList("""
                SELECT per_txn_max::text AS "perTransactionMax", per_day_max::text AS "perDayMax",
                       effective_from::text AS "effectiveFrom", effective_to::text AS "effectiveTo"
                  FROM platform.amount_limit
                 WHERE role_name = ? AND txn_type = ? AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                """, in.roleName(), in.txnType(), in.effectiveFrom(), in.effectiveFrom());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roleName", in.roleName());
        payload.put("txnType", in.txnType());
        payload.put("perTransactionMax", plain(in.perTransactionMax()));
        payload.put("perDayMax", plain(in.perDayMax()));
        payload.put("effectiveFrom", in.effectiveFrom().toString());
        payload.put("effectiveTo", in.effectiveTo() == null ? null : in.effectiveTo().toString());
        return ApprovalController.view(approvals.propose("AMOUNT_LIMIT", current.isEmpty() ? "CREATE" : "UPDATE",
                in.roleName() + "/" + in.txnType(), payload, current.isEmpty() ? null : current.get(0), null, null, null));
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().scale() < 0 ? v.setScale(0, java.math.RoundingMode.UNNECESSARY).toPlainString()
                : v.stripTrailingZeros().toPlainString();
    }

    /** Applies an approved limit. Overlapping periods are refused by the database. */
    @Component
    static class Applier implements ApprovalApplier {
        private final JdbcTemplate jdbc;

        Applier(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override public String entityType() { return "AMOUNT_LIMIT"; }

        @Override
        public String apply(ApprovalRequest r) {
            Map<String, Object> p = r.payload();
            LocalDate from = LocalDate.parse(String.valueOf(p.get("effectiveFrom")));
            Object to = p.get("effectiveTo");
            Object day = p.get("perDayMax");
            // the limit in force gives way to the new one from its first day
            jdbc.update("""
                    UPDATE platform.amount_limit SET effective_to = ?::date - 1
                     WHERE role_name = ? AND txn_type = ? AND effective_from < ?::date
                       AND (effective_to IS NULL OR effective_to >= ?::date)
                    """, from.toString(), p.get("roleName"), p.get("txnType"), from.toString(), from.toString());
            jdbc.update("""
                    INSERT INTO platform.amount_limit (role_name, txn_type, per_txn_max, per_day_max, effective_from, effective_to,
                                                       approval_id, created_by)
                    VALUES (?, ?, ?, ?, ?::date, ?::date, ?, ?)
                    """, p.get("roleName"), p.get("txnType"), new BigDecimal(String.valueOf(p.get("perTransactionMax"))),
                    day == null ? null : new BigDecimal(String.valueOf(day)), from.toString(), to == null ? null : String.valueOf(to),
                    r.id(), r.maker());
            return p.get("roleName") + "/" + p.get("txnType") + "@" + from;
        }
    }
}
