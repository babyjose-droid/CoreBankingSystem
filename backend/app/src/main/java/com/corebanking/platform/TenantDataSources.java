package com.corebanking.platform;

import com.corebanking.platform.tenancy.TenantContext;
import com.corebanking.platform.tenancy.TenantDataSourceRouter;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Access to a specific tenant's database for background work (EOD, schedulers) that runs outside a request —
 * worker threads do not inherit the request's tenant binding, so they use the tenant's own data source.
 */
@Component
public class TenantDataSources {

    private final TenantDataSourceRouter router;

    public TenantDataSources(TenantDataSourceRouter router) {
        this.router = router;
    }

    public DataSource of(String tenant) {
        Map<Object, DataSource> resolved = router.getResolvedDataSources();
        DataSource ds = resolved.get(tenant);
        if (ds == null) throw new IllegalStateException("tenant " + tenant + " is not registered");
        return ds;
    }

    public List<String> tenants() {
        return router.getResolvedDataSources().keySet().stream().map(String::valueOf).sorted().toList();
    }

    /** Runs {@code work} with {@code tenant} bound to the current thread (for code that uses the routed data source). */
    public static void runAs(String tenant, Runnable work) {
        String previous = TenantContext.currentOrNull();
        try {
            TenantContext.set(tenant);
            work.run();
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }
}
