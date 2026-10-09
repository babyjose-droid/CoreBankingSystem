package com.corebanking.lending.engine;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.EmiCalculator;
import com.corebanking.calc.RateSolver;
import com.corebanking.calc.Rounding;
import com.corebanking.calc.ScheduleGenerator;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.engine.LoanTerms.BpiMode;
import com.corebanking.lending.engine.LoanTerms.InterestBasis;
import com.corebanking.lending.engine.LoanTerms.Options;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds repayment schedules for every {@link RepaymentMethod}, {@link Frequency}, interest basis and broken-period
 * mode (US-039, US-054). One code path serves the preview, the KFS, the booked schedule and every re-schedule.
 */
public final class ScheduleBuilder {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ScheduleBuilder() {}

    /**
     * A schedule and the figures that go with it.
     *
     * @param emi                  the regular instalment of an equated loan (the first step's for a step loan); null otherwise
     * @param accrualRatePercent   nominal annual rate on the reducing balance that the account accrues at: the quoted
     *                             rate, or its equivalent for a flat rate or a given instalment
     * @param brokenPeriodInterest interest for the broken period, when it is collected on its own or added to the
     *                             first instalment; zero otherwise
     * @param bpiDeducted          the broken-period interest is taken from the payout (row 1 is then settled from it)
     */
    public record Plan(List<Instalment> schedule, BigDecimal emi, BigDecimal accrualRatePercent, BigDecimal brokenPeriodInterest,
                       boolean bpiDeducted) {
        public BigDecimal totalInterest() {
            return schedule.stream().map(Instalment::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public static List<Instalment> build(LoanTerms t) {
        return plan(t).schedule();
    }

    public static Plan plan(LoanTerms t) {
        return switch (t.method()) {
            case EQUATED, STEP_EQUATED -> equated(t);
            case FIXED_PRINCIPAL -> fixedPrincipal(t);
            case BULLET_TOTAL_INTEREST -> bulletTotal(t);
            case BULLET_PERIODIC_INTEREST -> bulletPeriodic(t);
            case TRANCHE_BULLET -> t.options().interestAtMaturity() ? bulletTotal(t) : bulletPeriodic(t);
            case STRUCTURED -> structured(t);
        };
    }

    /**
     * The rows from index {@code from} on, re-priced at {@code ratePercent} with the instalment recomputed over the rows
     * left (the tenure is kept): an elapsed-tenure rate step. Dates, numbers and leading interest-only rows are kept;
     * the last row clears the balance. Interest is by actual days from the previous row's due date, as
     * {@link LoanAccount} computes it when it applies the step on its date, so the two agree.
     */
    public static List<Instalment> restep(List<Instalment> rows, int from, BigDecimal ratePercent, DayCount dayCount, Rounding rounding) {
        if (from < 1 || from >= rows.size()) return rows;
        List<Instalment> out = new ArrayList<>(rows.subList(0, from));
        BigDecimal bal = rows.get(from).openingBalance();
        int n = rows.size() - from;
        int moratorium = 0;
        while (moratorium < n - 1 && rows.get(from + moratorium).principal().signum() == 0) moratorium++;
        BigDecimal emi = bal.signum() == 0 ? BigDecimal.ZERO : EmiCalculator.pmt(bal, ratePercent, n - moratorium, rounding);
        LocalDate prev = rows.get(from - 1).dueDate();
        for (int k = 0; k < n; k++) {
            Instalment r = rows.get(from + k);
            BigDecimal interest = rounding.apply(bal.multiply(ratePercent.divide(HUNDRED, MC), MC)
                    .multiply(dayCount.yearFraction(prev, r.dueDate()), MC));
            BigDecimal principal;
            if (k < moratorium) {
                principal = BigDecimal.ZERO;
            } else if (k == n - 1) {
                principal = bal;
            } else {
                principal = emi.subtract(interest);
                if (principal.signum() <= 0) {
                    throw new IllegalArgumentException("at " + ratePercent.stripTrailingZeros().toPlainString() + "% the instalment of "
                            + emi.toPlainString() + " does not cover the interest of " + interest.toPlainString() + " due on " + r.dueDate()
                            + "; the loan would negatively amortise");
                }
                principal = principal.min(bal);
            }
            out.add(new Instalment(r.number(), r.dueDate(), r.days(), bal, interest, principal, principal.add(interest), bal.subtract(principal)));
            bal = bal.subtract(principal);
            prev = r.dueDate();
        }
        return Collections.unmodifiableList(out);
    }

    /** Due date of instalment n (1-based), month-end anchored when the anchor is a month end. */
    public static LocalDate dueDate(LoanTerms t, int n) {
        Frequency f = t.frequency();
        if (t.firstDueDate() == null) {
            return f == Frequency.MONTHLY ? ScheduleGenerator.dueDate(t.disbursalDate(), n) : f.plus(t.disbursalDate(), n);
        }
        return n == 1 ? t.firstDueDate() : f.plus(t.firstDueDate(), n - 1);
    }

    /** Regular instalment of an equated loan (after any moratorium), allowing for a balloon, flat rate or steps. */
    public static BigDecimal emi(LoanTerms t) {
        Frame fr = new Frame(t);
        return fr.baseEmi == null ? null : fr.emi(1 + t.moratoriumMonths());
    }

    /** Rate per period as a fraction: annual % / (100 × periods per year). */
    static BigDecimal periodicRate(BigDecimal annualPercent, Frequency f) {
        return annualPercent.divide(HUNDRED.multiply(BigDecimal.valueOf(f.periodsPerYear())), MC);
    }

    // ------------------------------------------------------------------------------------------------ shared frame
    /** Dates, rates and broken-period handling common to the periodic methods. */
    private static final class Frame {
        final LoanTerms t;
        final Options o;
        final int n;
        /** Start of the first regular period: one period before the first due date. */
        final LocalDate periodStart;
        /** Broken-period mode in effect: NONE when there is no broken period to collect. */
        final BpiMode bpi;
        /** The first row is a full regular period (or its broken part is handled by {@link #bpi}). */
        final boolean firstRegular;
        /** Rate the account accrues at (annual %, reducing). */
        final BigDecimal rate;
        /** Rate per period, used for PERIODIC_REDUCING / FLAT interest and for the instalment formula. */
        final BigDecimal periodic;
        final boolean byPeriod;
        /** FLAT: total interest of the loan (rounded); null otherwise. */
        final BigDecimal flatInterest;
        final BigDecimal bpiAmount;
        private final BigDecimal baseEmi;

        Frame(LoanTerms t) {
            this.t = t;
            this.o = t.options();
            this.n = t.tenorMonths();
            Frequency f = o.frequency();
            periodStart = t.firstDueDate() == null ? t.disbursalDate() : f.plus(t.firstDueDate(), -1);
            boolean broken = periodStart.isAfter(t.disbursalDate());
            bpi = broken ? o.bpiMode() : BpiMode.NONE;
            firstRegular = t.firstDueDate() == null || periodStart.equals(t.disbursalDate()) || bpi != BpiMode.NONE;
            // a rate derived from a given instalment is a rate per period, so it is applied per period
            byPeriod = o.interestBasis() != InterestBasis.DAILY_REDUCING || o.fixedInstalment() != null;

            int ppy = f.periodsPerYear();
            if (o.interestBasis() == InterestBasis.FLAT) {
                flatInterest = t.rounding().apply(RateSolver.flatInterest(t.principal(), t.ratePercent(), n, ppy));
                BigDecimal level = t.principal().add(flatInterest).divide(BigDecimal.valueOf(n), MC);
                periodic = RateSolver.periodicRate(t.principal(), level, n);
                rate = RateSolver.annual(periodic, ppy);
                baseEmi = t.rounding().apply(level);
            } else if (o.fixedInstalment() != null) {
                flatInterest = null;
                periodic = RateSolver.periodicRate(t.principal(), o.fixedInstalment(), n);
                rate = RateSolver.annual(periodic, ppy);
                baseEmi = o.fixedInstalment();
            } else {
                flatInterest = null;
                rate = t.ratePercent();
                periodic = periodicRate(rate, f);
                baseEmi = t.method() == RepaymentMethod.EQUATED || t.method() == RepaymentMethod.STEP_EQUATED ? solveEmi() : null;
            }
            bpiAmount = bpi == BpiMode.NONE ? BigDecimal.ZERO
                    : t.rounding().apply(dayInterest(t.principal(), t.disbursalDate(), periodStart));
        }

        /** Growth of the instalment at equated instalment j (1-based, after the moratorium). */
        private BigDecimal step(int j) {
            if (t.method() != RepaymentMethod.STEP_EQUATED) return BigDecimal.ONE;
            int steps = (j - 1) / o.stepEvery();
            return BigDecimal.ONE.add(o.stepPercent().divide(HUNDRED, MC)).pow(steps, MC);
        }

        /**
         * Base instalment E such that the instalments E × step(j), discounted at the periodic rate, repay the principal
         * less the present value of the balloon. Without steps this is PMT.
         */
        private BigDecimal solveEmi() {
            int m = n - t.moratoriumMonths();
            boolean stepped = t.method() == RepaymentMethod.STEP_EQUATED;
            if (periodic.signum() == 0) {
                BigDecimal weights = BigDecimal.ZERO;
                for (int j = 1; j <= m; j++) weights = weights.add(step(j));
                return t.principal().subtract(t.balloon()).divide(weights, MC);
            }
            BigDecimal growth = BigDecimal.ONE.add(periodic).pow(m, MC);
            BigDecimal pv = t.principal().subtract(t.balloon().divide(growth, MC));
            if (!stepped) return pv.multiply(periodic, MC).multiply(growth, MC).divide(growth.subtract(BigDecimal.ONE), MC);
            BigDecimal weights = BigDecimal.ZERO;
            BigDecimal discount = BigDecimal.ONE;
            BigDecimal factor = BigDecimal.ONE.add(periodic);
            for (int j = 1; j <= m; j++) {
                discount = discount.multiply(factor, MC);
                weights = weights.add(step(j).divide(discount, MC));
            }
            return pv.divide(weights, MC);
        }

        /** Instalment of row k (1-based over the regular rows); meaningful after the moratorium. */
        BigDecimal emi(int k) {
            int j = Math.max(1, k - t.moratoriumMonths());
            return t.rounding().apply(baseEmi.multiply(step(j), MC));
        }

        BigDecimal dayInterest(BigDecimal balance, LocalDate from, LocalDate to) {
            return balance.multiply(rate.divide(HUNDRED, MC), MC).multiply(t.dayCount().yearFraction(from, to), MC);
        }

        /** Interest of one row: by actual days, or balance × periodic rate for a full period on a periodic basis. */
        BigDecimal interest(BigDecimal balance, LocalDate from, LocalDate to, boolean regular) {
            if (byPeriod && regular) return t.rounding().apply(balance.multiply(periodic, MC));
            return t.rounding().apply(dayInterest(balance, from, to));
        }
    }

    /** Principal of a regular row given what a standard period's interest is. */
    private interface PrincipalRule {
        BigDecimal principal(int k, BigDecimal balance, BigDecimal standardInterest);
    }

    /**
     * Rows for the periodic methods: an optional broken-period row, then one row per period. On a flat-rate loan the
     * last row's interest is whatever makes the total equal the flat interest exactly.
     */
    private static Plan rows(Frame fr, PrincipalRule rule, BigDecimal emi) {
        LoanTerms t = fr.t;
        List<Instalment> rows = new ArrayList<>();
        BigDecimal bal = t.principal();
        LocalDate prev = t.disbursalDate();
        int offset = 0;
        boolean ownRow = fr.bpi == BpiMode.SEPARATE_DEMAND || fr.bpi == BpiMode.DEDUCT_AT_DISBURSAL;
        if (ownRow) {
            rows.add(new Instalment(1, fr.periodStart, t.dayCount().days(prev, fr.periodStart), bal, fr.bpiAmount, BigDecimal.ZERO,
                    fr.bpiAmount, bal));
            prev = fr.periodStart;
            offset = 1;
        }
        BigDecimal interestSoFar = BigDecimal.ZERO;
        for (int k = 1; k <= fr.n; k++) {
            LocalDate due = dueDate(t, k);
            BigDecimal interest;
            BigDecimal standard;
            if (k == 1 && fr.bpi == BpiMode.ADD_TO_FIRST_INSTALMENT) {
                standard = fr.interest(bal, fr.periodStart, due, true);
                interest = fr.byPeriod ? standard.add(fr.bpiAmount) : fr.interest(bal, prev, due, false);
            } else {
                interest = fr.interest(bal, prev, due, k > 1 || fr.firstRegular);
                standard = interest;
            }
            BigDecimal principal = k == fr.n ? bal : rule.principal(k, bal, standard).max(BigDecimal.ZERO).min(bal);
            if (k == fr.n && fr.flatInterest != null) {
                // the last instalment absorbs rounding so that the borrower pays the flat interest exactly
                BigDecimal bpiInRows = fr.bpi == BpiMode.ADD_TO_FIRST_INSTALMENT ? fr.bpiAmount : BigDecimal.ZERO;
                interest = fr.flatInterest.add(bpiInRows).subtract(interestSoFar).max(BigDecimal.ZERO);
            }
            interestSoFar = interestSoFar.add(interest);
            BigDecimal closing = bal.subtract(principal);
            rows.add(new Instalment(k + offset, due, t.dayCount().days(prev, due), bal, interest, principal, principal.add(interest), closing));
            bal = closing;
            prev = due;
        }
        return new Plan(Collections.unmodifiableList(rows), emi, fr.rate, fr.bpiAmount, fr.bpi == BpiMode.DEDUCT_AT_DISBURSAL);
    }

    // ------------------------------------------------------------------------------------------------ methods
    private static Plan equated(LoanTerms t) {
        if (!t.options().rateSteps().isEmpty()) return stepped(t);
        Frame fr = new Frame(t);
        boolean stepped = t.method() == RepaymentMethod.STEP_EQUATED;
        return rows(fr, (k, bal, standard) -> {
            if (k <= t.moratoriumMonths()) return BigDecimal.ZERO;
            BigDecimal principal = fr.emi(k).subtract(standard);
            if (stepped && principal.signum() <= 0) {
                throw new IllegalArgumentException("instalment " + k + " (" + fr.emi(k).toPlainString() + ") does not cover its interest of "
                        + standard.toPlainString() + ": the steps are too steep and the loan would negatively amortise");
            }
            return principal;
        }, fr.emi(1 + t.moratoriumMonths()));
    }

    /**
     * Elapsed-tenure rate table: the loan at the first step's rate, then from each later step the instalments left
     * re-priced at that step's rate over the same tenure ({@link #restep}). The whole stepped schedule is disclosed.
     */
    private static Plan stepped(LoanTerms t) {
        List<LoanTerms.RateStep> steps = t.options().rateSteps();
        Plan base = equated(new LoanTerms(t.principal(), t.ratePercent(), t.tenorMonths(), t.disbursalDate(), t.firstDueDate(), t.method(),
                t.moratoriumMonths(), t.balloon(), t.dayCount(), t.rounding(), t.extraDayOnFirst(), t.options().withRateSteps(null)));
        List<Instalment> rows = base.schedule();
        for (LoanTerms.RateStep step : steps.subList(1, steps.size())) {
            rows = restep(rows, step.fromMonth() - 1, step.ratePercent(), t.dayCount(), t.rounding());
        }
        return new Plan(rows, base.emi(), base.accrualRatePercent(), base.brokenPeriodInterest(), base.bpiDeducted());
    }

    private static Plan fixedPrincipal(LoanTerms t) {
        Frame fr = new Frame(t);
        int every = t.options().principalEvery();
        BigDecimal part = t.rounding().apply(t.principal().divide(BigDecimal.valueOf(t.tenorMonths() / every), MC));
        return rows(fr, (k, bal, standard) -> k % every == 0 ? part : BigDecimal.ZERO, null);
    }

    private static Plan bulletPeriodic(LoanTerms t) {
        return rows(new Frame(t), (k, bal, standard) -> BigDecimal.ZERO, null);
    }

    private static Plan bulletTotal(LoanTerms t) {
        LocalDate maturity = dueDate(t, t.tenorMonths());
        BigDecimal interest = ScheduleGenerator.bulletTotalInterest(t.principal(), t.ratePercent(), t.disbursalDate(),
                maturity, t.dayCount(), t.extraDayOnFirst(), t.rounding());
        return new Plan(List.of(new Instalment(1, maturity, t.dayCount().days(t.disbursalDate(), maturity), t.principal(), interest,
                t.principal(), t.principal().add(interest), BigDecimal.ZERO)), null, t.ratePercent(), BigDecimal.ZERO, false);
    }

    private static Plan structured(LoanTerms t) {
        List<Instalment> rows = new ArrayList<>();
        BigDecimal bal = t.principal();
        LocalDate prev = t.disbursalDate();
        int k = 0;
        for (LoanTerms.CustomRow r : t.options().customRows()) {
            BigDecimal interest = t.rounding().apply(bal.multiply(t.ratePercent().divide(HUNDRED, MC), MC)
                    .multiply(t.dayCount().yearFraction(prev, r.dueDate()), MC));
            BigDecimal closing = bal.subtract(r.principal());
            rows.add(new Instalment(++k, r.dueDate(), t.dayCount().days(prev, r.dueDate()), bal, interest, r.principal(),
                    r.principal().add(interest), closing));
            bal = closing;
            prev = r.dueDate();
        }
        return new Plan(Collections.unmodifiableList(rows), null, t.ratePercent(), BigDecimal.ZERO, false);
    }
}
