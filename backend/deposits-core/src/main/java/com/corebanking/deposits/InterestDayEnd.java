package com.corebanking.deposits;

import com.corebanking.calc.Rounding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * What the day-end does with a deposit account's interest (P4-3): which amounts it accrues and which interest it
 * credits, for the calendar days from the day after the last one accrued up to {@code through}. The caller posts the
 * events in order; this class only works them out, so a re-run of a day that is already done produces nothing.
 *
 * <p>Accrual is booked as the change in the interest earned so far, rounded to the paisa, so daily rounding never
 * builds up: on a credit date the accrued total equals the credit. When the product rounds its credits more coarsely
 * (to the rupee), the difference is booked as an accrual of its own just before the credit (it may be negative).
 */
public final class InterestDayEnd {

    /** Accrual is booked to the paisa, whatever the product's rounding of credits. */
    static final Rounding ACCRUAL_ROUNDING = Rounding.PAISE_HALF_UP;

    /** Something to post. */
    public sealed interface Event permits Accrual, Credit {}

    /** Interest expense against interest payable; a negative amount is the reverse (a rounding true-up). */
    public record Accrual(LocalDate from, LocalDate to, BigDecimal amount) implements Event {}

    /**
     * Interest moved from payable to the account.
     *
     * @param periodEnd the last day earning in the period (savings), or the credit date (term deposit)
     * @param number    the period's number for a term deposit; 0 for savings
     */
    public record Credit(int number, LocalDate periodFrom, LocalDate periodEnd, BigDecimal amount) implements Event {}

    /**
     * Savings interest state of an account.
     *
     * @param accruedThrough last day accrued (inclusive)
     * @param exact          interest of the open period, unrounded (8 places)
     * @param accrued        interest of the open period booked to payable so far (paise)
     * @param periodFrom     first day of the open period
     */
    public record SavingsState(LocalDate accruedThrough, BigDecimal exact, BigDecimal accrued, LocalDate periodFrom) {
        public SavingsState {
            Objects.requireNonNull(accruedThrough);
            Objects.requireNonNull(exact);
            Objects.requireNonNull(accrued);
            Objects.requireNonNull(periodFrom);
        }

        /** An account that has not accrued yet, from {@code firstDay}. */
        public static SavingsState startingOn(LocalDate firstDay) {
            return new SavingsState(firstDay.minusDays(1), BigDecimal.ZERO, BigDecimal.ZERO, firstDay);
        }
    }

    public record SavingsResult(SavingsState state, List<Event> events) {}

    /**
     * Term deposit interest state.
     *
     * @param accruedThrough   last day accrued (inclusive)
     * @param booked           interest booked to payable since the start, including what has been credited
     * @param periodsCredited  periods credited so far
     */
    public record DepositState(LocalDate accruedThrough, BigDecimal booked, int periodsCredited) {
        public static DepositState startingOn(LocalDate start) {
            return new DepositState(start.minusDays(1), BigDecimal.ZERO, 0);
        }
    }

    public record DepositResult(DepositState state, List<Event> events) {}

    private InterestDayEnd() {}

    /**
     * Savings: each day's end-of-day balance earns one day's interest; on the last day of an interest period the
     * period's interest is credited, and it earns from the next day.
     *
     * @param balanceAt         end-of-day balance of a day, as it stood before this run's credits
     * @param postingMonths     1, 3, 6 or 12
     * @param yearStartMonth    first month of the financial year
     * @param creditRounding    the product's rounding of a credit
     */
    public static SavingsResult savings(SavingsInterest interest, SavingsState state, LocalDate through, Function<LocalDate, BigDecimal> balanceAt,
                                        int postingMonths, int yearStartMonth, Rounding creditRounding) {
        List<Event> events = new ArrayList<>();
        BigDecimal exact = state.exact();
        BigDecimal accrued = state.accrued();
        LocalDate periodFrom = state.periodFrom();
        LocalDate accrualFrom = state.accruedThrough().plusDays(1);
        BigDecimal creditedInRun = BigDecimal.ZERO;
        LocalDate d = state.accruedThrough().plusDays(1);
        for (; !d.isAfter(through); d = d.plusDays(1)) {
            BigDecimal balance = balanceAt.apply(d).add(creditedInRun);
            exact = exact.add(interest.forOneDay(balance));
            if (d.equals(SavingsInterest.periodEnd(d, postingMonths, yearStartMonth))) {
                BigDecimal credit = creditRounding.apply(exact);
                BigDecimal trueUp = credit.subtract(accrued);
                if (trueUp.signum() != 0) events.add(new Accrual(accrualFrom, d, trueUp));
                if (credit.signum() > 0) {
                    events.add(new Credit(0, periodFrom, d, credit));
                    creditedInRun = creditedInRun.add(credit);
                }
                exact = BigDecimal.ZERO;
                accrued = BigDecimal.ZERO;
                periodFrom = d.plusDays(1);
                accrualFrom = d.plusDays(1);
            }
        }
        LocalDate last = d.minusDays(1);
        if (!last.isBefore(accrualFrom)) {
            BigDecimal delta = ACCRUAL_ROUNDING.apply(exact).subtract(accrued);
            if (delta.signum() != 0) {
                events.add(new Accrual(accrualFrom, last, delta));
                accrued = accrued.add(delta);
            }
        }
        LocalDate accruedThrough = through.isAfter(state.accruedThrough()) ? through : state.accruedThrough();
        return new SavingsResult(new SavingsState(accruedThrough, exact.setScale(SavingsInterest.ACCRUAL_SCALE, java.math.RoundingMode.HALF_UP),
                accrued, periodFrom), List.copyOf(events));
    }

    /**
     * A term deposit: interest accrues for every day from the start date up to the day before maturity; each period
     * is credited on its credit date (the day after its last earning day), the last one on the maturity date.
     */
    public static DepositResult deposit(TermDeposit.Terms t, DepositState state, LocalDate through) {
        List<Event> events = new ArrayList<>();
        LocalDate lastEarning = t.maturity().minusDays(1);
        LocalDate upTo = through.isBefore(lastEarning) ? through : lastEarning;
        BigDecimal booked = state.booked();
        LocalDate accruedThrough = state.accruedThrough();
        if (upTo.isAfter(accruedThrough)) {
            BigDecimal delta = TermDeposit.earnedBefore(t, upTo.plusDays(1)).subtract(booked);
            if (delta.signum() != 0) events.add(new Accrual(accruedThrough.plusDays(1), upTo, delta));
            booked = booked.add(delta);
            accruedThrough = upTo;
        }
        int credited = state.periodsCredited();
        for (TermDeposit.Period p : TermDeposit.periods(t)) {
            if (p.number() <= credited || p.to().isAfter(through)) continue;
            if (p.interest().signum() > 0) events.add(new Credit(p.number(), p.from(), p.to(), p.interest()));
            credited = p.number();
        }
        return new DepositResult(new DepositState(accruedThrough, booked, credited), List.copyOf(events));
    }
}
