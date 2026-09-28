package com.corebanking.platform.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class TenancyConfig {

    @Bean
    @ConfigurationProperties("corebanking.control.datasource")
    HikariDataSource controlDataSource() {
        return new HikariDataSource();
    }

    /**
     * Dev/standalone credentials from environment: COREBANKING_TENANT_&lt;CODE&gt;_URL / _USER / _PASSWORD, with the code
     * upper-cased and '-' replaced by '_'. Cloud tiers sync Secrets Manager into these variables (runbook).
     */
    @Bean
    TenantRegistry.TenantCredentials envCredentials(Environment env) {
        return new TenantRegistry.TenantCredentials() {
            private String key(String code, String part) {
                return "COREBANKING_TENANT_" + code.toUpperCase().replace('-', '_') + "_" + part;
            }
            @Override public String jdbcUrl(String code) { return env.getRequiredProperty(key(code, "URL")); }
            @Override public String username(String code) { return env.getRequiredProperty(key(code, "USER")); }
            @Override public String password(String code) { return env.getRequiredProperty(key(code, "PASSWORD")); }
        };
    }

    @Bean
    TenantDataSourceRouter tenantRouter() {
        TenantDataSourceRouter router = new TenantDataSourceRouter();
        router.afterPropertiesSet();
        return router;
    }

    @Bean
    TenantRegistry tenantRegistry(@Qualifier("controlDataSource") DataSource control,
                                  TenantRegistry.TenantCredentials credentials, TenantDataSourceRouter router) {
        TenantMigrator.migrateControl(control);
        TenantRegistry registry = new TenantRegistry(control, credentials, router);
        registry.startAll();
        return registry;
    }

    /** The data source everything uses by default: routed to the current tenant. */
    @Bean
    @Primary
    DataSource tenantDataSource(TenantDataSourceRouter router, TenantRegistry registry) {
        return router;
    }

    @Bean
    @Primary
    JdbcTemplate jdbcTemplate(DataSource tenantDataSource) {
        return new JdbcTemplate(tenantDataSource);
    }

    @Bean("controlJdbc")
    JdbcTemplate controlJdbc(@Qualifier("controlDataSource") DataSource control) {
        return new JdbcTemplate(control);
    }

    @Bean
    @Primary
    PlatformTransactionManager transactionManager(DataSource tenantDataSource) {
        return new DataSourceTransactionManager(tenantDataSource);
    }
}
