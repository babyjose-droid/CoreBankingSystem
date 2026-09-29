package com.corebanking.eod;

import com.corebanking.kernel.EodEngine;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lets other modules add their end-of-day steps without the EOD module depending on them. Steps run between the
 * pre-checks and the GL snapshot, ordered by {@link #order()}.
 */
public interface EodStepProvider {

    /** Lower runs first. Lending uses 100–199. */
    int order();

    /**
     * @param tenantJdbc JdbcTemplate bound to the tenant's own data source (worker threads have no request tenant)
     * @param tenant     tenant code
     */
    List<EodEngine.Step> steps(JdbcTemplate tenantJdbc, String tenant);
}
