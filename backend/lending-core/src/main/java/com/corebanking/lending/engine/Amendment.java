package com.corebanking.lending.engine;

import com.corebanking.calc.ScheduleGenerator.Instalment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A change to the terms of a live loan (P2-3, P2-6): rate, tenure, EMI, due date or maturity date. The engine rebuilds the future
 * schedule from the current outstanding; demands already raised are never touched and interest accrued but not yet
 * demanded is carried into the next instalment, so no interest is lost or counted twice.
 *
 * <p>Rate resets follow RBI's circular of 18-Aug-2023 on reset of floating interest rates on EMI-based personal
 * loans: the borrower chooses to keep the EMI and change the tenure, keep the tenure and change the EMI, or change
 * both. A tenure extension may not take the loan beyond the product's maximum tenure, and the EMI must always cover
 * the monthly interest (no negative amortisation).
 *
 * @param newRatePercent       RATE_CHANGE: the new annual rate
 * @param rateOption           RATE_CHANGE: the borrower's choice
 * @param remainingInstalments TENURE_CHANGE, or RATE_CHANGE with CHANGE_BOTH: instalments still to be demanded
 * @param newEmi               EMI_CHANGE, or RATE_CHANGE with CHANGE_BOTH (instead of remainingInstalments)
 * @param newDueDay            DUE_DAY_CHANGE: day of month 1..31 (beyond the month's length means its last day)
 * @param maxTenureMonths      product maximum tenure, counted from disbursal (instalments raised + remaining)
 * @param newMaturityDate      MATURITY_CHANGE: the last instalment falls due in this date's month (on the loan's due
 *                             day); the EMI is recomputed, exactly as a tenure change to that many instalments
 */
public record Amendment(Kind kind, BigDecimal newRatePercent, RateResetOption rateOption, Integer remainingInstalments,
                        BigDecimal newEmi, Integer newDueDay, Integer maxTenureMonths, String reason, LocalDate newMaturityDate) {

    public enum Kind { RATE_CHANGE, TENURE_CHANGE, EMI_CHANGE, DUE_DAY_CHANGE, MATURITY_CHANGE }

    /** RBI 18-Aug-2023: the options offered to the borrower on a rate reset. */
    public enum RateResetOption { KEEP_EMI_CHANGE_TENURE, KEEP_TENURE_CHANGE_EMI, CHANGE_BOTH }

    public Amendment {
        Objects.requireNonNull(kind, "kind");
        if (maxTenureMonths != null && (maxTenureMonths < 1 || maxTenureMonths > 480)) {
            throw new IllegalArgumentException("maxTenureMonths must be 1..480");
        }
        switch (kind) {
            case RATE_CHANGE -> {
                if (newRatePercent == null) throw new IllegalArgumentException("newRatePercent is required for a rate change");
                if (newRatePercent.signum() < 0 || newRatePercent.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new IllegalArgumentException("newRatePercent must be 0..100");
                }
                if (rateOption == null) throw new IllegalArgumentException("rateOption is required for a rate change");
                if (rateOption == RateResetOption.CHANGE_BOTH && (remainingInstalments == null) == (newEmi == null)) {
                    throw new IllegalArgumentException("CHANGE_BOTH needs either remainingInstalments or newEmi");
                }
            }
            case TENURE_CHANGE -> {
                if (remainingInstalments == null) throw new IllegalArgumentException("remainingInstalments is required for a tenure change");
            }
            case EMI_CHANGE -> {
                if (newEmi == null) throw new IllegalArgumentException("newEmi is required for an EMI change");
            }
            case DUE_DAY_CHANGE -> {
                if (newDueDay == null || newDueDay < 1 || newDueDay > 31) throw new IllegalArgumentException("newDueDay must be 1..31");
            }
            case MATURITY_CHANGE -> {
                if (newMaturityDate == null) throw new IllegalArgumentException("newMaturityDate is required for a maturity change");
            }
        }
        if (remainingInstalments != null && (remainingInstalments < 1 || remainingInstalments > 480)) {
            throw new IllegalArgumentException("remainingInstalments must be 1..480");
        }
        if (newEmi != null && newEmi.signum() <= 0) throw new IllegalArgumentException("newEmi must be positive");
    }

    /** As before P2-6: no maturity date. */
    public Amendment(Kind kind, BigDecimal newRatePercent, RateResetOption rateOption, Integer remainingInstalments,
                     BigDecimal newEmi, Integer newDueDay, Integer maxTenureMonths, String reason) {
        this(kind, newRatePercent, rateOption, remainingInstalments, newEmi, newDueDay, maxTenureMonths, reason, null);
    }

    public static Amendment maturity(LocalDate newMaturityDate, Integer maxTenureMonths) {
        return new Amendment(Kind.MATURITY_CHANGE, null, null, null, null, null, maxTenureMonths, null, newMaturityDate);
    }

    public static Amendment rate(BigDecimal newRate, RateResetOption option, Integer maxTenureMonths) {
        return new Amendment(Kind.RATE_CHANGE, newRate, option, null, null, null, maxTenureMonths, null);
    }

    public static Amendment tenure(int remainingInstalments, Integer maxTenureMonths) {
        return new Amendment(Kind.TENURE_CHANGE, null, null, remainingInstalments, null, null, maxTenureMonths, null);
    }

    public static Amendment emi(BigDecimal newEmi, Integer maxTenureMonths) {
        return new Amendment(Kind.EMI_CHANGE, null, null, null, newEmi, null, maxTenureMonths, null);
    }

    public static Amendment dueDay(int day, Integer maxTenureMonths) {
        return new Amendment(Kind.DUE_DAY_CHANGE, null, null, null, null, day, maxTenureMonths, null);
    }

    /**
     * Before/after figures of an amendment. The same computation previews and applies (ADR-006).
     *
     * @param interestBefore        interest still to be demanded under the current schedule (incl. accrued)
     * @param interestAfter         the same under the new schedule
     * @param brokenPeriodInterest  DUE_DAY_CHANGE: interest for the days between the old and new due date, added to
     *                              the next instalment
     */
    public record Effect(Kind kind, BigDecimal principal, BigDecimal accruedCarried, BigDecimal rateBefore, BigDecimal rateAfter,
                         BigDecimal emiBefore, BigDecimal emiAfter, int remainingBefore, int remainingAfter,
                         LocalDate nextDueBefore, LocalDate nextDueAfter, LocalDate maturityBefore, LocalDate maturityAfter,
                         BigDecimal interestBefore, BigDecimal interestAfter, BigDecimal brokenPeriodInterest,
                         List<Instalment> scheduleBefore, List<Instalment> scheduleAfter) {

        /** True when the figures a checker saw differ from these (state changed between proposal and approval). */
        public boolean differsMateriallyFrom(Effect other) {
            return other == null || emiAfter.compareTo(other.emiAfter()) != 0 || remainingAfter != other.remainingAfter()
                    || rateAfter.compareTo(other.rateAfter()) != 0 || !maturityAfter.equals(other.maturityAfter())
                    || interestAfter.subtract(other.interestAfter()).abs().compareTo(BigDecimal.ONE) > 0;
        }
    }
}
