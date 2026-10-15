package com.corebanking.deposits.internal;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import com.corebanking.deposits.DepositEvents;
import com.corebanking.deposits.DepositPostings;
import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.deposits.InterestDayEnd;
import com.corebanking.deposits.InterestDayEnd.Accrual;
import com.corebanking.deposits.InterestDayEnd.Credit;
import com.corebanking.deposits.InterestDayEnd.Event;
import com.corebanking.deposits.SavingsInterest;
import com.corebanking.deposits.TermDeposit;
import com.corebanking.eod.EodStepProvider;
import com.corebanking.kernel.EodEngine;
import com.corebanking.ledger.PostingService;
import com.corebanking.ledger.TransactionLot;
import com.corebanking.platform.Outbox;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deposit interest at end of day (P4-3), after the lending steps:
 * <ol>
 *   <li><b>Savings interest</b>: every interest-bearing savings or current account accrues each calendar day's
 *       interest on that day's closing balance, and on the last day of its interest period (quarterly by default,
 *       counted from the financial year) the period's interest is credited to it.</li>
 *   <li><b>Term deposit interest</b>: every funded deposit accrues day by day; on each credit date the period's
 *       interest is credited to the deposit, and a deposit that pays its interest out pays it at once to the savings
 *       or current account named at booking, or to the depositor's bank account through the payment gateway.</li>
 * </ol>
 * Per account, in its own transaction: an account that fails is an EOD exception, the run goes on (above 5% failing
 * the step fails). The days after the business date up to the next working day are closed with it, as for loans.
 * Re-running a day posts nothing: each account records the last day it accrued. Amounts come from the deposit engine
 * ({@link InterestDayEnd}); tax deducted at source is not deducted yet (P4-3b).
 */
@Component
class DepositEod implements EodStepProvider {

    static final String USER = "eod";
    private final Outbox outbox;

    DepositEod(Outbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public List<EodEngine.Step> steps(JdbcTemplate jdbc, String tenant) {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        return List.of(savings(jdbc, tx), termDeposits(jdbc, tx));
    }

    /** The last calendar day this business date closes: the day before the next working day. */
    static LocalDate through(JdbcTemplate jdbc, LocalDate businessDate) {
        return jdbc.queryForObject("SELECT platform.next_working_day(?::date) - 1", LocalDate.class, businessDate.toString());
    }

    static String payableGl(JdbcTemplate jdbc) {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = 'deposits.gl.interest-payable'", String.class);
        return v.isEmpty() ? "2404" : v.get(0).trim();
    }

    static int yearStartMonth(JdbcTemplate jdbc) {
        List<Integer> v = jdbc.queryForList("SELECT financial_year_start_month FROM platform.legal_entity WHERE id = 1", Integer.class);
        return v.isEmpty() || v.get(0) == null ? 4 : v.get(0);
    }

    // ================================================================================================ savings

    private EodEngine.ItemStep savings(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new EodEngine.ItemStep() {
            private LocalDate through;
            private String payable;
            private int yearStart;
            private DepositPostings post;

            @Override public String name() { return "Savings interest (accrual and credit)"; }

            @Override public List<String> items(EodEngine.Context ctx) {
                through = through(jdbc, ctx.businessDate());
                payable = payableGl(jdbc);
                yearStart = yearStartMonth(jdbc);
                post = new DepositPostings(DepositMoneyService.headsOf(jdbc), ctx.businessDate());
                return jdbc.queryForList("""
                        SELECT a.id::text FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code
                         WHERE a.family = 'CASA' AND a.status = 'ACTIVE' AND p.interest_bands IS NOT NULL ORDER BY a.account_no
                        """, String.class);
            }

            @Override public void process(EodEngine.Context ctx, String accountId) {
                tx.executeWithoutResult(s -> savingsDayEnd(jdbc, post, payable, yearStart, ctx.businessDate(), through, UUID.fromString(accountId)));
            }

            @Override public double maxFailureRatio() { return 0.05; }
        };
    }

    static void savingsDayEnd(JdbcTemplate jdbc, DepositPostings post, String payable, int yearStart, LocalDate businessDate, LocalDate through,
                              UUID id) {
        Map<String, Object> a = jdbc.queryForMap("""
                SELECT a.account_no, a.branch_code, p.gl_liability, p.gl_interest_expense, p.band_mode, p.day_count, p.interest_posting_months,
                       p.rounding, p.code
                  FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code WHERE a.id = ? FOR UPDATE OF a
                """, id);
        String accountNo = (String) a.get("account_no");
        String branch = (String) a.get("branch_code");
        List<SavingsInterest.Band> bands = jdbc.query("""
                SELECT (b->>'fromBalance')::numeric, (b->>'ratePercent')::numeric
                  FROM deposits.product p, jsonb_array_elements(p.interest_bands) b WHERE p.code = ?
                """, (rs, i) -> new SavingsInterest.Band(rs.getBigDecimal(1), rs.getBigDecimal(2)), a.get("code"));
        SavingsInterest interest = new SavingsInterest(bands, SavingsInterest.BandMode.valueOf((String) a.get("band_mode")),
                DayCount.valueOf(a.get("day_count") == null ? "ACTUAL_365" : (String) a.get("day_count")));

        List<Map<String, Object>> st = jdbc.queryForList("""
                SELECT accrued_through, period_from, exact::numeric AS exact, accrued::numeric AS accrued FROM deposits.interest_state WHERE account_id = ?
                """, id);
        InterestDayEnd.SavingsState state;
        if (st.isEmpty()) {
            LocalDate first = jdbc.queryForObject("SELECT min(business_date) FROM deposits.movement WHERE account_id = ?", LocalDate.class, id);
            if (first == null) return;                                       // nothing has been paid in: nothing to earn
            state = InterestDayEnd.SavingsState.startingOn(first);
        } else {
            Map<String, Object> r = st.get(0);
            state = new InterestDayEnd.SavingsState(date(r.get("accrued_through")), (BigDecimal) r.get("exact"), (BigDecimal) r.get("accrued"),
                    date(r.get("period_from")));
        }
        if (!through.isAfter(state.accruedThrough())) return;

        // closing balance of each day: the balance after the day's last movement, carried forward
        LocalDate firstDay = state.accruedThrough().plusDays(1);
        List<BigDecimal> opening = jdbc.queryForList("""
                SELECT balance_after::numeric FROM deposits.movement WHERE account_id = ? AND business_date < ? ORDER BY seq DESC LIMIT 1
                """, BigDecimal.class, id, firstDay);
        NavigableMap<LocalDate, BigDecimal> closing = new TreeMap<>();
        jdbc.query("SELECT business_date, balance_after::numeric FROM deposits.movement WHERE account_id = ? AND business_date >= ? ORDER BY seq",
                rs -> { closing.put(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)); }, id, firstDay);
        BigDecimal before = opening.isEmpty() ? BigDecimal.ZERO : opening.get(0);
        InterestDayEnd.SavingsResult r = InterestDayEnd.savings(interest, state, through, d -> {
            Map.Entry<LocalDate, BigDecimal> e = closing.floorEntry(d);
            return e == null ? before : e.getValue();
        }, ((Number) a.get("interest_posting_months")).intValue(), yearStart, Rounding.valueOf((String) a.get("rounding")));

        Leg leg = new Leg(branch, (String) a.get("gl_liability"), accountNo);
        BigDecimal credited = BigDecimal.ZERO;
        LocalDate lastCredit = null;
        for (Event e : r.events()) {
            if (e instanceof Accrual acc) {
                TransactionLot lot = post.interestAccrual(branch, (String) a.get("gl_interest_expense"), payable, accountNo, acc.amount(), accountNo);
                PostingService.postDuringEod(jdbc, lot, USER);
                event(jdbc, id, "ACCRUAL", acc.from(), acc.to(), null, acc.amount(), lot.id(), null, businessDate, null);
            } else if (e instanceof Credit c) {
                String narration = "Interest " + c.periodFrom() + " to " + c.periodEnd();
                TransactionLot lot = post.interestCredit(leg, payable, c.amount(), accountNo, narration);
                PostingService.postDuringEod(jdbc, lot, USER);
                UUID m = movement(jdbc, id, "INTEREST_CREDIT", "CREDIT", c.amount(), lot.id(), null, narration, null, null);
                event(jdbc, id, "CREDIT", c.periodFrom(), c.periodEnd(), null, c.amount(), lot.id(), m, businessDate, null);
                credited = credited.add(c.amount());
                lastCredit = c.periodEnd();
                jdbc.update("UPDATE deposits.casa_account SET next_interest_date = ? WHERE account_id = ?",
                        SavingsInterest.periodEnd(c.periodEnd().plusDays(1), ((Number) a.get("interest_posting_months")).intValue(), yearStart), id);
            }
        }
        InterestDayEnd.SavingsState s = r.state();
        jdbc.update("""
                INSERT INTO deposits.interest_state (account_id, accrued_through, period_from, exact, accrued, credited, last_credit_on)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id) DO UPDATE SET accrued_through = EXCLUDED.accrued_through, period_from = EXCLUDED.period_from,
                       exact = EXCLUDED.exact, accrued = EXCLUDED.accrued, credited = deposits.interest_state.credited + EXCLUDED.credited,
                       last_credit_on = coalesce(EXCLUDED.last_credit_on, deposits.interest_state.last_credit_on), updated_at = now()
                """, id, s.accruedThrough(), s.periodFrom(), s.exact(), s.accrued(), credited, lastCredit);
    }

    // ================================================================================================ term deposits

    private EodEngine.ItemStep termDeposits(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new EodEngine.ItemStep() {
            private LocalDate through;
            private String payable;
            private DepositPostings post;

            @Override public String name() { return "Term deposit interest (accrual, credit, payout)"; }

            @Override public List<String> items(EodEngine.Context ctx) {
                through = through(jdbc, ctx.businessDate());
                payable = payableGl(jdbc);
                post = new DepositPostings(DepositMoneyService.headsOf(jdbc), ctx.businessDate());
                return jdbc.queryForList("""
                        SELECT a.id::text FROM deposits.account a JOIN deposits.term_deposit t ON t.account_id = a.id
                         WHERE a.family = 'TERM_DEPOSIT' AND a.status = 'ACTIVE' AND t.start_date IS NOT NULL ORDER BY a.account_no
                        """, String.class);
            }

            @Override public void process(EodEngine.Context ctx, String accountId) {
                tx.executeWithoutResult(s -> depositDayEnd(jdbc, outbox, post, payable, ctx.businessDate(), through, UUID.fromString(accountId)));
            }

            @Override public double maxFailureRatio() { return 0.05; }
        };
    }

    /** A funded deposit's terms as the engine needs them. */
    static TermDeposit.Terms terms(Map<String, Object> t) {
        return new TermDeposit.Terms(((BigDecimal) t.get("principal")).stripTrailingZeros(), ((BigDecimal) t.get("rate_percent")).stripTrailingZeros(),
                date(t.get("start_date")), date(t.get("maturity_date")), ((Number) t.get("interest_period_months")).intValue(),
                Boolean.TRUE.equals(t.get("cumulative")), DayCount.valueOf((String) t.get("day_count")), Rounding.valueOf((String) t.get("rounding")));
    }

    static final String TERMS = """
            SELECT a.account_no, a.branch_code, a.customer_id, p.gl_liability, p.gl_interest_expense, t.principal::numeric AS principal,
                   t.rate_percent::numeric AS rate_percent, t.start_date, t.maturity_date, t.interest_period_months, t.cumulative, t.day_count, t.rounding,
                   t.payout_to, t.payout_account_id, t.payout_bank_name_cipher, t.payout_bank_account_cipher, t.payout_bank_account_last4,
                   t.payout_bank_ifsc
              FROM deposits.account a JOIN deposits.term_deposit t ON t.account_id = a.id JOIN deposits.product p ON p.code = a.product_code
             WHERE a.id = ?""";

    static void depositDayEnd(JdbcTemplate jdbc, Outbox outbox, DepositPostings post, String payable, LocalDate businessDate, LocalDate through,
                              UUID id) {
        Map<String, Object> t = jdbc.queryForMap(TERMS + " FOR UPDATE OF a", id);
        TermDeposit.Terms terms = terms(t);
        List<Map<String, Object>> st = jdbc.queryForList("""
                SELECT accrued_through, booked::numeric AS booked, credited::numeric AS credited, periods_credited FROM deposits.interest_state
                 WHERE account_id = ?
                """, id);
        InterestDayEnd.DepositState state = st.isEmpty() ? InterestDayEnd.DepositState.startingOn(terms.start())
                : new InterestDayEnd.DepositState(date(st.get(0).get("accrued_through")), (BigDecimal) st.get(0).get("booked"),
                        ((Number) st.get(0).get("periods_credited")).intValue());
        BigDecimal creditedBefore = st.isEmpty() ? BigDecimal.ZERO : (BigDecimal) st.get(0).get("credited");
        InterestDayEnd.DepositResult r = InterestDayEnd.deposit(terms, state, through);
        if (r.events().isEmpty() && r.state().equals(state)) return;

        String accountNo = (String) t.get("account_no");
        String branch = (String) t.get("branch_code");
        Leg leg = new Leg(branch, (String) t.get("gl_liability"), accountNo);
        BigDecimal credited = BigDecimal.ZERO;
        LocalDate lastCredit = null;
        for (Event e : r.events()) {
            if (e instanceof Accrual acc) {
                TransactionLot lot = post.interestAccrual(branch, (String) t.get("gl_interest_expense"), payable, accountNo, acc.amount(), accountNo);
                PostingService.postDuringEod(jdbc, lot, USER);
                event(jdbc, id, "ACCRUAL", acc.from(), acc.to(), null, acc.amount(), lot.id(), null, businessDate, null);
            } else if (e instanceof Credit c) {
                String narration = "Interest for period " + c.number() + " (" + c.periodFrom() + " to " + c.periodEnd().minusDays(1) + ")";
                TransactionLot lot = post.interestCredit(leg, payable, c.amount(), accountNo, narration);
                PostingService.postDuringEod(jdbc, lot, USER);
                UUID m = movement(jdbc, id, "INTEREST_CREDIT", "CREDIT", c.amount(), lot.id(), null, narration, null, null);
                event(jdbc, id, "CREDIT", c.periodFrom(), c.periodEnd(), c.number(), c.amount(), lot.id(), m, businessDate, null);
                credited = credited.add(c.amount());
                lastCredit = c.periodEnd();
                if (!terms.cumulative()) payOut(jdbc, outbox, post, businessDate, id, t, leg, c);
            }
        }
        InterestDayEnd.DepositState s = r.state();
        BigDecimal creditedNow = creditedBefore.add(credited);
        jdbc.update("""
                INSERT INTO deposits.interest_state (account_id, accrued_through, booked, accrued, credited, periods_credited, last_credit_on)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id) DO UPDATE SET accrued_through = EXCLUDED.accrued_through, booked = EXCLUDED.booked, accrued = EXCLUDED.accrued,
                       credited = EXCLUDED.credited, periods_credited = EXCLUDED.periods_credited,
                       last_credit_on = coalesce(EXCLUDED.last_credit_on, deposits.interest_state.last_credit_on), updated_at = now()
                """, id, s.accruedThrough(), s.booked(), s.booked().subtract(creditedNow), creditedNow, s.periodsCredited(), lastCredit);
    }

    /**
     * Pays a period's interest out of the deposit: to the savings or current account named at booking, or to the
     * depositor's bank account through the gateway. When it cannot be paid (the account is no longer active or is
     * frozen for credits, no bank account recorded), it stays on the deposit, to be paid with the proceeds.
     */
    private static void payOut(JdbcTemplate jdbc, Outbox outbox, DepositPostings post, LocalDate businessDate, UUID id, Map<String, Object> t,
                               Leg leg, Credit c) {
        String accountNo = leg.accountNo();
        if ("CASA".equals(t.get("payout_to"))) {
            UUID payId = (UUID) t.get("payout_account_id");
            Map<String, Object> pay = jdbc.queryForMap("""
                    SELECT a.account_no, a.branch_code, a.status, p.gl_liability,
                           EXISTS (SELECT 1 FROM deposits.account_hold h WHERE h.account_id = a.id AND h.status = 'ACTIVE'
                                      AND h.kind IN ('CREDIT_FREEZE','TOTAL_FREEZE')) AS frozen
                      FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code WHERE a.id = ?
                    """, payId);
            if (!"ACTIVE".equals(pay.get("status")) || Boolean.TRUE.equals(pay.get("frozen"))) {
                event(jdbc, id, "PAYOUT_HELD", c.periodFrom(), c.periodEnd(), c.number(), c.amount(), null, null, businessDate,
                        "account " + pay.get("account_no") + (Boolean.TRUE.equals(pay.get("frozen")) ? " is frozen for credits" : " is " + pay.get("status"))
                                + ": the interest stays on the deposit");
                return;
            }
            Leg to = new Leg((String) pay.get("branch_code"), (String) pay.get("gl_liability"), (String) pay.get("account_no"));
            TransactionLot lot = post.interestPayout(leg, to, c.amount(), accountNo);
            PostingService.postDuringEod(jdbc, lot, USER);
            String narration = "Interest on deposit " + accountNo;
            UUID out = movement(jdbc, id, "INTEREST_PAYOUT", "DEBIT", c.amount(), lot.id(), payId, "Interest paid to " + to.accountNo(), null, null);
            movement(jdbc, payId, "TRANSFER_IN", "CREDIT", c.amount(), lot.id(), id, narration, null, null);
            event(jdbc, id, "PAYOUT", c.periodFrom(), c.periodEnd(), c.number(), c.amount(), lot.id(), out, businessDate, "paid to account " + to.accountNo());
            return;
        }
        if (t.get("payout_bank_ifsc") == null) {
            event(jdbc, id, "PAYOUT_HELD", c.periodFrom(), c.periodEnd(), c.number(), c.amount(), null, null, businessDate,
                    "no bank account is recorded: the interest stays on the deposit");
            return;
        }
        TransactionLot lot = post.gatewayTransferOut(leg, c.amount(), accountNo, "Interest on deposit " + accountNo);
        PostingService.postDuringEod(jdbc, lot, USER);
        byte[][] party = {(byte[]) t.get("payout_bank_name_cipher"), (byte[]) t.get("payout_bank_account_cipher")};
        UUID out = movement(jdbc, id, "BANK_TRANSFER_OUT", "DEBIT", c.amount(), lot.id(), null, "Interest on deposit " + accountNo, party,
                new String[] {(String) t.get("payout_bank_account_last4"), (String) t.get("payout_bank_ifsc")});
        jdbc.update("INSERT INTO deposits.gateway_transfer (movement_id, status) VALUES (?, 'SENT')", out);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("movementId", out.toString());
        payload.put("accountId", id.toString());
        payload.put("accountNo", accountNo);
        payload.put("customerId", String.valueOf(t.get("customer_id")));
        payload.put("branch", leg.branch());
        payload.put("amount", DepositRuleSets.plain(c.amount()));
        payload.put("businessDate", businessDate.toString());
        outbox.publish(jdbc, DepositEvents.TRANSFER_OUT, accountNo, payload);
        event(jdbc, id, "PAYOUT", c.periodFrom(), c.periodEnd(), c.number(), c.amount(), lot.id(), out, businessDate,
                "sent through the payment gateway to XXXXXXXX" + t.get("payout_bank_account_last4"));
    }

    // ================================================================================================ rows

    private static UUID movement(JdbcTemplate jdbc, UUID accountId, String kind, String direction, BigDecimal amount, UUID lotId, UUID counterparty,
                                 String narration, byte[][] party, String[] partyClear) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deposits.movement (id, account_id, kind, direction, amount, balance_after, business_date, lot_id, narration, counterparty_id,
                                               party_name_cipher, party_account_cipher, party_account_last4, party_ifsc, created_by)
                VALUES (?, ?, ?, ?, ?, 0, current_date, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, accountId, kind, direction, amount, lotId, narration.length() > 200 ? narration.substring(0, 200) : narration, counterparty,
                party == null ? null : party[0], party == null ? null : party[1], partyClear == null ? null : partyClear[0],
                partyClear == null ? null : partyClear[1], USER);
        return id;
    }

    static void event(JdbcTemplate jdbc, UUID accountId, String kind, LocalDate from, LocalDate to, Integer periodNo, BigDecimal amount, UUID lotId,
                      UUID movementId, LocalDate businessDate, String note) {
        jdbc.update("""
                INSERT INTO deposits.interest_event (account_id, kind, period_from, period_to, period_no, amount, lot_id, movement_id, business_date, note,
                                                     created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, accountId, kind, from, to, periodNo, amount, lotId, movementId, businessDate, note, USER);
    }

    /** A date column read through queryForMap: java.sql.Date from the JDBC driver. */
    static LocalDate date(Object v) {
        return v == null ? null : v instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) v;
    }
}
