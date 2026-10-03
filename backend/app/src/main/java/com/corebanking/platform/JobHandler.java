package com.corebanking.platform;

import java.util.Map;

/**
 * One kind of tenant job (US-112). Each module registers a bean per kind it owns (the platform: usage snapshot;
 * customer: KYC and consent expiry; reporting: scheduled reports, dashboard refresh, export clean-up; lending:
 * booking of receipts accepted during end of day). The job framework decides when a job runs and records the run;
 * the handler only does the work.
 * <p>
 * A handler runs on a worker thread with the tenant bound ({@link CurrentUser#requireTenant()} works, the routed
 * JdbcTemplate reaches the tenant's database) and with no user: {@link CurrentUser#get()} is the system user. It is
 * not inside a transaction; a handler opens its own. An exception marks the run FAILED with its message.
 */
public interface JobHandler {

    /** The job kind handled, as in {@code platform.job_definition.kind}, e.g. "KYC_EXPIRY". */
    String kind();

    Result run(Context context);

    /**
     * Checks the parameters of a schedule proposed by a staff user and returns them as they should be stored.
     * Called on the request thread, so {@link CurrentUser} is the maker. The default accepts no parameters.
     *
     * @param current the parameters stored today (never null)
     * @throws ApiException 422 when a parameter is not valid, 403 when the maker may not schedule this
     */
    default Map<String, Object> checkParameters(String jobCode, Map<String, Object> current, Map<String, Object> proposed) {
        if (proposed != null && !proposed.isEmpty() && !proposed.equals(current)) {
            throw ApiException.invalid("job " + jobCode + " takes no parameters");
        }
        return current;
    }

    /** Shortest time between two scheduled runs, in seconds. */
    default int minimumIntervalSeconds() {
        return 60;
    }

    /**
     * @param parameters  the job's stored parameters (never null)
     * @param requestedBy "scheduler" or the user who started a manual run
     * @param schedule    the cron expression that fired, or null for a manual run
     */
    record Context(String tenant, String jobCode, Map<String, Object> parameters, String requestedBy, String schedule) {}

    /**
     * @param processed items handled (customers expired, files removed, receipts booked …)
     * @param failed    items that could not be handled; the run still counts as completed
     * @param artifact  what the run produced, shown with the run; may be null. Never personal data.
     */
    record Result(int processed, int failed, Map<String, Object> artifact) {
        public static Result of(int processed) {
            return new Result(processed, 0, null);
        }
    }
}
