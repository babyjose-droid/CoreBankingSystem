package com.corebanking.deposits.internal;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import com.corebanking.deposits.DepositEvents;
import com.corebanking.deposits.DepositPostings;
import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.deposits.DepositRules;
import com.corebanking.deposits.Dormancy;
import com.corebanking.deposits.InterestDayEnd;
import com.corebanking.deposits.InterestDayEnd.Accrual;
import com.corebanking.deposits.InterestDayEnd.Credit;
import com.corebanking.deposits.InterestDayEnd.Event;
import com.corebanking.deposits.SavingsInterest;
import com.corebanking.deposits.TermDeposit;
import com.corebanking.eod.EodStepProvider;
import com.corebanking.kernel.EodEngine;
import com.corebanking.ledger.TransactionLot;
import com.corebanking.platform.Json;
import com.corebanking.platform.Outbox;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deposits at end of day, after the lending steps:
 * <ol>
 *   <li><b>Savings interest</b> (P4-3): every interest-bearing savings or current account accrues each calendar day's
 *       interest on that day's closing balance, and on the last day of its interest period (quarterly by default,
 *       counted from the financial year) the period's interest is credited to it.</li>
 *   <li><b>Term deposit interest</b> (P4-3): every funded deposit accrues day by day; on each credit date the period's
 *       interest is credited to the deposit, and a deposit that pays its interest out pays it at once to the savings
 *       or current account named at booking, or to the depositor's bank account through the payment gateway.</li>
 *   <li><b>Pledges</b> (P4-5b): a deposit pledged for a loan that is now closed or cancelled is released.</li>
 *   <li><b>Maturity</b> (P4-4): a deposit that has reached its maturity date is paid out or renewed as instructed;
 *       when that cannot be done it waits as MATURED, to be paid when claimed ({@link DepositClosing#mature}).</li>
 *   <li><b>Maturity notices</b> (P4-4): a deposit maturing within the notice period is noticed once, and the depositor
 *       is told (message template DEPOSIT_MATURITY).</li>
 *   <li><b>Inoperative accounts</b> (P4-6): a savings or current account without a customer movement for 24 months is inoperative.</li>
 *   <li><b>Minimum balance</b> (P4-4): at each month-end a savings or current account's average balance is compared
 *       with its product's minimum, and a charge is levied when the shortfall follows a notified one.</li>
 * </ol>
 * Per account, in its own transaction: an account that fails is an EOD exception, the run goes on (above 5% failing
 * the step fails). The days after the business date up to the next working day are closed with it, as for loans.
 * Re-running a day posts nothing: each account records the last day it accrued, a deposit is matured once, a notice is
 * sent once and a month is evaluated once. Amounts come from the deposit engine ({@link InterestDayEnd}). Tax deducted
 * at source (P4-3b) is taken from each credit of a product that deducts it ({@link TaxAtSource}); a payout pays what is left.
 */
@Component
class DepositEod implements EodStepProvider {

    static final String USER = "eod";
    private final Outbox outbox;
    private final Json json;

    DepositEod(Outbox outbox, Json json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public List<EodEngine.Step> steps(JdbcTemplate jdbc, String tenant) {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        return List.of(savings(jdbc, tx), termDeposits(jdbc, tx), recurringDeposits(jdbc, tx), pledges(jdbc, tx), maturities(jdbc, tx), notices(jdbc, tx),
                inoperative(jdbc, tx), minimumBalances(jdbc, tx), unclaimed(jdbc, tx));
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

    /** One step over a list of accounts, each in its own transaction. */
    private abstract static class AccountStep implements EodEngine.ItemStep {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        DepositBooks books;
        DepositRules rules;

        AccountStep(JdbcTemplate jdbc, TransactionTemplate tx) {
            this.jdbc = jdbc;
            this.tx = tx;
        }

        abstract List<String> accounts(EodEngine.Context ctx);

        abstract void each(EodEngine.Context ctx, UUID accountId);

        @Override public List<String> items(EodEngine.Context ctx) {
            books = DepositBooks.eod(jdbc, new DepositPostings(DepositMoneyService.headsOf(jdbc), ctx.businessDate()), ctx.businessDate());
            List<String> ids = accounts(ctx);
            rules = ids.isEmpty() ? null : new DepositRuleSets(jdbc).current();
            return ids;
        }

        @Override public void process(EodEngine.Context ctx, String accountId) {
            tx.executeWithoutResult(s -> each(ctx, UUID.fromString(accountId)));
        }

        @Override public double maxFailureRatio() { return 0.05; }
    }

    // ================================================================================================ savings

    private EodEngine.ItemStep savings(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new AccountStep(jdbc, tx) {
            private LocalDate through;

            @Override public String name() { return "Savings interest (accrual and credit)"; }

            @Override List<String> accounts(EodEngine.Context ctx) {
                through = through(jdbc, ctx.businessDate());
                return jdbc.queryForList("""
                        SELECT a.id::text FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code
                         WHERE a.family = 'CASA' AND a.status = 'ACTIVE' AND p.interest_bands IS NOT NULL ORDER BY a.account_no
                        """, String.class);
            }

            @Override void each(EodEngine.Context ctx, UUID id) {
                savingsDayEnd(books, rules, through, id);
            }
        };
    }

    /**
     * What the savings interest of an account works from: its product's bands, where its interest stands (null when
     * nothing has ever been paid in) and its closing balance on each day from {@code firstDay}.
     */
    record SavingsContext(String accountNo, String branch, String gl, String expenseGl, UUID customerId, boolean tds, SavingsInterest interest,
                          int postingMonths, Rounding rounding, InterestDayEnd.SavingsState state, Function<LocalDate, BigDecimal> balanceAt) {
        Leg leg() { return new Leg(branch, gl, accountNo); }
    }

    static SavingsContext savingsContext(JdbcTemplate jdbc, UUID id) {
        Map<String, Object> a = jdbc.queryForMap("""
                SELECT a.account_no, a.branch_code, p.gl_liability, p.gl_interest_expense, p.band_mode, p.day_count, p.interest_posting_months,
                       p.rounding, p.code, p.tds_applicable, a.customer_id
                  FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code WHERE a.id = ? FOR UPDATE OF a
                """, id);
        List<SavingsInterest.Band> bands = jdbc.query("""
                SELECT (b->>'fromBalance')::numeric, (b->>'ratePercent')::numeric
                  FROM deposits.product p, jsonb_array_elements(p.interest_bands) b WHERE p.code = ?
                """, (rs, i) -> new SavingsInterest.Band(rs.getBigDecimal(1), rs.getBigDecimal(2)), a.get("code"));
        SavingsInterest interest = new SavingsInterest(bands, SavingsInterest.BandMode.valueOf((String) a.get("band_mode")),
                DayCount.valueOf(a.get("day_count") == null ? "ACTUAL_365" : (String) a.get("day_count")));
        List<Map<String, Object>> st = jdbc.queryForList("""
                SELECT accrued_through, period_from, exact::numeric AS exact, accrued::numeric AS accrued FROM deposits.interest_state WHERE account_id = ?
                """, id);
        InterestDayEnd.SavingsState state = null;
        if (st.isEmpty()) {
            LocalDate first = jdbc.queryForObject("SELECT min(business_date) FROM deposits.movement WHERE account_id = ?", LocalDate.class, id);
            if (first != null) state = InterestDayEnd.SavingsState.startingOn(first);
        } else {
            Map<String, Object> r = st.get(0);
            state = new InterestDayEnd.SavingsState(date(r.get("accrued_through")), (BigDecimal) r.get("exact"), (BigDecimal) r.get("accrued"),
                    date(r.get("period_from")));
        }
        Function<LocalDate, BigDecimal> balanceAt = state == null ? d -> BigDecimal.ZERO : closingBalances(jdbc, id, state.accruedThrough().plusDays(1));
        return new SavingsContext((String) a.get("account_no"), (String) a.get("branch_code"), (String) a.get("gl_liability"),
                (String) a.get("gl_interest_expense"), (UUID) a.get("customer_id"), Boolean.TRUE.equals(a.get("tds_applicable")), interest,
                ((Number) a.get("interest_posting_months")).intValue(), Rounding.valueOf((String) a.get("rounding")), state, balanceAt);
    }

    /** The closing balance of an account on each day from {@code firstDay}: the balance after the day's last movement, carried forward. */
    static Function<LocalDate, BigDecimal> closingBalances(JdbcTemplate jdbc, UUID id, LocalDate firstDay) {
        List<BigDecimal> opening = jdbc.queryForList("""
                SELECT balance_after::numeric FROM deposits.movement WHERE account_id = ? AND business_date < ? ORDER BY seq DESC LIMIT 1
                """, BigDecimal.class, id, firstDay);
        NavigableMap<LocalDate, BigDecimal> closing = new TreeMap<>();
        jdbc.query("SELECT business_date, balance_after::numeric FROM deposits.movement WHERE account_id = ? AND business_date >= ? ORDER BY seq",
                rs -> { closing.put(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)); }, id, firstDay);
        BigDecimal before = opening.isEmpty() ? BigDecimal.ZERO : opening.get(0);
        return d -> {
            Map.Entry<LocalDate, BigDecimal> e = closing.floorEntry(d);
            return e == null ? before : e.getValue();
        };
    }

    static void savingsDayEnd(DepositBooks b, DepositRules rules, LocalDate through, UUID id) {
        JdbcTemplate jdbc = b.jdbc();
        SavingsContext c = savingsContext(jdbc, id);
        if (c.state() == null || !through.isAfter(c.state().accruedThrough())) return;   // nothing paid in, or the day is done
        int yearStart = yearStartMonth(jdbc);
        InterestDayEnd.SavingsResult r = InterestDayEnd.savings(c.interest(), c.state(), through, c.balanceAt(), c.postingMonths(), yearStart, c.rounding());
        postSavings(b, rules, c, id, r, yearStart, true);
    }

    /**
     * Posts the events of a savings run and saves where the interest stands. {@code moveNextDate}: after a credit the
     * account's next interest date moves on (not when it is being closed).
     */
    static void postSavings(DepositBooks b, DepositRules rules, SavingsContext c, UUID id, InterestDayEnd.SavingsResult r, int yearStart, boolean moveNextDate) {
        JdbcTemplate jdbc = b.jdbc();
        String payable = payableGl(jdbc);
        Leg leg = c.leg();
        BigDecimal credited = BigDecimal.ZERO;
        LocalDate lastCredit = null;
        for (Event e : r.events()) {
            if (e instanceof Accrual acc) {
                TransactionLot lot = b.post().interestAccrual(c.branch(), c.expenseGl(), payable, c.accountNo(), acc.amount(), c.accountNo());
                b.post(lot);
                b.event(id, "ACCRUAL", acc.from(), acc.to(), null, acc.amount(), lot.id(), null, null);
            } else if (e instanceof Credit cr) {
                String narration = "Interest " + cr.periodFrom() + " to " + cr.periodEnd();
                TransactionLot lot = b.post().interestCredit(leg, payable, cr.amount(), c.accountNo(), narration);
                b.post(lot);
                UUID m = b.movement(id, "INTEREST_CREDIT", "CREDIT", cr.amount(), lot.id(), null, narration, null);
                b.event(id, "CREDIT", cr.periodFrom(), cr.periodEnd(), null, cr.amount(), lot.id(), m, null);
                if (c.tds() && rules.flag(DepositRules.TDS_ON_SAVINGS, false)) {
                    TaxAtSource.onCredit(b, rules, id, c.customerId(), leg, cr.amount(), m, cr.periodFrom(), cr.periodEnd());
                }
                credited = credited.add(cr.amount());
                lastCredit = cr.periodEnd();
                if (moveNextDate) {
                    jdbc.update("UPDATE deposits.casa_account SET next_interest_date = ? WHERE account_id = ?",
                            SavingsInterest.periodEnd(cr.periodEnd().plusDays(1), c.postingMonths(), yearStart), id);
                }
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
        return new AccountStep(jdbc, tx) {
            private LocalDate through;

            @Override public String name() { return "Term deposit interest (accrual, credit, payout)"; }

            @Override List<String> accounts(EodEngine.Context ctx) {
                through = through(jdbc, ctx.businessDate());
                return jdbc.queryForList("""
                        SELECT a.id::text FROM deposits.account a JOIN deposits.term_deposit t ON t.account_id = a.id
                          JOIN deposits.product p ON p.code = a.product_code
                         WHERE a.family = 'TERM_DEPOSIT' AND a.status = 'ACTIVE' AND t.start_date IS NOT NULL AND p.product_type <> 'RD' ORDER BY a.account_no
                        """, String.class);
            }

            @Override void each(EodEngine.Context ctx, UUID id) {
                depositDayEnd(books, outbox, rules, through, id);
            }
        };
    }

    /** Recurring deposits (P4-5): each month that has ended is booked on the instalments held through it; each rest and maturity credited. */
    private EodEngine.ItemStep recurringDeposits(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new AccountStep(jdbc, tx) {
            private LocalDate through;

            @Override public String name() { return "Recurring deposit interest (monthly)"; }

            @Override List<String> accounts(EodEngine.Context ctx) {
                through = through(jdbc, ctx.businessDate());
                return jdbc.queryForList("""
                        SELECT a.id::text FROM deposits.account a JOIN deposits.term_deposit t ON t.account_id = a.id
                          JOIN deposits.product p ON p.code = a.product_code
                         WHERE a.family = 'TERM_DEPOSIT' AND a.status = 'ACTIVE' AND t.start_date IS NOT NULL AND p.product_type = 'RD' ORDER BY a.account_no
                        """, String.class);
            }

            @Override void each(EodEngine.Context ctx, UUID id) {
                RecurringDeposits.dayEnd(books, rules, through, id);
            }
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
                   t.payout_bank_ifsc, t.tds_applicable
              FROM deposits.account a JOIN deposits.term_deposit t ON t.account_id = a.id JOIN deposits.product p ON p.code = a.product_code
             WHERE a.id = ?""";

    static void depositDayEnd(DepositBooks b, Outbox outbox, DepositRules rules, LocalDate through, UUID id) {
        JdbcTemplate jdbc = b.jdbc();
        String payable = payableGl(jdbc);
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
                TransactionLot lot = b.post().interestAccrual(branch, (String) t.get("gl_interest_expense"), payable, accountNo, acc.amount(), accountNo);
                b.post(lot);
                b.event(id, "ACCRUAL", acc.from(), acc.to(), null, acc.amount(), lot.id(), null, null);
            } else if (e instanceof Credit c) {
                String narration = "Interest for period " + c.number() + " (" + c.periodFrom() + " to " + c.periodEnd().minusDays(1) + ")";
                TransactionLot lot = b.post().interestCredit(leg, payable, c.amount(), accountNo, narration);
                b.post(lot);
                UUID m = b.movement(id, "INTEREST_CREDIT", "CREDIT", c.amount(), lot.id(), null, narration, null);
                b.event(id, "CREDIT", c.periodFrom(), c.periodEnd(), c.number(), c.amount(), lot.id(), m, null);
                BigDecimal tax = Boolean.TRUE.equals(t.get("tds_applicable"))
                        ? TaxAtSource.onCredit(b, rules, id, (UUID) t.get("customer_id"), leg, c.amount(), m, c.periodFrom(), c.periodEnd())
                        : BigDecimal.ZERO;
                credited = credited.add(c.amount());
                lastCredit = c.periodEnd();
                BigDecimal net = c.amount().subtract(tax);
                if (!terms.cumulative() && net.signum() > 0) payOut(b, outbox, id, t, leg, new Credit(c.number(), c.periodFrom(), c.periodEnd(), net));
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
    private static void payOut(DepositBooks b, Outbox outbox, UUID id, Map<String, Object> t, Leg leg, Credit c) {
        JdbcTemplate jdbc = b.jdbc();
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
                b.event(id, "PAYOUT_HELD", c.periodFrom(), c.periodEnd(), c.number(), c.amount(), null, null,
                        "account " + pay.get("account_no") + (Boolean.TRUE.equals(pay.get("frozen")) ? " is frozen for credits" : " is " + pay.get("status"))
                                + ": the interest stays on the deposit");
                return;
            }
            Leg to = new Leg((String) pay.get("branch_code"), (String) pay.get("gl_liability"), (String) pay.get("account_no"));
            TransactionLot lot = b.post().interestPayout(leg, to, c.amount(), accountNo);
            b.post(lot);
            UUID out = b.movement(id, "INTEREST_PAYOUT", "DEBIT", c.amount(), lot.id(), payId, "Interest paid to " + to.accountNo(), null);
            b.movement(payId, "TRANSFER_IN", "CREDIT", c.amount(), lot.id(), id, "Interest on deposit " + accountNo, null);
            b.event(id, "PAYOUT", c.periodFrom(), c.periodEnd(), c.number(), c.amount(), lot.id(), out, "paid to account " + to.accountNo());
            return;
        }
        if (t.get("payout_bank_ifsc") == null) {
            b.event(id, "PAYOUT_HELD", c.periodFrom(), c.periodEnd(), c.number(), c.amount(), null, null,
                    "no bank account is recorded: the interest stays on the deposit");
            return;
        }
        TransactionLot lot = b.post().gatewayTransferOut(leg, c.amount(), accountNo, "Interest on deposit " + accountNo);
        UUID out = sendThroughGateway(b, outbox, id, t, leg, lot, c.amount(), "Interest on deposit " + accountNo);
        b.event(id, "PAYOUT", c.periodFrom(), c.periodEnd(), c.number(), c.amount(), lot.id(), out,
                "sent through the payment gateway to XXXXXXXX" + t.get("payout_bank_account_last4"));
    }

    /**
     * Money out of a deposit to its recorded bank account through the payment gateway: the lot (already built, to the
     * in-transit head), the BANK_TRANSFER_OUT movement with the payee, the transfer to follow, and the outbox event the
     * integration module sends it from. Returns the movement.
     */
    static UUID sendThroughGateway(DepositBooks b, Outbox outbox, UUID id, Map<String, Object> t, Leg leg, TransactionLot lot, BigDecimal amount,
                                   String narration) {
        b.post(lot);
        byte[][] party = {(byte[]) t.get("payout_bank_name_cipher"), (byte[]) t.get("payout_bank_account_cipher")};
        UUID out = b.movement(id, "BANK_TRANSFER_OUT", "DEBIT", amount, lot.id(), null, narration, null, party,
                new String[] {(String) t.get("payout_bank_account_last4"), (String) t.get("payout_bank_ifsc")});
        b.jdbc().update("INSERT INTO deposits.gateway_transfer (movement_id, status) VALUES (?, 'SENT')", out);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("movementId", out.toString());
        payload.put("accountId", id.toString());
        payload.put("accountNo", leg.accountNo());
        payload.put("customerId", String.valueOf(t.get("customer_id")));
        payload.put("branch", leg.branch());
        payload.put("amount", DepositRuleSets.plain(amount));
        payload.put("businessDate", b.today().toString());
        outbox.publish(b.jdbc(), DepositEvents.TRANSFER_OUT, leg.accountNo(), payload);
        return out;
    }

    // ================================================================================================ lifecycle (P4-4)

    /** P4-5b: a deposit pledged for a loan is freed once the loan is closed or cancelled, before maturities are paid. */
    private EodEngine.ItemStep pledges(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new AccountStep(jdbc, tx) {
            @Override public String name() { return "Pledges of closed loans released"; }

            @Override List<String> accounts(EodEngine.Context ctx) {
                return DepositPledges.releasable(jdbc);
            }

            @Override void each(EodEngine.Context ctx, UUID holdId) {
                DepositPledges.release(jdbc, holdId, USER);
            }
        };
    }

    private EodEngine.ItemStep maturities(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new AccountStep(jdbc, tx) {
            @Override public String name() { return "Term deposit maturity (pay out or renew)"; }

            @Override List<String> accounts(EodEngine.Context ctx) {
                return jdbc.queryForList("""
                        SELECT a.id::text FROM deposits.account a JOIN deposits.term_deposit t ON t.account_id = a.id
                         WHERE a.family = 'TERM_DEPOSIT' AND a.status = 'ACTIVE' AND t.maturity_date <= ?::date ORDER BY t.maturity_date, a.account_no
                        """, String.class, ctx.businessDate().toString());
            }

            @Override void each(EodEngine.Context ctx, UUID id) {
                DepositClosing.mature(books, rules, json, outbox, id);
            }
        };
    }

    private EodEngine.ItemStep notices(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new AccountStep(jdbc, tx) {
            private int days;

            @Override public String name() { return "Term deposit maturity notices"; }

            @Override List<String> accounts(EodEngine.Context ctx) {
                days = DepositClosing.noticeDays(jdbc, new DepositRuleSets(jdbc).current());
                return jdbc.queryForList("""
                        SELECT a.id::text FROM deposits.account a JOIN deposits.term_deposit t ON t.account_id = a.id
                         WHERE a.family = 'TERM_DEPOSIT' AND a.status = 'ACTIVE' AND t.maturity_date > ?::date AND t.maturity_date - ? <= ?::date
                           AND NOT EXISTS (SELECT 1 FROM deposits.maturity_notice n WHERE n.account_id = a.id AND n.maturity_date = t.maturity_date)
                         ORDER BY t.maturity_date, a.account_no
                        """, String.class, ctx.businessDate().toString(), days, ctx.businessDate().toString());
            }

            @Override void each(EodEngine.Context ctx, UUID id) {
                DepositClosing.notice(books, outbox, id);
            }
        };
    }

    /** P4-6: savings and current accounts without a customer movement for the rule set's months become inoperative (a bank only). */
    private EodEngine.ItemStep inoperative(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new AccountStep(jdbc, tx) {
            @Override public String name() { return "Inoperative accounts"; }

            @Override List<String> accounts(EodEngine.Context ctx) {
                if (jdbc.queryForObject("SELECT deposits.rule_kind()", String.class) == null) return List.of();
                Dormancy.Rules r = DepositDormancy.rulesOf(new DepositRuleSets(jdbc).current());
                return r == null ? List.of() : DepositDormancy.toMarkInoperative(jdbc, r, ctx.businessDate());
            }

            @Override void each(EodEngine.Context ctx, UUID id) {
                DepositDormancy.markInoperative(jdbc, id, ctx.businessDate());
            }
        };
    }

    /** P4-6: balances not operated or claimed for the rule set's years go to the unclaimed deposits head (a bank only). */
    private EodEngine.ItemStep unclaimed(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new AccountStep(jdbc, tx) {
            @Override public String name() { return "Unclaimed deposits (DEA Fund)"; }

            @Override List<String> accounts(EodEngine.Context ctx) {
                if (jdbc.queryForObject("SELECT deposits.rule_kind()", String.class) == null) return List.of();
                Dormancy.Rules r = DepositDormancy.rulesOf(new DepositRuleSets(jdbc).current());
                return r == null ? List.of() : DepositDormancy.toTransferUnclaimed(jdbc, r, ctx.businessDate());
            }

            @Override void each(EodEngine.Context ctx, UUID id) {
                DepositDormancy.transferUnclaimed(books, id);
            }
        };
    }

    private EodEngine.ItemStep minimumBalances(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new AccountStep(jdbc, tx) {
            private LocalDate through;

            @Override public String name() { return "Minimum balance (month-end)"; }

            @Override List<String> accounts(EodEngine.Context ctx) {
                through = through(jdbc, ctx.businessDate());
                // accounts whose last month-end evaluated is before the last month-end this date closes
                return jdbc.queryForList("""
                        SELECT a.id::text FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code
                          JOIN deposits.casa_account c ON c.account_id = a.id
                         WHERE a.family = 'CASA' AND a.status = 'ACTIVE' AND p.min_balance > 0
                           AND (date_trunc('month', ?::date + 1) - interval '1 day')::date >= a.opened_on
                           AND (c.min_balance_through IS NULL
                                OR c.min_balance_through < (date_trunc('month', ?::date + 1) - interval '1 day')::date)
                         ORDER BY a.account_no
                        """, String.class, through.toString(), through.toString());
            }

            @Override void each(EodEngine.Context ctx, UUID id) {
                DepositClosing.minimumBalance(books, json, id, through);
            }
        };
    }

    // ================================================================================================ rows

    /** A date column read through queryForMap: java.sql.Date from the JDBC driver. */
    static LocalDate date(Object v) {
        return v == null ? null : v instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) v;
    }
}
