package com.corebanking.deposits;

import java.util.ArrayList;
import java.util.List;

/**
 * Who holds a deposit account and how it is operated. A deposit may be in one name or in joint names, with or
 * without a survivorship clause (for a deposit-taking NBFC: RBI Directions of 28-Nov-2025, para 27, "with or
 * without any of the clauses").
 */
public final class AccountHolders {

    public enum OperatingMode {
        /** One holder, who operates the account. */
        SINGLE(1, 1),
        /** Two holders; either may operate, and the balance goes to the survivor. */
        EITHER_OR_SURVIVOR(2, 2),
        /** Two holders; the first-named operates while alive ("Number One or Survivor"). */
        FORMER_OR_SURVIVOR(2, 2),
        /** Two or more holders; any one may operate, and the balance goes to the survivors. */
        ANYONE_OR_SURVIVOR(2, MAX_HOLDERS_VALUE),
        /** Two or more holders who operate together; no survivorship clause. */
        JOINTLY(2, MAX_HOLDERS_VALUE);

        private final int min;
        private final int max;

        OperatingMode(int min, int max) {
            this.min = min;
            this.max = max;
        }

        public int minHolders() { return min; }
        public int maxHolders() { return max; }
    }

    private static final int MAX_HOLDERS_VALUE = 4;

    /** The most holders one account can have. A product choice, not a regulatory figure. */
    public static final int MAX_HOLDERS = MAX_HOLDERS_VALUE;

    public static final String RULE = "operating-mode";

    private AccountHolders() {}

    /** Why this number of holders does not go with the operating mode; empty when it does. */
    public static List<DepositRules.Violation> check(OperatingMode mode, int holders) {
        List<DepositRules.Violation> out = new ArrayList<>();
        if (mode == null) {
            out.add(new DepositRules.Violation(RULE, "the operating mode is required"));
            return out;
        }
        if (holders < 1) {
            out.add(new DepositRules.Violation(RULE, "an account needs at least one holder"));
        } else if (holders < mode.minHolders() || holders > mode.maxHolders()) {
            String need = mode.minHolders() == mode.maxHolders()
                    ? (mode.minHolders() == 1 ? "exactly one holder" : "exactly " + mode.minHolders() + " holders")
                    : "from " + mode.minHolders() + " to " + mode.maxHolders() + " holders";
            out.add(new DepositRules.Violation(RULE, "operating mode " + mode + " needs " + need + "; " + holders + " named"));
        }
        return out;
    }
}
