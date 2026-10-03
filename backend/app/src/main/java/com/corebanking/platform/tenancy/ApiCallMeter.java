package com.corebanking.platform.tenancy;

import com.corebanking.kernel.UsageMeter;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Counts API calls per tenant in memory (US-004). {@link TenantFilter} counts a call once the tenant of the request
 * is established; nothing is written to a database on the request path. The control plane's usage service drains
 * the counts on a timer and adds them to {@code control.usage_daily}.
 * Calls made under support access are not counted: they are the platform's own, not the tenant's usage.
 */
@Component
public class ApiCallMeter {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final UsageMeter meter = new UsageMeter();

    public void count(String tenant) {
        meter.count(tenant, LocalDate.now(IST));
    }

    /** Takes the counts gathered since the last call. */
    public Map<UsageMeter.Key, Long> drain() {
        return meter.drain();
    }

    /** Puts back a count whose write to the control plane failed. */
    public void restore(UsageMeter.Key key, long calls) {
        meter.add(key, calls);
    }
}
