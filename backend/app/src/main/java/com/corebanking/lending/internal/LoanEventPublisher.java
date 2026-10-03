package com.corebanking.lending.internal;

import com.corebanking.lending.LoanEvents;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.platform.Outbox;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes loan events to the outbox with the connection of the transaction that made the change (ADR-008). The
 * payloads hold identifiers and amounts only.
 */
@Component
class LoanEventPublisher {

    private final Outbox outbox;

    LoanEventPublisher(Outbox outbox) {
        this.outbox = outbox;
    }

    private static Map<String, Object> base(JdbcTemplate jdbc, UUID loanId, LocalDate businessDate) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT loan_no, customer_id, branch_code, external_ref FROM lending.loan_account WHERE id = ?", loanId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loanId", loanId.toString());
        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            m.put("loanNo", r.get("loan_no"));
            m.put("customerId", String.valueOf(r.get("customer_id")));
            m.put("externalRef", r.get("external_ref"));
            m.put("branch", r.get("branch_code"));
        }
        m.put("businessDate", businessDate.toString());
        return m;
    }

    private void publish(JdbcTemplate jdbc, String topic, Map<String, Object> payload) {
        outbox.publish(jdbc, topic, String.valueOf(payload.get("loanNo")), payload);
    }

    /** One event per disbursement: the first and every later tranche ({@code trancheNo} 1, 2 …), each with its own transaction. */
    void disbursed(JdbcTemplate jdbc, UUID loanId, UUID txnId, LocalDate businessDate, BigDecimal amount, BigDecimal net, int trancheNo) {
        Map<String, Object> m = base(jdbc, loanId, businessDate);
        m.put("amount", LoanService.plain(amount));
        m.put("netDisbursed", LoanService.plain(net));
        m.put("trancheNo", trancheNo);
        m.put("transactionId", txnId.toString());
        publish(jdbc, LoanEvents.DISBURSED, m);
    }

    void disbursementReversed(JdbcTemplate jdbc, UUID loanId, UUID txnId, LocalDate businessDate, String reason) {
        Map<String, Object> m = base(jdbc, loanId, businessDate);
        m.put("transactionId", txnId.toString());
        m.put("reason", reason);
        publish(jdbc, LoanEvents.DISBURSEMENT_REVERSED, m);
    }

    void payment(JdbcTemplate jdbc, UUID loanId, UUID txnId, String kind, BigDecimal amount, LocalDate valueDate,
                 LocalDate businessDate, String channel, LoanAccount.Status statusAfter) {
        Map<String, Object> m = base(jdbc, loanId, businessDate);
        m.put("amount", LoanService.plain(amount));
        m.put("valueDate", valueDate.toString());
        m.put("transactionId", txnId.toString());
        m.put("kind", kind);
        m.put("channel", channel);
        m.put("loanStatus", statusAfter.name());
        publish(jdbc, LoanEvents.PAYMENT_RECEIVED, m);
    }

    /** Closure and the move into NPA, whichever transaction or day-end caused them. */
    void transitions(JdbcTemplate jdbc, UUID loanId, LoanAccount.Status statusBefore, boolean npaBefore, LoanAccount after,
                     LocalDate businessDate) {
        boolean closedNow = after.status() == LoanAccount.Status.CLOSED || after.status() == LoanAccount.Status.CANCELLED;
        boolean closedBefore = statusBefore == LoanAccount.Status.CLOSED || statusBefore == LoanAccount.Status.CANCELLED;
        if (closedNow && !closedBefore) {
            Map<String, Object> m = base(jdbc, loanId, businessDate);
            m.put("closure", after.status().name());
            publish(jdbc, LoanEvents.CLOSED, m);
        }
        if (after.assetClass().isNpa() && !npaBefore) {
            Map<String, Object> m = base(jdbc, loanId, businessDate);
            m.put("assetClass", after.assetClass().name());
            m.put("npaSince", after.npaSince() == null ? null : after.npaSince().toString());
            m.put("dpd", after.dpd());
            publish(jdbc, LoanEvents.NPA, m);
        }
    }

    void rateReset(JdbcTemplate jdbc, UUID loanId, LocalDate businessDate, Map<String, Object> figures, String option) {
        Map<String, Object> m = base(jdbc, loanId, businessDate);
        for (String k : List.of("rateBefore", "rateAfter", "emiBefore", "emiAfter", "remainingBefore", "remainingAfter",
                "maturityBefore", "maturityAfter")) {
            m.put(k, figures.get(k));
        }
        m.put("option", option);
        publish(jdbc, LoanEvents.RATE_RESET, m);
    }

    void nocIssued(JdbcTemplate jdbc, UUID loanId, LocalDate businessDate) {
        publish(jdbc, LoanEvents.NOC_ISSUED, base(jdbc, loanId, businessDate));
    }
}
