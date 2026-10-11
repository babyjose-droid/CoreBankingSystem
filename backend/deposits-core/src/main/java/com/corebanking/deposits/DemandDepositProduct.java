package com.corebanking.deposits;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A savings or current account product as the engine sees it, and the check that decides whether it may be
 * approved. Only an institution whose rule set allows deposits repayable on demand may have one.
 */
public final class DemandDepositProduct {

    public enum Type { SAVINGS, CURRENT }

    /**
     * @param minBalance            balance the account is expected to keep; 0 for none. The charge for a shortfall
     *                              is a product charge, not part of this check
     * @param bands                 interest bands by balance; empty for an account that bears no interest
     * @param interestPostingMonths months between two interest credits: 1, 3, 6 or 12; 0 when there is no interest
     */
    public record Settings(Type type, BigDecimal minBalance, List<SavingsInterest.Band> bands, SavingsInterest.BandMode bandMode,
                           DayCount dayCount, int interestPostingMonths, Rounding rounding) {

        public Settings {
            bands = bands == null ? List.of() : List.copyOf(bands);
        }

        public boolean bearsInterest() { return !bands.isEmpty(); }

        /** The interest calculation of this product; only for a product that bears interest. */
        public SavingsInterest interest() { return new SavingsInterest(bands, bandMode, dayCount); }
    }

    private static final Set<Integer> POSTING = Set.of(1, 3, 6, 12);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private DemandDepositProduct() {}

    /** Why the product cannot be approved under this rule set; empty when it can. */
    public static List<String> problems(DepositRules rules, Settings s) {
        Set<String> out = new LinkedHashSet<>();
        for (DepositRules.Violation v : rules.checkDemandDeposit()) out.add(v.message());
        if (s.type() == null) out.add("the product type is required");
        if (s.minBalance() == null || s.minBalance().signum() < 0) out.add("the minimum balance is required (0 for none)");
        if (s.rounding() == null) out.add("the rounding is required");
        if (s.type() == Type.SAVINGS && !s.bearsInterest()) out.add("a savings product needs at least one interest band");
        if (!s.bearsInterest()) {
            if (s.interestPostingMonths() != 0) out.add("an account that bears no interest has no interest posting frequency");
            return new ArrayList<>(out);
        }
        if (s.type() == Type.CURRENT && !rules.flag(DepositRules.CURRENT_INTEREST_ALLOWED, true)) {
            out.add("a current account bears no interest; remove the interest bands");
        }
        if (!POSTING.contains(s.interestPostingMonths())) out.add("interest is credited every 1, 3, 6 or 12 months");
        boolean bandsOk = true;
        for (SavingsInterest.Band b : s.bands()) {
            if (b == null || b.fromBalance() == null || b.ratePercent() == null) {
                out.add("every interest band needs a starting balance and a rate");
                bandsOk = false;
            } else if (b.fromBalance().signum() < 0 || b.ratePercent().signum() < 0 || b.ratePercent().compareTo(HUNDRED) > 0) {
                out.add("an interest band starts at 0 or above and its rate is from 0 to 100");
                bandsOk = false;
            }
        }
        if (s.bandMode() == null) {
            out.add("say whether the whole balance earns the rate of its band or each part its own (WHOLE_BALANCE or INCREMENTAL)");
            bandsOk = false;
        }
        if (s.dayCount() == null) {
            out.add("the day count is required");
            bandsOk = false;
        }
        if (bandsOk) {
            try {
                for (DepositRules.Violation v : s.interest().check(rules)) out.add(v.message());
            } catch (IllegalArgumentException e) {
                out.add(e.getMessage());
            }
        }
        return new ArrayList<>(out);
    }
}
