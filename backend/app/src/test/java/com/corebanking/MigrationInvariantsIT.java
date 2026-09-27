package com.corebanking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.platform.tenancy.TenantMigrator;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Applies the tenant migrations to a real PostgreSQL 16 through Flyway, then runs the SQL invariant
 * suite (src/test/resources/db/ledger_invariants_test.sql) with psql inside the container.
 */
class MigrationInvariantsIT {

    @Test
    void tenantMigrationsEnforceLedgerInvariants() throws Exception {
        try (GenericContainer<?> pg = new GenericContainer<>("postgres:16-alpine")
                .withEnv("POSTGRES_USER", "ar")
                .withEnv("POSTGRES_PASSWORD", "ar")
                .withEnv("POSTGRES_DB", "tenant_it")
                .withExposedPorts(5432)
                .withCopyFileToContainer(
                        MountableFile.forHostPath("src/test/resources/db/ledger_invariants_test.sql"), "/tmp/t.sql")
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))) {
            pg.start();

            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/tenant_it");
            ds.setUser("ar");
            ds.setPassword("ar");
            TenantMigrator.migrateTenant(ds);

            var result = pg.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U", "ar", "-d", "tenant_it", "-f", "/tmp/t.sql");
            assertEquals(0, result.getExitCode(), result.getStdout() + result.getStderr());
            assertTrue(result.getStdout().contains("ALL DB INVARIANT TESTS PASSED"), result.getStdout());
        }
    }
}
