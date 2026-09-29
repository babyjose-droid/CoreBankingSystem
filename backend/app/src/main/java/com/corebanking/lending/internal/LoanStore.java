package com.corebanking.lending.internal;

import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.lending.engine.RestructureStatus;
import com.corebanking.ledger.TransactionLot;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.Json;
import java.sql.Array;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Persistence for loans: the engine state is stored as JSON (the snapshot) with key figures copied into columns
 * for queries and reports. Every method takes the JdbcTemplate to use, so the same code serves request threads
 * (routed data source) and EOD workers (the tenant's own data source).
 */
@Component
public class LoanStore {

    public record Loaded(UUID id, String loanNo, UUID customerId, String status, LoanAccount account) {}

    private final Json json;

    public LoanStore(Json json) {
        this.json = json;
    }

    public Loaded lock(JdbcTemplate jdbc, UUID loanId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, loan_no, customer_id, status, product_snapshot::text AS params, state::text AS state "
                        + "FROM lending.loan_account WHERE id = ? FOR UPDATE", loanId);
        if (rows.isEmpty()) throw ApiException.notFound("loan " + loanId);
        return toLoaded(rows.get(0));
    }

    public Loaded lockByNo(JdbcTemplate jdbc, String loanNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, loan_no, customer_id, status, product_snapshot::text AS params, state::text AS state "
                        + "FROM lending.loan_account WHERE loan_no = ? FOR UPDATE", loanNo);
        if (rows.isEmpty()) throw ApiException.notFound("loan " + loanNo);
        return toLoaded(rows.get(0));
    }

    private Loaded toLoaded(Map<String, Object> r) {
        String state = (String) r.get("state");
        LoanAccount a = null;
        if (state != null && !state.equals("{}")) {
            LoanAccount.Params p = json.read((String) r.get("params"), LoanAccount.Params.class);
            a = LoanAccount.restore(p, json.read(state, LoanAccount.Snapshot.class));
        }
        return new Loaded((UUID) r.get("id"), (String) r.get("loan_no"), (UUID) r.get("customer_id"), (String) r.get("status"), a);
    }

    /** Saves engine state and the denormalised figures; keeps status in step with the engine. */
    public void save(JdbcTemplate jdbc, UUID loanId, LoanAccount a, LocalDate asOf) {
        String status = switch (a.status()) {
            case ACTIVE -> "ACTIVE";
            case FROZEN -> "FROZEN";
            case CLOSED -> "CLOSED";
            case CANCELLED -> "CANCELLED";
            case WRITTEN_OFF -> "WRITTEN_OFF";
        };
        RestructureStatus rs = a.restructureStatus();
        jdbc.update("""
                UPDATE lending.loan_account
                   SET state = ?::jsonb, status = ?, principal_outstanding = ?, overdue_amount = ?, next_due_date = ?,
                       dpd = ?, asset_class = ?, npa_since = ?, suspense = ?, provision_held = ?,
                       closed_on = CASE WHEN ? IN ('CLOSED','CANCELLED') AND closed_on IS NULL THEN ?::date ELSE closed_on END,
                       current_rate = ?, restructured_on = ?, restructure_count = ?, upgrade_not_before = ?,
                       restructure_defaulted = ?, version = version + 1
                 WHERE id = ?
                """, json.write(a.snapshot()), status, a.principalOutstanding(), a.overdueAmount(asOf), a.nextDueDate(),
                a.dpd(), a.assetClass().name(), a.npaSince(), a.suspense(), a.provisionHeld(), status, Date.valueOf(asOf),
                a.ratePercent(), rs == null ? null : rs.restructuredOn(), rs == null ? 0 : rs.count(),
                rs == null || !rs.underMonitoring() ? null : rs.specifiedPeriodMinEnd(), rs != null && rs.defaulted(), loanId);
    }

    /** Records a financial event with the state before it (for reversal) and the lots it posted. */
    public UUID recordTxn(JdbcTemplate jdbc, UUID loanId, String type, LocalDate valueDate, LocalDate businessDate,
                          java.math.BigDecimal amount, List<TransactionLot> lots, LoanAccount.Snapshot before, String summary,
                          UUID reverses, String user) {
        UUID id = UUID.randomUUID();
        Integer seq = jdbc.queryForObject("SELECT coalesce(max(seq), 0) + 1 FROM lending.loan_txn WHERE loan_id = ?", Integer.class, loanId);
        String lotIds = "{" + String.join(",", lots.stream().map(l -> l.id().toString()).toList()) + "}";
        jdbc.update("""
                INSERT INTO lending.loan_txn (id, loan_id, seq, txn_type, value_date, business_date, amount, lot_ids,
                                              state_before, summary, reverses, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::uuid[], ?::jsonb, ?, ?, ?)
                """, id, loanId, seq, type, Date.valueOf(valueDate), Date.valueOf(businessDate), amount, lotIds,
                before == null ? "{}" : json.write(before), summary, reverses, user);
        return id;
    }

    public void replaceSchedule(JdbcTemplate jdbc, UUID loanId, List<Instalment> rows) {
        Integer version = jdbc.queryForObject(
                "SELECT coalesce(max(schedule_version), 0) + 1 FROM lending.repayment_schedule WHERE loan_id = ?", Integer.class, loanId);
        jdbc.batchUpdate("""
                INSERT INTO lending.repayment_schedule (loan_id, schedule_version, instalment_no, due_date, opening_balance,
                                                        principal_due, interest_due)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, rows.stream().map(i -> new Object[] {loanId, version, i.number(), Date.valueOf(i.dueDate()),
                        i.openingBalance(), i.principal(), i.interest()}).toList());
    }

    public void recordDpd(JdbcTemplate jdbc, UUID loanId, LocalDate day, LoanAccount a) {
        jdbc.update("""
                INSERT INTO lending.dpd_history (loan_id, business_date, dpd, asset_class, principal_outstanding, overdue_amount)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (loan_id, business_date) DO UPDATE SET dpd = EXCLUDED.dpd, asset_class = EXCLUDED.asset_class,
                    principal_outstanding = EXCLUDED.principal_outstanding, overdue_amount = EXCLUDED.overdue_amount
                """, loanId, Date.valueOf(day), a.dpd(), a.assetClass().name(), a.principalOutstanding(), a.overdueAmount(day));
    }

    static List<UUID> uuids(Array a) throws java.sql.SQLException {
        Object[] values = (Object[]) a.getArray();
        return java.util.Arrays.stream(values).map(v -> (UUID) v).toList();
    }
}
