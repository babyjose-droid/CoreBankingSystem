package com.corebanking.lending.engine;

/** Repayment methods supported in Phase 2 (the reference system lists 18; the remaining are variants of these). */
public enum RepaymentMethod {
    /** Equal monthly instalments (PMT); optional moratorium and balloon. */
    EQUATED,
    /** Equal principal each month plus interest on the declining balance. */
    FIXED_PRINCIPAL,
    /** Principal and all interest in one payment at maturity. */
    BULLET_TOTAL_INTEREST,
    /** Interest every month, principal at maturity. */
    BULLET_PERIODIC_INTEREST
}
