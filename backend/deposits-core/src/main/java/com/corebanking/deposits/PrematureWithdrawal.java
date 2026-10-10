package com.corebanking.deposits;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * What a term deposit pays when it is closed before maturity. The quote shown to the customer and the entry
 * posted come from this one function.
 *
 * <p>Deposit-taking NBFC (RBI Directions of 28-Nov-2025): nothing is repaid in the first three months except on
 * death or, for an individual, for an emergency expense (para 35, 37); from three to six months no interest; after
 * six months the rate for the period the deposit has run less 2 points, or the lowest rate the NBFC offers less 3
 * points when it has no rate for that period (para 40). Bank: the rate for the period the deposit has run, not
 * above the contracted rate, less the product's penalty; nothing below the product's minimum period.
 */
public final class PrematureWithdrawal {

    public enum Reason { DEPOSITOR_REQUEST, DEATH, EMERGENCY_EXPENSE, CRITICAL_ILLNESS }

    /**
     * @param individual             the depositor is an individual (the emergency rules are for individuals)
     * @param depositorTotalDeposits all deposits of the sole or first-named depositor with the institution, this
     *                               one included: decides whether it is a "tiny deposit"
     * @param rateForPeriodRun       the rate the institution offered, when this deposit was booked, for a deposit
     *                               of the period actually run, add-ons included; null when it had none
     * @param minimumCardRate        the lowest rate it offered then (used only when rateForPeriodRun is null)
     * @param penaltyPercent         bank product setting: points taken off the rate
     * @param minimumDays            bank product setting: no interest when the deposit ran for fewer days
     * @param penaltyWaivedOnDeath   bank product setting
     * @param interestPaidOut        interest already paid out to the depositor (0 for a cumulative deposit)
     */
    public record Request(TermDeposit.Terms terms, LocalDate on, Reason reason, boolean individual,
                          BigDecimal depositorTotalDeposits, BigDecimal rateForPeriodRun, BigDecimal minimumCardRate,
                          BigDecimal penaltyPercent, int minimumDays, boolean penaltyWaivedOnDeath,
                          BigDecimal interestPaidOut) {}

    /**
     * @param principalPayable    principal repaid now; less than the deposit when only part may be withdrawn
     * @param ratePercent         rate allowed for the period run
     * @param interestDue         interest for the period run at that rate, on the principal repaid
     * @param interestRecoverable interest already paid out above what is due; taken from the proceeds
     * @param netPayable          principalPayable + interestDue - interest already paid on that principal
     * @param basis               one sentence saying which rule gave the figures
     */
    public record Quote(boolean allowed, String refusal, BigDecimal principalPayable, BigDecimal principalRemaining,
                        BigDecimal ratePercent, BigDecimal interestDue, BigDecimal interestRecoverable,
                        BigDecimal netPayable, String basis) {}

    private PrematureWithdrawal() {}

    public static Quote quote(DepositRules rules, Request r) {
        TermDeposit.Terms t = r.terms();
        if (!r.on().isAfter(t.start())) return refuse("a deposit cannot be closed on or before its start date");
        if (!r.on().isBefore(t.maturity())) return refuse("the deposit has matured; pay it as a maturity");

        int lockIn = rules.whole(DepositRules.TD_LOCK_IN_MONTHS).orElse(0);
        boolean inLockIn = lockIn > 0 && r.on().isBefore(t.start().plusMonths(lockIn));
        if (inLockIn) return duringLockIn(rules, r, lockIn);

        BigDecimal rate;
        String basis;
        if (rules.number(DepositRules.TD_PREMATURE_RATE_CUT).isPresent()) {
            int noInterestBefore = rules.whole(DepositRules.TD_NO_INTEREST_BEFORE_MONTHS).orElse(0);
            if (r.on().isBefore(t.start().plusMonths(noInterestBefore))) {
                rate = BigDecimal.ZERO;
                basis = "closed before " + noInterestBefore + " months: no interest";
            } else if (r.rateForPeriodRun() != null) {
                BigDecimal cut = rules.required(DepositRules.TD_PREMATURE_RATE_CUT);
                rate = r.rateForPeriodRun().subtract(cut).max(BigDecimal.ZERO);
                basis = "rate for the period run " + plain(r.rateForPeriodRun()) + "% less " + plain(cut) + " points";
            } else {
                BigDecimal cut = rules.required(DepositRules.TD_PREMATURE_CUT_FROM_MINIMUM);
                rate = r.minimumCardRate().subtract(cut).max(BigDecimal.ZERO);
                basis = "no rate for the period run: lowest rate " + plain(r.minimumCardRate()) + "% less " + plain(cut) + " points";
            }
        } else {
            long days = java.time.temporal.ChronoUnit.DAYS.between(t.start(), r.on());
            if (days < r.minimumDays()) {
                rate = BigDecimal.ZERO;
                basis = "closed within " + r.minimumDays() + " days: no interest";
            } else {
                BigDecimal base = r.rateForPeriodRun() == null ? t.ratePercent() : r.rateForPeriodRun().min(t.ratePercent());
                boolean waived = r.reason() == Reason.DEATH && r.penaltyWaivedOnDeath();
                BigDecimal penalty = waived ? BigDecimal.ZERO : r.penaltyPercent();
                rate = base.subtract(penalty).max(BigDecimal.ZERO);
                basis = "rate for the period run " + plain(base) + "%"
                        + (waived ? ", penalty waived on death" : " less penalty " + plain(penalty) + " points");
            }
        }
        BigDecimal due = rate.signum() == 0 ? zero(t) : TermDeposit.totalInterest(t.endedOn(r.on(), rate));
        return settle(t, t.principal(), rate, due, r.interestPaidOut(), basis);
    }

    private static Quote duringLockIn(DepositRules rules, Request r, int lockIn) {
        TermDeposit.Terms t = r.terms();
        String within = "within " + lockIn + " months of the deposit";
        switch (r.reason()) {
            case DEATH:
                return settle(t, t.principal(), BigDecimal.ZERO, zero(t), r.interestPaidOut(),
                        "death of the depositor " + within + ": principal repaid, no interest");
            case CRITICAL_ILLNESS:
                if (!r.individual()) return refuse("repayment " + within + " for critical illness is for individual depositors only");
                return settle(t, t.principal(), BigDecimal.ZERO, zero(t), r.interestPaidOut(),
                        "critical illness " + within + ": whole principal, no interest");
            case EMERGENCY_EXPENSE:
                if (!r.individual()) return refuse("repayment " + within + " for an emergency is for individual depositors only");
                BigDecimal tinyMax = rules.required(DepositRules.TD_TINY_DEPOSIT_MAX);
                if (r.depositorTotalDeposits().compareTo(tinyMax) <= 0) {
                    return settle(t, t.principal(), BigDecimal.ZERO, zero(t), r.interestPaidOut(),
                            "tiny deposit (all deposits up to " + plain(tinyMax) + "): whole principal, no interest");
                }
                BigDecimal percent = rules.required(DepositRules.TD_EMERGENCY_MAX_PERCENT);
                BigDecimal cap = rules.required(DepositRules.TD_EMERGENCY_MAX_AMOUNT);
                BigDecimal part = t.principal().multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN).min(cap);
                return settle(t, part, BigDecimal.ZERO, zero(t), r.interestPaidOut(),
                        "emergency expense " + within + ": " + plain(percent) + "% of principal, at most " + plain(cap)
                                + ", no interest; the rest stays on deposit");
            default:
                return refuse("no repayment " + within + " except on death or, for an individual, an emergency expense or critical illness");
        }
    }

    private static Quote settle(TermDeposit.Terms t, BigDecimal principal, BigDecimal rate, BigDecimal due,
                                BigDecimal paidOut, String basis) {
        // Interest already paid out belongs to the principal repaid now in proportion.
        BigDecimal paidOnThis = principal.compareTo(t.principal()) == 0 ? paidOut
                : t.rounding().apply(paidOut.multiply(principal).divide(t.principal(), 10, RoundingMode.HALF_UP));
        BigDecimal recoverable = paidOnThis.subtract(due).max(BigDecimal.ZERO);
        return new Quote(true, null, principal, t.principal().subtract(principal), rate, due, recoverable,
                principal.add(due).subtract(paidOnThis), basis);
    }

    private static Quote refuse(String why) {
        return new Quote(false, why, null, null, null, null, null, null, why);
    }

    private static BigDecimal zero(TermDeposit.Terms t) {
        return t.rounding().apply(BigDecimal.ZERO);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
