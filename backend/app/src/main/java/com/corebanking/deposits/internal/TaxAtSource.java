package com.corebanking.deposits.internal;

import com.corebanking.calc.Rounding;
import com.corebanking.deposits.DepositPostings;
import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.deposits.DepositRules;
import com.corebanking.deposits.TdsOnInterest;
import com.corebanking.ledger.PostingService;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tax deducted at source on deposit interest (P4-3b), when the day-end credits interest of a product that deducts tax.
 * The payee is the first holder. The interest of the tax year is added up per payee across all deposits here (the tax
 * ledger), and the engine ({@link TdsOnInterest}) says what to deduct: nothing within the threshold or with Form 121
 * and a PAN, the rate on the whole year's interest once the threshold is passed, the higher rate without a PAN, never
 * more than the interest just credited. Every credit is recorded in the ledger, with the tax (zero or not) and why.
 */
final class TaxAtSource {

    private TaxAtSource() {}

    static String payableGl(JdbcTemplate jdbc) {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = 'deposits.gl.tds-payable'", String.class);
        return v.isEmpty() ? "2205" : v.get(0).trim();
    }

    static Rounding rounding(JdbcTemplate jdbc) {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = 'deposits.tds.rounding'", String.class);
        return v.isEmpty() ? Rounding.RUPEE_HALF_UP : Rounding.valueOf(v.get(0).trim());
    }

    /** What the payee is for the tax: senior, PAN on file, Form 121 for the year of {@code date}. */
    record Payee(TdsOnInterest.Payee payee, int taxYear, UUID declarationId) {}

    static Payee payee(JdbcTemplate jdbc, DepositRules rules, UUID customerId, LocalDate date) {
        Map<String, Object> c = jdbc.queryForMap("SELECT customer_type, date_of_birth, pan_hash IS NOT NULL AS pan FROM customer.customer WHERE id = ?", customerId);
        int taxYear = TdsOnInterest.taxYearStarting(date);
        LocalDate dob = DepositEod.date(c.get("date_of_birth"));
        boolean senior = "INDIVIDUAL".equals(c.get("customer_type")) && dob != null
                && TdsOnInterest.seniorInTaxYear(dob, date, rules.whole(DepositRules.SENIOR_CITIZEN_AGE).orElse(60));
        List<UUID> d = jdbc.queryForList("SELECT id FROM deposits.tds_declaration WHERE customer_id = ? AND tax_year = ? AND status = 'ACTIVE'",
                UUID.class, customerId, taxYear);
        boolean pan = Boolean.TRUE.equals(c.get("pan"));
        return new Payee(new TdsOnInterest.Payee(senior, pan, !d.isEmpty(), false), taxYear, d.isEmpty() ? null : d.get(0));
    }

    static TdsOnInterest.YearToDate yearToDate(JdbcTemplate jdbc, UUID customerId, int taxYear) {
        Map<String, Object> y = jdbc.queryForMap("""
                SELECT coalesce(sum(interest), 0)::numeric AS interest, coalesce(sum(tax), 0)::numeric AS tax FROM deposits.tds_ledger
                 WHERE customer_id = ? AND tax_year = ?
                """, customerId, taxYear);
        return new TdsOnInterest.YearToDate((BigDecimal) y.get("interest"), (BigDecimal) y.get("tax"));
    }

    /**
     * Records a credit of {@code interest} to the account in the tax ledger and deducts what is due from the account.
     *
     * @return the tax deducted (zero when none is)
     */
    static BigDecimal onCredit(JdbcTemplate jdbc, DepositPostings post, DepositRules rules, LocalDate businessDate, UUID accountId, UUID customerId,
                               Leg leg, BigDecimal interest, UUID interestMovement, LocalDate periodFrom, LocalDate periodTo) {
        Payee p = payee(jdbc, rules, customerId, businessDate);
        TdsOnInterest.YearToDate before = yearToDate(jdbc, customerId, p.taxYear());
        BigDecimal tax = TdsOnInterest.deduct(rules, p.payee(), before, interest, rounding(jdbc));
        TdsOnInterest.Basis basis = TdsOnInterest.basis(rules, p.payee(), before, interest);
        BigDecimal rate = switch (basis) {
            case RATE -> rules.required(DepositRules.TDS_RATE);
            case NO_PAN_RATE -> rules.required(DepositRules.TDS_RATE).max(rules.required(DepositRules.TDS_NO_PAN_RATE));
            default -> null;
        };
        UUID lotId = null;
        UUID taxMovement = null;
        if (tax.signum() > 0) {
            String narration = "Tax deducted at source (" + rate.stripTrailingZeros().toPlainString() + "%" + (basis == TdsOnInterest.Basis.NO_PAN_RATE ? ", no PAN" : "")
                    + ") on interest, tax year " + p.taxYear() + "-" + String.valueOf(p.taxYear() + 1).substring(2);
            TransactionLot lot = post.taxDeducted(leg, payableGl(jdbc), tax, leg.accountNo(), narration);
            PostingService.postDuringEod(jdbc, lot, DepositEod.USER);
            lotId = lot.id();
            taxMovement = DepositEod.movement(jdbc, accountId, "TAX_DEDUCTED", "DEBIT", tax, lot.id(), null, narration, null, null);
            DepositEod.event(jdbc, accountId, "TAX", periodFrom, periodTo, null, tax, lot.id(), taxMovement, businessDate, narration);
        }
        jdbc.update("""
                INSERT INTO deposits.tds_ledger (customer_id, tax_year, account_id, business_date, interest, tax, rate_percent, basis, senior, pan_on_file,
                                                 declaration_id, interest_movement_id, tax_movement_id, lot_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, customerId, p.taxYear(), accountId, businessDate, interest, tax, rate, basis.name(), p.payee().seniorCitizen(), p.payee().panOnFile(),
                p.declarationId(), interestMovement, taxMovement, lotId, DepositEod.USER);
        return tax;
    }
}
