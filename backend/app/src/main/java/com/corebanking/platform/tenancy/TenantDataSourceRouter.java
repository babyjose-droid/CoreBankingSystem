package com.corebanking.platform.tenancy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Routes every connection to the current tenant's database (ADR-003: database per tenant). Tenants can be added
 * at runtime when the control plane provisions them.
 */
public final class TenantDataSourceRouter extends AbstractRoutingDataSource {

    private final Map<Object, Object> targets = new ConcurrentHashMap<>();

    public TenantDataSourceRouter() {
        setTargetDataSources(targets);
        setLenientFallback(false);
    }

    public synchronized void addTenant(String code, DataSource ds) {
        targets.put(code, ds);
        initialize();
    }

    public boolean hasTenant(String code) {
        return targets.containsKey(code);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.require();
    }
}
