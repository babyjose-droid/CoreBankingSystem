package com.corebanking.deposits;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A term deposit product as the engine sees it, and the check that decides whether it may be approved. The check
 * reports every problem at once. Its purpose is that a booking the product allows is never refused later: every
 * amount and tenure the product accepts has a rate, and no rate it can produce breaks the rule set.
 */
public final class TermDepositProduct {

    public enum Type {
        /** Interest is added to the deposit at each rest and paid with the principal at maturity. */
        FD_CUMULATIVE,
        /** Interest is paid out at each rest, or once at maturity. */
        FD_PAYOUT
    }

    /**
     * @param maxAmount               largest deposit accepted; null for no limit
     * @param interestPeriodMonths    months between two interest credits: 1, 3, 6 or 12; 0 (payout products only)
     *                                for interest once, at maturity
     * @param prematurePenaltyPercent points taken off the rate for the period run when the deposit is closed early.
     *                                Not set where the rule set fixes the rate (a deposit-taking NBFC)
     * @param prematureMinDays        no interest when the deposit ran for fewer days. Not set where the rule set
     *                                fixes the rate
     * @param tdsApplicable           false: no tax is deducted, and no preview or schedule shows any
     */
    public record Settings(Type type, BigDecimal minAmount, BigDecimal maxAmount, Tenure minTenure, Tenure maxTenure,
                           int interestPeriodMonths, DayCount dayCount, Rounding rounding,
                           List<DepositRateTable.Cell> rates, List<DepositRateTable.AddOn> addOns,
                           boolean prematureAllowed, BigDecimal prematurePenaltyPercent, Integer prematureMinDays,
                           boolean tdsApplicable) {

        public Settings {
            rates = rates == null ? List.of() : List.copyOf(rates);
            addOns = addOns == null ? List.of() : List.copyOf(addOns);
        }

        public boolean cumulative() { return type == Type.FD_CUMULATIVE; }

        public DepositRateTable rateTable() { return new DepositRateTable(rates, addOns); }
    }

    private static final Set<Integer> RESTS = Set.of(1, 3, 6, 12);
    private static final Set<DayCount> DAY_COUNTS = Set.of(DayCount.ACTUAL_365, DayCount.ACTUAL_360, DayCount.ACTUAL_ACTUAL);

    private TermDepositProduct() {}

    /** Why the product cannot be approved under this rule set; empty when it can. */
    public static List<String> problems(DepositRules rules, Settings s) {
        Set<String> out = new LinkedHashSet<>();
        if (s.type() == null) out.add("the product type is required");
        boolean amountsOk = s.minAmount() != null && s.minAmount().signum() > 0;
        if (!amountsOk) out.add("the smallest deposit must be above zero");
        if (amountsOk && s.maxAmount() != null && s.maxAmount().compareTo(s.minAmount()) < 0) {
            out.add("the largest deposit is below the smallest");
            amountsOk = false;
        }
        boolean tenuresOk = s.minTenure() != null && s.maxTenure() != null && s.minTenure().compareTo(Tenure.ofDays(0)) > 0;
        if (!tenuresOk) out.add("the shortest and the longest tenure are required, and the shortest must be above zero");
        if (tenuresOk && s.minTenure().compareTo(s.maxTenure()) > 0) {
            out.add("the shortest tenure " + s.minTenure() + " is longer than the longest " + s.maxTenure());
            tenuresOk = false;
        }
        boolean restOk = RESTS.contains(s.interestPeriodMonths()) || (s.interestPeriodMonths() == 0 && s.type() == Type.FD_PAYOUT);
        if (!restOk) {
            out.add(s.type() == Type.FD_PAYOUT
                    ? "interest is paid every 1, 3, 6 or 12 months, or once at maturity (0)"
                    : "interest is compounded every 1, 3, 6 or 12 months");
        }
        if (s.dayCount() == null || !DAY_COUNTS.contains(s.dayCount())) out.add("the day count must be ACTUAL_365, ACTUAL_360 or ACTUAL_ACTUAL");
        if (s.rounding() == null) out.add("the rounding is required");

        DepositRateTable table = null;
        try {
            for (DepositRateTable.Cell c : s.rates()) {
                if (c == null || c.fromAmount() == null || c.fromTenure() == null || c.ratePercent() == null) {
                    throw new IllegalArgumentException("every row of the rate table needs an amount, a tenure and a rate");
                }
            }
            table = s.rateTable();
        } catch (IllegalArgumentException | NullPointerException e) {
            out.add(e.getMessage() == null ? "the rate table is not complete: every row needs an amount, a tenure and a rate" : e.getMessage());
        }
        if (table != null && amountsOk && tenuresOk) {
            out.addAll(table.coverageProblems(s.minAmount(), s.minTenure()));
            for (DepositRateTable.Cell c : s.rates()) {
                if (c.fromTenure().compareTo(s.maxTenure()) > 0) {
                    out.add("the rate from " + c.fromTenure() + " can never apply: the longest tenure is " + s.maxTenure());
                }
                if (s.maxAmount() != null && c.fromAmount().compareTo(s.maxAmount()) > 0) {
                    out.add("the rate from amount " + plain(c.fromAmount()) + " can never apply: the largest deposit is " + plain(s.maxAmount()));
                }
            }
        }
        if (!s.rates().isEmpty() && table != null) rateProblems(rules, s, out);
        if (tenuresOk && restOk) {
            for (Tenure t : List.of(s.minTenure(), s.maxTenure())) {
                for (DepositRules.Violation v : rules.checkTermDeposit(t, BigDecimal.ZERO, s.interestPeriodMonths(), 0)) out.add(v.message());
            }
        }
        prematureProblems(rules, s, amountsOk, out);
        return new ArrayList<>(out);
    }

    /** The highest and the lowest rate the card can produce, with every add-on that raises or lowers it. */
    private static void rateProblems(DepositRules rules, Settings s, Set<String> out) {
        BigDecimal highest = s.rates().stream().map(DepositRateTable.Cell::ratePercent).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal lowest = s.rates().stream().map(DepositRateTable.Cell::ratePercent).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal up = BigDecimal.ZERO;
        BigDecimal down = BigDecimal.ZERO;
        for (DepositRateTable.AddOn a : s.addOns()) {
            if (a.code() == null || !a.code().matches("[A-Z][A-Z0-9_]{1,29}")) {
                out.add("an add-on code is 2 to 30 capitals, digits or underscores, starting with a letter");
            }
            if (a.percent() == null || a.percent().signum() == 0) {
                out.add("add-on " + a.code() + ": the percentage is required and cannot be zero");
            } else if (a.percent().signum() > 0) {
                up = up.add(a.percent());
            } else {
                down = down.add(a.percent());
            }
        }
        BigDecimal top = highest.add(up);
        BigDecimal ceiling = rules.number(DepositRules.TD_MAX_RATE).orElse(null);
        if (ceiling != null && top.compareTo(ceiling) > 0) {
            out.add(up.signum() == 0
                    ? "the highest rate " + plain(highest) + "% is above the ceiling of " + plain(ceiling) + "%"
                    : "the highest rate " + plain(highest) + "% with every add-on (" + plain(up) + "%) is " + plain(top)
                            + "%, above the ceiling of " + plain(ceiling) + "%");
        }
        if (lowest.add(down).signum() < 0) {
            out.add("the lowest rate " + plain(lowest) + "% with every reduction (" + plain(down) + "%) is below zero");
        }
    }

    private static void prematureProblems(DepositRules rules, Settings s, boolean amountsOk, Set<String> out) {
        boolean fixedByRules = rules.number(DepositRules.TD_PREMATURE_RATE_CUT).isPresent();
        boolean given = s.prematurePenaltyPercent() != null || s.prematureMinDays() != null;
        if (fixedByRules) {
            if (given) {
                out.add("the interest on premature repayment is fixed by the rule set for this institution; leave the penalty"
                        + " and the minimum period empty");
            }
        } else if (s.prematureAllowed()) {
            if (s.prematurePenaltyPercent() == null || s.prematurePenaltyPercent().signum() < 0) {
                out.add("the premature-withdrawal penalty is required (0 for none)");
            }
            if (s.prematureMinDays() == null || s.prematureMinDays() < 0) {
                out.add("the minimum period before which no interest is paid is required (0 days for none)");
            }
        } else if (given) {
            out.add("the product does not allow premature withdrawal; leave the penalty and the minimum period empty");
        }
        BigDecimal mandatoryUpTo = rules.number(DepositRules.TD_PREMATURE_MANDATORY_UP_TO).orElse(null);
        if (mandatoryUpTo != null && !s.prematureAllowed() && amountsOk && s.minAmount().compareTo(mandatoryUpTo) <= 0) {
            out.add("term deposits of individuals up to " + plain(mandatoryUpTo) + " must allow premature withdrawal; this product"
                    + " accepts deposits from " + plain(s.minAmount()));
        }
    }

    static String plain(BigDecimal v) {
        return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
