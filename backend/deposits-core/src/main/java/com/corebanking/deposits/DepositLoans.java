package com.corebanking.deposits;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A loan against term deposits (P4-5b): whether the deposits pledged can secure the loan. The deposit-taking NBFC's
 * rule set fixes it (RBI NBFC Public Deposits Directions para 36(2): at most 75% of the deposits, at least 2 points
 * above the deposit's rate, and none in the three months after the deposit is taken); a bank lends on its own policy,
 * which the tenant sets ({@code policyMaxPercent}, {@code policySpreadPercent}).
 */
public final class DepositLoans {

    /** A deposit pledged (or to be pledged): its value today (the balance), its rate, when it started and matures. */
    public record Pledged(String accountNo, BigDecimal value, BigDecimal ratePercent, LocalDate start, LocalDate maturity) {}

    /** The loan: the amount sanctioned, its rate and the date its last instalment falls due. */
    public record Loan(String loanNo, BigDecimal sanctioned, BigDecimal ratePercent, LocalDate maturity) {}

    /** The terms that apply. {@code lockInMonths} 0 when none. */
    public record Terms(BigDecimal maxPercent, BigDecimal spreadPercent, int lockInMonths) {}

    private DepositLoans() {}

    public static Terms terms(DepositRules rules, BigDecimal policyMaxPercent, BigDecimal policySpreadPercent) {
        return new Terms(rules.number(DepositRules.TD_LOAN_MAX_PERCENT).orElse(policyMaxPercent),
                rules.number(DepositRules.TD_LOAN_SPREAD).orElse(policySpreadPercent),
                rules.whole(DepositRules.TD_LOCK_IN_MONTHS).orElse(0));
    }

    /** The most that may be lent against the deposits, to the rupee below. */
    public static BigDecimal maxLoan(Terms t, List<Pledged> deposits) {
        BigDecimal value = deposits.stream().map(Pledged::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        return value.multiply(t.maxPercent()).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
    }

    /** The lowest rate the loan may carry: the highest deposit rate plus the spread. */
    public static BigDecimal minRate(Terms t, List<Pledged> deposits) {
        return deposits.stream().map(Pledged::ratePercent).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO).add(t.spreadPercent());
    }

    /** Why the deposits cannot secure the loan on {@code today}; empty when they can. */
    public static List<String> problems(Terms t, Loan loan, List<Pledged> deposits, LocalDate today) {
        List<String> out = new ArrayList<>();
        if (deposits.isEmpty()) {
            out.add("no deposit is pledged");
            return out;
        }
        for (Pledged d : deposits) {
            if (t.lockInMonths() > 0 && today.isBefore(d.start().plusMonths(t.lockInMonths()))) {
                out.add("deposit " + d.accountNo() + " started on " + d.start() + ": no loan against it within " + t.lockInMonths()
                        + " months of the deposit (until " + d.start().plusMonths(t.lockInMonths()) + ")");
            }
            if (loan.maturity() != null && d.maturity() != null && loan.maturity().isAfter(d.maturity())) {
                out.add("loan " + loan.loanNo() + " runs to " + loan.maturity() + ", after deposit " + d.accountNo() + " matures on " + d.maturity());
            }
        }
        BigDecimal max = maxLoan(t, deposits);
        if (loan.sanctioned().compareTo(max) > 0) {
            out.add("loan " + loan.loanNo() + " of " + plain(loan.sanctioned()) + " is more than " + plain(t.maxPercent()) + "% of the deposits pledged ("
                    + plain(max) + ")");
        }
        BigDecimal min = minRate(t, deposits);
        if (loan.ratePercent().compareTo(min) < 0) {
            out.add("the loan's rate " + plain(loan.ratePercent()) + "% is below " + plain(min) + "% (the deposit's rate plus " + plain(t.spreadPercent())
                    + " points)");
        }
        return out;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
