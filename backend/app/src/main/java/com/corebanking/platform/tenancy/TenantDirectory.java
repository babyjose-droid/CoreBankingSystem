package com.corebanking.platform.tenancy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Cached view of the control plane: which tenants are active, their names and enabled modules. Entries refresh
 * every 60 seconds, so enabling a module takes effect without a restart.
 */
@Component
public class TenantDirectory {

    public record Entry(String code, String legalName, String status, Set<String> modules, Instant loadedAt) {}

    private static final Duration TTL = Duration.ofSeconds(60);

    /** Path prefix → module that must be enabled. Core platform, customer, EOD and audit are always on. */
    private static final Map<String, String> MODULE_PATHS = Map.of(
            "/api/v1/gl/", "GL",
            "/api/v1/loans/", "LENDING",
            "/api/v1/loan-products/", "LENDING",
            "/api/v1/casa/", "CASA",
            "/api/v1/deposits/", "TD",
            "/api/v1/collections/", "COLLECTIONS",
            "/api/v1/reports/", "REPORTS");

    private final JdbcTemplate control;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public TenantDirectory(@Qualifier("controlDataSource") DataSource controlDataSource) {
        this.control = new JdbcTemplate(controlDataSource);
    }

    public static String moduleForPath(String path) {
        for (var e : MODULE_PATHS.entrySet()) {
            String prefix = e.getKey();
            if (path.startsWith(prefix) || path.equals(prefix.substring(0, prefix.length() - 1))) return e.getValue();
        }
        return null;
    }

    public boolean isActive(String tenant) {
        Entry e = get(tenant);
        return e != null && "ACTIVE".equals(e.status());
    }

    public Set<String> modules(String tenant) {
        Entry e = get(tenant);
        return e == null ? Set.of() : e.modules();
    }

    public String legalName(String tenant) {
        Entry e = get(tenant);
        return e == null ? tenant : e.legalName();
    }

    public void evict(String tenant) {
        cache.remove(tenant);
    }

    private static Set<String> toSet(java.sql.Array array) throws java.sql.SQLException {
        Object values = array.getArray();
        Set<String> out = new java.util.TreeSet<>();
        for (Object v : (Object[]) values) out.add(String.valueOf(v));
        return Set.copyOf(out);
    }

    private Entry get(String tenant) {
        Entry e = cache.get(tenant);
        if (e != null && e.loadedAt().plus(TTL).isAfter(Instant.now())) return e;
        List<Entry> rows = control.query("""
                SELECT t.code, t.legal_name, t.status,
                       coalesce(array_agg(m.module_code) FILTER (WHERE m.enabled), '{}') AS modules
                  FROM control.tenant t LEFT JOIN control.tenant_module m ON m.tenant_id = t.id
                 WHERE t.code = ?
                 GROUP BY t.code, t.legal_name, t.status
                """, (rs, i) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3),
                        toSet(rs.getArray(4)), Instant.now()), tenant);
        if (rows.isEmpty()) {
            cache.remove(tenant);
            return null;
        }
        cache.put(tenant, rows.get(0));
        return rows.get(0);
    }
}
