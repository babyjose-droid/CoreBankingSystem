package com.corebanking.eod.web;

import com.corebanking.audit.AuditLog;
import com.corebanking.kernel.EodEngine;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.Json;
import com.corebanking.platform.TenantDataSources;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Starts, restarts and reports end-of-day runs (US-108, US-109). A run executes on a background virtual thread
 * against the tenant's own data source. Only one run per tenant can be RUNNING (database unique index).
 */
@Service
class EodService {

    private static final Logger log = LoggerFactory.getLogger(EodService.class);

    private final TenantDataSources dataSources;
    private final AuditLog audit;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final int partitions;
    private final List<com.corebanking.eod.EodStepProvider> providers;
    private final List<com.corebanking.eod.EodListener> listeners;
    private final Json json;
    private final com.corebanking.platform.Mail mail;

    EodService(TenantDataSources dataSources, AuditLog audit, List<com.corebanking.eod.EodStepProvider> providers,
               List<com.corebanking.eod.EodListener> listeners, Json json, com.corebanking.platform.Mail mail,
               @org.springframework.beans.factory.annotation.Value("${corebanking.eod.partitions:4}") int partitions) {
        this.listeners = List.copyOf(listeners);
        this.dataSources = dataSources;
        this.audit = audit;
        this.partitions = partitions;
        this.providers = List.copyOf(providers);
        this.json = json;
        this.mail = mail;
    }

    /** Pending dated approvals, for the start confirmation (GET /eod/pending-approvals). */
    Map<String, Object> pendingApprovals(String tenant) {
        return PendingApprovals.of(new JdbcTemplate(dataSources.of(tenant))).view();
    }

    Map<String, Object> start(String tenant, String user) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSources.of(tenant));
        Map<String, Object> day = jdbc.queryForMap("SELECT business_date, status FROM platform.business_day WHERE id = 1");
        if (!"OPEN".equals(day.get("status"))) throw ApiException.conflict("business day is " + day.get("status"));
        LocalDate bd = ((java.sql.Date) day.get("business_date")).toLocalDate();
        PendingApprovals.Summary pending = PendingApprovals.of(jdbc);
        if (pending.blocking()) throw ApiException.conflict(pending.message());
        Long runId;
        try {
            runId = jdbc.queryForObject("""
                    INSERT INTO platform.eod_run (business_date, status, started_by) VALUES (?, 'RUNNING', ?) RETURNING id
                    """, Long.class, bd, user);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("end of day is already running or already completed for " + bd);
        }
        jdbc.update("UPDATE platform.business_day SET status = 'EOD_RUNNING' WHERE id = 1");
        audit(tenant, user, "EOD_START", runId);
        launch(tenant, runId, bd);
        return run(tenant, runId);
    }

    Map<String, Object> restart(String tenant, long runId, String user) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSources.of(tenant));
        Map<String, Object> r = jdbc.queryForMap("SELECT business_date, status FROM platform.eod_run WHERE id = ?", runId);
        if (!"FAILED".equals(r.get("status"))) throw ApiException.conflict("only a FAILED run can be restarted");
        try {
            jdbc.update("UPDATE platform.eod_run SET status = 'RUNNING', finished_at = NULL WHERE id = ?", runId);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("another end of day is running");
        }
        jdbc.update("UPDATE platform.business_day SET status = 'EOD_RUNNING' WHERE id = 1");
        audit(tenant, user, "EOD_RESTART", runId);
        launch(tenant, runId, ((java.sql.Date) r.get("business_date")).toLocalDate());
        return run(tenant, runId);
    }

    private void launch(String tenant, long runId, LocalDate bd) {
        executor.submit(() -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSources.of(tenant));
            EodEngine.RunStatus status;
            try {
                status = new EodEngine(EodSteps.all(jdbc, tenant, providers), new JdbcEodStore(jdbc), partitions)
                        .run(new EodEngine.Context(runId, bd, tenant));
            } catch (RuntimeException e) {
                log.error("EOD run {} for {} crashed", runId, tenant, e);
                jdbc.update("UPDATE platform.eod_run SET status = 'FAILED', finished_at = now() WHERE id = ?", runId);
                status = EodEngine.RunStatus.FAILED;
            }
            if (status == EodEngine.RunStatus.FAILED) {
                jdbc.update("UPDATE platform.business_day SET status = 'EOD_FAILED' WHERE id = 1");
                alert(jdbc, tenant, runId);
            }
            log.info("EOD run {} for {} finished {}", runId, tenant, status);
            if (status != EodEngine.RunStatus.FAILED) {
                LocalDate opened = jdbc.queryForObject("SELECT business_date FROM platform.business_day WHERE id = 1", LocalDate.class);
                for (com.corebanking.eod.EodListener listener : listeners) {
                    try {
                        TenantDataSources.runAs(tenant, () -> listener.businessDateOpened(tenant, bd, opened));
                    } catch (RuntimeException e) {
                        log.warn("after end of day {} for {}: {} failed", runId, tenant, listener.getClass().getSimpleName(), e);
                    }
                }
            }
        });
    }

    /**
     * A failed run is e-mailed to the schedule's alert recipients (no personal data: run, date, failed steps). Without a
     * mail relay it is only logged. Addresses are never logged.
     */
    private void alert(JdbcTemplate jdbc, String tenant, long runId) {
        List<String> to = jdbc.queryForList("SELECT unnest(alert_emails) FROM platform.eod_schedule WHERE id = 1", String.class);
        log.warn("ALERT: EOD run {} for tenant {} FAILED; {} alert recipient(s)", runId, tenant, to.size());
        if (to.isEmpty() || !mail.enabled()) return;
        List<String> failed = jdbc.queryForList("SELECT name FROM platform.eod_step_run WHERE run_id = ? AND status = 'FAILED' ORDER BY step_no",
                String.class, runId);
        LocalDate date = jdbc.queryForObject("SELECT business_date FROM platform.eod_run WHERE id = ?", LocalDate.class, runId);
        try {
            mail.send(to, "End of day FAILED for " + tenant + " (" + date + ")",
                    "End-of-day run " + runId + " for business date " + date + " failed.\nFailed step(s): " + String.join(", ", failed)
                            + "\nFix the cause and restart the run from the console (End of day → runs).", List.of());
        } catch (IllegalStateException e) {
            log.warn("EOD failure alert for run {} of tenant {} could not be e-mailed: {}", runId, tenant, e.getMessage());
        }
    }

    List<Map<String, Object>> runs(String tenant) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSources.of(tenant));
        return jdbc.queryForList("SELECT id FROM platform.eod_run ORDER BY id DESC LIMIT 60", Long.class).stream()
                .map(id -> run(tenant, id)).toList();
    }

    Map<String, Object> run(String tenant, long runId) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSources.of(tenant));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, business_date::text AS "businessDate", status, started_at AS "startedAt",
                       finished_at AS "finishedAt", next_business_date::text AS "nextBusinessDate", warnings::text AS warnings
                  FROM platform.eod_run WHERE id = ?
                """, runId);
        if (rows.isEmpty()) throw ApiException.notFound("EOD run " + runId);
        Map<String, Object> m = new LinkedHashMap<>(rows.get(0));
        m.put("warnings", m.get("warnings") == null ? List.of() : json.read((String) m.get("warnings"), Object.class));
        m.put("steps", jdbc.queryForList("""
                SELECT step_no AS "stepNo", name, status, processed, failed, started_at AS "startedAt", finished_at AS "finishedAt"
                  FROM platform.eod_step_run WHERE run_id = ? ORDER BY step_no
                """, runId));
        m.put("exceptions", jdbc.queryForList("""
                SELECT step, account_no AS "accountNo", error, resolved FROM platform.eod_exception
                 WHERE run_id = ? ORDER BY step_no, account_no
                """, runId));
        return m;
    }

    private void audit(String tenant, String user, String action, long runId) {
        TenantDataSources.runAs(tenant, () -> audit.record(user, action, "EOD_RUN", String.valueOf(runId), Map.of()));
    }
}
