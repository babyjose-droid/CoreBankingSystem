package com.corebanking.lending.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.platform.AmountLimitService;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.CurrentUser;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional steps on {@code lending.deferred_receipt} (US-111, ADR-015): accept a receipt after the
 * cut-off, book it on the new business date, mark a failed booking, queue it again or set it aside. Each method is
 * one transaction; {@link DeferredReceiptService} decides which to call. The database guards every step (V19).
 */
@Component
public class DeferredReceiptStore {

    private static final String UTC = "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'";
    private static final String SELECT = "SELECT r.id::text, r.loan_id::text, l.loan_no, r.amount::text, r.mode, r.reference,"
            + " to_char(r.received_at AT TIME ZONE 'UTC', " + UTC + "), r.received_by, r.cutoff_business_date::text, r.expected_posting_date::text,"
            + " r.status, r.posting_date::text, r.value_date::text, r.loan_txn_id::text, r.attempts, r.error, r.resolved_by, r.resolution_note"
            + " FROM lending.deferred_receipt r JOIN lending.loan_account l ON l.id = r.loan_id";

    private final JdbcTemplate jdbc;
    private final LoanService loans;
    private final AmountLimitService limits;
    private final AuditLog audit;

    public DeferredReceiptStore(JdbcTemplate jdbc, LoanService loans, AmountLimitService limits, AuditLog audit) {
        this.jdbc = jdbc;
        this.loans = loans;
        this.limits = limits;
        this.audit = audit;
    }

    /** Accepts a receipt for the next business date. The database refuses it when the day is open or the loan is not active. */
    @Transactional
    public Map<String, Object> accept(UUID loanId, BigDecimal amount, String mode, String reference, String idempotencyKey) {
        String user = CurrentUser.username();
        if (idempotencyKey != null) {
            List<Map<String, Object>> existing = find("r.received_by = ? AND r.idempotency_key = ?", user, idempotencyKey);
            if (!existing.isEmpty()) return existing.get(0);
        }
        limits.require("LOAN_REPAYMENT", AmountLimitService.MAKE, amount, loanId.toString());
        UUID id = UUID.randomUUID();
        // cut-off and expected posting date are set by the database from the business day, not from these values
        jdbc.update("""
                INSERT INTO lending.deferred_receipt (id, loan_id, amount, mode, reference, received_by, idempotency_key,
                                                      cutoff_business_date, expected_posting_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, current_date, current_date)
                """, id, loanId, amount, mode, reference, user, idempotencyKey);
        Map<String, Object> receipt = find("r.id = ?", id).get(0);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("receiptId", id.toString());
        detail.put("amount", amount.toPlainString());
        detail.put("cutoffBusinessDate", receipt.get("cutoffBusinessDate"));
        detail.put("expectedPostingDate", receipt.get("expectedPostingDate"));
        audit.record(user, "LOAN_RECEIPT_DEFERRED", "LOAN", (String) receipt.get("loanNo"), detail);
        return receipt;
    }

    /**
     * Books one pending receipt as a repayment on the open business date, valued on that date.
     *
     * @return false when the receipt is no longer pending or another instance is booking it
     */
    @Transactional
    public boolean book(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT loan_id, amount, mode, reference, received_by FROM lending.deferred_receipt
                 WHERE id = ? AND status = 'PENDING' FOR UPDATE SKIP LOCKED
                """, id);
        if (rows.isEmpty()) return false;
        Map<String, Object> r = rows.get(0);
        UUID loanId = (UUID) r.get("loan_id");
        Map<String, Object> loan = loans.repay(loanId, (BigDecimal) r.get("amount"), null, (String) r.get("mode"), (String) r.get("reference"));
        UUID txn = jdbc.queryForObject("SELECT id FROM lending.loan_txn WHERE loan_id = ? ORDER BY seq DESC LIMIT 1", UUID.class, loanId);
        jdbc.update("UPDATE lending.deferred_receipt SET status = 'APPLIED', loan_txn_id = ? WHERE id = ?", txn, id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("receiptId", id.toString());
        detail.put("receivedBy", r.get("received_by"));
        detail.put("loanTxnId", String.valueOf(txn));
        audit.record(CurrentUser.username(), "LOAN_RECEIPT_DEFERRED_BOOKED", "LOAN", String.valueOf(loan.get("loanNo")), detail);
        return true;
    }

    @Transactional
    public void failed(UUID id, String error) {
        jdbc.update("UPDATE lending.deferred_receipt SET status = 'FAILED', error = ? WHERE id = ? AND status = 'PENDING'", error, id);
    }

    /** Puts a failed receipt back in the queue, once the cause is fixed. */
    @Transactional
    public Map<String, Object> retry(UUID id) {
        changed(jdbc.update("UPDATE lending.deferred_receipt SET status = 'PENDING' WHERE id = ? AND status = 'FAILED'", id), id);
        audit.record(CurrentUser.username(), "LOAN_RECEIPT_DEFERRED_RETRY", "DEFERRED_RECEIPT", id.toString(), Map.of());
        return find("r.id = ?", id).get(0);
    }

    /** Sets a failed receipt aside (refunded or handled outside the system); it will never be booked. */
    @Transactional
    public Map<String, Object> cancel(UUID id, String note) {
        if (note == null || note.isBlank()) throw ApiException.invalid("a note saying what was done with the money is required");
        String user = CurrentUser.username();
        changed(jdbc.update("UPDATE lending.deferred_receipt SET status = 'CANCELLED', resolved_by = ?, resolution_note = ? WHERE id = ? AND status = 'FAILED'",
                user, note.trim(), id), id);
        audit.record(user, "LOAN_RECEIPT_DEFERRED_CANCELLED", "DEFERRED_RECEIPT", id.toString(), Map.of("note", note.trim()));
        return find("r.id = ?", id).get(0);
    }

    private void changed(int rows, UUID id) {
        if (rows == 1) return;
        if (find("r.id = ?", id).isEmpty()) throw ApiException.notFound("receipt " + id);
        throw ApiException.conflict("only a FAILED receipt can be queued again or set aside");
    }

    /** Pending receipts whose cut-off date is before the open business date, oldest first. */
    public List<UUID> due(java.time.LocalDate businessDate) {
        return jdbc.queryForList("SELECT id FROM lending.deferred_receipt WHERE status = 'PENDING' AND cutoff_business_date < ? "
                + "ORDER BY received_at, id", UUID.class, businessDate);
    }

    /** The branch of the receipt's loan, for the branch-scope check; 404 when there is no such receipt. */
    public String branchOf(UUID id) {
        List<String> b = jdbc.queryForList("SELECT l.branch_code FROM lending.deferred_receipt r JOIN lending.loan_account l ON l.id = r.loan_id "
                + "WHERE r.id = ?", String.class, id);
        if (b.isEmpty()) throw ApiException.notFound("receipt " + id);
        return b.get(0);
    }

    public List<Map<String, Object>> ofLoan(UUID loanId) {
        return find("r.loan_id = ? ORDER BY r.received_at DESC", loanId);
    }

    /** Receipts of loans in the caller's branch scope, newest first. */
    public List<Map<String, Object>> list(String user, String status) {
        return find("l.branch_code" + BranchScope.SQL_VISIBLE + " AND (?::text IS NULL OR r.status = ?) ORDER BY r.received_at DESC LIMIT 500",
                user, status, status);
    }

    /** openapi.yaml#/components/schemas/DeferredReceipt. */
    private List<Map<String, Object>> find(String where, Object... args) {
        return jdbc.query(SELECT + " WHERE " + where, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString(1));
            m.put("loanId", rs.getString(2));
            m.put("loanNo", rs.getString(3));
            m.put("amount", rs.getString(4));
            m.put("mode", rs.getString(5));
            m.put("reference", rs.getString(6));
            m.put("receivedAt", rs.getString(7));
            m.put("receivedBy", rs.getString(8));
            m.put("cutoffBusinessDate", rs.getString(9));
            m.put("expectedPostingDate", rs.getString(10));
            m.put("status", rs.getString(11));
            m.put("postingDate", rs.getString(12));
            m.put("valueDate", rs.getString(13));
            m.put("loanTxnId", rs.getString(14));
            m.put("attempts", rs.getInt(15));
            m.put("error", rs.getString(16));
            m.put("resolvedBy", rs.getString(17));
            m.put("resolutionNote", rs.getString(18));
            return m;
        }, args);
    }
}
