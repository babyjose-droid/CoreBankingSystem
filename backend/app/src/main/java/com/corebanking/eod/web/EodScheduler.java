package com.corebanking.eod.web;

import com.corebanking.platform.TenantDataSources;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Starts end of day for tenants whose schedule is SCHEDULED when their cron fires (US-110). Times are IST.
 * With several app instances, the database's single-running-run index makes sure only one start succeeds.
 */
@Component
class EodScheduler {

    private static final Logger log = LoggerFactory.getLogger(EodScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TenantDataSources dataSources;
    private final EodService eod;
    private final boolean enabled;
    private final Map<String, LocalDateTime> lastChecked = new ConcurrentHashMap<>();

    EodScheduler(TenantDataSources dataSources, EodService eod, @Value("${corebanking.eod.scheduler-enabled:true}") boolean enabled) {
        this.dataSources = dataSources;
        this.eod = eod;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${corebanking.eod.scheduler-interval-ms:60000}")
    void tick() {
        if (!enabled) return;
        LocalDateTime now = LocalDateTime.now(IST);
        for (String tenant : dataSources.tenants()) {
            try {
                check(tenant, now);
            } catch (RuntimeException e) {
                log.warn("EOD schedule check failed for {}: {}", tenant, e.getMessage());
            }
        }
    }

    private void check(String tenant, LocalDateTime now) {
        LocalDateTime since = lastChecked.put(tenant, now);
        if (since == null) return;                                   // first tick after start: just record
        JdbcTemplate jdbc = new JdbcTemplate(dataSources.of(tenant));
        List<Map<String, Object>> s = jdbc.queryForList("SELECT mode, cron FROM platform.eod_schedule WHERE id = 1");
        if (s.isEmpty() || !"SCHEDULED".equals(s.get(0).get("mode"))) return;
        CronExpression cron = CronExpression.parse((String) s.get(0).get("cron"));
        LocalDateTime next = cron.next(since);
        if (next != null && !next.isAfter(now)) {
            log.info("scheduled EOD firing for {}", tenant);
            eod.start(tenant, "scheduler");
        }
    }
}
