package com.corebanking.platform.control;

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

    private static final Logger log = LoggerFactory.getLogger(DemoTenantBootstrap.class);

    private final TenantProvisioner provisioner;
    private final JdbcTemplate control;
    private final com.corebanking.platform.TenantDataSources dataSources;

    DemoTenantBootstrap(TenantProvisioner provisioner, @Qualifier("controlJdbc") JdbcTemplate control,
                        com.corebanking.platform.TenantDataSources dataSources) {
        this.provisioner = provisioner;
        this.control = control;
        this.dataSources = dataSources;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!control.queryForList("SELECT 1 FROM control.tenant WHERE code = 'demo-nbfc'").isEmpty()) return;
        log.warn("bootstrapping LOCAL demo tenant demo-nbfc (corebanking.bootstrap.demo-tenant=true)");
        provisioner.provision(new TenantProvisioner.Request("demo-nbfc", "CLAUDE-TEST Demo NBFC Limited", "NBFC", "STANDALONE",
                "GROWTH", "NBFC", new TenantProvisioner.HeadOffice("HO", "Head Office Kochi", "32"),
                LocalDate.of(2026, 6, 30), List.of(), null, null), "bootstrap");
        DataSource ds = dataSources.of("demo-nbfc");
        JdbcTemplate t = new JdbcTemplate(ds);
        t.update("INSERT INTO platform.branch (code, name, state_code, parent_code) VALUES ('MUM', 'Mumbai', '27', 'HO')");
        // The integration client's profile (service-account-corebanking-service) is created by the provisioner.
        for (String u : new String[] {"maker", "checker", "ops", "auditor", "admin"}) {
            t.update("INSERT INTO platform.staff_user (user_id, username, display_name, home_branch, all_branches) VALUES (?, ?, ?, 'HO', true)",
                    "local:" + u, u, "CLAUDE-TEST " + u);
        }
    }
}
