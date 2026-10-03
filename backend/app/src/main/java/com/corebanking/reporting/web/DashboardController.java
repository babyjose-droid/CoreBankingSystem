package com.corebanking.reporting.web;

import com.corebanking.platform.BranchScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard (US-115). Module: REPORTING. Each group of figures is one SQL function (V16 reporting.dashboard_*),
 * limited to the branches the caller may see. Money is a decimal string; a percentage is null when it has no base.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController {

    private final JdbcTemplate jdbc;
    private final BranchScope scope;

    DashboardController(JdbcTemplate jdbc, BranchScope scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    /** openapi.yaml#/components/schemas/Dashboard. */
    @GetMapping
    @PreAuthorize("hasAuthority('dashboard:view')")
    Map<String, Object> dashboard() {
        String user = scope.user();
        Map<String, Object> m = new LinkedHashMap<>();

        Map<String, Object> f = jdbc.queryForMap("SELECT * FROM reporting.dashboard_flows(?)", user);
        m.put("businessDate", f.get("business_date") == null ? null : date(f.get("business_date")).toString());

        Map<String, Object> p = jdbc.queryForMap("SELECT * FROM reporting.dashboard_portfolio(?)", user);
        m.put("activeLoans", p.get("active_loans"));
        m.put("portfolioOutstanding", money(p.get("portfolio_outstanding")));
        m.put("overdueAmount", money(p.get("overdue_amount")));
        m.put("grossNpa", money(p.get("gross_npa")));
        m.put("npaLoans", p.get("npa_loans"));
        m.put("npaPercent", money(p.get("npa_percent")));

        m.put("disbursedToday", money(f.get("disbursed_today")));
        m.put("disbursedMtd", money(f.get("disbursed_mtd")));
        m.put("disbursementsToday", f.get("disbursements_today"));
        m.put("disbursementsMtd", f.get("disbursements_mtd"));
        m.put("collectedToday", money(f.get("collected_today")));
        m.put("collectedMtd", money(f.get("collected_mtd")));
        m.put("demandMtd", money(f.get("demand_mtd")));
        m.put("collectedAgainstDemandMtd", money(f.get("collected_against_demand_mtd")));
        m.put("collectionEfficiencyMtd", money(f.get("collection_efficiency_mtd")));

        List<Map<String, Object>> buckets = jdbc.query("SELECT bucket, loans, amount FROM reporting.dashboard_dpd(?) ORDER BY sort_order",
                (rs, i) -> {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("bucket", rs.getString(1));
                    b.put("loans", rs.getLong(2));
                    b.put("amount", money(rs.getBigDecimal(3)));
                    return b;
                }, user);
        m.put("dpdBuckets", buckets);

        Map<String, Object> o = jdbc.queryForMap("""
                SELECT pending_approvals, last_eod_business_date, last_eod_status,
                       to_char(last_eod_finished_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS last_eod_finished_at, open_eod_exceptions
                  FROM reporting.dashboard_operations(?)
                """, user);
        m.put("pendingApprovals", o.get("pending_approvals"));
        if (o.get("last_eod_status") == null) {
            m.put("lastEod", null);
        } else {
            Map<String, Object> eod = new LinkedHashMap<>();
            eod.put("businessDate", date(o.get("last_eod_business_date")).toString());
            eod.put("status", o.get("last_eod_status"));
            eod.put("finishedAt", o.get("last_eod_finished_at"));
            m.put("lastEod", eod);
        }
        m.put("openEodExceptions", o.get("open_eod_exceptions"));
        return m;
    }

    /**
     * Whole-book figures per business date, as stored by the DASHBOARD_REFRESH job (V19): the trend behind the
     * dashboard. For users who see every branch, because the history is not kept per branch.
     * openapi.yaml#/components/schemas/DashboardTrendPoint.
     */
    @GetMapping("/trend")
    @PreAuthorize("hasAuthority('dashboard:view')")
    List<Map<String, Object>> trend(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "30") int days) {
        scope.requireAll();
        return jdbc.query("""
                SELECT business_date::text, active_loans, portfolio_outstanding, overdue_amount, overdue_loans, gross_npa, npa_loans,
                       disbursed, collected, pending_approvals, to_char(refreshed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                  FROM reporting.dashboard_snapshot ORDER BY business_date DESC LIMIT ?
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("businessDate", rs.getString(1));
                    m.put("activeLoans", rs.getLong(2));
                    m.put("portfolioOutstanding", money(rs.getBigDecimal(3)));
                    m.put("overdueAmount", money(rs.getBigDecimal(4)));
                    m.put("overdueLoans", rs.getLong(5));
                    m.put("grossNpa", money(rs.getBigDecimal(6)));
                    m.put("npaLoans", rs.getLong(7));
                    m.put("disbursed", money(rs.getBigDecimal(8)));
                    m.put("collected", money(rs.getBigDecimal(9)));
                    m.put("pendingApprovals", rs.getLong(10));
                    m.put("refreshedAt", rs.getString(11));
                    return m;
                }, Math.min(Math.max(days, 1), 366));
    }

    /** Plain decimal text without trailing zeros (1250.5000 → "1250.5"); null stays null. */
    static String money(Object v) {
        if (v == null) return null;
        BigDecimal d = v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
        BigDecimal s = d.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static LocalDate date(Object v) {
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(String.valueOf(v).substring(0, 10));
    }
}
