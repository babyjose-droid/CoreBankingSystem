package com.corebanking.deposits;

import com.corebanking.calc.Rounding;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Recurring deposit: equal monthly instalments. Interest is worked out on the monthly balance (the balance held
 * through each month earns one twelfth of the annual rate) and added to the deposit every {@code compoundingMonths}
 * months and at maturity. This is the "monthly product, quarterly compounding" method; it is not the closed formula
 * some banks publish, which gives a figure a few paise different.
 *
 * <p>P4-5: the same method gives the preview (every instalment paid on its due date), the day-end (on the instalments
 * actually paid: a month earns on the lowest amount of instalments held during it, so an instalment paid late earns
 * from the month after) and a premature closure (whole months run, at the rate the rule set allows). Month {@code m}
 * runs from {@code start + (m-1)} months to {@code start + m} months; its interest is known on the day it ends.
 */
public final class RecurringDeposit {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TWELVE_HUNDRED = BigDecimal.valueOf(1200);
    private static final Rounding ACCRUAL = Rounding.PAISE_HALF_UP;

    public record Instalment(int number, LocalDate dueDate, BigDecimal amount) {}

    /**
     * One month of a recurring deposit.
     *
     * @param balance  what earned this month: the instalments held through it and the interest credited before it
     * @param interest the month's interest, not rounded
     * @param credited interest credited at the end of this month (a compounding month or the last), rounded; else 0
     * @param earned   interest earned to the end of this month: all credited so far plus the rest, rounded to the paisa
     */
    public record Month(int number, LocalDate from, LocalDate to, BigDecimal balance, BigDecimal interest, BigDecimal credited, BigDecimal earned) {}

    /** Where a deposit's interest stands at day-end: months worked out, interest booked so far, interest credited so far. */
    public record State(int monthsDone, BigDecimal booked, BigDecimal credited) {
        public static State starting() {
            return new State(0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public record Result(State state, List<InterestDayEnd.Event> events) {}

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

    /**
     * The months of a deposit, 1 to {@code upTo}.
     *
     * @param held for month m, the lowest amount of instalments held during it (instalments only, no interest)
     */
    public static List<Month> months(LocalDate start, int tenureMonths, BigDecimal ratePercent, int compoundingMonths, Rounding rounding, int upTo,
                                     IntFunction<BigDecimal> held) {
        if (tenureMonths < 1) throw new IllegalArgumentException("months must be at least 1");
        if (compoundingMonths < 1) throw new IllegalArgumentException("compoundingMonths must be at least 1");
        if (upTo > tenureMonths) throw new IllegalArgumentException("the deposit runs for " + tenureMonths + " months");
        List<Month> out = new ArrayList<>();
        BigDecimal creditedSoFar = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        for (int m = 1; m <= upTo; m++) {
            BigDecimal balance = held.apply(m).add(creditedSoFar);
            BigDecimal interest = balance.multiply(ratePercent, MC).divide(TWELVE_HUNDRED, MC);
            pending = pending.add(interest);
            BigDecimal credit = BigDecimal.ZERO;
            if (m % compoundingMonths == 0 || m == tenureMonths) {
                credit = rounding.apply(pending);
                creditedSoFar = creditedSoFar.add(credit);
                pending = BigDecimal.ZERO;
            }
            BigDecimal earned = creditedSoFar.add(ACCRUAL.apply(pending));
            out.add(new Month(m, start.plusMonths(m - 1), start.plusMonths(m), balance, interest, credit, earned));
        }
        return List.copyOf(out);
    }

    /** The months of a deposit whose instalments are all paid on their due dates. */
    public static List<Month> onTime(LocalDate start, int tenureMonths, BigDecimal instalment, BigDecimal ratePercent, int compoundingMonths, Rounding rounding) {
        return months(start, tenureMonths, ratePercent, compoundingMonths, rounding, tenureMonths, m -> instalment.multiply(BigDecimal.valueOf(m)));
    }

    /** The interest credits of a deposit paid on time, as the term deposit preview shows periods. */
    public static List<TermDeposit.Period> periods(LocalDate start, int tenureMonths, BigDecimal instalment, BigDecimal ratePercent, int compoundingMonths,
                                                   Rounding rounding) {
        List<TermDeposit.Period> out = new ArrayList<>();
        LocalDate from = start;
        int n = 0;
        for (Month m : onTime(start, tenureMonths, instalment, ratePercent, compoundingMonths, rounding)) {
            if (m.number() % compoundingMonths != 0 && m.number() != tenureMonths) continue;
            out.add(new TermDeposit.Period(++n, from, m.to(), m.balance(), m.credited(), m.number() % compoundingMonths == 0));
            from = m.to();
        }
        return List.copyOf(out);
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

    /** Interest of a deposit paid on time: the maturity value less the instalments. */
    public static BigDecimal totalInterest(BigDecimal instalment, BigDecimal ratePercent, int months, int compoundingMonths, Rounding rounding) {
        return maturityValue(instalment, ratePercent, months, compoundingMonths, rounding).subtract(instalment.multiply(BigDecimal.valueOf(months)));
    }

    /**
     * The simple annual rate the interest works out to on the average amount deposited (instalment x (n+1)/2 over n
     * months), in percent to two places: what a depositor compares with a fixed deposit's rate.
     */
    public static BigDecimal annualisedYield(BigDecimal instalment, BigDecimal ratePercent, int months, int compoundingMonths, Rounding rounding) {
        BigDecimal interest = totalInterest(instalment, ratePercent, months, compoundingMonths, rounding);
        BigDecimal monthsOfMoney = instalment.multiply(BigDecimal.valueOf((long) months * (months + 1) / 2));
        return interest.multiply(TWELVE_HUNDRED).divide(monthsOfMoney, 2, RoundingMode.HALF_UP);
    }

    /** Whole months run from the start date by {@code on}, at most the tenure. */
    public static int monthsRun(LocalDate start, int tenureMonths, LocalDate on) {
        int m = 0;
        while (m < tenureMonths && !start.plusMonths(m + 1).isAfter(on)) m++;
        return m;
    }

    /**
     * Interest earned at {@code ratePercent} for the whole months run by {@code on}, on the instalments actually held:
     * what a premature closure pays. A month not completed earns nothing.
     */
    public static BigDecimal interestFor(LocalDate start, int tenureMonths, BigDecimal ratePercent, int compoundingMonths, Rounding rounding, LocalDate on,
                                         IntFunction<BigDecimal> held) {
        int run = monthsRun(start, tenureMonths, on);
        if (run == 0 || ratePercent.signum() == 0) return rounding.apply(BigDecimal.ZERO);
        // what was credited at each compounding month, and the months since, credited at closure (rounded as the product says)
        BigDecimal credited = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        for (Month m : months(start, tenureMonths, ratePercent, compoundingMonths, rounding, run, held)) {
            if (m.number() % compoundingMonths == 0 || m.number() == tenureMonths) {
                credited = credited.add(m.credited());
                pending = BigDecimal.ZERO;
            } else {
                pending = pending.add(m.interest());
            }
        }
        return credited.add(rounding.apply(pending));
    }

    /**
     * What the day-end does with a deposit's interest: works out every month that has ended by {@code through} and
     * was not worked out before. Each month's interest is booked (accrued) when it ends; at the end of each compounding
     * month and at maturity it is credited. Re-running a day produces nothing.
     */
    public static Result dayEnd(LocalDate start, int tenureMonths, BigDecimal ratePercent, int compoundingMonths, Rounding rounding, State state,
                                LocalDate through, IntFunction<BigDecimal> held) {
        int upTo = monthsRun(start, tenureMonths, through);
        if (upTo <= state.monthsDone()) return new Result(state, List.of());
        List<Month> ms = months(start, tenureMonths, ratePercent, compoundingMonths, rounding, upTo, held);
        List<InterestDayEnd.Event> events = new ArrayList<>();
        BigDecimal booked = state.booked();
        BigDecimal credited = state.credited();
        LocalDate periodFrom = start;
        for (Month m : ms) {
            boolean boundary = m.number() % compoundingMonths == 0 || m.number() == tenureMonths;
            if (m.number() > state.monthsDone()) {
                BigDecimal delta = m.earned().subtract(booked);
                if (delta.signum() != 0) events.add(new InterestDayEnd.Accrual(m.from(), m.to().minusDays(1), delta));
                booked = m.earned();
                if (m.credited().signum() > 0) {
                    events.add(new InterestDayEnd.Credit(m.number(), periodFrom, m.to(), m.credited()));
                    credited = credited.add(m.credited());
                }
            }
            if (boundary) periodFrom = m.to();
        }
        return new Result(new State(upTo, booked, credited), List.copyOf(events));
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
