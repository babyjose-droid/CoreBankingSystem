package com.corebanking.platform.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class TenancyConfig {

    @Bean
    @ConfigurationProperties("corebanking.control.datasource")
    HikariDataSource controlDataSource() {
        return new HikariDataSource();
    }

    /** Standalone/dev credentials from environment: COREBANKING_TENANT_<CODE>_URL / _USER / _PASSWORD. */
    @Bean
    TenantRegistry.TenantCredentials envCredentials(Environment env) {
        return new TenantRegistry.TenantCredentials() {
            private String key(String code, String part) {
                return "COREBANKING_TENANT_" + code.toUpperCase().replace('-', '_') + "_" + part;
            }
            public String jdbcUrl(String code) { return env.getRequiredProperty(key(code, "URL")); }
            public String username(String code) { return env.getRequiredProperty(key(code, "USER")); }
            public String password(String code) { return env.getRequiredProperty(key(code, "PASSWORD")); }
        };
    }

    @Bean
    TenantRegistry tenantRegistry(@Qualifier("controlDataSource") DataSource control,
                                  TenantRegistry.TenantCredentials credentials) {
        TenantMigrator.migrateControl(control);
        return new TenantRegistry(control, credentials);
    }

    @Bean
    @Primary
    DataSource tenantDataSource(TenantRegistry registry) {
        TenantDataSourceRouter router = new TenantDataSourceRouter();
        var targets = registry.dataSources();
        targets.values().forEach(ds -> TenantMigrator.migrateTenant((DataSource) ds));
        router.setTargetDataSources(targets);
        router.setLenientFallback(false);
        router.afterPropertiesSet();
        return router;
    }

    @Bean
    @Primary
    PlatformTransactionManager transactionManager(DataSource tenantDataSource) {
        return new DataSourceTransactionManager(tenantDataSource);
    }
}
