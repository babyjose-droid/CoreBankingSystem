package com.corebanking.platform.tenancy;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Routes every connection to the current tenant's database (ADR-003: database per tenant).
 * Target data sources are registered by {@link TenantRegistry} from the control plane.
 */
public class TenantDataSourceRouter extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.require();
    }
}
