package com.corebanking.lending.engine;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything needed to build a repayment schedule. The same terms drive the preview, the KFS and the booked
 * schedule (ADR-006).
 *
 * @param ratePercent      annual rate as quoted: on the reducing balance, or the flat rate when
 *                         {@link Options#interestBasis()} is FLAT; ignored when {@link Options#fixedInstalment()} is given
 * @param tenorMonths      number of periods of the loan's {@link Frequency} (months for a monthly loan; the name is
 *                         kept for the stored JSON)
 * @param firstDueDate     optional; when null the first due date is one period after disbursal
 * @param moratoriumMonths leading instalments that carry interest only (EQUATED and STEP_EQUATED only)
 * @param balloon          principal left for the last instalment (EQUATED and STEP_EQUATED only); zero for none
 * @param options          frequency, interest basis, broken-period interest and method parameters; null in terms
 *                         stored before P2-6 means monthly, daily-reducing, no broken-period handling
 */
public record LoanTerms(BigDecimal principal, BigDecimal ratePercent, int tenorMonths, LocalDate disbursalDate,
                        LocalDate firstDueDate, RepaymentMethod method, int moratoriumMonths, BigDecimal balloon,
                        DayCount dayCount, Rounding rounding, boolean extraDayOnFirst, Options options) {

    /**
     * How the rate is applied (the reference system's rate bases; see {@link com.corebanking.calc.RateSolver}).
     * <ul>
     *   <li>DAILY_REDUCING: simple interest per annum on the balance for the actual days of each period, by the day
     *       count (the default; "Simple Interest Rate Annual").</li>
     *   <li>PERIODIC_REDUCING: balance × rate / periods per year for every full period, whatever its length in days
     *       (monthly reducing for a monthly loan). A broken first period uses the day count.</li>
     *   <li>FLAT: interest = principal × rate × tenor, spread over equal instalments ("Simple Interest Rate Flat").
     *       The schedule amortises at the equivalent reducing rate, which is the rate accrued and disclosed.</li>
     * </ul>
     */
    public enum InterestBasis { DAILY_REDUCING, PERIODIC_REDUCING, FLAT }

    /**
     * Broken-period interest (BPI): interest for the days between disbursal and the start of the first regular
     * period (one period before the first due date), when the first due date is set later than one period.
     * <ul>
     *   <li>NONE: the first instalment is a normal instalment whose interest covers the actual days (the principal
     *       part absorbs the difference).</li>
     *   <li>ADD_TO_FIRST_INSTALMENT: the first instalment is the normal instalment plus the BPI.</li>
     *   <li>SEPARATE_DEMAND: an interest-only demand for the BPI on the day the first regular period starts.</li>
     *   <li>DEDUCT_AT_DISBURSAL: as SEPARATE_DEMAND, but the amount is taken from the payout and held as an advance
     *       that settles the demand on its date, so the interest is still earned day by day.</li>
     * </ul>
     */
    public enum BpiMode { NONE, ADD_TO_FIRST_INSTALMENT, SEPARATE_DEMAND, DEDUCT_AT_DISBURSAL }

    /** One row of a STRUCTURED schedule: the principal falling due on a date. */
    public record CustomRow(LocalDate dueDate, BigDecimal principal) {
        public CustomRow {
            Objects.requireNonNull(dueDate, "dueDate");
            Objects.requireNonNull(principal, "principal");
            if (principal.signum() < 0) throw new IllegalArgumentException("principal of a schedule row cannot be negative");
        }
    }

    /**
     * @param stepPercent     STEP_EQUATED: change of the instalment at each step, e.g. 10 (step-up) or -10 (step-down)
     * @param stepEvery       STEP_EQUATED: instalments between steps (12 = yearly on a monthly loan)
     * @param principalEvery  FIXED_PRINCIPAL: principal falls due every n-th instalment (1 = every instalment)
     * @param fixedInstalment EQUATED: the instalment is given and the rate follows from it ("Tenure Amount and
     *                        Installment")
     * @param customRows      STRUCTURED: principal by date; must add up to the principal
     */
    public record Options(Frequency frequency, InterestBasis interestBasis, BpiMode bpiMode, BigDecimal stepPercent,
                          Integer stepEvery, Integer principalEvery, BigDecimal fixedInstalment, List<CustomRow> customRows) {

        public static final Options NONE = new Options(null, null, null, null, null, null, null, null);

        public Options {
            frequency = frequency == null ? Frequency.MONTHLY : frequency;
            interestBasis = interestBasis == null ? InterestBasis.DAILY_REDUCING : interestBasis;
            bpiMode = bpiMode == null ? BpiMode.NONE : bpiMode;
            principalEvery = principalEvery == null ? 1 : principalEvery;
            customRows = customRows == null ? List.of() : List.copyOf(customRows);
        }

        public static Options of(Frequency frequency) {
            return new Options(frequency, null, null, null, null, null, null, null);
        }

        public Options withBasis(InterestBasis basis) {
            return new Options(frequency, basis, bpiMode, stepPercent, stepEvery, principalEvery, fixedInstalment, customRows);
        }

        public Options withBpi(BpiMode mode) {
            return new Options(frequency, interestBasis, mode, stepPercent, stepEvery, principalEvery, fixedInstalment, customRows);
        }

        public Options withStep(BigDecimal percent, int every) {
            return new Options(frequency, interestBasis, bpiMode, percent, every, principalEvery, fixedInstalment, customRows);
        }

        public Options withPrincipalEvery(int n) {
            return new Options(frequency, interestBasis, bpiMode, stepPercent, stepEvery, n, fixedInstalment, customRows);
        }

        public Options withFixedInstalment(BigDecimal instalment) {
            return new Options(frequency, interestBasis, bpiMode, stepPercent, stepEvery, principalEvery, instalment, customRows);
        }

        public Options withCustomRows(List<CustomRow> rows) {
            return new Options(frequency, interestBasis, bpiMode, stepPercent, stepEvery, principalEvery, fixedInstalment, rows);
        }

        /** Same frequency, nothing else: what a schedule rebuilt mid-life keeps. */
        public Options plain() {
            boolean byPeriod = interestBasis != InterestBasis.DAILY_REDUCING || fixedInstalment != null;
            return new Options(frequency, byPeriod ? InterestBasis.PERIODIC_REDUCING : InterestBasis.DAILY_REDUCING,
                    null, null, null, principalEvery, null, null);
        }
    }

    public LoanTerms {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(ratePercent, "ratePercent");
        Objects.requireNonNull(disbursalDate, "disbursalDate");
        Objects.requireNonNull(method, "method");
        options = options == null ? Options.NONE : options;
        Frequency f = options.frequency();
        if (principal.signum() <= 0) throw new IllegalArgumentException("principal must be positive");
        if (ratePercent.signum() < 0) throw new IllegalArgumentException("rate cannot be negative");
        if (tenorMonths < 1 || tenorMonths > f.maxPeriods()) {
            throw new IllegalArgumentException(f == Frequency.MONTHLY ? "tenor must be 1..480 months"
                    : "tenor must be 1.." + f.maxPeriods() + " " + f.name().toLowerCase(java.util.Locale.ROOT) + " periods");
        }
        if (moratoriumMonths < 0 || moratoriumMonths >= tenorMonths) throw new IllegalArgumentException("moratorium must be shorter than the tenor");
        if (firstDueDate != null && !firstDueDate.isAfter(disbursalDate)) throw new IllegalArgumentException("first due date must be after disbursal");
        balloon = balloon == null ? BigDecimal.ZERO : balloon;
        if (balloon.signum() < 0 || balloon.compareTo(principal) >= 0) throw new IllegalArgumentException("balloon must be 0..principal");
        boolean equated = method == RepaymentMethod.EQUATED || method == RepaymentMethod.STEP_EQUATED;
        if (!equated && (moratoriumMonths > 0 || balloon.signum() > 0)) {
            throw new IllegalArgumentException("moratorium and balloon apply to EQUATED loans only");
        }
        dayCount = dayCount == null ? DayCount.ACTUAL_365 : dayCount;
        rounding = rounding == null ? Rounding.RUPEE_HALF_UP : rounding;

        if (method == RepaymentMethod.STEP_EQUATED) {
            if (options.stepPercent() == null || options.stepEvery() == null) {
                throw new IllegalArgumentException("a step loan needs stepPercent and stepEvery");
            }
            if (options.stepEvery() < 1) throw new IllegalArgumentException("stepEvery must be at least 1 instalment");
            if (options.stepPercent().signum() == 0 || options.stepPercent().compareTo(BigDecimal.valueOf(-50)) <= 0
                    || options.stepPercent().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("stepPercent must be above -50 and at most 100, and not zero");
            }
        } else if (options.stepPercent() != null || options.stepEvery() != null) {
            throw new IllegalArgumentException("stepPercent and stepEvery apply to STEP_EQUATED loans only");
        }
        if (options.principalEvery() < 1) throw new IllegalArgumentException("principalEvery must be at least 1");
        if (options.principalEvery() > 1) {
            if (method != RepaymentMethod.FIXED_PRINCIPAL) throw new IllegalArgumentException("principalEvery applies to FIXED_PRINCIPAL loans only");
            if (tenorMonths % options.principalEvery() != 0) {
                throw new IllegalArgumentException("the tenor must be a multiple of principalEvery (" + options.principalEvery() + ")");
            }
        }
        boolean flat = options.interestBasis() == InterestBasis.FLAT;
        if (flat || options.fixedInstalment() != null) {
            String what = flat ? "a flat rate" : "a given instalment";
            if (method != RepaymentMethod.EQUATED) throw new IllegalArgumentException(what + " applies to EQUATED loans only");
            if (moratoriumMonths > 0 || balloon.signum() > 0) throw new IllegalArgumentException(what + " cannot be combined with a moratorium or balloon");
            if (flat && options.fixedInstalment() != null) throw new IllegalArgumentException("give either a flat rate or the instalment, not both");
            if (firstDueDate != null && options.bpiMode() == BpiMode.NONE
                    && !f.plus(firstDueDate, -1).equals(disbursalDate)) {
                throw new IllegalArgumentException(what + " needs equal periods: leave the first due date empty or choose how the"
                        + " broken-period interest is collected");
            }
        }
        if (options.fixedInstalment() != null && options.fixedInstalment().signum() <= 0) {
            throw new IllegalArgumentException("the instalment must be positive");
        }
        if (method == RepaymentMethod.STRUCTURED) {
            List<CustomRow> rows = new ArrayList<>(options.customRows());
            if (rows.isEmpty()) throw new IllegalArgumentException("a structured loan needs its schedule rows (date and principal)");
            if (rows.size() != tenorMonths) throw new IllegalArgumentException("the tenor must equal the number of schedule rows (" + rows.size() + ")");
            if (flat || options.interestBasis() == InterestBasis.PERIODIC_REDUCING) {
                throw new IllegalArgumentException("a structured loan accrues on the daily-reducing basis");
            }
            LocalDate prev = disbursalDate;
            BigDecimal total = BigDecimal.ZERO;
            for (CustomRow r : rows) {
                if (!r.dueDate().isAfter(prev)) throw new IllegalArgumentException("schedule dates must be after disbursal and in ascending order");
                prev = r.dueDate();
                total = total.add(r.principal());
            }
            if (total.compareTo(principal) != 0) {
                throw new IllegalArgumentException("the schedule's principal adds up to " + total.toPlainString() + ", not the loan amount "
                        + principal.toPlainString());
            }
            if (rows.get(rows.size() - 1).principal().signum() == 0) throw new IllegalArgumentException("the last schedule row must repay principal");
            if (firstDueDate != null && !firstDueDate.equals(rows.get(0).dueDate())) {
                throw new IllegalArgumentException("the first due date of a structured loan is its first schedule row");
            }
        } else if (!options.customRows().isEmpty()) {
            throw new IllegalArgumentException("schedule rows apply to STRUCTURED loans only");
        }
    }

    /** Terms as stored before P2-6: monthly, daily-reducing. */
    public LoanTerms(BigDecimal principal, BigDecimal ratePercent, int tenorMonths, LocalDate disbursalDate,
                     LocalDate firstDueDate, RepaymentMethod method, int moratoriumMonths, BigDecimal balloon,
                     DayCount dayCount, Rounding rounding, boolean extraDayOnFirst) {
        this(principal, ratePercent, tenorMonths, disbursalDate, firstDueDate, method, moratoriumMonths, balloon, dayCount, rounding,
                extraDayOnFirst, null);
    }

    /** Standard monthly EMI loan with no moratorium or balloon. */
    public static LoanTerms equated(BigDecimal principal, BigDecimal rate, int months, LocalDate disbursal) {
        return new LoanTerms(principal, rate, months, disbursal, null, RepaymentMethod.EQUATED, 0, BigDecimal.ZERO,
                DayCount.ACTUAL_365, Rounding.RUPEE_HALF_UP, false);
    }

    public Frequency frequency() { return options.frequency(); }

    /** The same terms for another amount, start and shape (re-scheduling on a tranche or prepayment). */
    public LoanTerms rescheduled(BigDecimal newPrincipal, BigDecimal newRate, int periods, LocalDate from, LocalDate firstDue,
                                 RepaymentMethod newMethod, int moratorium) {
        return new LoanTerms(newPrincipal, newRate, periods, from, firstDue, newMethod, moratorium, BigDecimal.ZERO, dayCount, rounding,
                false, options.plain());
    }
}
