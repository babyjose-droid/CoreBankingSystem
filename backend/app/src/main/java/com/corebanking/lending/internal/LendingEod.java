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
 *   <li><b>Loan day-end</b> — per loan, in its own transaction: raise due demands, reset a floating rate whose reset
 *       date has come (benchmark + spread) and apply elapsed-tenure rate steps, accrue interest, adjust advances,
 *       charge penal on overdue amounts, classify (SMA/NPA, suspense), provision. One failing loan is an EOD
 *       exception, not a failed EOD; above 5% failures the step fails (something systemic).</li>
 *   <li><b>Borrower-level NPA</b> — every loan of a borrower with an NPA loan becomes NPA.</li>
 *   <li><b>GST fee invoices</b> — a tax invoice for each fee charged in the day (P2-4).</li>
 * </ol>
 * Re-running a day is safe: the engine ignores a day it has already processed for a loan.
 */
@Component
class LendingEod implements EodStepProvider {

    private static final String USER = "eod";
    private final LoanStore store;
    private final LoanEventPublisher events;
    private final RateChangeRecorder rateChanges;

    LendingEod(LoanStore store, LoanEventPublisher events, RateChangeRecorder rateChanges) {
        this.store = store;
        this.events = events;
        this.rateChanges = rateChanges;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public List<EodEngine.Step> steps(JdbcTemplate jdbc, String tenant) {
        DataSource ds = jdbc.getDataSource();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        return List.of(loanDayEnd(jdbc, tx), borrowerNpa(jdbc, tx), feeInvoices(jdbc));
    }

    /**
     * GST tax invoices for the fees charged today (and cancellation of those reversed today), from the ledger
     * (V16 lending.issue_fee_invoices). Per loan, so a loan whose invoice cannot be issued becomes an EOD exception
     * and never stops the day from closing. The invoice PDF and the GST reports also issue what is missing.
     */
    private EodEngine.ItemStep feeInvoices(JdbcTemplate jdbc) {
        return new EodEngine.ItemStep() {
            @Override public String name() { return "GST fee invoices"; }

            @Override public List<String> items(EodEngine.Context ctx) {
                return jdbc.queryForList("""
                        SELECT DISTINCT loan_id::text FROM lending.loan_txn
                         WHERE business_date = ? AND txn_type IN ('DISBURSEMENT','FEE_CHARGE','PREPAYMENT','PRECLOSURE','REVERSAL')
                         ORDER BY 1
                        """, String.class, ctx.businessDate());
            }

            @Override public void process(EodEngine.Context ctx, String loanId) {
                jdbc.queryForObject("SELECT lending.issue_fee_invoices(?)", Integer.class, UUID.fromString(loanId));
            }
        };
    }

    private EodEngine.ItemStep loanDayEnd(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new EodEngine.ItemStep() {
            private Provisioning.Rates rates;
            private LocalDate through;

            @Override public String name() { return "Loan day-end (demands, rate resets, accrual, penal, DPD/NPA, provisioning)"; }

            @Override public List<String> items(EodEngine.Context ctx) {
                rates = LoanService.rates(jdbc);
                // the non-working days that follow have no day-end run of their own: they are closed with this day
                through = jdbc.queryForObject("SELECT platform.next_working_day(?::date) - 1", LocalDate.class, ctx.businessDate().toString());
                return jdbc.queryForList("SELECT id::text FROM lending.loan_account WHERE status IN ('ACTIVE','FROZEN') ORDER BY loan_no", String.class);
            }

            @Override public void process(EodEngine.Context ctx, String loanId) {
                tx.executeWithoutResult(s -> {
                    UUID id = UUID.fromString(loanId);
                    LoanStore.Loaded l = store.lock(jdbc, id);
                    LoanAccount a = l.account();
                    LoanAccount.Status statusBefore = a.status();
                    boolean npaBefore = a.assetClass().isNpa();
                    // Every calendar day is a day-end for a loan: interest, penal charges and days past due do not
                    // stop on a Sunday. Each day not yet processed, up to the day before the next business date, is
                    // closed in turn, in this business date's books with its own value date (a day missed earlier is
                    // caught up the same way). Days past due shown tomorrow are therefore those of the last calendar
                    // day before the open business date.
                    LocalDate last = ctx.businessDate();
                    boolean any = false;
                    for (LocalDate d = a.lastAccrualDate().plusDays(1); !d.isAfter(through) && isLive(a); d = d.plusDays(1)) {
                        LoanAccount.Snapshot before = a.snapshot();
                        LoanAccount.Result r = a.endOfDay(d, rates, ctx.businessDate());
                        for (TransactionLot lot : r.lots()) PostingService.postDuringEod(jdbc, lot, USER);
                        if (!r.lots().isEmpty()) {
                            store.recordTxn(jdbc, id, "EOD", d, ctx.businessDate(), null, r.lots(), before, r.summary(), null, USER);
                        }
                        // a reset that cannot be made (no benchmark rate) has thrown: the loan is an EOD exception
                        rateChanges.record(jdbc, id, a, before, ctx.businessDate(), USER, false);
                        store.recordDpd(jdbc, id, d, a);
                        last = d;
                        any = true;
                    }
                    store.save(jdbc, id, a, last);
                    if (!any) store.recordDpd(jdbc, id, ctx.businessDate(), a);
                    // loan.closed / loan.npa, in the loan's own day-end transaction (ADR-008)
                    events.transitions(jdbc, id, statusBefore, npaBefore, a, ctx.businessDate());
                });
            }

            @Override public double maxFailureRatio() { return 0.05; }
        };
    }

    private static boolean isLive(LoanAccount a) {
        return a.status() == LoanAccount.Status.ACTIVE || a.status() == LoanAccount.Status.FROZEN;
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
                    LoanAccount.Status statusBefore = a.status();
                    boolean npaBefore = a.assetClass().isNpa();
                    LocalDate since = b.get("npa_since") == null ? ctx.businessDate() : ((java.sql.Date) b.get("npa_since")).toLocalDate();
                    LoanAccount.Result r = a.applyBorrowerClass(AssetClass.valueOf((String) b.get("worst_class")), since, ctx.businessDate());
                    for (TransactionLot lot : r.lots()) PostingService.postDuringEod(jdbc, lot, USER);
                    store.save(jdbc, id, a, ctx.businessDate());
                    store.recordTxn(jdbc, id, "EOD", ctx.businessDate(), ctx.businessDate(), null, r.lots(), before, r.summary(), null, USER);
                    store.recordDpd(jdbc, id, ctx.businessDate(), a);
                    events.transitions(jdbc, id, statusBefore, npaBefore, a, ctx.businessDate());
                });
            }
        };
    }
}
