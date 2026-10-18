package com.corebanking.deposits.internal;

import com.corebanking.deposits.DepositPostings;
import com.corebanking.ledger.PostingService;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Where deposit changes are written (P4-4): the tenant's database, the posting rules for the business date, who is
 * writing, and how a lot is posted. The day-end posts on its own connection while it closes the date
 * ({@link PostingService#postDuringEod}); a request approved by a checker posts through {@link PostingService#post}.
 * The interest, tax, charge and closure code is the same for both.
 *
 * @param approvalId the approval request being applied; null for the day-end
 */
record DepositBooks(JdbcTemplate jdbc, DepositPostings post, LocalDate today, String user, UUID approvalId, Poster poster) {

    /** Posts one lot. */
    interface Poster {
        void post(TransactionLot lot);
    }

    /** The day-end's books for {@code businessDate}. */
    static DepositBooks eod(JdbcTemplate jdbc, DepositPostings post, LocalDate businessDate) {
        return new DepositBooks(jdbc, post, businessDate, DepositEod.USER, null, lot -> PostingService.postDuringEod(jdbc, lot, DepositEod.USER));
    }

    /** A request's books: posted by the posting service, written as the approval's maker. */
    static DepositBooks request(JdbcTemplate jdbc, PostingService postings, LocalDate today, String maker, UUID approvalId, String poster) {
        return new DepositBooks(jdbc, new DepositPostings(DepositMoneyService.headsOf(jdbc), today), today, maker, approvalId,
                lot -> postings.post(lot, poster));
    }

    void post(TransactionLot lot) {
        poster.post(lot);
    }

    /** A movement row; the database sets its business date and balance. */
    UUID movement(UUID accountId, String kind, String direction, BigDecimal amount, UUID lotId, UUID counterparty, String narration, String reference) {
        return movement(accountId, kind, direction, amount, lotId, counterparty, narration, reference, null, null);
    }

    /** A movement to another bank's account: {@code party} the sealed holder name and number, {@code partyClear} the last four and the IFSC. */
    UUID movement(UUID accountId, String kind, String direction, BigDecimal amount, UUID lotId, UUID counterparty, String narration, String reference,
                  byte[][] party, String[] partyClear) {
        UUID id = UUID.randomUUID();
        String text = narration == null ? null : narration.length() > 200 ? narration.substring(0, 200) : narration;
        String ref = reference == null ? null : reference.length() > 60 ? reference.substring(0, 60) : reference;
        jdbc.update("""
                INSERT INTO deposits.movement (id, account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, reference,
                                               narration, counterparty_id, party_name_cipher, party_account_cipher, party_account_last4, party_ifsc,
                                               approval_id, created_by)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, accountId, kind, direction, amount, today, today, lotId, ref, text, counterparty,
                party == null ? null : party[0], party == null ? null : party[1], partyClear == null ? null : partyClear[0],
                partyClear == null ? null : partyClear[1], approvalId, user);
        return id;
    }

    void event(UUID accountId, String kind, LocalDate from, LocalDate to, Integer periodNo, BigDecimal amount, UUID lotId, UUID movementId, String note) {
        jdbc.update("""
                INSERT INTO deposits.interest_event (account_id, kind, period_from, period_to, period_no, amount, lot_id, movement_id, business_date, note,
                                                     created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, accountId, kind, from, to, periodNo, amount, lotId, movementId, today, note == null || note.length() <= 2000 ? note : note.substring(0, 2000), user);
    }
}
