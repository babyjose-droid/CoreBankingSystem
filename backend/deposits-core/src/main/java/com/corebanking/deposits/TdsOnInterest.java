package com.corebanking.deposits;

import com.corebanking.calc.Rounding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;

/**
 * Tax deducted at source on deposit interest paid to a resident (section 393 of the Income-tax Act, 2025; section
 * 194A before 1-Apr-2026). The rate and thresholds come from the rule set. Interest is added up per depositor for
 * the tax year across all deposits; when the total passes the threshold, tax is due on the whole of it, so the
 * credit that crosses the line also carries the tax on the interest before it.
 */
public final class TdsOnInterest {

    /**
     * @param seniorCitizen     resident aged 60 or more at any time in the tax year
     * @param panOnFile         a PAN is recorded; without one the higher rate applies and a declaration is ignored
     * @param declarationOnFile Form 121 for this tax year (Forms 15G and 15H before 1-Apr-2026)
     * @param exempt            payee to whom no deduction applies (for example a lower-deduction certificate is
     *                          not this: only a full exemption)
     */
    public record Payee(boolean seniorCitizen, boolean panOnFile, boolean declarationOnFile, boolean exempt) {}

    /** The depositor's tax year so far, before the interest now being credited or paid. */
    public record YearToDate(BigDecimal interest, BigDecimal taxDeducted) {
        public static final YearToDate NONE = new YearToDate(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private TdsOnInterest() {}

    /** Tax to deduct from {@code interestNow}. Never more than the interest itself; a shortfall is caught up next time. */
    public static BigDecimal deduct(DepositRules rules, Payee payee, YearToDate before, BigDecimal interestNow, Rounding rounding) {
        BigDecimal none = rounding.apply(BigDecimal.ZERO);
        if (interestNow.signum() <= 0 || payee.exempt()) return none;
        if (payee.declarationOnFile() && payee.panOnFile()) return none;
        BigDecimal threshold = rules.required(payee.seniorCitizen() ? DepositRules.TDS_THRESHOLD_SENIOR : DepositRules.TDS_THRESHOLD);
        BigDecimal total = before.interest().add(interestNow);
        if (total.compareTo(threshold) <= 0) return none;
        BigDecimal rate = rules.required(DepositRules.TDS_RATE);
        if (!payee.panOnFile()) rate = rate.max(rules.required(DepositRules.TDS_NO_PAN_RATE));
        BigDecimal dueForYear = rounding.apply(total.multiply(rate).divide(BigDecimal.valueOf(100)));
        return dueForYear.subtract(before.taxDeducted()).max(none).min(rounding.apply(interestNow));
    }

    /** The tax year (1 April to 31 March) a date falls in, named by the calendar year it starts in. */
    public static int taxYearStarting(LocalDate date) {
        return date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
    }

    /** True when the person reaches {@code seniorAge} on or before the last day of the tax year of {@code date}. */
    public static boolean seniorInTaxYear(LocalDate dateOfBirth, LocalDate date, int seniorAge) {
        LocalDate yearEnd = LocalDate.of(taxYearStarting(date) + 1, 3, 31);
        return Period.between(dateOfBirth, yearEnd).getYears() >= seniorAge;
    }
}
