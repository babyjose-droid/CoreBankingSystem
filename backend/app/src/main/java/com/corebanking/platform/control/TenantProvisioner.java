package com.corebanking.platform.control;

import com.corebanking.platform.ApiException;
import com.corebanking.platform.tenancy.TenantDataSourceRouter;
import com.corebanking.platform.tenancy.TenantDirectory;
import com.corebanking.platform.tenancy.TenantMigrator;
import com.corebanking.platform.tenancy.TenantRegistry;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Tenant provisioning (US-001) and fleet migrations (US-005). Cloud resources (KMS key, Secrets Manager secret,
 * S3 prefix, Keycloak realm) are created by the Terraform {@code tenant} module and the realm renderer; this
 * service creates the database (when allowed), migrates it, loads the starter kit and activates the tenant.
 * Every step is written to control.provisioning_log.
 */
@Service
public class TenantProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioner.class);

    public record HeadOffice(String code, String name, String stateCode) {}
    public record Request(String code, String legalName, String entityType, String deploymentTier, String edition,
                          String starterKit, HeadOffice headOffice, LocalDate firstBusinessDate, List<String> adminUsers,
                          String kmsKeyArn, String dbSecretArn) {}

    /** Keycloak client of the tenant's LOS / integration (see infra/keycloak/new-tenant-realm.py). */
    static final String SERVICE_ACCOUNT = "service-account-corebanking-service";
    public record MigrationResult(String tenant, String currentVersion, List<String> pending, String status, String error) {}

    private final JdbcTemplate control;
    private final TenantRegistry registry;
    private final TenantDataSourceRouter router;
    private final TenantDirectory directory;
    private final boolean createDatabases;

    public TenantProvisioner(@Qualifier("controlJdbc") JdbcTemplate control, TenantRegistry registry,
                             TenantDataSourceRouter router, TenantDirectory directory,
                             @Value("${corebanking.control.create-databases:false}") boolean createDatabases) {
        this.control = control;
        this.registry = registry;
        this.router = router;
        this.directory = directory;
        this.createDatabases = createDatabases;
    }

    public Map<String, Object> provision(Request r, String operator) {
        if (r.code() == null || !r.code().matches("[a-z][a-z0-9-]{2,30}")) throw ApiException.invalid("invalid tenant code");
        if (r.code().equals("platform")) throw ApiException.invalid("'platform' is the operators' realm, not a tenant code");
        if (r.headOffice() == null) throw ApiException.invalid("headOffice is required");
        if (r.adminUsers() != null) {
            for (String u : r.adminUsers()) {
                if (u == null || !u.matches("[A-Za-z0-9._@-]{2,80}")) throw ApiException.invalid("invalid admin username " + u);
            }
        }
        if (!control.queryForList("SELECT 1 FROM control.tenant WHERE code = ?", r.code()).isEmpty()) {
            throw ApiException.conflict("tenant " + r.code() + " already exists");
        }
        UUID id = UUID.randomUUID();
        String kit = r.starterKit() == null ? ("BANK".equals(r.entityType()) ? "BANK" : "NBFC") : r.starterKit();
        control.update("""
                INSERT INTO control.tenant (id, code, legal_name, entity_type, deployment_tier, edition_code, starter_kit, oidc_realm,
                                            kms_key_arn, db_secret_arn)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, r.code(), r.legalName(), r.entityType(), r.deploymentTier(), r.edition(), kit, r.code(),
                r.kmsKeyArn(), r.dbSecretArn());
        // The edition's modules that this kind of institution may have: no CASA for an NBFC, no term deposits for an
        // NBFC whose registration to accept public deposits is not recorded (control V4).
        control.update("""
                INSERT INTO control.tenant_module (tenant_id, module_code)
                SELECT ?, module_code FROM control.edition_module
                 WHERE edition_code = ? AND control.module_allowed(?, false, module_code)
                """, id, r.edition(), r.entityType());
        try {
            if (createDatabases) {
                step(id, "create-database", () -> createDatabase(r.code()));
            }
            HikariDataSource ds = registry.pool(r.code());
            step(id, "migrate", () -> TenantMigrator.migrateTenant(ds));
            JdbcTemplate t = new JdbcTemplate(ds);
            step(id, "seed", () -> seed(t, r, kit));
            router.addTenant(r.code(), ds);
            control.update("UPDATE control.tenant SET status = 'ACTIVE', provisioned_at = now() WHERE id = ?", id);
            control.update("INSERT INTO control.operator_action (tenant_id, operator, action, reason) VALUES (?, ?, 'PROVISION', 'tenant onboarding')",
                    id, operator);
            directory.evict(r.code());
            return tenant(r.code());
        } catch (RuntimeException e) {
            log.error("provisioning {} failed", r.code(), e);
            throw ApiException.conflict("provisioning failed at a step; see control.provisioning_log: " + e.getMessage());
        }
    }

    private void seed(JdbcTemplate t, Request r, String kit) {
        LocalDate first = r.firstBusinessDate() == null ? LocalDate.now() : r.firstBusinessDate();
        t.update("INSERT INTO platform.legal_entity (legal_name, entity_type, registered_state) VALUES (?, ?, ?)",
                r.legalName(), r.entityType(), r.headOffice().stateCode());
        t.update("INSERT INTO platform.branch (code, name, state_code, is_head_office) VALUES (?, ?, ?, true)",
                r.headOffice().code(), r.headOffice().name(), r.headOffice().stateCode());
        t.update("INSERT INTO platform.business_day (id, business_date, status) VALUES (1, ?, 'OPEN')", first);
        t.update("INSERT INTO platform.weekly_off (branch_code, day_of_week, week_of_month) VALUES (NULL, 7, NULL)");
        if ("BANK".equals(kit)) {
            t.update("INSERT INTO platform.weekly_off (branch_code, day_of_week, week_of_month) VALUES (NULL, 6, 2), (NULL, 6, 4)");
        }
        // First staff profiles (US-020): the tenant admins named at onboarding and the integration client see every
        // branch; everyone else is set up by the tenant through maker-checker (POST /api/v1/staff).
        List<String> admins = new java.util.ArrayList<>();
        if (r.adminUsers() != null) admins.addAll(r.adminUsers());
        admins.add(SERVICE_ACCOUNT);
        for (String u : admins) {
            t.update("""
                    INSERT INTO platform.staff_user (user_id, username, display_name, home_branch, all_branches)
                    VALUES (?, ?, ?, ?, true) ON CONFLICT (user_id) DO NOTHING
                    """, u.toLowerCase(), u.toLowerCase(), u, r.headOffice().code());
        }
        t.queryForObject("SELECT ledger.load_starter_kit(?)", Integer.class, kit);
        t.execute("SELECT ledger.load_lending_heads()");
        t.execute("SELECT ledger.load_deposit_heads()");          // adds heads only for a bank (tenant V28)
        t.update("""
                INSERT INTO platform.tax_rate (code, tax_type, rate_percent, effective_from) VALUES
                  ('GST18', 'GST', 18, '2017-07-01'), ('TDS194A', 'TDS', 10, '2020-04-01')
                """);
    }

    private void createDatabase(String code) {
        String db = "tenant_" + code.replace('-', '_');
        if (control.queryForList("SELECT 1 FROM pg_database WHERE datname = ?", db).isEmpty()) {
            control.execute("CREATE DATABASE " + db);      // code is validated above: [a-z0-9_] only
        }
    }

    private void step(UUID tenantId, String name, Runnable action) {
        try {
            action.run();
            control.update("INSERT INTO control.provisioning_log (tenant_id, step, status) VALUES (?, ?, 'OK')", tenantId, name);
        } catch (RuntimeException e) {
            control.update("INSERT INTO control.provisioning_log (tenant_id, step, status, detail) VALUES (?, ?, 'FAILED', ?)",
                    tenantId, name, String.valueOf(e.getMessage()));
            throw e;
        }
    }

    public Map<String, Object> tenant(String code) {
        List<Map<String, Object>> rows = control.queryForList("""
                SELECT t.id, t.code, t.legal_name AS "legalName", t.entity_type AS "entityType",
                       t.deployment_tier AS "deploymentTier", t.edition_code AS edition, t.status,
                       coalesce(array_to_string(array_agg(m.module_code ORDER BY m.module_code) FILTER (WHERE m.enabled), ','), '') AS modules
                  FROM control.tenant t LEFT JOIN control.tenant_module m ON m.tenant_id = t.id
                 WHERE t.code = ? GROUP BY t.id
                """, code);
        if (rows.isEmpty()) throw ApiException.notFound("tenant " + code);
        return withModuleList(rows.get(0));
    }

    public List<Map<String, Object>> tenants() {
        return control.queryForList("SELECT code FROM control.tenant ORDER BY code", String.class).stream()
                .map(this::tenant).toList();
    }

    public Map<String, Object> setModules(String code, List<String> modules, String operator) {
        UUID id = control.queryForObject("SELECT id FROM control.tenant WHERE code = ?", UUID.class, code);
        control.update("UPDATE control.tenant_module SET enabled = false WHERE tenant_id = ?", id);
        for (String m : modules) {
            control.update("""
                    INSERT INTO control.tenant_module (tenant_id, module_code, enabled) VALUES (?, ?, true)
                    ON CONFLICT (tenant_id, module_code) DO UPDATE SET enabled = true
                    """, id, m);
        }
        control.update("INSERT INTO control.operator_action (tenant_id, operator, action, reason) VALUES (?, ?, 'SET_MODULES', ?)",
                id, operator, String.join(",", modules));
        directory.evict(code);
        return tenant(code);
    }

    /** Migrates every active tenant; one failure never stops the others. Dry run only reports pending versions. */
    public List<MigrationResult> migrateAll(boolean dryRun, String operator) {
        List<MigrationResult> out = new ArrayList<>();
        for (TenantRegistry.Tenant t : registry.activeTenants()) {
            try (HikariDataSource ds = registry.pool(t.code())) {
                TenantMigrator.Status before = TenantMigrator.status(ds);
                if (dryRun || before.pending().isEmpty()) {
                    out.add(new MigrationResult(t.code(), before.currentVersion(), before.pending(),
                            before.pending().isEmpty() ? "UP_TO_DATE" : "PENDING", null));
                } else {
                    TenantMigrator.migrateTenant(ds);
                    out.add(new MigrationResult(t.code(), TenantMigrator.status(ds).currentVersion(), before.pending(), "MIGRATED", null));
                }
            } catch (RuntimeException e) {
                out.add(new MigrationResult(t.code(), null, List.of(), "FAILED", e.getMessage()));
            }
        }
        control.update("INSERT INTO control.migration_run (started_by, dry_run, result) VALUES (?, ?, ?::jsonb)",
                operator, dryRun, toJson(out));
        return out;
    }

    private static Map<String, Object> withModuleList(Map<String, Object> row) {
        String mods = (String) row.get("modules");
        row.put("modules", mods == null || mods.isEmpty() ? List.of() : List.of(mods.split(",")));
        return row;
    }

    private static String toJson(List<MigrationResult> results) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < results.size(); i++) {
            MigrationResult r = results.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"tenant\":\"").append(r.tenant()).append("\",\"status\":\"").append(r.status()).append("\"}");
        }
        return sb.append(']').toString();
    }
}
