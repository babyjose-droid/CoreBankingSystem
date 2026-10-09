package com.corebanking.lending.engine;

/**
 * Repayment methods. The reference system lists 18; docs/phase2-status.md ("P2-6 product completion") maps each one
 * to a method here or says why it is not built. Every method works at every {@link Frequency}.
 */
public enum RepaymentMethod {
    /** Equal instalments (PMT); optional moratorium and balloon; flat-rate and given-instalment loans are equated. */
    EQUATED,
    /** Equated instalments that step up or down by a percentage every so many instalments (step EMI). */
    STEP_EQUATED,
    /**
     * Equal principal plus interest on the declining balance ("Periodic Fixed Principal And Accrued Interest"). With
     * {@code principalEvery} above 1, interest falls due every period and principal every n-th ("With Differing
     * Interval").
     */
    FIXED_PRINCIPAL,
    /** Principal and all interest in one payment at maturity. */
    BULLET_TOTAL_INTEREST,
    /** Interest every period, principal at maturity. */
    BULLET_PERIODIC_INTEREST,
    /**
     * Principal as assigned to each date by the lender ("Periodic Assigned Principal And Accrued Interest"; seasonal
     * and irregular schedules), with the interest accrued on the balance.
     */
    STRUCTURED,
    /**
     * Each tranche is repaid as its own bullet at its own maturity, the tranche date plus the loan's tenor (reference
     * method 18 "Tranche Bullet Tranche Repayment"). Interest falls due every period on the amount outstanding, or,
     * with {@code interestAtMaturity}, with each bullet.
     */
    TRANCHE_BULLET
}
