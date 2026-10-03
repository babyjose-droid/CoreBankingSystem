package com.corebanking.platform.control;

import com.corebanking.kernel.Csv;
import com.corebanking.kernel.DocumentKey;
import com.corebanking.kernel.UsageMeter;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.DocumentStore;
import com.corebanking.platform.JobHandler;
import com.corebanking.platform.TenantDataSources;
import com.corebanking.platform.tenancy.ApiCallMeter;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Usage metering per tenant (US-004), kept in the control plane ({@code control.usage_daily}, control V3).
 * <ul>
 *   <li><b>API calls</b> are counted in memory by {@link ApiCallMeter} and added here on a timer (default every
 *       minute) and at shutdown. A request never waits for a database write. Several instances add to the same
 *       row. If the control database is unreachable the counts are kept and tried again; an instance that is
 *       killed loses at most the calls of its last interval.</li>
 *   <li><b>Daily snapshot</b> (the tenant job USAGE_SNAPSHOT, or on demand by an operator): active loan accounts,
 *       active customers, active staff users, and storage = files in the document store under the tenant's prefix
 *       plus the size of the tenant database.</li>
 *   <li><b>Monthly figures</b> for billing: peak and closing value of each count and the API calls of the month,
 *       as JSON or CSV.</li>
 * </ul>
 * Days are calendar days in IST. The control plane holds counts and sizes only, never customer data.
 */
@Service
public class UsageService implements JobHandler, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final List<String> MONTHLY_COLUMNS = List.of("tenant", "legal_name", "edition", "month", "days_measured", "peak_active_loans",
            "closing_active_loans", "peak_active_customers", "closing_active_customers", "peak_staff_users", "api_calls",
            "peak_storage_bytes", "closing_storage_bytes");

    private final JdbcTemplate control;
    private final TenantDataSources dataSources;
    private final DocumentStore documents;
    private final ApiCallMeter meter;

    public UsageService(@Qualifier("controlJdbc") JdbcTemplate control, TenantDataSources dataSources, DocumentStore documents,
                        ApiCallMeter meter) {
        this.control = control;
        this.dataSources = dataSources;
        this.documents = documents;
        this.meter = meter;
    }

    // ------------------------------------------------------------------------------------------------ API calls
    /** Adds the calls counted since the last flush to the control plane. */
    @Scheduled(fixedDelayString = "${corebanking.usage.flush-interval-ms:60000}")
    public void flush() {
        Map<UsageMeter.Key, Long> counts = meter.drain();
        Iterator<Map.Entry<UsageMeter.Key, Long>> it = counts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UsageMeter.Key, Long> e = it.next();
            try {
                control.queryForObject("SELECT control.add_api_calls(?, ?, ?)", Boolean.class, e.getKey().tenant(),
                        java.sql.Date.valueOf(e.getKey().day()), e.getValue());
            } catch (RuntimeException x) {
                // Keep this count and the ones not written yet for the next flush.
                log.warn("usage flush failed, {} tenant-day counts kept for the next attempt: {}", counts.size(), x.getMessage());
                meter.restore(e.getKey(), e.getValue());
                while (it.hasNext()) {
                    Map.Entry<UsageMeter.Key, Long> rest = it.next();
                    meter.restore(rest.getKey(), rest.getValue());
                }
                return;
            }
            it.remove();
        }
    }

    @Override
    public void destroy() {
        flush();
    }

    // ------------------------------------------------------------------------------------------------ snapshot
    @Override
    public String kind() {
        return "USAGE_SNAPSHOT";
    }

    @Override
    public Result run(Context context) {
        return new Result(1, 0, snapshot(context.tenant()));
    }

    /** Takes today's snapshot of one tenant and stores it; returns the figures. */
    public Map<String, Object> snapshot(String tenant) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSources.of(tenant));
        Map<String, Object> counts = jdbc.queryForMap("SELECT active_loans, active_customers, staff_users, database_bytes FROM platform.usage_snapshot()");
        long documentBytes = documents.bytesUnder(DocumentKey.validate("tenants/" + tenant));
        LocalDate day = LocalDate.now(IST);
        control.queryForObject("SELECT 1 FROM control.record_usage(?, ?, ?, ?, ?, ?, ?)", Integer.class, tenant, java.sql.Date.valueOf(day),
                counts.get("active_loans"), counts.get("active_customers"), counts.get("staff_users"), documentBytes, counts.get("database_bytes"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", day.toString());
        m.put("activeLoans", counts.get("active_loans"));
        m.put("activeCustomers", counts.get("active_customers"));
        m.put("staffUsers", counts.get("staff_users"));
        m.put("documentBytes", documentBytes);
        m.put("databaseBytes", counts.get("database_bytes"));
        return m;
    }

    /** Snapshot of one tenant, or of every tenant this instance serves; a tenant that fails is reported, not fatal. */
    public List<Map<String, Object>> snapshotNow(String tenant) {
        List<String> tenants = tenant == null || tenant.isBlank() ? dataSources.tenants() : List.of(requireTenant(tenant));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String t : tenants) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenant", t);
            try {
                m.putAll(snapshot(t));
                m.put("ok", true);
            } catch (RuntimeException e) {
                log.warn("usage snapshot of {} failed", t, e);
                m.put("ok", false);
                m.put("error", "the snapshot could not be taken; see the application log");
            }
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ reports
    /** openapi.yaml#/components/schemas/UsageDay, by tenant and day. */
    public List<Map<String, Object>> daily(String tenant, String from, String to) {
        UsageMeter.Range range;
        try {
            range = UsageMeter.range(from, to, LocalDate.now(IST));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage());
        }
        String code = tenant == null || tenant.isBlank() ? null : requireTenant(tenant);
        return control.query("""
                SELECT t.code, u.day::text, u.active_loans, u.active_customers, u.staff_users, u.api_calls, u.document_bytes, u.database_bytes,
                       u.storage_bytes, to_char(u.captured_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                  FROM control.usage_daily u JOIN control.tenant t ON t.id = u.tenant_id
                 WHERE u.day BETWEEN ? AND ? AND (?::text IS NULL OR t.code = ?)
                 ORDER BY t.code, u.day
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tenant", rs.getString(1));
                    m.put("day", rs.getString(2));
                    m.put("activeLoans", rs.getObject(3));
                    m.put("activeCustomers", rs.getObject(4));
                    m.put("staffUsers", rs.getObject(5));
                    m.put("apiCalls", rs.getLong(6));
                    m.put("documentBytes", rs.getObject(7));
                    m.put("databaseBytes", rs.getObject(8));
                    m.put("storageBytes", rs.getLong(9));
                    m.put("capturedAt", rs.getString(10));
                    return m;
                }, java.sql.Date.valueOf(range.from()), java.sql.Date.valueOf(range.to()), code, code);
    }

    /** One row per tenant for the month ({@code YYYY-MM}; blank = last month), in the order of {@link #MONTHLY_COLUMNS}. */
    public List<List<Object>> monthly(String month, String tenant) {
        YearMonth ym;
        try {
            ym = UsageMeter.month(month, LocalDate.now(IST));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage());
        }
        String code = tenant == null || tenant.isBlank() ? null : requireTenant(tenant);
        return control.query("SELECT * FROM control.usage_monthly(?, ?)", (rs, i) -> {
            List<Object> row = new ArrayList<>(MONTHLY_COLUMNS.size());
            for (int c = 1; c <= MONTHLY_COLUMNS.size(); c++) row.add(rs.getObject(c));
            return row;
        }, java.sql.Date.valueOf(ym.atDay(1)), code);
    }

    /** openapi.yaml#/components/schemas/UsageMonth. */
    public List<Map<String, Object>> monthlyJson(String month, String tenant) {
        List<String> names = List.of("tenant", "legalName", "edition", "month", "daysMeasured", "peakActiveLoans", "closingActiveLoans",
                "peakActiveCustomers", "closingActiveCustomers", "peakStaffUsers", "apiCalls", "peakStorageBytes", "closingStorageBytes");
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Object> row : monthly(month, tenant)) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                Object v = row.get(i);
                m.put(names.get(i), v instanceof java.math.BigDecimal b ? b.longValue() : v);
            }
            out.add(m);
        }
        return out;
    }

    /** The monthly figures as CSV for billing: a header row and one row per tenant. */
    public String monthlyCsv(String month, String tenant) {
        StringBuilder sb = new StringBuilder(Csv.line(MONTHLY_COLUMNS));
        for (List<Object> row : monthly(month, tenant)) {
            sb.append(Csv.line(row.stream().map(v -> v == null ? "" : String.valueOf(v)).toList()));
        }
        return sb.toString();
    }

    private String requireTenant(String tenant) {
        if (!tenant.matches("[a-z][a-z0-9-]{2,30}")
                || control.queryForList("SELECT 1 FROM control.tenant WHERE code = ?", tenant).isEmpty()) {
            throw ApiException.notFound("tenant " + tenant);
        }
        return tenant;
    }
}
