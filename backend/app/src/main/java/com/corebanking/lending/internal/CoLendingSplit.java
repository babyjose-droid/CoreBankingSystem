package com.corebanking.lending.internal;

import com.corebanking.ledger.PostingService;
import com.corebanking.ledger.TransactionLot;
import com.corebanking.lending.engine.Appropriation.Component;
import com.corebanking.lending.engine.CoLending;
import com.corebanking.lending.engine.CoLending.FeeReceived;
import com.corebanking.lending.engine.CoLending.Heads;
import com.corebanking.lending.engine.CoLending.Position;
import com.corebanking.lending.engine.CoLending.Realised;
import com.corebanking.lending.engine.CoLending.Sharing;
import com.corebanking.lending.engine.CoLending.Terms;
import com.corebanking.lending.engine.FeeRule;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.lending.engine.LoanPostings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Co-lending (P5-2): the daily split of a loan whose share the partner holds. Static, so the day-end (tenant data
 * source) and request threads use the same code.
 *
 * <p>The split counts from what the borrower had paid when the partner took its share (the baseline, set at
 * acceptance). Each day-end it works out where the partner should stand ({@link CoLending#target}) from the loan's own
 * figures, accrues the servicing fee for each calendar day since the last split, and posts the difference
 * ({@link CoLending#splitLot}). Interest, fees and penal charges are divided as they are received (cash basis): the
 * originator's books carry the whole loan's accrued interest until the borrower pays it (open item P5-03).
 */
final class CoLendingSplit {

    private CoLendingSplit() {}

    static Heads heads(JdbcTemplate jdbc) {
        Heads d = Heads.starter();
        return new Heads(setting(jdbc, "lending.co-lending.partner-share-gl", d.partnerShare()), setting(jdbc, "lending.co-lending.escrow-gl", d.escrow()),
                setting(jdbc, "lending.co-lending.partner-payable-gl", d.partnerPayable()),
                setting(jdbc, "lending.co-lending.servicing-fee-gl", d.servicingFeeIncome()));
    }

    private static String setting(JdbcTemplate jdbc, String key, String fallback) {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = ?", String.class, key);
        return v.isEmpty() || v.get(0).isBlank() ? fallback : v.get(0).trim();
    }

    // -------------------------------------------------------------------------------------------- baseline

    static BigDecimal interestPaid(LoanAccount a) {
        return a.demands().stream().map(LoanAccount.DemandRow::interestPaid).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BigDecimal penalPaid(LoanAccount a) {
        return a.charges().stream().filter(c -> c.kind() == Component.PENAL).map(LoanAccount.ChargeRow::paid).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static Map<String, BigDecimal> feesPaid(LoanAccount a) {
        Map<String, BigDecimal> m = new TreeMap<>();
        for (LoanAccount.ChargeRow c : a.charges()) if (c.kind() == Component.FEE) m.put(c.id(), c.paid());
        return m;
    }

    /** When the partner takes its share: the split counts from what the borrower has paid so far. */
    static void open(JdbcTemplate jdbc, UUID loanId, LoanAccount a, LocalDate today) {
        jdbc.update("""
                INSERT INTO lending.co_lent_position (loan_id, base_interest, base_penal, base_fees, as_of) VALUES (?, ?, ?, ?::jsonb, ?)
                """, loanId, interestPaid(a), penalPaid(a), jsonObject(feesPaid(a)), today);
    }

    static String jsonObject(Map<String, BigDecimal> m) {
        return m.entrySet().stream().map(e -> "\"" + e.getKey().replace("\\", "\\\\").replace("\"", "\\\"") + "\":" + e.getValue().toPlainString())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static Map<String, BigDecimal> jsonMap(JdbcTemplate jdbc, UUID loanId, String column) {
        Map<String, BigDecimal> m = new TreeMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT key, value::numeric AS amount FROM lending.co_lent_position p, jsonb_each_text(p." + column
                + ") WHERE p.loan_id = ?", loanId)) {
            m.put((String) r.get("key"), (BigDecimal) r.get("amount"));
        }
        return m;
    }

    // -------------------------------------------------------------------------------------------- the day's split

    /** Loans to split at a day-end: the partner holds a share and the split is not final. Written-off loans wait for P5-3. */
    static List<String> due(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT p.loan_id::text FROM lending.co_lent_position p JOIN lending.loan_account l ON l.id = p.loan_id
                 WHERE NOT p.final AND l.status <> 'WRITTEN_OFF' ORDER BY l.loan_no
                """, String.class);
    }

    static Terms terms(Map<String, Object> c) {
        return new Terms((BigDecimal) c.get("originator_share"), (BigDecimal) c.get("originator_rate"), (BigDecimal) c.get("partner_rate"),
                (BigDecimal) c.get("servicing_fee"), Sharing.valueOf((String) c.get("fee_sharing")), Sharing.valueOf((String) c.get("penal_sharing")),
                (BigDecimal) c.get("dlg_percent"));
    }

    /**
     * Splits one loan up to {@code through} (the last calendar day the day-end closes). Posts the day's lot, records the
     * split and moves the position. Returns the split's net (0 when nothing moved).
     */
    static BigDecimal split(JdbcTemplate jdbc, LoanStore store, UUID loanId, LocalDate businessDate, LocalDate through, String user) {
        Map<String, Object> c = jdbc.queryForMap("""
                SELECT c.*, p.as_of, p.base_interest, p.base_penal, p.principal AS g_principal, p.interest AS g_interest, p.penal AS g_penal,
                       p.servicing_accrued, p.servicing AS g_servicing
                  FROM lending.co_lent_loan c JOIN lending.co_lent_position p ON p.loan_id = c.loan_id WHERE c.loan_id = ? FOR UPDATE OF p
                """, loanId);
        LoanStore.Loaded l = store.read(jdbc, loanId);
        LoanAccount a = l.account();
        Terms t = terms(c);
        LocalDate asOf = ((java.sql.Date) c.get("as_of")).toLocalDate();

        // servicing fee: each calendar day since the last split, on the partner's principal outstanding tonight
        BigDecimal partnerNow = CoLending.partnerOutstanding(t, a.principalOutstanding());
        BigDecimal accrued = (BigDecimal) c.get("servicing_accrued");
        LocalDate last = asOf;
        for (LocalDate d = asOf.plusDays(1); !d.isAfter(through); d = d.plusDays(1)) {
            accrued = accrued.add(CoLending.servicingForDay(t, partnerNow));
            last = d;
        }

        LoanPostings.GlMap gl = a.params().gl();
        Map<String, FeeRule> rules = a.params().fees().stream().collect(Collectors.toMap(FeeRule::code, f -> f, (x, y) -> x));
        Map<String, BigDecimal> baseFees = jsonMap(jdbc, loanId, "base_fees");
        List<FeeReceived> fees = a.charges().stream().filter(x -> x.kind() == Component.FEE).map(x -> {
            BigDecimal paid = x.paid().subtract(baseFees.getOrDefault(x.id(), BigDecimal.ZERO));
            FeeRule rule = rules.get(x.code());
            BigDecimal gst = rule == null ? BigDecimal.ZERO : rule.gstRatePercent();
            BigDecimal exGst = paid.multiply(new BigDecimal("100")).divide(new BigDecimal("100").add(gst), 2, RoundingMode.HALF_UP);
            String head = rule != null && rule.event() == FeeRule.Event.PRECLOSURE ? gl.foreclosureIncome() : gl.feeIncome();
            return new FeeReceived(head, exGst);
        }).filter(f -> f.amountExGst().signum() != 0).toList();
        Realised r = new Realised((BigDecimal) c.get("partner_principal"), a.principalOutstanding(),
                interestPaid(a).subtract((BigDecimal) c.get("base_interest")), fees, penalPaid(a).subtract((BigDecimal) c.get("base_penal")));
        Position before = new Position((BigDecimal) c.get("g_principal"), (BigDecimal) c.get("g_interest"), jsonMap(jdbc, loanId, "fees"),
                (BigDecimal) c.get("g_penal"), (BigDecimal) c.get("servicing_accrued"), (BigDecimal) c.get("g_servicing"));
        Position after = CoLending.target(t, r, accrued);

        Heads h = heads(jdbc);
        TransactionLot lot = CoLending.splitLot(a.params().branch(), h, l.loanNo(), gl.interestIncome(), gl.penalIncome(), before, after, businessDate);
        BigDecimal net = BigDecimal.ZERO;
        if (lot != null) {
            PostingService.postDuringEod(jdbc, lot, user);
            Position d = after.minus(before);
            jdbc.update("""
                    INSERT INTO lending.co_lending_split (id, loan_id, business_date, principal, interest, fees, penal, servicing, lot_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), loanId, businessDate, d.principal(), d.interest(), d.feesTotal(), d.penal(), d.servicing(), lot.id());
            net = d.net();
        }
        boolean closed = a.status() == LoanAccount.Status.CLOSED || a.status() == LoanAccount.Status.CANCELLED;
        jdbc.update("""
                UPDATE lending.co_lent_position SET as_of = ?, principal = ?, interest = ?, fees = ?::jsonb, penal = ?, servicing_accrued = ?, servicing = ?,
                       final = ? WHERE loan_id = ?
                """, last.isBefore(asOf) ? asOf : last, after.principal(), after.interest(), jsonObject(new HashMap<>(after.fees())), after.penal(),
                after.servicingAccrued(), after.servicing(), closed && partnerNow.signum() == 0, loanId);
        return net;
    }
}
