package com.corebanking.platform.control;

import com.corebanking.platform.DemoTenantSeeder;
import com.corebanking.platform.TenantDataSources;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * LOCAL DEVELOPMENT ONLY (corebanking.bootstrap.demo-tenant=true): provisions the demo tenant "demo-nbfc" on first
 * start so a developer can log in straight away. Never enable in shared environments — real tenants are
 * provisioned through /platform/v1/tenants by a platform operator.
 */
@Component
@ConditionalOnProperty(name = "corebanking.bootstrap.demo-tenant", havingValue = "true")
class DemoTenantBootstrap implements ApplicationRunner {

    private static final String TENANT = "demo-nbfc";
    private static final Logger log = LoggerFactory.getLogger(DemoTenantBootstrap.class);

    private final TenantProvisioner provisioner;
    private final JdbcTemplate control;
    private final TenantDataSources dataSources;
    private final List<DemoTenantSeeder> seeders;

    DemoTenantBootstrap(TenantProvisioner provisioner, @Qualifier("controlJdbc") JdbcTemplate control,
                        TenantDataSources dataSources, List<DemoTenantSeeder> seeders) {
        this.provisioner = provisioner;
        this.control = control;
        this.dataSources = dataSources;
        this.seeders = seeders;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (control.queryForList("SELECT 1 FROM control.tenant WHERE code = 'demo-nbfc'").isEmpty()) provision();
        if (!dataSources.tenants().contains(TENANT)) return;      // provisioning did not finish: nothing to seed into
        // Every start, not only the first: a demo tenant created by an earlier build gets what was added since.
        TenantDataSources.runAs(TENANT, () -> {
            for (DemoTenantSeeder s : seeders) {
                try {
                    s.seed();
                } catch (RuntimeException e) {
                    log.warn("demo tenant seeding by {} failed: {}", s.getClass().getSimpleName(), e.getClass().getSimpleName());
                }
            }
        });
    }

    private void provision() {
        log.warn("bootstrapping LOCAL demo tenant demo-nbfc (corebanking.bootstrap.demo-tenant=true)");
        provisioner.provision(new TenantProvisioner.Request(TENANT, "CLAUDE-TEST Demo NBFC Limited", "NBFC", "STANDALONE",
                "GROWTH", "NBFC", new TenantProvisioner.HeadOffice("HO", "Head Office Kochi", "32"),
                LocalDate.of(2026, 6, 30), List.of(), null, null), "bootstrap");
        DataSource ds = dataSources.of(TENANT);
        JdbcTemplate t = new JdbcTemplate(ds);
        t.update("INSERT INTO platform.branch (code, name, state_code, parent_code) VALUES ('MUM', 'Mumbai', '27', 'HO')");
        // The integration client's profile (service-account-corebanking-service) is created by the provisioner.
        // dev-*: users of the console's local-only development sign-in (Keycloak client console-dev, ADR-007 amendment).
        for (String u : new String[] {"maker", "checker", "ops", "auditor", "admin",
                "dev-maker", "dev-checker", "dev-admin", "dev-ops", "dev-auditor"}) {
            t.update("INSERT INTO platform.staff_user (user_id, username, display_name, home_branch, all_branches) VALUES (?, ?, ?, 'HO', true)",
                    "local:" + u, u, "CLAUDE-TEST " + u);
        }
    }
}
