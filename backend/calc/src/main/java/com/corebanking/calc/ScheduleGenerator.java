package com.corebanking.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Repayment schedules. The same code produces the "simulate" preview and the schedule that is
 * booked, so a preview can never disagree with what is posted (ADR-006: simulate = post).
 */
public final class ScheduleGenerator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ScheduleGenerator() {}

    public record Instalment(int number, LocalDate dueDate, long days, BigDecimal openingBalance,
                             BigDecimal interest, BigDecimal principal, BigDecimal instalment,
                             BigDecimal closingBalance) {}

    /**
     * Monthly due date {@code n} for a loan opened on {@code open}. Dates are always derived from the
     * open date (never chained), so 30-Jan gives 28-Feb then 30-Mar. When the open date is the last day
     * of its month, every due date is the last day of its month.
     */
    public static LocalDate dueDate(LocalDate open, int n) {
        LocalDate d = open.plusMonths(n);
        boolean monthEnd = open.getDayOfMonth() == open.lengthOfMonth();
        return monthEnd ? d.withDayOfMonth(d.lengthOfMonth()) : d;
    }

    /**
     * Equated (EMI) schedule. Interest per period = opening balance × rate × day-count fraction,
     * rounded per product rule. The last instalment clears the balance exactly and absorbs rounding.
     */
    public static List<Instalment> equated(BigDecimal principal, BigDecimal annualRatePercent, int instalments,
                                           LocalDate openDate, DayCount dayCount, Rounding rounding) {
        BigDecimal emi = EmiCalculator.pmt(principal, annualRatePercent, instalments, rounding);
        return equatedWithEmi(principal, annualRatePercent, instalments, openDate, dayCount, rounding, emi);
    }

    public static List<Instalment> equatedWithEmi(BigDecimal principal, BigDecimal annualRatePercent, int instalments,
                                                  LocalDate openDate, DayCount dayCount, Rounding rounding,
                                                  BigDecimal emi) {
        BigDecimal rate = annualRatePercent.divide(HUNDRED, MC);
        List<Instalment> rows = new ArrayList<>(instalments);
        BigDecimal balance = principal;
        LocalDate prev = openDate;
        for (int n = 1; n <= instalments; n++) {
            LocalDate due = dueDate(openDate, n);
            BigDecimal interest = rounding.apply(balance.multiply(rate, MC).multiply(dayCount.yearFraction(prev, due), MC));
            BigDecimal principalPart;
            BigDecimal amount;
            if (n == instalments || emi.subtract(interest).compareTo(balance) >= 0) {
                principalPart = balance;
                amount = balance.add(interest);
            } else {
                principalPart = emi.subtract(interest);
                amount = emi;
            }
            BigDecimal closing = balance.subtract(principalPart);
            rows.add(new Instalment(n, due, dayCount.days(prev, due), balance, interest, principalPart, amount, closing));
            balance = closing;
            prev = due;
            if (balance.signum() == 0) break;
        }
        return Collections.unmodifiableList(rows);
    }

    /**
     * Bullet (total interest) loan: principal and all interest due at maturity.
     *
     * @param extraDayOnFirst product flag "extra day interest on first disbursement date": the
     *                        disbursement day itself also bears interest.
     */
    public static BigDecimal bulletTotalInterest(BigDecimal principal, BigDecimal annualRatePercent,
                                                 LocalDate disbursed, LocalDate maturity, DayCount dayCount,
                                                 boolean extraDayOnFirst, Rounding rounding) {
        LocalDate from = extraDayOnFirst ? disbursed.minusDays(1) : disbursed;
        BigDecimal rate = annualRatePercent.divide(HUNDRED, MC);
        return rounding.apply(principal.multiply(rate, MC).multiply(dayCount.yearFraction(from, maturity), MC));
    }
}
