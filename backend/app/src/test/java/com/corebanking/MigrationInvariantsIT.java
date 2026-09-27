package com.corebanking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.platform.tenancy.TenantMigrator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies the tenant migrations to a real PostgreSQL 16 and runs the SQL invariant suite
 * (src/test/resources/db/ledger_invariants_test.sql) through psql inside the container.
 */
@Testcontainers
class MigrationInvariantsIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void tenantMigrationsEnforceLedgerInvariants() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        TenantMigrator.migrateTenant(ds);

        String sql = Files.readString(Path.of("src/test/resources/db/ledger_invariants_test.sql"));
        PG.copyFileToContainer(org.testcontainers.images.builder.Transferable.of(sql), "/tmp/t.sql");
        var result = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U", PG.getUsername(),
                "-d", PG.getDatabaseName(), "-f", "/tmp/t.sql");
        assertEquals(0, result.getExitCode(), result.getStdout() + result.getStderr());
        assertTrue(result.getStdout().contains("ALL DB INVARIANT TESTS PASSED"));
    }
}
