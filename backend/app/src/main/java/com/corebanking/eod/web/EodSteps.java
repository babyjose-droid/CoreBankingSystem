package com.corebanking.eod.web;

import com.corebanking.kernel.EodEngine;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 1 end-of-day steps. Lending steps (interest accrual, demand raising, DPD/NPA, provisioning) are added in
 * Phase 2 as further {@link EodEngine.ItemStep}s between the account checks and the GL snapshot.
 */
final class EodSteps {

    private EodSteps() {}

    static List<EodEngine.Step> phase1(JdbcTemplate jdbc) {
        return List.of(preChecks(jdbc), loanAccountChecks(jdbc), glSnapshot(jdbc), trialBalanceGate(jdbc), advanceDate(jdbc));
    }

    static EodEngine.TaskStep preChecks(JdbcTemplate jdbc) {
        return new EodEngine.TaskStep() {
            @Override public String name() { return "Pre-checks"; }
            @Override public void run(EodEngine.Context ctx) {
                LocalDate bd = jdbc.queryForObject("SELECT business_date FROM platform.business_day WHERE id = 1", LocalDate.class);
                if (!ctx.businessDate().equals(bd)) {
                    throw new IllegalStateException("run is for " + ctx.businessDate() + " but the business date is " + bd);
                }
            }
        };
    }

    /** Per-account step: every active loan must have a repayment schedule. One bad account never stops EOD. */
    static EodEngine.ItemStep loanAccountChecks(JdbcTemplate jdbc) {
        return new EodEngine.ItemStep() {
            @Override public String name() { return "Loan account checks"; }
            @Override public List<String> items(EodEngine.Context ctx) {
                return jdbc.queryForList("SELECT loan_no FROM lending.loan_account WHERE status = 'ACTIVE' ORDER BY loan_no", String.class);
            }
            @Override public void process(EodEngine.Context ctx, String loanNo) {
                Integer rows = jdbc.queryForObject("""
                        SELECT count(*) FROM lending.repayment_schedule s JOIN lending.loan_account a ON a.id = s.loan_id
                         WHERE a.loan_no = ?
                        """, Integer.class, loanNo);
                if (rows == null || rows == 0) throw new IllegalStateException("active loan has no repayment schedule");
            }
            @Override public double maxFailureRatio() { return 0.2; }
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
