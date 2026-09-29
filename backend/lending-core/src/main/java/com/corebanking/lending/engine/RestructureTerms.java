package com.corebanking.lending.engine;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Terms of a restructuring (P2-3) under RBI's Prudential Framework for Resolution of Stressed Assets (7-Jun-2019).
 * Overdue principal is always rescheduled into the new schedule; overdue interest is either capitalised into
 * principal or kept as arrears the borrower still owes. (A separate funded-interest term loan, FITL, is not
 * modelled: capitalisation covers the same economics on one schedule.)
 *
 * @param newRatePercent            new annual rate; null keeps the current rate
 * @param remainingInstalments      instalments in the new schedule, moratorium included
 * @param principalMoratoriumMonths leading instalments that carry interest only
 * @param maxTenureMonths           product maximum tenure from disbursal; null for the engine limit (480)
 */
public record RestructureTerms(BigDecimal newRatePercent, int remainingInstalments, int principalMoratoriumMonths,
                               OverdueInterest overdueInterest, Integer maxTenureMonths, String reason) {

    public enum OverdueInterest { CAPITALISE, KEEP_AS_ARREARS }

    public RestructureTerms {
        Objects.requireNonNull(overdueInterest, "overdueInterest");
        if (newRatePercent != null && (newRatePercent.signum() < 0 || newRatePercent.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException("newRatePercent must be 0..100");
        }
        if (remainingInstalments < 1 || remainingInstalments > 480) throw new IllegalArgumentException("remainingInstalments must be 1..480");
        if (principalMoratoriumMonths < 0 || principalMoratoriumMonths >= remainingInstalments) {
            throw new IllegalArgumentException("the principal moratorium must be shorter than the new tenure");
        }
        if (maxTenureMonths != null && (maxTenureMonths < 1 || maxTenureMonths > 480)) {
            throw new IllegalArgumentException("maxTenureMonths must be 1..480");
        }
    }
}
