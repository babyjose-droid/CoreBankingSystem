package com.corebanking.lending.internal;

import com.corebanking.eod.EodStepProvider;
import com.corebanking.kernel.EodEngine;
import com.corebanking.lending.engine.Delinquency.AssetClass;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.lending.engine.Provisioning;
import com.corebanking.ledger.PostingService;
import com.corebanking.ledger.TransactionLot;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lending end-of-day (US-076 – US-078, US-081, US-042):
 * <ol>
 *   <li><b>Loan day-end</b> — per loan, in its own transaction: raise due demands, accrue interest, adjust advances,
 *       charge penal on overdue amounts, classify (SMA/NPA, suspense), provision. One failing loan is an EOD
 *       exception, not a failed EOD; above 5% failures the step fails (something systemic).</li>
 *   <li><b>Borrower-level NPA</b> — every loan of a borrower with an NPA loan becomes NPA.</li>
 * </ol>
 * Re-running a day is safe: the engine ignores a day it has already processed for a loan.
 */
@Component
class LendingEod implements EodStepProvider {

    private static final String USER = "eod";
    private final LoanStore store;

    LendingEod(LoanStore store) {
        this.store = store;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public List<EodEngine.Step> steps(JdbcTemplate jdbc, String tenant) {
        DataSource ds = jdbc.getDataSource();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        return List.of(loanDayEnd(jdbc, tx), borrowerNpa(jdbc, tx));
    }

    private EodEngine.ItemStep loanDayEnd(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new EodEngine.ItemStep() {
            private Provisioning.Rates rates;

            @Override public String name() { return "Loan day-end (demands, accrual, penal, DPD/NPA, provisioning)"; }

            @Override public List<String> items(EodEngine.Context ctx) {
                rates = LoanService.rates(jdbc);
                return jdbc.queryForList("SELECT id::text FROM lending.loan_account WHERE status IN ('ACTIVE','FROZEN') ORDER BY loan_no", String.class);
            }

            @Override public void process(EodEngine.Context ctx, String loanId) {
                tx.executeWithoutResult(s -> {
                    UUID id = UUID.fromString(loanId);
                    LoanStore.Loaded l = store.lock(jdbc, id);
                    LoanAccount a = l.account();
                    LoanAccount.Snapshot before = a.snapshot();
                    LoanAccount.Result r = a.endOfDay(ctx.businessDate(), rates);
                    for (TransactionLot lot : r.lots()) PostingService.postDuringEod(jdbc, lot, USER);
                    store.save(jdbc, id, a, ctx.businessDate());
                    if (!r.lots().isEmpty()) {
                        store.recordTxn(jdbc, id, "EOD", ctx.businessDate(), ctx.businessDate(), null, r.lots(), before, r.summary(), null, USER);
                    }
                    store.recordDpd(jdbc, id, ctx.businessDate(), a);
                });
            }

            @Override public double maxFailureRatio() { return 0.05; }
        };
    }

    private EodEngine.ItemStep borrowerNpa(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new EodEngine.ItemStep() {
            @Override public String name() { return "Borrower-level NPA"; }

            @Override public List<String> items(EodEngine.Context ctx) {
                return jdbc.queryForList("""
                        SELECT l.id::text FROM lending.loan_account l JOIN lending.borrower_class b ON b.customer_id = l.customer_id
                         WHERE l.status IN ('ACTIVE','FROZEN') AND b.worst_class IN ('SUBSTANDARD','DOUBTFUL1','DOUBTFUL2','DOUBTFUL3','LOSS')
                           AND l.asset_class NOT IN ('SUBSTANDARD','DOUBTFUL1','DOUBTFUL2','DOUBTFUL3','LOSS')
                         ORDER BY l.loan_no
                        """, String.class);
            }

            @Override public void process(EodEngine.Context ctx, String loanId) {
                tx.executeWithoutResult(s -> {
                    UUID id = UUID.fromString(loanId);
                    Map<String, Object> b = jdbc.queryForMap("""
                            SELECT b.worst_class, b.npa_since FROM lending.borrower_class b
                              JOIN lending.loan_account l ON l.customer_id = b.customer_id WHERE l.id = ?
                            """, id);
                    LoanStore.Loaded l = store.lock(jdbc, id);
                    LoanAccount a = l.account();
                    LoanAccount.Snapshot before = a.snapshot();
                    LocalDate since = b.get("npa_since") == null ? ctx.businessDate() : ((java.sql.Date) b.get("npa_since")).toLocalDate();
                    LoanAccount.Result r = a.applyBorrowerClass(AssetClass.valueOf((String) b.get("worst_class")), since, ctx.businessDate());
                    for (TransactionLot lot : r.lots()) PostingService.postDuringEod(jdbc, lot, USER);
                    store.save(jdbc, id, a, ctx.businessDate());
                    store.recordTxn(jdbc, id, "EOD", ctx.businessDate(), ctx.businessDate(), null, r.lots(), before, r.summary(), null, USER);
                    store.recordDpd(jdbc, id, ctx.businessDate(), a);
                });
            }
        };
    }
}
