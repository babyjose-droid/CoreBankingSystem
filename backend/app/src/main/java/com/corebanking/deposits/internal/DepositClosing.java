package com.corebanking.deposits.internal;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import com.corebanking.deposits.DepositCharges;
import com.corebanking.deposits.DepositEvents;
import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.deposits.DepositPostings.PayTo;
import com.corebanking.deposits.DepositRateTable;
import com.corebanking.deposits.DepositRefusal;
import com.corebanking.deposits.DepositRules;
import com.corebanking.deposits.InterestDayEnd;
import com.corebanking.deposits.Maturity;
import com.corebanking.deposits.PrematureWithdrawal;
import com.corebanking.deposits.Tenure;
import com.corebanking.deposits.TermDeposit;
import com.corebanking.deposits.TermDepositBooking;
import com.corebanking.deposits.TermDepositProduct;
import com.corebanking.ledger.TransactionLot;
import com.corebanking.platform.Json;
import com.corebanking.platform.Outbox;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The end of a deposit account (P4-4), written the same way by the day-end and by an approved request
 * ({@link DepositBooks}): charges with GST, the month-end minimum balance, closing a savings or current account,
 * closing a term deposit before or at maturity, renewing it, and the maturity notice.
 *
 * <p>Each operation first works out every reason it cannot be done and does nothing when there is one
 * ({@link DepositRefusal}); then it writes. The amounts come from the deposit engine: {@link PrematureWithdrawal}
 * (both rule sets), {@link Maturity}, {@link InterestDayEnd#savingsToClose} and {@link DepositCharges}.
 */
final class DepositClosing {

    static final Set<String> REASONS = Set.of("DEPOSITOR_REQUEST", "DEATH", "EMERGENCY_EXPENSE", "CRITICAL_ILLNESS");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private DepositClosing() {}

    /**
     * Where the proceeds go. {@code account}: the receiving savings or current account (ACCOUNT); {@code reference}: the
     * slip number (CASH) or the bank's UTR (BANK_TRANSFER). GATEWAY pays a term deposit to the bank account recorded on it.
     */
    record Payout(PayTo to, UUID accountId, String reference) {}

    // ================================================================================================ reading

    /** A term deposit as closing needs it. */
    record Deposit(UUID id, String accountNo, String status, String branch, UUID customerId, String productCode, int productVersion, String gl,
                   String expenseGl, BigDecimal principal, Tenure tenure, LocalDate bookedOn, LocalDate start, LocalDate maturity, BigDecimal rate,
                   int periodMonths, boolean cumulative, DayCount dayCount, Rounding rounding, boolean tds, boolean prematureAllowed,
                   BigDecimal penalty, Integer minDays, String payoutTo, UUID payoutAccountId, String instruction, String bankIfsc, String fundingSource,
                   Map<String, Object> row, boolean rd) {
        Leg leg() { return new Leg(branch, gl, accountNo); }

        /** The interest credits a deposit has: its periods, or for a recurring deposit (P4-5) its months. */
        int creditsDue() {
            return rd ? tenure.months() : TermDeposit.periods(terms()).size();
        }

        boolean funded() { return start != null; }

        TermDeposit.Terms terms() {
            return new TermDeposit.Terms(principal, rate, start, maturity, periodMonths, cumulative, dayCount, rounding);
        }
    }

    static Deposit deposit(JdbcTemplate jdbc, UUID id, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.account_no, a.status, a.branch_code, a.customer_id, a.product_code, a.product_version, p.gl_liability, p.gl_interest_expense,
                       t.principal::numeric AS principal, t.tenure_months, t.tenure_days, t.booked_on, t.start_date, t.maturity_date,
                       t.rate_percent::numeric AS rate_percent, t.interest_period_months, t.cumulative, t.day_count, t.rounding, t.tds_applicable,
                       t.premature_allowed, t.premature_penalty_percent::numeric AS premature_penalty_percent, t.premature_min_days, t.payout_to,
                       t.payout_account_id, t.maturity_instruction, t.payout_bank_ifsc, t.payout_bank_name_cipher, t.payout_bank_account_cipher,
                       t.payout_bank_account_last4, t.funding_source, t.add_ons::text AS add_ons, a.operating_mode, a.nomination_mode, p.product_type
                  FROM deposits.account a JOIN deposits.term_deposit t ON t.account_id = a.id JOIN deposits.product p ON p.code = a.product_code
                 WHERE a.id = ?""" + (lock ? " FOR UPDATE OF a" : ""), id);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        return new Deposit(id, (String) r.get("account_no"), (String) r.get("status"), (String) r.get("branch_code"), (UUID) r.get("customer_id"),
                (String) r.get("product_code"), ((Number) r.get("product_version")).intValue(), (String) r.get("gl_liability"),
                (String) r.get("gl_interest_expense"), ((BigDecimal) r.get("principal")).stripTrailingZeros(),
                new Tenure(((Number) r.get("tenure_months")).intValue(), ((Number) r.get("tenure_days")).intValue()), DepositEod.date(r.get("booked_on")),
                DepositEod.date(r.get("start_date")), DepositEod.date(r.get("maturity_date")), ((BigDecimal) r.get("rate_percent")).stripTrailingZeros(),
                ((Number) r.get("interest_period_months")).intValue(), Boolean.TRUE.equals(r.get("cumulative")), DayCount.valueOf((String) r.get("day_count")),
                Rounding.valueOf((String) r.get("rounding")), Boolean.TRUE.equals(r.get("tds_applicable")), Boolean.TRUE.equals(r.get("premature_allowed")),
                (BigDecimal) r.get("premature_penalty_percent"), r.get("premature_min_days") == null ? null : ((Number) r.get("premature_min_days")).intValue(),
                (String) r.get("payout_to"), (UUID) r.get("payout_account_id"), (String) r.get("maturity_instruction"), (String) r.get("payout_bank_ifsc"),
                (String) r.get("funding_source"), r, "RD".equals(r.get("product_type")));
    }

    /** Where a deposit's interest stands. */
    record Interest(BigDecimal booked, BigDecimal accrued, BigDecimal credited, int periodsCredited) {
        static final Interest NONE = new Interest(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    static Interest interest(JdbcTemplate jdbc, UUID id) {
        List<Map<String, Object>> st = jdbc.queryForList("""
                SELECT booked::numeric AS booked, accrued::numeric AS accrued, credited::numeric AS credited, periods_credited FROM deposits.interest_state
                 WHERE account_id = ?
                """, id);
        if (st.isEmpty()) return Interest.NONE;
        Map<String, Object> s = st.get(0);
        return new Interest((BigDecimal) s.get("booked"), (BigDecimal) s.get("accrued"), (BigDecimal) s.get("credited"),
                ((Number) s.get("periods_credited")).intValue());
    }

    static BigDecimal balance(JdbcTemplate jdbc, UUID id) {
        List<BigDecimal> b = jdbc.queryForList("SELECT balance::numeric FROM deposits.account_balance WHERE account_id = ?", BigDecimal.class, id);
        return b.isEmpty() ? BigDecimal.ZERO : b.get(0).stripTrailingZeros();
    }

    /** The active freezes and liens of an account, described for staff. */
    static List<String> holds(JdbcTemplate jdbc, UUID id) {
        return jdbc.query("SELECT kind, amount::numeric FROM deposits.account_hold WHERE account_id = ? AND status = 'ACTIVE' ORDER BY kind",
                (rs, i) -> "LIEN".equals(rs.getString(1)) ? "a lien of " + DepositRuleSets.plain(rs.getBigDecimal(2))
                        : "a " + rs.getString(1).toLowerCase(Locale.ROOT).replace('_', ' '), id);
    }

    private static boolean frozenFor(JdbcTemplate jdbc, UUID id, String direction) {
        return !jdbc.queryForList("""
                SELECT 1 FROM deposits.account_hold WHERE account_id = ? AND status = 'ACTIVE'
                   AND kind IN ('TOTAL_FREEZE', CASE ? WHEN 'DEBIT' THEN 'DEBIT_FREEZE' ELSE 'CREDIT_FREEZE' END)
                """, id, direction).isEmpty();
    }

    /** The first working day on or after a date. */
    static LocalDate firstWorkingDay(JdbcTemplate jdbc, LocalDate d) {
        return jdbc.queryForObject("SELECT platform.next_working_day(?::date - 1)", LocalDate.class, d.toString());
    }

    /** The overdue rate for a matured deposit left unpaid, or null when the rule set pays none. */
    static BigDecimal overdueRate(JdbcTemplate jdbc, DepositRules rules) {
        if (!rules.flag("td.overdue-interest", false)) return null;
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = 'deposits.overdue-rate-percent'", String.class);
        return v.isEmpty() ? BigDecimal.ZERO : new BigDecimal(v.get(0).trim());
    }

    static int noticeDays(JdbcTemplate jdbc, DepositRules rules) {
        Optional<Integer> rule = rules.whole(DepositRules.TD_MATURITY_NOTICE_DAYS);
        if (rule.isPresent()) return rule.get();
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = 'deposits.maturity-notice-days'", String.class);
        return v.isEmpty() ? 14 : Integer.parseInt(v.get(0).trim());
    }

    // ================================================================================================ charges

    /** The states for GST: the branch's (supplier) and the depositor's communication address (place of supply). */
    static String[] gstStates(JdbcTemplate jdbc, UUID accountId) {
        Map<String, Object> s = jdbc.queryForMap("""
                SELECT platform.gst_state_code(b.state_code) AS supplier,
                       coalesce(platform.gst_state_code(ad.state_code), platform.gst_state_code(b.state_code)) AS recipient
                  FROM deposits.account a JOIN platform.branch b ON b.code = a.branch_code
                  LEFT JOIN customer.address ad ON ad.customer_id = a.customer_id AND ad.address_type = 'COMMUNICATION'
                 WHERE a.id = ?
                """, accountId);
        return new String[] {(String) s.get("supplier"), (String) s.get("recipient")};
    }

    static DepositCharges.Schedule schedule(JdbcTemplate jdbc, Json json, UUID accountId) {
        String code = jdbc.queryForObject("SELECT product_code FROM deposits.account WHERE id = ?", String.class, accountId);
        DepositProductService.Product p = DepositProductService.load(jdbc, json, code);
        return DepositProductService.schedule(p == null ? null : p.charges());
    }

    /** A charge of {@code taxable} with GST at the schedule's rate, by place of supply. */
    static DepositCharges.Charge charge(JdbcTemplate jdbc, DepositCharges.Schedule s, UUID accountId, String code, String description, BigDecimal taxable) {
        String[] st = gstStates(jdbc, accountId);
        return DepositCharges.charge(code, description, taxable, s.gstRatePercent(), st[0], st[1]);
    }

    /** What an account can pay a charge from: its balance less liens, nothing when it is frozen for debits. */
    static BigDecimal available(JdbcTemplate jdbc, UUID id) {
        if (frozenFor(jdbc, id, "DEBIT")) return BigDecimal.ZERO;
        BigDecimal liens = jdbc.queryForObject("SELECT coalesce(sum(amount), 0)::numeric FROM deposits.account_hold WHERE account_id = ? AND status = 'ACTIVE' AND kind = 'LIEN'",
                BigDecimal.class, id);
        return balance(jdbc, id).subtract(liens).max(BigDecimal.ZERO);
    }

    /** Posts a charge: the lot, the CHARGE movement and the charge with its tax invoice. Returns the charge id. */
    static UUID levy(DepositBooks b, UUID accountId, Leg leg, DepositCharges.Charge c, String reason) {
        TransactionLot lot = b.post().charge(leg, chargeHeads(b.jdbc()), c, leg.accountNo());
        b.post(lot);
        UUID m = b.movement(accountId, "CHARGE", "DEBIT", c.total(), lot.id(), null, c.description(), null);
        UUID id = UUID.randomUUID();
        b.jdbc().update("""
                INSERT INTO deposits.charge (id, account_id, charge_code, description, business_date, taxable_value, gst_rate, cgst, sgst, igst,
                                             branch_code, supplier_state, place_of_supply, invoice_no, movement_id, lot_id, reason, approval_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, platform.next_number('GST_INVOICE'), ?, ?, ?, ?, ?)
                """, id, accountId, c.code(), c.description(), b.today(), c.taxable(), c.gstRatePercent(), c.tax().cgst(), c.tax().sgst(), c.tax().igst(),
                leg.branch(), c.supplierState(), c.placeOfSupply(), m, lot.id(), reason, b.approvalId(), b.user());
        return id;
    }

    static com.corebanking.deposits.DepositPostings.ChargeHeads chargeHeads(JdbcTemplate jdbc) {
        com.corebanking.deposits.DepositPostings.ChargeHeads d = com.corebanking.deposits.DepositPostings.ChargeHeads.starter();
        Map<String, String> p = new LinkedHashMap<>();
        jdbc.query("SELECT key, value FROM platform.system_property WHERE key IN ('deposits.gl.charge-income','deposits.gl.cgst','deposits.gl.sgst','deposits.gl.igst')",
                rs -> { p.put(rs.getString(1), rs.getString(2).trim()); });
        return new com.corebanking.deposits.DepositPostings.ChargeHeads(p.getOrDefault("deposits.gl.charge-income", d.income()),
                p.getOrDefault("deposits.gl.cgst", d.cgst()), p.getOrDefault("deposits.gl.sgst", d.sgst()), p.getOrDefault("deposits.gl.igst", d.igst()));
    }

    /**
     * The month-ends of a savings or current account not yet evaluated, up to {@code through}: the average balance
     * against the minimum; a short month after a notified short month is charged (in proportion to the shortfall,
     * never below zero).
     */
    static void minimumBalance(DepositBooks b, Json json, UUID id, LocalDate through) {
        JdbcTemplate jdbc = b.jdbc();
        Map<String, Object> a = jdbc.queryForMap("""
                SELECT a.account_no, a.branch_code, a.opened_on, a.status, p.gl_liability, p.min_balance::numeric AS min_balance, c.min_balance_through,
                       c.inoperative_since
                  FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code JOIN deposits.casa_account c ON c.account_id = a.id
                 WHERE a.id = ? FOR UPDATE OF a
                """, id);
        BigDecimal minimum = (BigDecimal) a.get("min_balance");
        boolean inoperative = a.get("inoperative_since") != null;      // P4-6: no charge for want of balance on an inoperative account
        if (!"ACTIVE".equals(a.get("status")) || minimum.signum() == 0) return;
        LocalDate opened = DepositEod.date(a.get("opened_on"));
        LocalDate done = DepositEod.date(a.get("min_balance_through"));
        LocalDate month = (done == null ? opened : done.plusDays(1)).withDayOfMonth(1);
        DepositCharges.Schedule s = schedule(jdbc, json, id);
        Leg leg = new Leg((String) a.get("branch_code"), (String) a.get("gl_liability"), (String) a.get("account_no"));
        LocalDate last = done;
        for (; !month.plusMonths(1).minusDays(1).isAfter(through); month = month.plusMonths(1)) {
            LocalDate from = month.isBefore(opened) ? opened : month;
            LocalDate to = month.plusMonths(1).minusDays(1);
            BigDecimal average = DepositCharges.averageBalance(from, to, DepositEod.closingBalances(jdbc, id, from));
            BigDecimal shortfall = DepositCharges.shortfall(minimum, average);
            boolean previous = !jdbc.queryForList("SELECT 1 FROM deposits.min_balance_month WHERE account_id = ? AND month = ? AND notified",
                    id, month.minusMonths(1)).isEmpty();
            UUID chargeId = null;
            String note = null;
            if (shortfall.signum() > 0) {
                Optional<BigDecimal> amount = DepositCharges.minimumBalanceCharge(s, shortfall);
                if (inoperative) {
                    note = "short; the account is inoperative: no minimum balance charge";
                } else if (amount.isEmpty()) {
                    note = "short; the product has no minimum balance charge";
                } else if (!previous) {
                    note = "short; notified, not charged (the depositor has a month to restore the balance)";
                } else {
                    String what = "Minimum balance charge for " + MONTH.format(month);
                    Optional<DepositCharges.Charge> c = DepositCharges.fitTo(charge(jdbc, s, id, DepositCharges.MIN_BALANCE, what, amount.get()), available(jdbc, id));
                    if (c.isEmpty()) {
                        note = "short again; nothing could be charged (no free balance, or frozen for debits)";
                    } else {
                        chargeId = levy(b, id, leg, c.get(), "average " + DepositRuleSets.plain(average) + " against " + DepositRuleSets.plain(minimum));
                        note = "short again; charged " + DepositRuleSets.plain(c.get().total())
                                + (c.get().taxable().compareTo(amount.get()) < 0 ? " (cut to the free balance)" : "");
                    }
                }
            }
            jdbc.update("""
                    INSERT INTO deposits.min_balance_month (account_id, month, from_date, to_date, required, average, shortfall, notified, charge_id, note)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, month, from, to, minimum, average, shortfall, shortfall.signum() > 0 && !inoperative, chargeId, note);
            last = to;
        }
        if (last != null && !last.equals(done)) jdbc.update("UPDATE deposits.casa_account SET min_balance_through = ? WHERE account_id = ?", last, id);
    }

    // ================================================================================================ paying out

    /** Why proceeds cannot go where they are sent; empty when they can. {@code depositor}: the receiving account must be held by them. */
    static List<String> payoutProblems(JdbcTemplate jdbc, UUID closing, String family, Payout p, UUID depositor, Map<String, Object> td) {
        List<String> out = new ArrayList<>();
        if (p == null || p.to() == null) {
            out.add("say where the proceeds go: ACCOUNT, CASH or BANK_TRANSFER" + (td == null ? "" : ", or GATEWAY to the bank account recorded"));
            return out;
        }
        switch (p.to()) {
            case ACCOUNT -> {
                if (p.accountId() == null) {
                    out.add("name the savings or current account the proceeds go to");
                    break;
                }
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT a.account_no, a.family, a.status,
                               EXISTS (SELECT 1 FROM deposits.account_holder h WHERE h.account_id = a.id AND h.customer_id = ?) AS held
                          FROM deposits.account a WHERE a.id = ?
                        """, depositor, p.accountId());
                if (rows.isEmpty()) {
                    out.add("the account the proceeds go to does not exist");
                } else {
                    Map<String, Object> r = rows.get(0);
                    if (p.accountId().equals(closing)) out.add("an account cannot pay its proceeds to itself");
                    else if (!"CASA".equals(r.get("family")) || !"ACTIVE".equals(r.get("status"))) out.add("account " + r.get("account_no") + " is not an active savings or current account");
                    else if (frozenFor(jdbc, p.accountId(), "CREDIT")) out.add("account " + r.get("account_no") + " is frozen for credits");
                    else if (depositor != null && !Boolean.TRUE.equals(r.get("held"))) out.add("account " + r.get("account_no") + " is not held by the depositor");
                }
            }
            case CASH -> { }
            case BANK_TRANSFER -> {
                if (p.reference() == null || p.reference().isBlank()) out.add("the bank's reference (UTR) is required for a bank transfer");
            }
            case GATEWAY -> {
                if (td == null) out.add("a savings or current account is not closed through the payment gateway; pay it to an account, in cash or by a bank transfer");
                else if (td.get("payout_bank_ifsc") == null) out.add("no bank account is recorded on the deposit; it is paid through the gateway only to that account");
            }
        }
        if (p.reference() != null && p.reference().length() > 60) out.add("the reference is longer than 60 characters");
        return out;
    }

    /** Pays the whole balance of an account where it is sent. Returns the movement on the closing account, or null when nothing was left. */
    static UUID payOut(DepositBooks b, Outbox outbox, UUID id, Leg leg, Payout p, Map<String, Object> td, String narration) {
        JdbcTemplate jdbc = b.jdbc();
        BigDecimal amount = balance(jdbc, id);
        if (amount.signum() == 0) return null;
        String ref = p.reference() == null || p.reference().isBlank() ? leg.accountNo() : p.reference().trim();
        if (p.to() == PayTo.GATEWAY) {
            TransactionLot lot = b.post().closurePayout(leg, amount, PayTo.GATEWAY, null, ref, narration);
            return DepositEod.sendThroughGateway(b, outbox, id, td, leg, lot, amount, narration);
        }
        Leg to = null;
        if (p.to() == PayTo.ACCOUNT) {
            Map<String, Object> r = jdbc.queryForMap("""
                    SELECT a.account_no, a.branch_code, p.gl_liability FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code WHERE a.id = ?
                    """, p.accountId());
            to = new Leg((String) r.get("branch_code"), (String) r.get("gl_liability"), (String) r.get("account_no"));
        }
        TransactionLot lot = b.post().closurePayout(leg, amount, p.to(), to, ref, narration);
        b.post(lot);
        UUID m = b.movement(id, "CLOSURE", "DEBIT", amount, lot.id(), p.to() == PayTo.ACCOUNT ? p.accountId() : null,
                narration + (to == null ? "" : " to " + to.accountNo()), p.to() == PayTo.ACCOUNT ? null : p.reference());
        if (to != null) b.movement(p.accountId(), "TRANSFER_IN", "CREDIT", amount, lot.id(), id, "Proceeds of " + leg.accountNo(), null);
        return m;
    }

    /** The closure's record. */
    record ClosureRecord(String kind, String reason, BigDecimal principal, BigDecimal rate, BigDecimal interestDue, BigDecimal interestCredited,
                  BigDecimal interestRecovered, BigDecimal overdue, BigDecimal charges, BigDecimal tax, BigDecimal paid, Payout payout, UUID payMovement,
                  UUID renewedTo, String basis, String note) {}

    static void record(DepositBooks b, UUID id, ClosureRecord r) {
        String payTo = r.renewedTo() != null ? "RENEWAL" : r.paid().signum() > 0 && r.payout() != null ? r.payout().to().name() : null;
        b.jdbc().update("""
                INSERT INTO deposits.closure (account_id, kind, reason, business_date, principal, rate_percent, interest_due, interest_credited,
                    interest_recovered, overdue_interest, charges, tax, paid_amount, pay_to, pay_account_id, pay_reference, pay_movement_id, renewed_to,
                    basis, note, approval_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, r.kind(), r.reason(), b.today(), r.principal(), r.rate(), r.interestDue(), r.interestCredited(), r.interestRecovered(),
                r.overdue(), r.charges(), r.tax(), r.paid(), payTo, "ACCOUNT".equals(payTo) ? r.payout().accountId() : null,
                payTo != null && r.payout() != null && r.renewedTo() == null ? cut(r.payout().reference(), 60) : null, r.payMovement(), r.renewedTo(),
                cut(r.basis(), 500), cut(r.note(), 500), b.approvalId(), b.user());
    }

    private static void markClosed(DepositBooks b, UUID id, String reason) {
        b.jdbc().update("UPDATE deposits.account SET status = 'CLOSED', closed_by = ?, close_reason = ?, close_approval_id = ? WHERE id = ?",
                b.user(), cut(reason, 500), b.approvalId(), id);
    }

    private static String cut(String v, int max) {
        return v == null ? null : v.length() > max ? v.substring(0, max) : v;
    }

    /** The tax deducted from an account on this business date (by the closure just made). */
    private static BigDecimal taxToday(JdbcTemplate jdbc, UUID id, LocalDate today) {
        return jdbc.queryForObject("SELECT coalesce(sum(tax), 0)::numeric FROM deposits.tds_ledger WHERE account_id = ? AND business_date = ?",
                BigDecimal.class, id, today);
    }

    // ================================================================================================ savings and current accounts

    /** What closing a savings or current account today pays, and every reason it cannot be closed. */
    record CasaQuote(BigDecimal balance, BigDecimal interest, DepositCharges.Charge charge, BigDecimal payable, List<String> problems) {}

    static CasaQuote casaQuote(JdbcTemplate jdbc, Json json, UUID id, LocalDate today) {
        Map<String, Object> a = jdbc.queryForMap("""
                SELECT a.account_no, a.status, a.opened_on, p.interest_bands IS NOT NULL AS interest FROM deposits.account a
                  JOIN deposits.product p ON p.code = a.product_code WHERE a.id = ?
                """, id);
        List<String> problems = new ArrayList<>();
        if (!"ACTIVE".equals(a.get("status"))) problems.add("account " + a.get("account_no") + " is " + a.get("status"));
        for (String h : holds(jdbc, id)) problems.add("account " + a.get("account_no") + " has " + h + "; release it before the account is closed");
        String deposits = jdbc.queryForObject("""
                SELECT string_agg(x.account_no, ', ' ORDER BY x.account_no) FROM deposits.term_deposit t JOIN deposits.account x ON x.id = t.account_id
                 WHERE t.payout_account_id = ? AND x.status IN ('BOOKED','ACTIVE','MATURED')
                """, String.class, id);
        if (deposits != null) problems.add("deposit " + deposits + " pays out to this account; change the deposit's payout first");
        if (!jdbc.queryForList("""
                SELECT 1 FROM deposits.gateway_transfer g JOIN deposits.movement m ON m.id = g.movement_id WHERE m.account_id = ? AND g.status = 'SENT'
                """, id).isEmpty()) {
            problems.add("a transfer sent through the payment gateway has not been paid yet");
        }
        BigDecimal balance = balance(jdbc, id);
        BigDecimal interest = BigDecimal.ZERO;
        if (Boolean.TRUE.equals(a.get("interest"))) {
            DepositEod.SavingsContext c = DepositEod.savingsContext(jdbc, id);
            if (c.state() != null && !c.state().accruedThrough().isAfter(today.minusDays(1))) {
                InterestDayEnd.SavingsResult r = InterestDayEnd.savingsToClose(c.interest(), c.state(), today, c.balanceAt(), c.postingMonths(),
                        DepositEod.yearStartMonth(jdbc), c.rounding());
                for (InterestDayEnd.Event e : r.events()) if (e instanceof InterestDayEnd.Credit cr) interest = interest.add(cr.amount());
            }
        }
        DepositCharges.Schedule s = schedule(jdbc, json, id);
        BigDecimal before = balance.add(interest);
        DepositCharges.Charge charge = DepositCharges.closureCharge(s, DepositEod.date(a.get("opened_on")), today)
                .map(x -> charge(jdbc, s, id, DepositCharges.CLOSURE, "Account closure charge", x))
                .flatMap(c -> DepositCharges.fitTo(c, before)).orElse(null);
        BigDecimal payable = balance.add(interest).subtract(charge == null ? BigDecimal.ZERO : charge.total());
        return new CasaQuote(balance, interest, charge, payable, problems);
    }

    /** Closes a savings or current account: interest to yesterday, the closure charge, the rest paid out, the account CLOSED. */
    static String closeCasa(DepositBooks b, DepositRules rules, Json json, Outbox outbox, UUID id, Payout p, String reason) {
        JdbcTemplate jdbc = b.jdbc();
        Map<String, Object> a = jdbc.queryForMap("""
                SELECT a.account_no, a.branch_code, a.customer_id, p.gl_liability, p.interest_bands IS NOT NULL AS interest FROM deposits.account a
                  JOIN deposits.product p ON p.code = a.product_code WHERE a.id = ? FOR UPDATE OF a
                """, id);
        CasaQuote q = casaQuote(jdbc, json, id, b.today());
        List<String> problems = new ArrayList<>(q.problems());
        if (q.payable().signum() > 0) problems.addAll(payoutProblems(jdbc, id, "CASA", p, null, null));
        if (!problems.isEmpty()) throw new DepositRefusal(problems);
        Leg leg = new Leg((String) a.get("branch_code"), (String) a.get("gl_liability"), (String) a.get("account_no"));
        BigDecimal interest = BigDecimal.ZERO;
        if (Boolean.TRUE.equals(a.get("interest"))) {
            DepositEod.SavingsContext c = DepositEod.savingsContext(jdbc, id);
            if (c.state() != null) {
                int yearStart = DepositEod.yearStartMonth(jdbc);
                InterestDayEnd.SavingsResult r = InterestDayEnd.savingsToClose(c.interest(), c.state(), b.today(), c.balanceAt(), c.postingMonths(), yearStart,
                        c.rounding());
                for (InterestDayEnd.Event e : r.events()) if (e instanceof InterestDayEnd.Credit cr) interest = interest.add(cr.amount());
                DepositEod.postSavings(b, rules, c, id, r, yearStart, false);
            }
        }
        BigDecimal charges = BigDecimal.ZERO;
        if (q.charge() != null) {
            DepositCharges.Charge c = DepositCharges.fitTo(q.charge(), balance(jdbc, id)).orElse(null);
            if (c != null) {
                levy(b, id, leg, c, "closure of the account");
                charges = c.total();
            }
        }
        BigDecimal paid = balance(jdbc, id);
        UUID m = payOut(b, outbox, id, leg, p, null, "Closure of " + leg.accountNo());
        markClosed(b, id, reason);
        record(b, id, new ClosureRecord("CASA", "DEPOSITOR_REQUEST", null, null, interest, interest, BigDecimal.ZERO, BigDecimal.ZERO, charges,
                taxToday(jdbc, id, b.today()), paid, paid.signum() > 0 ? p : null, m, null, null, reason));
        return leg.accountNo();
    }

    // ================================================================================================ term deposits

    /**
     * What closing a term deposit today pays, and every reason it cannot be closed. Before maturity it is a premature
     * closure under the rule set ({@link PrematureWithdrawal}); at or after maturity the proceeds, with interest for the
     * days it waited ({@link Maturity#overdue}).
     */
    record DepositQuote(String kind, String reason, List<String> problems, String basis, BigDecimal principal, BigDecimal balance,
                        BigDecimal ratePercent, BigDecimal interestDue, BigDecimal interestCredited, BigDecimal interestAccrued, BigDecimal toCredit,
                        BigDecimal toRecover, BigDecimal overdueInterest, String overdueBasis, BigDecimal payableBeforeTax, LocalDate maturityDate,
                        LocalDate on) {}

    static DepositQuote depositQuote(JdbcTemplate jdbc, DepositRules rules, UUID id, String reason, LocalDate today) {
        Deposit d = deposit(jdbc, id, false);
        List<String> problems = new ArrayList<>();
        if (d == null) throw new DepositRefusal(List.of("term deposit " + id + " not found"));
        if (!d.funded() || !Set.of("ACTIVE", "MATURED").contains(d.status())) {
            problems.add("deposit " + d.accountNo() + " is " + d.status() + "; only a funded or matured deposit is closed");
            return new DepositQuote(null, reason, problems, null, d.principal(), BigDecimal.ZERO, null, null, null, null, null, null, null, null, null,
                    d.maturity(), today);
        }
        for (String h : holds(jdbc, id)) problems.add("deposit " + d.accountNo() + " has " + h + "; release it before the deposit is closed");
        Interest st = interest(jdbc, id);
        BigDecimal balance = balance(jdbc, id);
        if (today.isBefore(d.maturity())) {
            String why = reason == null ? "DEPOSITOR_REQUEST" : reason;
            if (!REASONS.contains(why)) {
                problems.add("reason is one of " + String.join(", ", new java.util.TreeSet<>(REASONS)));
                why = "DEPOSITOR_REQUEST";
            }
            PrematureWithdrawal.Reason r = PrematureWithdrawal.Reason.valueOf(why);
            boolean nbfc = rules.number(DepositRules.TD_PREMATURE_RATE_CUT).isPresent();
            if (!nbfc && !d.prematureAllowed() && r != PrematureWithdrawal.Reason.DEATH) {
                problems.add("product " + d.productCode() + " does not allow premature withdrawal (except on death)");
            }
            PrematureWithdrawal.Quote q = d.rd()
                    ? PrematureWithdrawal.quote(rules, premature(jdbc, d, r, today), rate -> RecurringDeposits.interestFor(jdbc, d, rate, today))
                    : PrematureWithdrawal.quote(rules, premature(jdbc, d, r, today));
            if (!q.allowed()) {
                problems.add(q.refusal());
                return new DepositQuote("PREMATURE", why, problems, q.basis(), d.principal(), balance, null, null, st.credited(), st.accrued(), null, null,
                        null, null, null, d.maturity(), today);
            }
            if (q.principalRemaining().signum() > 0) {
                problems.add("only " + DepositRuleSets.plain(q.principalPayable()) + " of the principal may be repaid now (" + q.basis()
                        + "); a part withdrawal that leaves the rest on deposit is not built yet");
            }
            BigDecimal due = q.interestDue();
            BigDecimal toCredit = due.subtract(st.credited()).max(BigDecimal.ZERO);
            BigDecimal toRecover = st.credited().subtract(due).max(BigDecimal.ZERO);
            return new DepositQuote("PREMATURE", why, problems, q.basis(), d.principal(), balance, q.ratePercent(), due, st.credited(), st.accrued(),
                    toCredit, toRecover, BigDecimal.ZERO, null, balance.add(toCredit).subtract(toRecover), d.maturity(), today);
        }
        int periods = d.creditsDue();
        if (st.periodsCredited() < periods) {
            problems.add("the last interest of deposit " + d.accountNo() + " is credited by the day-end of " + d.maturity() + "; close it after that day-end");
        }
        Maturity.Overdue o = Maturity.overdue(balance, d.rate(), d.maturity(), today, firstWorkingDay(jdbc, d.maturity()), overdueRate(jdbc, rules), d.rounding());
        return new DepositQuote("MATURITY", "MATURITY", problems, "matured on " + d.maturity(), d.principal(), balance, d.rate(), st.booked(), st.credited(),
                st.accrued(), BigDecimal.ZERO, BigDecimal.ZERO, o.interest(), o.basis(), balance.add(o.interest()), d.maturity(), today);
    }

    /** The engine's request for a premature closure of {@code d} on {@code on}. */
    private static PrematureWithdrawal.Request premature(JdbcTemplate jdbc, Deposit d, PrematureWithdrawal.Reason reason, LocalDate on) {
        Map<String, Object> c = jdbc.queryForMap("SELECT customer_type FROM customer.customer WHERE id = ?", d.customerId());
        BigDecimal total = jdbc.queryForObject("""
                SELECT coalesce(sum(b.balance), 0)::numeric FROM deposits.account a JOIN deposits.account_balance b ON b.account_id = a.id
                 WHERE a.customer_id = ? AND a.family = 'TERM_DEPOSIT' AND a.status IN ('ACTIVE','MATURED')
                """, BigDecimal.class, d.customerId());
        // the rates the institution offered when this deposit was booked: the product version it was booked on
        List<DepositRateTable.Cell> cells = jdbc.query("""
                SELECT (c->>'fromAmount')::numeric, (c->>'fromMonths')::int, (c->>'fromDays')::int, (c->>'ratePercent')::numeric
                  FROM deposits.product_history h, jsonb_array_elements(h.snapshot->'rate_card') c WHERE h.product_code = ? AND h.version = ?
                """, (rs, i) -> new DepositRateTable.Cell(rs.getBigDecimal(1), new Tenure(rs.getInt(2), rs.getInt(3)), rs.getBigDecimal(4)),
                d.productCode(), d.productVersion());
        BigDecimal addOns = jdbc.queryForObject("""
                SELECT coalesce(sum((x->>'percent')::numeric), 0) FROM deposits.term_deposit t, jsonb_array_elements(t.add_ons) x WHERE t.account_id = ?
                """, BigDecimal.class, d.id());
        BigDecimal rateRun = null;
        BigDecimal minimum = null;
        if (!cells.isEmpty()) {
            DepositRateTable card = new DepositRateTable(cells, List.of());
            minimum = card.minimumRate();
            try {
                rateRun = card.resolve(d.principal(), d.start(), on, Set.of()).cardRatePercent().add(addOns);
            } catch (IllegalArgumentException none) {
                rateRun = null;               // the deposit ran for less than the shortest tenure the card had
            }
        }
        BigDecimal paidOut = jdbc.queryForObject("""
                SELECT coalesce(sum(m.amount), 0)::numeric FROM deposits.movement m
                 WHERE m.account_id = ? AND m.reversed_by IS NULL
                   AND (m.kind = 'INTEREST_PAYOUT' OR (m.kind = 'BANK_TRANSFER_OUT' AND EXISTS (SELECT 1 FROM deposits.gateway_transfer g WHERE g.movement_id = m.id)))
                """, BigDecimal.class, d.id());
        TermDeposit.Terms terms = d.rd()
                ? new TermDeposit.Terms(RecurringDeposits.paidIn(RecurringDeposits.paid(jdbc, d.id())).max(d.principal()), d.rate(), d.start(), d.maturity(),
                        d.periodMonths(), true, d.dayCount(), d.rounding())
                : d.terms();
        return new PrematureWithdrawal.Request(terms, on, reason, "INDIVIDUAL".equals(c.get("customer_type")), total, rateRun,
                minimum == null ? d.rate() : minimum, d.penalty() == null ? BigDecimal.ZERO : d.penalty(), d.minDays() == null ? 0 : d.minDays(),
                true, paidOut);
    }

    /**
     * Closes a term deposit: before maturity at the rate the rule set allows (interest credited above it is recovered,
     * interest due beyond it credited, with tax), at or after maturity with interest for the days it waited; then the
     * whole balance is paid out and the deposit CLOSED.
     */
    static String closeDeposit(DepositBooks b, DepositRules rules, Outbox outbox, UUID id, String reason, Payout p, String note) {
        JdbcTemplate jdbc = b.jdbc();
        Deposit d = deposit(jdbc, id, true);
        DepositQuote q = depositQuote(jdbc, rules, id, reason, b.today());
        List<String> problems = new ArrayList<>(q.problems());
        if (problems.isEmpty() && q.payableBeforeTax().signum() > 0) problems.addAll(payoutProblems(jdbc, id, "TERM_DEPOSIT", p, "DEATH".equals(q.reason())
                ? null : d.customerId(), d.row()));
        if (!problems.isEmpty()) throw new DepositRefusal(problems);
        Leg leg = d.leg();
        String payable = DepositEod.payableGl(jdbc);
        BigDecimal credited = BigDecimal.ZERO;
        BigDecimal recovered = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        if ("PREMATURE".equals(q.kind())) {
            Interest st = interest(jdbc, id);
            if (st.accrued().signum() != 0) {
                TransactionLot back = b.post().interestAccrual(d.branch(), d.expenseGl(), payable, d.accountNo(), st.accrued().negate(), d.accountNo());
                b.post(back);
                b.event(id, "ACCRUAL", null, b.today().minusDays(1), null, st.accrued().negate(), back.id(), null, "accrual taken back: premature closure");
            }
            if (q.toCredit().signum() > 0) {
                credited = q.toCredit();
                creditInterest(b, rules, d, "CREDIT", credited, d.start(), b.today(), "Interest on premature closure (" + q.basis() + ")");
            }
            if (q.toRecover().signum() > 0) {
                recovered = q.toRecover();
                TransactionLot lot = b.post().interestRecovery(leg, d.expenseGl(), recovered, d.accountNo());
                b.post(lot);
                UUID m = b.movement(id, "INTEREST_RECOVERY", "DEBIT", recovered, lot.id(), null, "Interest recovered: " + q.basis(), null);
                b.event(id, "RECOVERY", d.start(), b.today(), null, recovered, lot.id(), m, q.basis());
            }
            jdbc.update("""
                    UPDATE deposits.interest_state SET accrued = 0, booked = ?, credited = ?, accrued_through = ?, updated_at = now() WHERE account_id = ?
                    """, q.interestDue(), q.interestDue(), b.today().minusDays(1), id);
        } else if (q.overdueInterest().signum() > 0) {
            overdue = q.overdueInterest();
            creditInterest(b, rules, d, "OVERDUE", overdue, d.maturity(), b.today(), "Interest after maturity: " + q.overdueBasis());
        }
        BigDecimal paid = balance(jdbc, id);
        UUID m = payOut(b, outbox, id, leg, p, d.row(), (q.kind().equals("PREMATURE") ? "Premature closure of " : "Maturity of ") + d.accountNo());
        markClosed(b, id, note == null ? q.basis() : note);
        record(b, id, new ClosureRecord(q.kind(), q.reason(), d.principal(), q.ratePercent(), q.interestDue() == null ? BigDecimal.ZERO : q.interestDue().add(overdue),
                credited.add(overdue), recovered, overdue, BigDecimal.ZERO, taxToday(jdbc, id, b.today()), paid, paid.signum() > 0 ? p : null, m, null,
                q.kind().equals("PREMATURE") ? q.basis() : q.overdueBasis(), note));
        return d.accountNo();
    }

    /** Interest credited to a deposit outside its periods (at closure, or after maturity): accrued, credited, taxed. */
    private static void creditInterest(DepositBooks b, DepositRules rules, Deposit d, String kind, BigDecimal amount, LocalDate from, LocalDate to, String narration) {
        String payable = DepositEod.payableGl(b.jdbc());
        TransactionLot acc = b.post().interestAccrual(d.branch(), d.expenseGl(), payable, d.accountNo(), amount, d.accountNo());
        b.post(acc);
        b.event(d.id(), "ACCRUAL", from, to.minusDays(1), null, amount, acc.id(), null, narration);
        TransactionLot lot = b.post().interestCredit(d.leg(), payable, amount, d.accountNo(), narration);
        b.post(lot);
        UUID m = b.movement(d.id(), "INTEREST_CREDIT", "CREDIT", amount, lot.id(), null, narration, null);
        b.event(d.id(), kind, from, to, null, amount, lot.id(), m, narration);
        if (d.tds()) TaxAtSource.onCredit(b, rules, d.id(), d.customerId(), d.leg(), amount, m, from, to);
        if ("OVERDUE".equals(kind)) {
            b.jdbc().update("UPDATE deposits.interest_state SET booked = booked + ?, credited = credited + ?, updated_at = now() WHERE account_id = ?",
                    amount, amount, d.id());
        }
    }

    // ================================================================================================ maturity at day-end

    /**
     * A deposit that has reached its maturity date, as the day-end processes it: renewed or paid out as instructed;
     * when that cannot be done (a lien, the payout account closed or frozen, no bank account, the renewal refused) it
     * becomes MATURED and waits, with the reason recorded.
     */
    static void mature(DepositBooks b, DepositRules rules, Json json, Outbox outbox, UUID id) {
        JdbcTemplate jdbc = b.jdbc();
        Deposit d = deposit(jdbc, id, true);
        if (d == null || !"ACTIVE".equals(d.status()) || d.maturity().isAfter(b.today())) return;
        if (interest(jdbc, id).periodsCredited() < d.creditsDue()) return;   // the interest step has not credited it yet
        List<String> why = new ArrayList<>();
        if (!"PAY_OUT".equals(d.instruction())) {
            try {
                renew(b, rules, json, outbox, id, d.instruction(), null);
                return;
            } catch (DepositRefusal e) {
                why.add("not renewed: " + String.join("; ", e.problems()));
            }
        } else {
            Payout p = "CASA".equals(d.payoutTo()) ? new Payout(PayTo.ACCOUNT, d.payoutAccountId(), null)
                    : d.bankIfsc() != null ? new Payout(PayTo.GATEWAY, null, null) : null;
            try {
                if (p == null) throw new DepositRefusal(List.of("no bank account is recorded to pay it to"));
                closeDeposit(b, rules, outbox, id, null, p, "paid at maturity");
                return;
            } catch (DepositRefusal e) {
                why.add("not paid: " + String.join("; ", e.problems()));
            }
        }
        jdbc.update("UPDATE deposits.account SET status = 'MATURED' WHERE id = ?", id);
        b.event(id, "PAYOUT_HELD", d.maturity(), d.maturity(), null, balance(jdbc, id), null, null,
                "matured; the proceeds wait to be claimed - " + String.join("; ", why));
    }

    /** The maturity notice of a deposit, once: recorded and announced (the depositor is told by the message templates). */
    static void notice(DepositBooks b, Outbox outbox, UUID id) {
        JdbcTemplate jdbc = b.jdbc();
        Deposit d = deposit(jdbc, id, true);
        if (d == null || !"ACTIVE".equals(d.status()) || !d.funded()) return;
        if (!jdbc.queryForList("SELECT 1 FROM deposits.maturity_notice WHERE account_id = ? AND maturity_date = ?", id, d.maturity()).isEmpty()) return;
        // a recurring deposit: the instalments paid, those still to come on time, and the interest on them
        BigDecimal expected = d.rd() ? RecurringDeposits.maturityValue(d.principal(), d.rate(), d.tenure().months(), d.periodMonths(), d.rounding())
                : TermDeposit.maturityValue(d.terms());
        jdbc.update("INSERT INTO deposits.maturity_notice (account_id, maturity_date, notice_on, instruction, expected_amount) VALUES (?, ?, ?, ?, ?)",
                id, d.maturity(), b.today(), d.instruction(), expected);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("accountId", id.toString());
        e.put("accountNo", d.accountNo());
        e.put("customerId", d.customerId().toString());
        e.put("maturityDate", d.maturity().toString());
        e.put("amount", DepositRuleSets.plain(expected));
        e.put("instruction", d.instruction());
        outbox.publish(jdbc, DepositEvents.MATURITY_NOTICE, d.accountNo(), e);
    }

    // ================================================================================================ renewal

    /**
     * Renews a deposit at or after maturity into a new one on the product's terms today (the same product, tenure,
     * holders, nominees and payout): the whole balance (RENEW_WITH_INTEREST), or the principal with the rest paid out
     * (RENEW_PRINCIPAL). Interest for the days it waited is credited first. Returns the new deposit's number.
     *
     * @param consent a consent just recorded by the request (null: the one on file, if the rule set needs one)
     */
    static String renew(DepositBooks b, DepositRules rules, Json json, Outbox outbox, UUID id, String instruction, UUID consent) {
        JdbcTemplate jdbc = b.jdbc();
        Deposit d = deposit(jdbc, id, true);
        List<String> problems = new ArrayList<>();
        if (!Set.of("RENEW_PRINCIPAL", "RENEW_WITH_INTEREST").contains(instruction)) problems.add("a renewal is RENEW_PRINCIPAL or RENEW_WITH_INTEREST");
        DepositQuote q = depositQuote(jdbc, rules, id, null, b.today());
        if (!"MATURITY".equals(q.kind())) problems.add("deposit " + d.accountNo() + " has not matured; it is renewed at or after maturity");
        problems.addAll(q.problems());
        if (!problems.isEmpty()) throw new DepositRefusal(problems);
        BigDecimal total = q.payableBeforeTax();
        BigDecimal amount = "RENEW_WITH_INTEREST".equals(instruction) ? total : d.principal().min(total);
        BigDecimal rest = total.subtract(amount);

        // everything that could stop it, before anything is written
        DepositProductService.Product product = DepositProductService.load(jdbc, json, d.productCode());
        if (product == null || !"ACTIVE".equals(product.status())) problems.add("product " + d.productCode() + " takes no new deposit");
        if (product != null && !Boolean.TRUE.equals(product.autoRenewalAllowed())) problems.add("product " + d.productCode() + " does not allow renewal");
        UUID consentId = consent;
        if (rules.flag(DepositRules.TD_RENEWAL_NEEDS_CONSENT, false) && consentId == null) {
            List<UUID> c = jdbc.queryForList("SELECT id FROM deposits.renewal_consent WHERE account_id = ? AND status = 'ACTIVE' AND instruction = ?",
                    UUID.class, id, instruction);
            if (c.isEmpty()) problems.add("a renewal needs the depositor's consent, recorded for this deposit");
            else consentId = c.get(0);
        }
        List<Map<String, Object>> holders = jdbc.queryForList("""
                SELECT h.holder_no, c.id, c.customer_no, c.customer_type, c.date_of_birth, c.pan_hash IS NOT NULL AS pan, c.status, c.kyc_status
                  FROM deposits.account_holder h JOIN customer.customer c ON c.id = h.customer_id WHERE h.account_id = ? ORDER BY h.holder_no
                """, id);
        for (Map<String, Object> h : holders) {
            if (!"ACTIVE".equals(h.get("status"))) problems.add("customer " + h.get("customer_no") + " is " + h.get("status"));
            if (!"VERIFIED".equals(h.get("kyc_status"))) problems.add("the KYC of customer " + h.get("customer_no") + " is " + h.get("kyc_status"));
        }
        if ("CASA".equals(d.payoutTo())) {
            List<String> pay = payoutProblems(jdbc, id, "TERM_DEPOSIT", new Payout(PayTo.ACCOUNT, d.payoutAccountId(), null), d.customerId(), d.row());
            if (!pay.isEmpty()) problems.add("the payout account: " + String.join("; ", pay));
        } else if (d.bankIfsc() == null) {
            problems.add("no bank account is recorded for the payout");
        }
        List<Map<String, Object>> nominees = jdbc.queryForList("""
                SELECT name, relationship, share_percent, is_minor, guardian_name FROM deposits.nominee WHERE account_id = ? AND status = 'ACTIVE' ORDER BY nominee_no
                """, id);
        Set<String> addOns = new LinkedHashSet<>(jdbc.queryForList("SELECT x->>'code' FROM deposits.term_deposit t, jsonb_array_elements(t.add_ons) x WHERE t.account_id = ?",
                String.class, id));
        TermDepositBooking.Quote booking = null;
        if (product != null) {
            Map<String, Object> first = holders.get(0);
            addOns.retainAll(product.addOns().stream().map(DepositProductService.AddOn::code).toList());
            TermDepositProduct.Settings settings = DepositProductService.term(product);
            TermDepositBooking.Request request = new TermDepositBooking.Request(amount, d.tenure(), b.today(), addOns, nominees.size(),
                    "INDIVIDUAL".equals(first.get("customer_type")), DepositEod.date(first.get("date_of_birth")), Boolean.TRUE.equals(first.get("pan")));
            problems.addAll(TermDepositBooking.problems(rules, settings, request));
            if (problems.isEmpty()) {
                try {
                    booking = TermDepositBooking.quote(rules, settings, request);
                } catch (DepositRefusal e) {
                    problems.addAll(e.problems());
                }
            }
        }
        if (!problems.isEmpty()) throw new DepositRefusal(problems);

        // interest for the days it waited, then the part not renewed paid out
        BigDecimal overdue = q.overdueInterest();
        if (overdue.signum() > 0) creditInterest(b, rules, d, "OVERDUE", overdue, d.maturity(), b.today(), "Interest after maturity: " + q.overdueBasis());
        BigDecimal balance = balance(jdbc, id);
        amount = "RENEW_WITH_INTEREST".equals(instruction) ? balance : d.principal().min(balance);
        rest = balance.subtract(amount);
        UUID restMovement = null;
        if (rest.signum() > 0) restMovement = payRest(b, outbox, d, rest);

        // the new deposit, as the application books one, funded from the old
        UUID renewed = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deposits.account (id, family, product_code, product_version, customer_id, branch_code, operating_mode, nomination_mode, status,
                                              opened_on, approval_id, opened_by)
                VALUES (?, 'TERM_DEPOSIT', ?, 0, ?, ?, ?, ?, 'BOOKED', ?, ?, ?)
                """, renewed, d.productCode(), d.customerId(), d.branch(), d.row().get("operating_mode"), d.row().get("nomination_mode"), b.today(),
                b.approvalId(), b.user());
        for (Map<String, Object> h : holders) {
            jdbc.update("INSERT INTO deposits.account_holder (account_id, holder_no, customer_id) VALUES (?, ?, ?)", renewed, h.get("holder_no"), h.get("id"));
        }
        int n = 0;
        for (Map<String, Object> x : nominees) {
            jdbc.update("""
                    INSERT INTO deposits.nominee (account_id, nominee_no, name, relationship, share_percent, is_minor, guardian_name) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, renewed, ++n, x.get("name"), x.get("relationship"), x.get("share_percent"), x.get("is_minor"), x.get("guardian_name"));
        }
        DepositRateTable.ResolvedRate rate = booking.rate();
        StringBuilder given = new StringBuilder("[");
        for (DepositRateTable.AddOn a : rate.addOns()) {
            if (given.length() > 1) given.append(", ");
            given.append("{\"code\": \"").append(a.code()).append("\", \"percent\": ").append(a.percent().toPlainString()).append('}');
        }
        given.append(']');
        // a deposit-taking NBFC needs fresh consent for the next renewal: the new deposit is paid out unless it is given
        String next = rules.flag(DepositRules.TD_RENEWAL_NEEDS_CONSENT, false) ? "PAY_OUT" : d.instruction();
        jdbc.update("""
                INSERT INTO deposits.term_deposit (account_id, principal, tenure_months, tenure_days, booked_on, rate_percent, card_rate_percent,
                    card_from_amount, card_from_months, card_from_days, add_ons, interest_period_months, cumulative, day_count, rounding,
                    tds_applicable, premature_allowed, premature_penalty_percent, premature_min_days, payout_to, payout_account_id, maturity_instruction)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, renewed, amount, d.tenure().months(), d.tenure().days(), b.today(), rate.ratePercent(), rate.cardRatePercent(), rate.fromAmount(),
                rate.fromTenure().months(), rate.fromTenure().days(), given.toString(), product.interestPeriodMonths(),
                "FD_CUMULATIVE".equals(product.productType()), product.dayCount(), product.rounding(), product.tdsApplicable(), product.prematureAllowed(),
                product.prematurePenaltyPercent() == null ? null : new BigDecimal(product.prematurePenaltyPercent()), product.prematureMinDays(),
                d.payoutTo(), d.payoutAccountId(), next);
        String newNo = jdbc.queryForObject("SELECT account_no FROM deposits.account WHERE id = ?", String.class, renewed);
        Leg to = new Leg(d.branch(), product.glLiability(), newNo);
        TransactionLot lot = b.post().renewal(d.leg(), to, amount);
        b.post(lot);
        UUID out = b.movement(id, "RENEWAL", "DEBIT", amount, lot.id(), renewed, "Renewed as " + newNo, null);
        UUID funding = b.movement(renewed, "FUNDING", "CREDIT", amount, lot.id(), id, "Renewal of " + d.accountNo(), null);
        jdbc.update("""
                UPDATE deposits.term_deposit SET start_date = ?, maturity_date = ?, funding_source = 'RENEWAL', renewed_from = ?, funding_movement_id = ?,
                       funding_reference = ?, payout_bank_name_cipher = ?, payout_bank_account_cipher = ?, payout_bank_account_last4 = ?, payout_bank_ifsc = ?
                 WHERE account_id = ?
                """, b.today(), d.tenure().addTo(b.today()), id, funding, d.accountNo(), d.row().get("payout_bank_name_cipher"),
                d.row().get("payout_bank_account_cipher"), d.row().get("payout_bank_account_last4"), d.bankIfsc(), renewed);
        jdbc.update("UPDATE deposits.account SET status = 'ACTIVE' WHERE id = ?", renewed);
        if (consentId != null) jdbc.update("UPDATE deposits.renewal_consent SET status = 'USED', used_on = ? WHERE id = ?", b.today(), consentId);
        markClosed(b, id, "renewed as " + newNo);
        record(b, id, new ClosureRecord("RENEWAL", "MATURITY", d.principal(), d.rate(), q.interestDue().add(overdue), overdue, BigDecimal.ZERO, overdue,
                BigDecimal.ZERO, taxToday(jdbc, id, b.today()), amount.add(rest), null, restMovement == null ? out : restMovement, renewed,
                q.overdueBasis(), "renewed as " + newNo + " at " + DepositRuleSets.plain(rate.ratePercent()) + "%"
                        + (rest.signum() > 0 ? "; " + DepositRuleSets.plain(rest) + " paid out" : "")));
        return newNo;
    }

    /** The part of a renewing deposit that is not renewed, paid out as its interest is. */
    private static UUID payRest(DepositBooks b, Outbox outbox, Deposit d, BigDecimal rest) {
        JdbcTemplate jdbc = b.jdbc();
        if ("CASA".equals(d.payoutTo())) {
            Map<String, Object> pay = jdbc.queryForMap("""
                    SELECT a.account_no, a.branch_code, p.gl_liability FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code WHERE a.id = ?
                    """, d.payoutAccountId());
            Leg to = new Leg((String) pay.get("branch_code"), (String) pay.get("gl_liability"), (String) pay.get("account_no"));
            TransactionLot lot = b.post().interestPayout(d.leg(), to, rest, d.accountNo());
            b.post(lot);
            UUID out = b.movement(d.id(), "INTEREST_PAYOUT", "DEBIT", rest, lot.id(), d.payoutAccountId(), "Interest paid to " + to.accountNo() + " on renewal", null);
            b.movement(d.payoutAccountId(), "TRANSFER_IN", "CREDIT", rest, lot.id(), d.id(), "Interest on deposit " + d.accountNo(), null);
            return out;
        }
        TransactionLot lot = b.post().gatewayTransferOut(d.leg(), rest, d.accountNo(), "Interest on deposit " + d.accountNo());
        return DepositEod.sendThroughGateway(b, outbox, d.id(), d.row(), d.leg(), lot, rest, "Interest on deposit " + d.accountNo());
    }
}
