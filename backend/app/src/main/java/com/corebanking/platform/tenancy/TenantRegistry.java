package com.corebanking.platform.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Loads ACTIVE tenants from the control plane and keeps one small connection pool per tenant.
 * Credentials come from AWS Secrets Manager in cloud tiers (secret ARN in control.tenant) and from
 * environment/K8s secrets in the standalone tier; see {@link TenantCredentials}.
 */
public class TenantRegistry {

    public record Tenant(String code, String jdbcUrl, String tier) {}

    private final JdbcTemplate control;
    private final TenantCredentials credentials;
    private final Map<Object, Object> pools = new ConcurrentHashMap<>();

    public TenantRegistry(DataSource controlDataSource, TenantCredentials credentials) {
        this.control = new JdbcTemplate(controlDataSource);
        this.credentials = credentials;
    }

    public List<Tenant> activeTenants() {
        return control.query("""
                SELECT code, deployment_tier FROM control.tenant WHERE status = 'ACTIVE' ORDER BY code
                """, (rs, i) -> new Tenant(rs.getString(1), credentials.jdbcUrl(rs.getString(1)), rs.getString(2)));
    }

    public Map<Object, Object> dataSources() {
        for (Tenant t : activeTenants()) {
            pools.computeIfAbsent(t.code(), k -> {
                HikariDataSource ds = new HikariDataSource();
                ds.setPoolName("tenant-" + t.code());
                ds.setJdbcUrl(t.jdbcUrl());
                ds.setUsername(credentials.username(t.code()));
                ds.setPassword(credentials.password(t.code()));
                ds.setMaximumPoolSize(10);
                return ds;
            });
        }
        return pools;
    }

    /** Resolves connection details per tenant. Implementations: AWS Secrets Manager, env vars. */
    public interface TenantCredentials {
        String jdbcUrl(String tenantCode);
        String username(String tenantCode);
        String password(String tenantCode);
    }
}
