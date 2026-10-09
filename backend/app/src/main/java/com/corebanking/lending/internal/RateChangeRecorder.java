package com.corebanking.lending.internal;

import com.corebanking.lending.engine.Amendment;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.platform.Json;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records the rate changes a day-end made ({@link LoanAccount#drainRateChanges()}): a floating-rate reset or an
 * elapsed-tenure step. Each is a loan transaction (RATE_RESET, RATE_STEP; no money moves) and, when the rate changed,
 * a row in the amendment history with its before/after figures and the borrower's message (RBI 18-Aug-2023: the
 * borrower is told how the EMI and tenure changed). History rows of the system kinds have no approval: the change
 * follows from the contract, not from a maker's request.
 */
@Component
class RateChangeRecorder {

    private final LoanStore store;
    private final LoanEventPublisher events;
    private final Json json;

    RateChangeRecorder(LoanStore store, LoanEventPublisher events, Json json) {
        this.store = store;
        this.events = events;
        this.json = json;
    }

    /**
     * @param before the state before the day-end that made the changes (reversal of an earlier transaction restores
     *               an earlier one and replays the change, so these transactions are never reversal targets)
     * @param replay the changes come from day-ends replayed after a reversal: the borrower was told when they were
     *               first made, so no message is sent again
     */
    void record(JdbcTemplate jdbc, UUID loanId, LoanAccount a, LoanAccount.Snapshot before, LocalDate businessDate, String user,
                boolean replay) {
        for (LoanAccount.RateChange c : a.drainRateChanges()) {
            String type = c.cause() == LoanAccount.RateCause.BENCHMARK_RESET ? "RATE_RESET" : "RATE_STEP";
            String summary = summary(c);
            UUID txn = store.recordTxn(jdbc, loanId, type, c.day(), businessDate, null, List.of(), before, summary, null, user);
            if (!c.changed()) continue;
            Amendment.Effect e = c.effect();
            Map<String, Object> applied = LoanService.figures(e);
            Integer seq = jdbc.queryForObject("SELECT coalesce(max(seq), 0) + 1 FROM lending.loan_amendment WHERE loan_id = ?", Integer.class, loanId);
            jdbc.update("""
                    INSERT INTO lending.loan_amendment (id, loan_id, seq, txn_id, kind, parameters, emi_before, emi_after, tenure_before,
                        tenure_after, rate_before, rate_after, maturity_before, maturity_after, interest_before, interest_after,
                        proposed_figures, applied_figures, differs_from_proposal, approval_id, made_by, checked_by, business_date, reason)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?::jsonb, false, NULL, ?, NULL, ?, ?)
                    """, UUID.randomUUID(), loanId, seq, txn, type, json.write(parameters(c)), e.emiBefore(), e.emiAfter(), e.remainingBefore(),
                    e.remainingAfter(), e.rateBefore(), e.rateAfter(), e.maturityBefore(), e.maturityAfter(), e.interestBefore(),
                    e.interestAfter(), json.write(applied), user, c.day(), summary);
            if (!replay) events.rateReset(jdbc, loanId, businessDate, applied, c.applied() == null ? null : c.applied().name());
        }
    }

    private static Map<String, Object> parameters(LoanAccount.RateChange c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cause", c.cause().name());
        m.put("resetDates", c.resetDates().stream().map(LocalDate::toString).toList());
        m.put("benchmarkCode", c.benchmarkCode());
        m.put("benchmarkRate", LoanService.plain(c.benchmarkRate()));
        m.put("spread", LoanService.plain(c.spread()));
        m.put("requestedOption", c.requested() == null ? null : c.requested().name());
        m.put("appliedOption", c.applied() == null ? null : c.applied().name());
        m.put("fallbackReason", c.fallbackReason());
        m.put("outsideBand", c.outsideBand());
        m.put("nextReset", c.nextReset() == null ? null : c.nextReset().toString());
        return m;
    }

    static String summary(LoanAccount.RateChange c) {
        StringBuilder s = new StringBuilder();
        if (c.cause() == LoanAccount.RateCause.BENCHMARK_RESET) {
            s.append("Rate reset: ").append(c.benchmarkCode()).append(' ').append(LoanService.plain(c.benchmarkRate())).append("% + spread ")
                    .append(LoanService.plain(c.spread())).append("% = ").append(LoanService.plain(c.rateAfter())).append('%');
            if (c.resetDates().size() > 1) s.append(" (catch-up over ").append(c.resetDates().size()).append(" reset dates)");
        } else {
            s.append("Elapsed-tenure step: rate ").append(LoanService.plain(c.rateAfter())).append('%');
        }
        if (!c.changed()) {
            s.append("; rate unchanged");
        } else {
            Amendment.Effect e = c.effect();
            s.append(" (was ").append(LoanService.plain(c.rateBefore())).append("%); EMI ").append(LoanService.plain(e.emiBefore()))
                    .append(" → ").append(LoanService.plain(e.emiAfter())).append("; instalments left ").append(e.remainingBefore())
                    .append(" → ").append(e.remainingAfter());
            if (c.fallbackReason() != null && c.requested() != c.applied()) {
                s.append("; EMI raised instead of a longer tenure: ").append(c.fallbackReason());
            }
        }
        if (c.outsideBand()) s.append("; outside the product rate band (applied, D-14)");
        if (c.nextReset() != null) s.append("; next reset ").append(c.nextReset());
        return s.toString();
    }
}
