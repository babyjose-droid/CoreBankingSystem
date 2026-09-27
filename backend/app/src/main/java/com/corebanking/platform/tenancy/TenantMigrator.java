package com.corebanking.platform.tenancy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Runs Flyway for the control plane and then for each tenant database. A failure in one tenant is
 * reported and that tenant stays out of rotation; other tenants still start (isolation, NFR-AV-3).
 */
public final class TenantMigrator {

    private TenantMigrator() {}

    public static void migrateControl(DataSource control) {
        Flyway.configure().dataSource(control).locations("classpath:db/migration/control")
                .schemas("control").load().migrate();
    }

    public static void migrateTenant(DataSource tenant) {
        Flyway.configure().dataSource(tenant).locations("classpath:db/migration/tenant")
                .schemas("platform").createSchemas(true).load().migrate();
    }
}
