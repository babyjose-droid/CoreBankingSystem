package com.corebanking.eod.web;

import com.corebanking.kernel.EodEngine;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Platform end-of-day steps. Modules add theirs through {@link com.corebanking.eod.EodStepProvider} (lending: loan
 * day-end and borrower-level NPA).
 */
final class EodSteps {

    private EodSteps() {}

    /** Pre-checks, then every module's steps (by order), then GL snapshot, trial balance gate and date roll. */
    static List<EodEngine.Step> all(JdbcTemplate jdbc, String tenant, List<com.corebanking.eod.EodStepProvider> providers) {
        List<EodEngine.Step> steps = new java.util.ArrayList<>();
        steps.add(preChecks(jdbc));
        providers.stream().sorted(java.util.Comparator.comparingInt(com.corebanking.eod.EodStepProvider::order))
                .forEach(p -> steps.addAll(p.steps(jdbc, tenant)));
        steps.add(glSnapshot(jdbc));
        steps.add(trialBalanceGate(jdbc));
        steps.add(advanceDate(jdbc));
        return steps;
    }

    static EodEngine.TaskStep preChecks(JdbcTemplate jdbc) {
        return new EodEngine.TaskStep() {
            @Override public String name() { return "Pre-checks"; }
            @Override public void run(EodEngine.Context ctx) {
                LocalDate bd = jdbc.queryForObject("SELECT business_date FROM platform.business_day WHERE id = 1", LocalDate.class);
                if (!ctx.businessDate().equals(bd)) {
                    throw new IllegalStateException("run is for " + ctx.businessDate() + " but the business date is " + bd);
                }
                // A warning on the run, never a failure: pending approvals do not stop the day (ADR-015).
                PendingApprovals.Summary pending = PendingApprovals.of(jdbc);
                jdbc.update("""
                        UPDATE platform.eod_run SET warnings = CASE WHEN ? = 0 THEN '[]'::jsonb
                               ELSE jsonb_build_array(jsonb_build_object('code', 'PENDING_APPROVALS', 'message', ?::text, 'count', ?::int)) END
                         WHERE id = ?
                        """, pending.total(), pending.message(), pending.total(), ctx.runId());
            }
        };
    }

    static EodEngine.TaskStep glSnapshot(JdbcTemplate jdbc) {
        return new EodEngine.TaskStep() {
            @Override public String name() { return "GL balance snapshot"; }
            @Override public void run(EodEngine.Context ctx) {
                jdbc.queryForObject("SELECT ledger.snapshot_balances(?)", Integer.class, ctx.businessDate());
            }
        };
    }

    static EodEngine.TaskStep trialBalanceGate(JdbcTemplate jdbc) {
        return new EodEngine.TaskStep() {
            @Override public String name() { return "Trial balance gate"; }
            @Override public void run(EodEngine.Context ctx) {
                jdbc.execute("SELECT ledger.trial_balance_gate('" + ctx.businessDate() + "'::date)");
            }
        };
    }

    static EodEngine.TaskStep advanceDate(JdbcTemplate jdbc) {
        return new EodEngine.TaskStep() {
            @Override public String name() { return "Advance business date"; }
            @Override public void run(EodEngine.Context ctx) {
                LocalDate next = jdbc.queryForObject("SELECT platform.advance_business_date()", LocalDate.class);
                jdbc.update("UPDATE platform.eod_run SET next_business_date = ? WHERE id = ?", next, ctx.runId());
            }
        };
    }
}
