package com.corebanking.lending.internal;

import com.corebanking.platform.DemoTenantSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * LOCAL DEVELOPMENT ONLY (corebanking.bootstrap.demo-tenant=true): one rate for the REPO benchmark, so a
 * floating-rate product (the HOME_FLOATING template) can book a loan on the demo tenant straight away. The figure
 * is demo data, not the Reserve Bank's rate: a real tenant records benchmark rates through maker-checker.
 */
@Component
@ConditionalOnProperty(name = "corebanking.bootstrap.demo-tenant", havingValue = "true")
class DemoLendingSeeder implements DemoTenantSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoLendingSeeder.class);

    private final JdbcTemplate jdbc;

    DemoLendingSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void seed() {
        // before the demo's opening business date (30-Jun-2026); only while the benchmark has no rate at all
        int n = jdbc.update("""
                INSERT INTO lending.benchmark_rate (benchmark_code, effective_from, rate, recorded_by)
                SELECT 'REPO', DATE '2026-01-01', 6.00, 'bootstrap (demo data, not the RBI rate)'
                 WHERE EXISTS (SELECT 1 FROM lending.benchmark WHERE code = 'REPO')
                   AND NOT EXISTS (SELECT 1 FROM lending.benchmark_rate WHERE benchmark_code = 'REPO')
                """);
        if (n > 0) log.warn("demo tenant: REPO benchmark rate 6.00% from 2026-01-01 added (demo data, local development only)");
    }
}
