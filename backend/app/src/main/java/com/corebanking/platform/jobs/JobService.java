package com.corebanking.platform.jobs;

import com.corebanking.audit.AuditLog;
import com.corebanking.kernel.CronSchedule;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.JobHandler;
import com.corebanking.platform.Json;
import com.corebanking.platform.TenantDataSources;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The tenant job catalogue (US-112): which jobs exist, when they run, and the record of every run.
 * <ul>
 *   <li>A job is a row of {@code platform.job_definition}; its work is done by the {@link JobHandler} of its kind.</li>
 *   <li>A run is recorded in {@code platform.job_run} before the work starts and finished afterwards, with counts,
 *       an error or an artifact.</li>
 *   <li><b>No double run.</b> While a job runs, the instance holds a PostgreSQL advisory lock for it on a dedicated
 *       connection; another instance (or a manual run) that cannot get the lock leaves the job alone. A scheduled
 *       fire time is also unique per job in the database, so two instances that poll at the same moment start it
 *       once. If an instance dies, its lock goes with its connection; the next run marks the run it left behind
 *       as FAILED ("interrupted").</li>
 *   <li>The work runs on its own thread with the tenant bound and no user: handlers act as the system, whoever
 *       pressed "run".</li>
 *   <li>Schedules (cron in IST), the on/off switch and parameters change through maker-checker (entity
 *       JOB_SCHEDULE). Missed fire times are not replayed: after an outage a job runs once.</li>
 * </ul>
 */
@Service
public class JobService {

    /** openapi.yaml#/components/schemas/JobScheduleInput. */
    public record ScheduleInput(String schedule, Boolean enabled, Map<String, Object> parameters) {}

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final String UTC = "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'";
    private static final String IST_LOCAL = "'YYYY-MM-DD\"T\"HH24:MI:SS'";

    private record Definition(String code, String name, String kind, String schedule, boolean enabled, Map<String, Object> parameters) {}
    private record Due(String code, String schedule, LocalDateTime since) {}

    private final TenantDataSources dataSources;
    private final Json json;
    private final ApprovalService approvals;
    private final AuditLog audit;
    private final Map<String, JobHandler> handlers;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public JobService(TenantDataSources dataSources, Json json, ApprovalService approvals, AuditLog audit, List<JobHandler> handlers) {
        this.dataSources = dataSources;
        this.json = json;
        this.approvals = approvals;
        this.audit = audit;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::kind, Function.identity()));
    }

    // ------------------------------------------------------------------------------------------------ catalogue
    /** openapi.yaml#/components/schemas/Job: every job with its next fire time and its last run. */
    public List<Map<String, Object>> list(String tenant) {
        JdbcTemplate jdbc = jdbc(tenant);
        LocalDateTime now = LocalDateTime.now(IST);
        return jdbc.query("SELECT d.code, d.name, d.kind, d.schedule, d.enabled, d.parameters::text, d.built_in, d.updated_by,"
                + " to_char(d.updated_at AT TIME ZONE 'UTC', " + UTC + ") FROM platform.job_definition d ORDER BY d.kind = 'REPORT', d.code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String code = rs.getString(1);
                    m.put("code", code);
                    m.put("name", rs.getString(2));
                    m.put("kind", rs.getString(3));
                    m.put("schedule", rs.getString(4));
                    m.put("enabled", rs.getBoolean(5));
                    m.put("parameters", json.readMap(rs.getString(6)));
                    m.put("builtIn", rs.getBoolean(7));
                    m.put("updatedBy", rs.getString(8));
                    m.put("updatedAt", rs.getString(9));
                    m.put("runnable", handlers.containsKey(rs.getString(3)));
                    m.put("nextRunAt", rs.getBoolean(5) ? nextRun(rs.getString(4), now) : null);
                    List<Map<String, Object>> last = runs(jdbc, "r.job_code = ? ORDER BY r.started_at DESC, r.id LIMIT 1", code);
                    m.put("lastRun", last.isEmpty() ? null : last.get(0));
                    return m;
                });
    }

    /** The next fire time as an IST timestamp with offset, or null when there is no (valid) schedule. */
    private static String nextRun(String schedule, LocalDateTime now) {
        if (schedule == null) return null;
        try {
            LocalDateTime next = CronSchedule.parse(schedule).next(now);
            return next == null ? null : next.atZone(IST).toOffsetDateTime().toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** openapi.yaml#/components/schemas/JobRun, newest first, optionally of one job. */
    public List<Map<String, Object>> runs(String tenant, String jobCode, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        return runs(jdbc(tenant), "(?::text IS NULL OR r.job_code = ?) ORDER BY r.started_at DESC, r.id LIMIT ? OFFSET ?",
                jobCode, jobCode, limit, Math.max(page, 0) * limit);
    }

    private List<Map<String, Object>> runs(JdbcTemplate jdbc, String where, Object... args) {
        return jdbc.query("SELECT r.id, r.job_code, r.origin, to_char(r.scheduled_for AT TIME ZONE 'UTC', " + UTC + "), r.requested_by, r.status,"
                + " to_char(r.started_at AT TIME ZONE 'UTC', " + UTC + "), to_char(r.finished_at AT TIME ZONE 'UTC', " + UTC + "),"
                + " r.processed, r.failed, r.error, r.artifact::text FROM platform.job_run r WHERE " + where,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("jobCode", rs.getString(2));
                    m.put("origin", rs.getString(3));
                    m.put("scheduledFor", rs.getString(4));
                    m.put("requestedBy", rs.getString(5));
                    m.put("status", rs.getString(6));
                    m.put("startedAt", rs.getString(7));
                    m.put("finishedAt", rs.getString(8));
                    m.put("processed", rs.getInt(9));
                    m.put("failed", rs.getInt(10));
                    m.put("error", rs.getString(11));
                    m.put("artifact", json.readMap(rs.getString(12)));
                    return m;
                }, args);
    }

    private Definition definition(JdbcTemplate jdbc, String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,60}")) throw ApiException.notFound("job " + code);
        List<Definition> l = jdbc.query("SELECT code, name, kind, schedule, enabled, parameters::text FROM platform.job_definition WHERE code = ?",
                (rs, i) -> new Definition(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                        orEmpty(json.readMap(rs.getString(6)))), code);
        if (l.isEmpty()) throw ApiException.notFound("job " + code);
        return l.get(0);
    }

    private JdbcTemplate jdbc(String tenant) {
        return new JdbcTemplate(dataSources.of(tenant));
    }

    private static Map<String, Object> orEmpty(Map<String, Object> m) {
        return m == null ? Map.of() : m;
    }

    // ------------------------------------------------------------------------------------------------ schedule
    /** Proposes a schedule, the on/off switch and parameters for a job (maker-checker). */
    public ApprovalRequest proposeSchedule(String tenant, String code, ScheduleInput in) {
        Definition d = definition(jdbc(tenant), code);
        JobHandler handler = handlers.get(d.kind());
        if (handler == null) throw ApiException.conflict("job kind " + d.kind() + " cannot run in this installation");
        String schedule = in.schedule() == null || in.schedule().isBlank() ? null : in.schedule().trim();
        boolean enabled = in.enabled() == null ? d.enabled() : in.enabled();
        if (schedule != null) {
            String problem = CronSchedule.problem(schedule);
            if (problem != null) throw ApiException.invalid(problem);
            CronSchedule cron = CronSchedule.parse(schedule);
            schedule = cron.toString();
            LocalDateTime first = cron.next(LocalDateTime.now(IST));
            LocalDateTime second = first == null ? null : cron.next(first);
            if (first == null) throw ApiException.invalid("this schedule never fires");
            if (second != null && Duration.between(first, second).getSeconds() < handler.minimumIntervalSeconds()) {
                throw ApiException.invalid("job " + code + " may run at most once every " + handler.minimumIntervalSeconds() + " seconds");
            }
        }
        Map<String, Object> parameters = handler.checkParameters(code, d.parameters(), in.parameters() == null ? d.parameters() : in.parameters());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("schedule", schedule);
        payload.put("enabled", enabled);
        payload.put("parameters", parameters);
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("code", code);
        current.put("schedule", d.schedule());
        current.put("enabled", d.enabled());
        current.put("parameters", d.parameters());
        return approvals.propose("JOB_SCHEDULE", "UPDATE", code, payload, current, null, null, null);
    }

    /** Applies an approved schedule. */
    @Service
    static class ScheduleApplier implements ApprovalApplier {
        private final JdbcTemplate jdbc;
        private final Json json;

        ScheduleApplier(JdbcTemplate jdbc, Json json) {
            this.jdbc = jdbc;
            this.json = json;
        }

        @Override public String entityType() { return "JOB_SCHEDULE"; }

        @Override
        public String apply(ApprovalRequest r) {
            Map<String, Object> p = r.payload();
            Object parameters = p.get("parameters") == null ? Map.of() : p.get("parameters");
            int n = jdbc.update("UPDATE platform.job_definition SET schedule = ?, enabled = ?, parameters = ?::jsonb, updated_by = ? WHERE code = ?",
                    p.get("schedule"), Boolean.TRUE.equals(p.get("enabled")), json.write(parameters), r.maker(), p.get("code"));
            if (n == 0) throw ApiException.notFound("job " + p.get("code"));
            return String.valueOf(p.get("code"));
        }
    }

    // ------------------------------------------------------------------------------------------------ running
    /** Runs a job now at a user's request and returns its run. 409 when the job is already running. */
    public Map<String, Object> runNow(String tenant, String code) {
        String user = CurrentUser.username();
        Definition d = definition(jdbc(tenant), code);
        TenantDataSources.runAs(tenant, () -> audit.record(user, "JOB_RUN_REQUESTED", "JOB", code, Map.of("kind", d.kind())));
        UUID id = execute(tenant, d, null, user);
        if (id == null) throw ApiException.conflict("job " + code + " is already running");
        return runs(jdbc(tenant), "r.id = ?", id).get(0);
    }

    /** Starts every job of the tenant whose schedule has fired since it last ran. Called by the scheduler. */
    void runDue(String tenant) {
        JdbcTemplate jdbc = jdbc(tenant);
        LocalDateTime now = LocalDateTime.now(IST);
        List<Due> due = jdbc.query("SELECT d.code, d.schedule, to_char(greatest(d.updated_at, coalesce((SELECT max(r.scheduled_for) FROM platform.job_run r"
                + " WHERE r.job_code = d.code), d.updated_at)) AT TIME ZONE 'Asia/Kolkata', " + IST_LOCAL + ")"
                + " FROM platform.job_definition d WHERE d.enabled AND d.schedule IS NOT NULL ORDER BY d.code",
                (rs, i) -> new Due(rs.getString(1), rs.getString(2), LocalDateTime.parse(rs.getString(3))));
        for (Due j : due) {
            try {
                LocalDateTime fire = CronSchedule.parse(j.schedule()).due(j.since(), now);
                if (fire == null) continue;
                Definition d = definition(jdbc, j.code());
                if (!handlers.containsKey(d.kind())) continue;
                execute(tenant, d, fire, "scheduler");
            } catch (RuntimeException e) {
                log.warn("job {} of tenant {} could not be started: {}", j.code(), tenant, e.getMessage());
            }
        }
    }

    /**
     * Runs one job under its advisory lock.
     *
     * @param fire the cron fire time (IST) this run answers, or null for a manual run
     * @return the run id, or null when the job is running elsewhere or the fire time was already answered
     */
    private UUID execute(String tenant, Definition d, LocalDateTime fire, String requestedBy) {
        JobHandler handler = handlers.get(d.kind());
        if (handler == null) throw ApiException.conflict("job kind " + d.kind() + " cannot run in this installation");
        DataSource ds = dataSources.of(tenant);
        try (Connection lock = ds.getConnection()) {
            if (!advisory(lock, "SELECT pg_try_advisory_lock(hashtext('corebanking.job'), hashtext(?))", d.code())) return null;
            try {
                JdbcTemplate jdbc = new JdbcTemplate(ds);
                // We hold the lock, so nobody is running this job: a RUNNING row was left by an instance that stopped.
                jdbc.update("UPDATE platform.job_run SET status = 'FAILED', error = 'interrupted: the instance running it stopped' "
                        + "WHERE job_code = ? AND status = 'RUNNING'", d.code());
                UUID id = UUID.randomUUID();
                int inserted = jdbc.update("INSERT INTO platform.job_run (id, job_code, origin, scheduled_for, requested_by) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT DO NOTHING", id, d.code(), fire == null ? "MANUAL" : "SCHEDULE",
                        fire == null ? null : Timestamp.from(fire.atZone(IST).toInstant()), requestedBy);
                if (inserted == 0) return null;                    // this fire time was answered by another instance
                JobHandler.Context context = new JobHandler.Context(tenant, d.code(), d.parameters(), requestedBy, fire == null ? null : d.schedule());
                JobHandler.Result result = null;
                String error = null;
                try {
                    result = onWorker(tenant, handler, context);
                } catch (RuntimeException e) {
                    error = failure(e);
                    log.warn("job {} of tenant {} failed: {}", d.code(), tenant, error);
                }
                if (error == null) {
                    jdbc.update("UPDATE platform.job_run SET status = 'COMPLETED', processed = ?, failed = ?, artifact = ?::jsonb WHERE id = ?",
                            result.processed(), result.failed(), result.artifact() == null ? null : json.write(result.artifact()), id);
                } else {
                    jdbc.update("UPDATE platform.job_run SET status = 'FAILED', error = ? WHERE id = ?", error, id);
                }
                return id;
            } finally {
                release(lock, d.code());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("job " + d.code() + " could not take its lock", e);
        }
    }

    /** The handler's work, on its own thread: tenant bound, no user (the system acts), no inherited transaction. */
    private JobHandler.Result onWorker(String tenant, JobHandler handler, JobHandler.Context context) {
        Future<JobHandler.Result> future = workers.submit(() -> {
            JobHandler.Result[] out = new JobHandler.Result[1];
            TenantDataSources.runAs(tenant, () -> out[0] = handler.run(context));
            return out[0] == null ? JobHandler.Result.of(0) : out[0];
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * Gives the lock back. The connection returns to the pool afterwards, so a lock that could not be released must
     * not stay on it: in that case the database connection itself is closed, which releases every lock it holds.
     */
    private static void release(Connection lock, String code) {
        try {
            advisory(lock, "SELECT pg_advisory_unlock(hashtext('corebanking.job'), hashtext(?))", code);
        } catch (SQLException e) {
            log.warn("job {}: lock not released cleanly, closing its connection", code);
            try {
                lock.unwrap(Connection.class).close();
            } catch (SQLException ignored) {
                // the connection is already gone, and its locks with it
            }
        }
    }

    private static boolean advisory(Connection c, String sql, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** What went wrong, for the run record: the first line of the innermost message, at most 500 characters. */
    private static String failure(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        int nl = m.indexOf('\n');
        if (nl > 0) m = m.substring(0, nl);
        m = m.replaceFirst("^ERROR:\\s*", "");
        return m.isBlank() ? "the job failed" : m.length() > 500 ? m.substring(0, 500) : m;
    }
}
