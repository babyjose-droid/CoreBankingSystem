package com.corebanking.lending.internal;

import com.corebanking.eod.EodListener;
import com.corebanking.kernel.PostingWindow;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.JobHandler;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Repayments around end of day (US-111, ADR-015): 24x7 acceptance without ever posting to a date being closed.
 * <ul>
 *   <li>Day OPEN: the repayment is posted at once, as before.</li>
 *   <li>After the cut-off (end of day running or failed): a repayment from a customer-facing channel (a client with
 *       {@code loan:stp}) is accepted as a deferred receipt; a staff posting is refused with 409, as before.</li>
 *   <li>Deferred receipts are booked on the next business date, valued on that date, as soon as it opens: right
 *       after end of day ({@link EodListener}) and by the job DEFERRED_RECEIPTS every few minutes, which also covers
 *       an application that stopped in between. A receipt that cannot be booked (loan frozen or closed meanwhile)
 *       becomes FAILED with the reason and waits for a person; it is never dropped.</li>
 * </ul>
 * The rule itself is the pure {@link PostingWindow}.
 */
@Service
public class DeferredReceiptService implements JobHandler, EodListener {

    private static final Logger log = LoggerFactory.getLogger(DeferredReceiptService.class);

    /** Either the loan after an immediate posting, or the receipt accepted for the next business date. */
    public record Outcome(Map<String, Object> loan, Map<String, Object> receipt) {
        public boolean deferred() {
            return receipt != null;
        }
    }

    private final JdbcTemplate jdbc;
    private final LoanService loans;
    private final DeferredReceiptStore store;
    private final BusinessDays days;

    public DeferredReceiptService(JdbcTemplate jdbc, LoanService loans, DeferredReceiptStore store, BusinessDays days) {
        this.jdbc = jdbc;
        this.loans = loans;
        this.store = store;
        this.days = days;
    }

    /** Posts the repayment now, or accepts it for the next business date when end of day has started. */
    public Outcome repay(UUID loanId, BigDecimal amount, LocalDate valueDate, String mode, String reference, String idempotencyKey) {
        if (amount == null || amount.signum() <= 0) throw ApiException.invalid("amount must be positive");
        boolean straightThrough = CurrentUser.get().has("loan:stp");
        RuntimeException last = null;
        // The day can change between our look at it and the posting; one more look settles it.
        for (int attempt = 0; attempt < 2; attempt++) {
            BusinessDays.BusinessDay day = days.current();
            if (day == null) throw ApiException.conflict("business date not initialised");
            PostingWindow.Decision decision = PostingWindow.decide(PostingWindow.DayStatus.valueOf(day.status()),
                    PostingWindow.Kind.REPAYMENT, straightThrough);
            try {
                switch (decision) {
                    case POST_NOW -> {
                        return new Outcome(loans.repay(loanId, amount, valueDate, mode, reference), null);
                    }
                    case DEFER_TO_NEXT_BUSINESS_DATE -> {
                        LocalDate next = jdbc.queryForObject("SELECT platform.next_working_day(?)", LocalDate.class, day.businessDate());
                        try {
                            PostingWindow.booking(decision, day.businessDate(), next, valueDate);
                        } catch (IllegalArgumentException e) {
                            throw ApiException.invalid(e.getMessage());
                        }
                        return new Outcome(null, store.accept(loanId, amount, mode, reference, idempotencyKey));
                    }
                    default -> throw ApiException.conflict("business day is " + day.status() + "; try after end of day");
                }
            } catch (RuntimeException e) {
                BusinessDays.BusinessDay now = days.current();
                if (now == null || now.status().equals(day.status())) throw e;       // not a race with the cut-off: a real refusal
                last = e;
            }
        }
        throw last == null ? ApiException.conflict("the business day is changing; try again") : last;
    }

    /** Books every pending receipt whose business date has opened. Does nothing while the day is not open. */
    public JobHandler.Result bookPending() {
        BusinessDays.BusinessDay day = days.current();
        Map<String, Object> artifact = new LinkedHashMap<>();
        if (day == null || !"OPEN".equals(day.status())) {
            artifact.put("skipped", "the business day is not open");
            return new JobHandler.Result(0, 0, artifact);
        }
        int booked = 0;
        int failed = 0;
        for (UUID id : store.due(day.businessDate())) {
            try {
                if (store.book(id)) booked++;
            } catch (RuntimeException e) {
                failed++;
                String reason = e instanceof ApiException ? e.getMessage() : "the repayment could not be posted; see the application log";
                if (!(e instanceof ApiException)) log.warn("deferred receipt {} could not be booked", id, e);
                store.failed(id, reason);
            }
        }
        artifact.put("businessDate", day.businessDate().toString());
        return new JobHandler.Result(booked, failed, artifact);
    }

    @Override
    public String kind() {
        return "DEFERRED_RECEIPTS";
    }

    @Override
    public Result run(Context context) {
        return bookPending();
    }

    @Override
    public void businessDateOpened(String tenant, LocalDate closed, LocalDate opened) {
        JobHandler.Result r = bookPending();
        if (r.processed() + r.failed() > 0) {
            log.info("after end of day for {}: {} deferred receipts booked on {}, {} failed", tenant, r.processed(), opened, r.failed());
        }
    }
}
