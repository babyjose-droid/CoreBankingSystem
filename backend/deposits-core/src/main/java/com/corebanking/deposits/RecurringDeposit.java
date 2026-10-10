package com.corebanking.deposits;

import com.corebanking.calc.Rounding;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Recurring deposit: equal monthly instalments. Interest is worked out on the monthly balance (the balance after
 * each month's instalment earns one twelfth of the annual rate) and added to the deposit every
 * {@code compoundingMonths} months. This is the "monthly product, quarterly compounding" method; it is not the
 * closed formula some banks publish, which gives a figure a few paise different.
 */
public final class RecurringDeposit {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TWELVE_HUNDRED = BigDecimal.valueOf(1200);

    public record Instalment(int number, LocalDate dueDate, BigDecimal amount) {}

    private RecurringDeposit() {}

    /** Instalment n falls due n-1 months after the start date; the deposit matures {@code months} months after it. */
    public static List<Instalment> schedule(LocalDate start, int months, BigDecimal instalment) {
        if (months < 1) throw new IllegalArgumentException("months must be at least 1");
        if (instalment.signum() <= 0) throw new IllegalArgumentException("instalment must be positive");
        List<Instalment> out = new ArrayList<>();
        for (int i = 0; i < months; i++) out.add(new Instalment(i + 1, start.plusMonths(i), instalment));
        return List.copyOf(out);
    }

    public static LocalDate maturityDate(LocalDate start, int months) {
        return start.plusMonths(months);
    }

    /** Maturity value when every instalment is paid on its due date. */
    public static BigDecimal maturityValue(BigDecimal instalment, BigDecimal ratePercent, int months,
                                           int compoundingMonths, Rounding rounding) {
        if (months < 1) throw new IllegalArgumentException("months must be at least 1");
        if (compoundingMonths < 1) throw new IllegalArgumentException("compoundingMonths must be at least 1");
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        for (int m = 1; m <= months; m++) {
            balance = balance.add(instalment);
            pending = pending.add(balance.multiply(ratePercent, MC).divide(TWELVE_HUNDRED, MC));
            if (m % compoundingMonths == 0) {
                balance = balance.add(rounding.apply(pending));
                pending = BigDecimal.ZERO;
            }
        }
        return balance.add(rounding.apply(pending));
    }

    /**
     * Charge for an instalment paid late: {@code ratePer100PerMonth} rupees for every Rs 100 of the instalment for
     * each month or part of a month of delay (decision D-19; the rate is a product setting).
     */
    public static BigDecimal latePenalty(BigDecimal instalment, LocalDate dueDate, LocalDate paidOn,
                                         BigDecimal ratePer100PerMonth, Rounding rounding) {
        if (!paidOn.isAfter(dueDate)) return rounding.apply(BigDecimal.ZERO);
        int months = 0;
        while (dueDate.plusMonths(months).isBefore(paidOn)) months++;
        return rounding.apply(instalment.multiply(ratePer100PerMonth).multiply(BigDecimal.valueOf(months))
                .divide(BigDecimal.valueOf(100)));
    }
}
