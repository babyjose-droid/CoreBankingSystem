package com.corebanking.platform;

/**
 * LOCAL DEVELOPMENT ONLY. A module's demo configuration for the tenant "demo-nbfc", applied by the demo tenant
 * bootstrap (corebanking.bootstrap.demo-tenant=true) with that tenant bound to the thread. Runs on every start, so
 * an implementation adds only what is missing and never changes what is there.
 */
public interface DemoTenantSeeder {

    void seed();
}
