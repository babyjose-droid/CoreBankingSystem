package com.corebanking.deposits;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Interest on a term deposit. One method produces the interest periods; the preview, the day-end accrual, the
 * payout or compounding entries, the maturity value and a premature closure all read from it, so they cannot
 * disagree.
 *
 * <p>Convention: interest periods are counted in whole months from the start date. A whole period earns
 * principal x rate x months / 12; a last period that is not whole earns for its actual days on the product's day
 * count. Each period's interest is rounded when it is credited, because that is the amount posted.
 */
public final class TermDeposit {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    /**
     * @param periodMonths months between two interest credits (1, 3, 6, 12); 0 = interest once, at maturity
     * @param cumulative   true: credited interest is added to the deposit and earns interest; false: paid out
     */
    public record Terms(BigDecimal principal, BigDecimal ratePercent, LocalDate start, LocalDate maturity,
                        int periodMonths, boolean cumulative, DayCount dayCount, Rounding rounding) {
        public Terms {
            if (principal.signum() <= 0) throw new IllegalArgumentException("principal must be positive");
            if (ratePercent.signum() < 0) throw new IllegalArgumentException("rate cannot be negative");
            if (!maturity.isAfter(start)) throw new IllegalArgumentException("maturity must be after the start date");
            if (periodMonths < 0) throw new IllegalArgumentException("periodMonths cannot be negative");
            if (dayCount == DayCount.THIRTY_360 || dayCount == DayCount.THIRTY_E_360) {
                throw new IllegalArgumentException("a deposit accrues on actual days; " + dayCount + " is not supported");
            }
        }

        /** The same deposit ended early on {@code end} at another rate: what a premature closure pays. */
        public Terms endedOn(LocalDate end, BigDecimal newRatePercent) {
            return new Terms(principal, newRatePercent, start, end, periodMonths, cumulative, dayCount, rounding);
        }
    }

    /** One interest credit. {@code from} is the first day that earns, {@code to} the day of the credit (not earning). */
    public record Period(int number, LocalDate from, LocalDate to, BigDecimal balance, BigDecimal interest, boolean whole) {}

    private TermDeposit() {}

    public static List<Period> periods(Terms t) {
        List<Period> out = new ArrayList<>();
        BigDecimal balance = t.principal();
        BigDecimal rate = t.ratePercent().divide(HUNDRED, MC);
        LocalDate from = t.start();
        int n = 0;
        while (from.isBefore(t.maturity())) {
            n++;
            LocalDate end = t.periodMonths() == 0 ? t.maturity() : t.start().plusMonths((long) n * t.periodMonths());
            boolean whole = t.periodMonths() != 0 && !end.isAfter(t.maturity());
            if (!whole) end = t.maturity();
            BigDecimal raw = whole
                    ? balance.multiply(rate, MC).multiply(BigDecimal.valueOf(t.periodMonths()), MC).divide(TWELVE, MC)
                    : balance.multiply(rate, MC).multiply(t.dayCount().yearFraction(from, end), MC);
            BigDecimal interest = t.rounding().apply(raw);
            out.add(new Period(n, from, end, balance, interest, whole));
            if (t.cumulative()) balance = balance.add(interest);
            from = end;
        }
        return List.copyOf(out);
    }

    public static BigDecimal totalInterest(Terms t) {
        return periods(t).stream().map(Period::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** What is repaid at maturity: the principal, plus all interest for a cumulative deposit. */
    public static BigDecimal maturityValue(Terms t) {
        return t.cumulative() ? t.principal().add(totalInterest(t)) : t.principal();
    }

    /**
     * Interest earned for the days from the start date up to, not including, {@code asOf}. The day-end books the
     * difference between this figure for tomorrow and what it has booked so far, so rounding never builds up and
     * the accrued total on a credit date equals the credit exactly.
     */
    public static BigDecimal earnedBefore(Terms t, LocalDate asOf) {
        BigDecimal total = BigDecimal.ZERO;
        for (Period p : periods(t)) {
            if (!asOf.isBefore(p.to())) {
                total = total.add(p.interest());
            } else if (asOf.isAfter(p.from())) {
                long days = DayCount.ACTUAL_365.days(p.from(), asOf);
                long all = DayCount.ACTUAL_365.days(p.from(), p.to());
                total = total.add(t.rounding().apply(p.interest().multiply(BigDecimal.valueOf(days), MC)
                        .divide(BigDecimal.valueOf(all), MC)));
            }
        }
        return total;
    }

    /** The annual yield a cumulative deposit works out to, in percent to two places: shown beside the rate. */
    public static BigDecimal annualisedYield(Terms t) {
        BigDecimal years = t.dayCount().yearFraction(t.start(), t.maturity());
        return totalInterest(t).multiply(HUNDRED, MC).divide(t.principal(), MC).divide(years, MC)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
