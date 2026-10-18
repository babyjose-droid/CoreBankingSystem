package com.corebanking.deposits;

import com.corebanking.calc.Rounding;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * A term deposit at and after its maturity date (P4-4). Pure.
 *
 * <ul>
 *   <li><b>Maturity on a holiday.</b> Paid on the next working day, with interest at the contracted rate for the days
 *       in between (the practice of Indian banks; for banks, a summary of the RBI interest rate Directions).</li>
 *   <li><b>Matured and unpaid.</b> A deposit whose proceeds could not be paid or renewed waits as MATURED. When it
 *       is paid or renewed, a bank pays interest for the days it waited at the lower of the contracted rate and the
 *       tenant's overdue rate (its savings rate); a deposit-taking NBFC pays none unless its rule set says otherwise.
 *       Rule {@code td.overdue-interest}; unverified (open item P4-19).</li>
 * </ul>
 * Simple interest on the balance, actual days over 365.
 */
public final class Maturity {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal YEAR = BigDecimal.valueOf(365);

    /** @param basis one sentence saying which rule gave the figure */
    public record Overdue(long days, BigDecimal ratePercent, BigDecimal interest, String basis) {}

    private Maturity() {}

    /**
     * Interest for the days from maturity to payment.
     *
     * @param firstWorkingDay the first working day on or after the maturity date
     * @param overdueRate     the overdue rate; null when the rule set pays no interest after maturity
     */
    public static Overdue overdue(BigDecimal balance, BigDecimal contractedRate, LocalDate maturity, LocalDate paidOn, LocalDate firstWorkingDay,
                                  BigDecimal overdueRate, Rounding rounding) {
        long days = ChronoUnit.DAYS.between(maturity, paidOn);
        BigDecimal zero = rounding.apply(BigDecimal.ZERO);
        if (days <= 0) return new Overdue(0, BigDecimal.ZERO, zero, "paid on the maturity date");
        BigDecimal rate;
        String basis;
        if (!paidOn.isAfter(firstWorkingDay)) {
            rate = contractedRate;
            basis = "matured on a holiday: the contracted rate " + plain(rate) + "% for " + days + " day(s) to the next working day";
        } else if (overdueRate == null) {
            return new Overdue(days, BigDecimal.ZERO, zero, "no interest after maturity for " + days + " day(s)");
        } else {
            rate = contractedRate.min(overdueRate);
            basis = "overdue " + days + " day(s) at " + plain(rate) + "% (the lower of the contracted rate and the overdue rate)";
        }
        BigDecimal interest = rounding.apply(balance.multiply(rate, MC).divide(HUNDRED, MC).multiply(BigDecimal.valueOf(days), MC).divide(YEAR, MC));
        return new Overdue(days, rate, interest, basis);
    }

    /** Whether the maturity notice of a deposit is due on {@code today}: within {@code noticeDays} before maturity. */
    public static boolean noticeDue(LocalDate maturity, LocalDate today, int noticeDays) {
        return today.isBefore(maturity) && !today.isBefore(maturity.minusDays(noticeDays));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
