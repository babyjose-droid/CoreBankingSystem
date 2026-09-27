package com.corebanking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CoreBanking — modular monolith (ADR-002). Each top-level package is a Spring Modulith module;
 * modules talk through their public API package or through events, never through each other's internals.
 * Flyway auto-configuration is off (spring.flyway.enabled=false in application.yml): {@link com.corebanking.platform.tenancy.TenantMigrator} migrates
 * the control plane and every tenant database explicitly.
 */
@SpringBootApplication
public class CoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}
