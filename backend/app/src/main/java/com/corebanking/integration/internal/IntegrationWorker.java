package com.corebanking.integration.internal;

import com.corebanking.platform.TenantDataSources;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the integration tasks for every tenant: the outbox relay first, then the senders (payouts, webhooks,
 * messages) and the processors (provider callbacks, NACH response files). One failing task or tenant never stops
 * the others. Messages in the log name the task and the tenant, never payload data.
 */
@Component
class IntegrationWorker {

    private static final Logger log = LoggerFactory.getLogger(IntegrationWorker.class);

    private final TenantDataSources dataSources;
    private final List<IntegrationTask> tasks;
    private final boolean enabled;

    IntegrationWorker(TenantDataSources dataSources, List<IntegrationTask> tasks,
                      @Value("${corebanking.integration.worker-enabled:true}") boolean enabled) {
        this.dataSources = dataSources;
        this.tasks = tasks.stream().sorted(java.util.Comparator.comparing(t -> t instanceof OutboxRelay ? 0 : 1)).toList();
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${corebanking.integration.worker-interval-ms:5000}")
    void tick() {
        if (!enabled) return;
        for (String tenant : dataSources.tenants()) {
            TenantDataSources.runAs(tenant, () -> {
                for (IntegrationTask task : tasks) {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        log.warn("integration task {} failed for tenant {}: {}", task.name(), tenant, e.getClass().getSimpleName());
                    }
                }
            });
        }
    }
}
