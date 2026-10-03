package com.corebanking.platform.jobs;

import com.corebanking.platform.TenantDataSources;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls every tenant for jobs whose schedule has fired (US-112), like the end-of-day scheduler. Each tenant is
 * looked at on its own thread, so one tenant's long report does not hold up another tenant's jobs; a tenant that
 * is still being worked on is skipped until it is done. Which instance runs a job is settled in the database
 * ({@link JobService}), so every instance may run this.
 */
@Component
class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final TenantDataSources dataSources;
    private final JobService jobs;
    private final boolean enabled;
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    JobScheduler(TenantDataSources dataSources, JobService jobs, @Value("${corebanking.jobs.scheduler-enabled:true}") boolean enabled) {
        this.dataSources = dataSources;
        this.jobs = jobs;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${corebanking.jobs.scheduler-interval-ms:30000}", initialDelayString = "${corebanking.jobs.scheduler-initial-delay-ms:60000}")
    void tick() {
        if (!enabled) return;
        for (String tenant : dataSources.tenants()) {
            if (!busy.add(tenant)) continue;
            pool.submit(() -> {
                try {
                    jobs.runDue(tenant);
                } catch (RuntimeException e) {
                    log.warn("job schedule check failed for {}: {}", tenant, e.getMessage());
                } finally {
                    busy.remove(tenant);
                }
            });
        }
    }
}
