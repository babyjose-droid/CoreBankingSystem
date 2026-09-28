package com.corebanking.platform.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Loads ACTIVE tenants from the control plane, migrates each tenant database and registers a small connection
 * pool per tenant in the router. A tenant whose migration fails is logged and left out of rotation; the other
 * tenants still start (US-005: one failed tenant does not block the others).
 */
public class TenantRegistry {

    private static final Logger log = LoggerFactory.getLogger(TenantRegistry.class);

    public record Tenant(String code, String tier) {}

    private final JdbcTemplate control;
    private final TenantCredentials credentials;
    private final TenantDataSourceRouter router;

    public TenantRegistry(DataSource controlDataSource, TenantCredentials credentials, TenantDataSourceRouter router) {
        this.control = new JdbcTemplate(controlDataSource);
        this.credentials = credentials;
        this.router = router;
    }

    public List<Tenant> activeTenants() {
        return control.query("SELECT code, deployment_tier FROM control.tenant WHERE status = 'ACTIVE' ORDER BY code",
                (rs, i) -> new Tenant(rs.getString(1), rs.getString(2)));
    }

    /** Migrates and registers every active tenant. Called once at startup. */
    public void startAll() {
        for (Tenant t : activeTenants()) {
            try {
                register(t.code());
            } catch (RuntimeException e) {
                log.error("tenant {} not started: {}", t.code(), e.getMessage(), e);
            }
        }
    }

    /** Creates the pool for one tenant, applies pending migrations and adds it to the router. */
    public DataSource register(String code) {
        HikariDataSource ds = pool(code);
        TenantMigrator.migrateTenant(ds);
        router.addTenant(code, ds);
        log.info("tenant {} registered", code);
        return ds;
    }

    public HikariDataSource pool(String code) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("tenant-" + code);
        ds.setJdbcUrl(credentials.jdbcUrl(code));
        ds.setUsername(credentials.username(code));
        ds.setPassword(credentials.password(code));
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(0);
        return ds;
    }

    public TenantCredentials credentials() {
        return credentials;
    }

    /** Resolves connection details per tenant. Implementations: environment (dev/standalone), Secrets Manager. */
    public interface TenantCredentials {
        String jdbcUrl(String tenantCode);
        String username(String tenantCode);
        String password(String tenantCode);
    }
}
