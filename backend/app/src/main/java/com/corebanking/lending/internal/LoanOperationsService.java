package com.corebanking.lending.internal;

import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.LoanOperations;
import com.corebanking.lending.engine.FeeRule;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.platform.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link LoanOperations} on top of {@link LoanService}: the same engine and postings as the staff API. */
@Service
class LoanOperationsService implements LoanOperations {

    private final JdbcTemplate jdbc;
    private final LoanService loans;
    private final LoanStore store;

    LoanOperationsService(JdbcTemplate jdbc, LoanService loans, LoanStore store) {
        this.jdbc = jdbc;
        this.loans = loans;
        this.store = store;
    }

    private static final String REF = """
            SELECT id, loan_no, customer_id, branch_code, status, source, external_ref, net_disbursed, next_due_date
              FROM lending.loan_account""";

    private static LoanRef ref(Map<String, Object> r) {
        java.sql.Date next = (java.sql.Date) r.get("next_due_date");
        return new LoanRef((UUID) r.get("id"), (String) r.get("loan_no"), (UUID) r.get("customer_id"), (String) r.get("branch_code"),
                (String) r.get("status"), (String) r.get("source"), (String) r.get("external_ref"), (BigDecimal) r.get("net_disbursed"),
                next == null ? null : next.toLocalDate());
    }

    @Override
    public LoanRef find(UUID loanId) {
        List<Map<String, Object>> rows = jdbc.queryForList(REF + " WHERE id = ?", loanId);
        if (rows.isEmpty()) throw ApiException.notFound("loan " + loanId);
        return ref(rows.get(0));
    }

    @Override
    public LoanRef findByNo(String loanNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(REF + " WHERE loan_no = ?", loanNo);
        if (rows.isEmpty()) throw ApiException.notFound("loan " + loanNo);
        return ref(rows.get(0));
    }

    @Override
    @Transactional
    public Posting postRepayment(UUID loanId, BigDecimal amount, LocalDate valueDate, String channel, String reference) {
        Map<String, Object> loan = loans.repay(loanId, amount, valueDate, channel, reference);
        Map<String, Object> txn = jdbc.queryForMap(
                "SELECT id, business_date FROM lending.loan_txn WHERE loan_id = ? AND txn_type = 'REPAYMENT' ORDER BY seq DESC LIMIT 1", loanId);
        return new Posting((UUID) txn.get("id"), ((java.sql.Date) txn.get("business_date")).toLocalDate(), (String) loan.get("status"));
    }

    @Override
    @Transactional
    public BounceCharge chargeBounceFee(UUID loanId, BigDecimal bouncedAmount) {
        LoanAccount account = store.lock(jdbc, loanId).account();
        if (account == null) throw ApiException.conflict("the loan is not active");
        Optional<FeeRule> rule = account.params().fees().stream().filter(f -> f.event() == FeeRule.Event.BOUNCE).findFirst();
        if (rule.isEmpty()) return new BounceCharge(false, null, "the loan's product has no fee for a bounce");
        loans.chargeFee(loanId, rule.get().code(), bouncedAmount);
        String summary = jdbc.queryForObject(
                "SELECT summary FROM lending.loan_txn WHERE loan_id = ? AND txn_type = 'FEE_CHARGE' ORDER BY seq DESC LIMIT 1", String.class, loanId);
        return new BounceCharge(true, rule.get().code(), summary);
    }

    @Override
    public List<Due> duesOn(JdbcTemplate j, LocalDate dueDate) {
        List<UUID> ids = j.queryForList(
                "SELECT id FROM lending.loan_account WHERE status = 'ACTIVE' AND next_due_date = ? ORDER BY loan_no", UUID.class, dueDate);
        List<Due> out = new ArrayList<>();
        for (UUID id : ids) {
            Due d = dueFor(j, id, dueDate);
            if (d != null) out.add(d);
        }
        return out;
    }

    @Override
    public Due dueFor(JdbcTemplate j, UUID loanId, LocalDate dueDate) {
        List<Map<String, Object>> rows = j.queryForList("SELECT branch_code FROM lending.loan_account WHERE id = ? AND status = 'ACTIVE'", loanId);
        if (rows.isEmpty()) return null;
        LoanStore.Loaded l = store.read(j, loanId);
        LoanAccount a = l.account();
        if (a == null) return null;
        BigDecimal amount = null;
        for (LoanAccount.DemandRow d : a.demands()) {
            if (d.dueDate().equals(dueDate)) amount = d.principalUnpaid().add(d.interestUnpaid());
        }
        if (amount == null) {
            for (Instalment i : a.futureSchedule()) {
                if (i.dueDate().equals(dueDate)) amount = i.instalment().subtract(a.excess());    // an advance already held covers part
            }
        }
        if (amount == null || amount.signum() <= 0) return null;
        return new Due(loanId, l.loanNo(), l.customerId(), (String) rows.get(0).get("branch_code"), dueDate, amount);
    }

    @Override
    @Transactional
    public String reverseDisbursement(UUID loanId, String reason, String user) {
        return loans.reverseDisbursement(loanId, reason, user);
    }
}
