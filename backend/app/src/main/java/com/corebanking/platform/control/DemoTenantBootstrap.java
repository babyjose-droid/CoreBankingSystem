package com.corebanking.platform.control;

import com.corebanking.platform.DemoTenantSeeder;
import com.corebanking.platform.TenantDataSources;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * LOCAL DEVELOPMENT ONLY (corebanking.bootstrap.demo-tenant=true): provisions the demo tenants on first start so a
 * developer can log in straight away. Never enable in shared environments — real tenants are provisioned through
 * /platform/v1/tenants by a platform operator.
 * <ul>
 *   <li>{@code demo-nbfc}: an NBFC that lends and takes no deposits (the deposit APIs answer 403).</li>
 *   <li>{@code demo-bank}: a bank on the ENTERPRISE edition, with savings, current and term deposits (Phase 4).</li>
 *   <li>{@code demo-nbfc-d}: an NBFC recorded as registered to accept public deposits, with term deposits only. The
 *       registration reference is demo data, not a certificate.</li>
 * </ul>
 * A demo tenant is set up only when the deployment has a database for it (COREBANKING_TENANT_&lt;CODE&gt;_URL): a
 * stack started from an older compose file keeps working with the tenants it has.
 */
@Component
@ConditionalOnProperty(name = "corebanking.bootstrap.demo-tenant", havingValue = "true")
class DemoTenantBootstrap implements ApplicationRunner {

    /** @param depositRegistrationRef set for an NBFC that takes deposits: recorded after provisioning, then TD is enabled */
    record Demo(String code, String legalName, String entityType, String edition, String starterKit, String depositRegistrationRef) {}

    static final List<Demo> DEMOS = List.of(
            new Demo("demo-nbfc", "CLAUDE-TEST Demo NBFC Limited", "NBFC", "GROWTH", "NBFC", null),
            new Demo("demo-bank", "CLAUDE-TEST Demo Bank Limited", "BANK", "ENTERPRISE", "BANK", null),
            new Demo("demo-nbfc-d", "CLAUDE-TEST Demo Deposit NBFC Limited", "NBFC", "ENTERPRISE", "NBFC", "CLAUDE-TEST demo registration (not a certificate)"));

    private static final Logger log = LoggerFactory.getLogger(DemoTenantBootstrap.class);

    private final TenantProvisioner provisioner;
    private final JdbcTemplate control;
    private final TenantDataSources dataSources;
    private final List<DemoTenantSeeder> seeders;
    private final Environment env;

    DemoTenantBootstrap(TenantProvisioner provisioner, @Qualifier("controlJdbc") JdbcTemplate control,
                        TenantDataSources dataSources, List<DemoTenantSeeder> seeders, Environment env) {
        this.provisioner = provisioner;
        this.control = control;
        this.dataSources = dataSources;
        this.seeders = seeders;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Demo demo : DEMOS) {
            try {
                bootstrap(demo);
            } catch (RuntimeException e) {
                // one demo tenant that cannot be set up never stops the application or the other demo tenants
                log.warn("demo tenant {} not set up: {}", demo.code(), e.getMessage());
            }
        }
    }

    private void bootstrap(Demo demo) {
        boolean known = !control.queryForList("SELECT 1 FROM control.tenant WHERE code = ?", demo.code()).isEmpty();
        if (!known) {
            String url = env.getProperty("COREBANKING_TENANT_" + demo.code().toUpperCase().replace('-', '_') + "_URL");
            if (url == null || url.isBlank()) {
                log.info("demo tenant {} skipped: this deployment has no database for it", demo.code());
                return;
            }
            provision(demo);
        }
        if (!dataSources.tenants().contains(demo.code())) return;     // provisioning did not finish: nothing to seed into
        // Every start, not only the first: a demo tenant created by an earlier build gets what was added since.
        TenantDataSources.runAs(demo.code(), () -> {
            for (DemoTenantSeeder s : seeders) {
                try {
                    s.seed();
                } catch (RuntimeException e) {
                    log.warn("demo tenant {}: seeding by {} failed: {}", demo.code(), s.getClass().getSimpleName(), e.getClass().getSimpleName());
                }
            }
        });
    }

    private void provision(Demo demo) {
        log.warn("bootstrapping LOCAL demo tenant {} (corebanking.bootstrap.demo-tenant=true)", demo.code());
        provisioner.provision(new TenantProvisioner.Request(demo.code(), demo.legalName(), demo.entityType(), "STANDALONE",
                demo.edition(), demo.starterKit(), new TenantProvisioner.HeadOffice("HO", "Head Office Kochi", "32"),
                LocalDate.of(2026, 6, 30), List.of(), null, null), "bootstrap");
        if (demo.depositRegistrationRef() != null) {
            // as an operator would: record the registration, then enable term deposits on top of the edition's modules
            provisioner.setDepositTaking(demo.code(), new TenantProvisioner.DepositRegistration(true, demo.depositRegistrationRef()), "bootstrap");
            @SuppressWarnings("unchecked")
            List<String> modules = new ArrayList<>((List<String>) provisioner.tenant(demo.code()).get("modules"));
            if (!modules.contains("TD")) modules.add("TD");
            provisioner.setModules(demo.code(), modules, "bootstrap");
        }
        DataSource ds = dataSources.of(demo.code());
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
