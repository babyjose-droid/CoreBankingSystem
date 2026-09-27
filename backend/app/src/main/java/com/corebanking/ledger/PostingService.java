package com.corebanking.ledger;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single posting engine (ADR-005). Every module that moves money builds a {@link TransactionLot}
 * and calls {@link #post}. Rules are checked twice: by {@link TransactionLot.Builder} in Java and by
 * database triggers at commit (V3__ledger.sql), so a bug in one layer cannot unbalance the books.
 */
@Service
public class PostingService {

    private final JdbcTemplate jdbc;

    public PostingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Must join the caller's transaction so the business change and its postings commit together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void post(TransactionLot lot, String user) {
        LocalDate open = jdbc.queryForObject(
                "SELECT business_date FROM platform.business_day WHERE id = 1 AND status = 'OPEN' FOR SHARE",
                LocalDate.class);
        if (!lot.businessDate().equals(open)) {
            throw new LedgerException("lot business date " + lot.businessDate() + " is not the open business date " + open);
        }
        jdbc.update("""
                INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, reference, reverses, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, lot.id(), lot.type(), Date.valueOf(lot.businessDate()), Date.valueOf(lot.valueDate()),
                lot.reference(), lot.reverses(), user);
        List<Object[]> rows = lot.lines().stream().map(l -> new Object[] {
                lot.id(), Date.valueOf(lot.businessDate()), l.branch(), l.glCode(), l.account(),
                l.side().name(), l.amount(), l.currency(), l.narration()}).toList();
        jdbc.batchUpdate("""
                INSERT INTO ledger.account_entry
                  (lot_id, business_date, branch_code, gl_code, account_no, side, amount, currency, narration)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }
}
