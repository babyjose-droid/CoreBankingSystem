package com.corebanking.eod.web;

import com.corebanking.kernel.EodEngine;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** EOD state in the tenant database (platform.eod_*). Uses the tenant's own data source: safe on worker threads. */
class JdbcEodStore implements EodEngine.Store {

    private final JdbcTemplate jdbc;

    JdbcEodStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EodEngine.StepStatus stepStatus(long runId, int stepNo) {
        List<String> s = jdbc.queryForList("SELECT status FROM platform.eod_step_run WHERE run_id = ? AND step_no = ?", String.class, runId, stepNo);
        return s.isEmpty() ? EodEngine.StepStatus.PENDING : EodEngine.StepStatus.valueOf(s.get(0));
    }

    @Override
    public void stepStarted(long runId, int stepNo, String name) {
        jdbc.update("""
                INSERT INTO platform.eod_step_run (run_id, step_no, name, status, started_at) VALUES (?, ?, ?, 'RUNNING', now())
                ON CONFLICT (run_id, step_no) DO UPDATE SET status = 'RUNNING', started_at = now(), finished_at = NULL
                """, runId, stepNo, name);
    }

    @Override
    public void stepFinished(long runId, int stepNo, EodEngine.StepStatus status, int processed, int failed) {
        jdbc.update("UPDATE platform.eod_step_run SET status = ?, processed = ?, failed = ?, finished_at = now() WHERE run_id = ? AND step_no = ?",
                status.name(), processed, failed, runId, stepNo);
    }

    @Override
    public boolean isCheckpointed(long runId, int stepNo, String item) {
        return !jdbc.queryForList("SELECT 1 FROM platform.eod_checkpoint WHERE run_id = ? AND step_no = ? AND item = ?", runId, stepNo, item).isEmpty();
    }

    @Override
    public void checkpoint(long runId, int stepNo, String item) {
        jdbc.update("INSERT INTO platform.eod_checkpoint (run_id, step_no, item) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", runId, stepNo, item);
    }

    @Override
    public void recordException(long runId, int stepNo, String stepName, String item, String error) {
        jdbc.update("""
                INSERT INTO platform.eod_exception (run_id, step, account_no, error, step_no) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (run_id, step, account_no) DO UPDATE SET error = EXCLUDED.error, at = now(), resolved = false
                """, runId, stepName, item, error, stepNo);
    }

    @Override
    public void runFinished(long runId, EodEngine.RunStatus status) {
        jdbc.update("UPDATE platform.eod_run SET status = ?, finished_at = now() WHERE id = ?", status.name(), runId);
    }
}
