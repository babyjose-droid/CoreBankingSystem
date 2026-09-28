package com.corebanking.platform.tenancy;

import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

/** Runs Flyway for the control plane and for tenant databases (explicitly; Spring's auto-run is disabled). */
public final class TenantMigrator {

    private TenantMigrator() {}

    public static void migrateControl(DataSource control) {
        flyway(control, "classpath:db/migration/control", "control").migrate();
    }

    public static void migrateTenant(DataSource tenant) {
        flyway(tenant, "classpath:db/migration/tenant", "platform").migrate();
    }

    /** Current version and pending versions for a tenant database (dry run). */
    public static Status status(DataSource tenant) {
        Flyway f = flyway(tenant, "classpath:db/migration/tenant", "platform");
        MigrationInfo current = f.info().current();
        List<String> pending = Arrays.stream(f.info().pending())
                .map(i -> i.getVersion() + " " + i.getDescription()).toList();
        return new Status(current == null ? null : current.getVersion().getVersion(), pending);
    }

    public record Status(String currentVersion, List<String> pending) {}

    private static Flyway flyway(DataSource ds, String location, String schema) {
        return Flyway.configure().dataSource(ds).locations(location).schemas(schema).createSchemas(true).load();
    }
}
