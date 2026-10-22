package com.corebanking.deposits;

import java.math.BigDecimal;
import java.util.List;

/**
 * A deposit-taking NBFC holds its public deposits to a multiple of its net owned fund (1.5: RBI NBFC Public Deposits
 * Directions para 17). Checked when a deposit is accepted: a funding and a recurring deposit's instalment (P4-6).
 */
public final class NetOwnedFund {

    private NetOwnedFund() {}

    /** The most the institution may hold. */
    public static BigDecimal cap(BigDecimal multiple, BigDecimal nof) {
        return nof.multiply(multiple);
    }

    /** What may still be accepted: the cap less what is held, never below zero. */
    public static BigDecimal headroom(BigDecimal multiple, BigDecimal nof, BigDecimal outstanding) {
        return cap(multiple, nof).subtract(outstanding).max(BigDecimal.ZERO);
    }

    /**
     * Why {@code adding} cannot be accepted. {@code nof} null: none is recorded.
     *
     * @param multiple null when the rule set has no cap (a bank): nothing to check
     */
    public static List<String> problems(BigDecimal multiple, BigDecimal nof, BigDecimal outstanding, BigDecimal adding) {
        if (multiple == null) return List.of();
        if (nof == null) return List.of("the net owned fund is not recorded: public deposits are held to " + plain(multiple) + " times it");
        BigDecimal cap = cap(multiple, nof);
        if (outstanding.add(adding).compareTo(cap) > 0) {
            return List.of("public deposits would be " + plain(outstanding.add(adding)) + ", above " + plain(multiple) + " times the net owned fund of "
                    + plain(nof) + " (" + plain(cap) + "); " + plain(headroom(multiple, nof, outstanding)) + " may still be accepted");
        }
        return List.of();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
