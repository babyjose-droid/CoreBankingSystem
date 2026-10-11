package com.corebanking.deposits;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The nomination on a deposit account. A bank account may name up to four nominees, either together with shares
 * that total 100% or one after another (Banking Laws (Amendment) Act, 2025); a deposit with an NBFC names one
 * person (RBI Directions of 28-Nov-2025, para 50). The limit is the rule {@link DepositRules#NOMINEES_MAX}.
 */
public final class Nomination {

    public enum Mode {
        /** No nominee named. */
        NONE,
        /** The nominees take together, each a share; one nominee takes 100%. */
        SIMULTANEOUS,
        /** The nominees take one after another, in the order named: the next only if the one before has died. */
        SUCCESSIVE
    }

    /**
     * @param sharePercent the nominee's share for {@link Mode#SIMULTANEOUS}; null for {@link Mode#SUCCESSIVE}
     * @param minor        the nominee is a minor; then {@code guardianName} is who receives on the minor's behalf
     */
    public record Nominee(String name, String relationship, BigDecimal sharePercent, boolean minor, String guardianName) {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Nomination() {}

    /** Everything wrong with a nomination, at once; empty when it can be recorded. */
    public static List<DepositRules.Violation> check(DepositRules rules, Mode mode, List<Nominee> nominees) {
        List<DepositRules.Violation> out = new ArrayList<>();
        List<Nominee> list = nominees == null ? List.of() : nominees;
        if (mode == null) {
            out.add(v("the nomination mode is required (NONE when no nominee is named)"));
            return out;
        }
        if (mode == Mode.NONE) {
            if (!list.isEmpty()) out.add(v("nominees are named but the nomination mode is NONE"));
            return out;
        }
        if (list.isEmpty()) {
            out.add(v("nomination mode " + mode + " needs at least one nominee"));
            return out;
        }
        rules.whole(DepositRules.NOMINEES_MAX).ifPresent(max -> {
            if (list.size() > max) {
                out.add(new DepositRules.Violation(DepositRules.NOMINEES_MAX,
                        list.size() + " nominees named; at most " + max + " allowed"));
            }
        });
        if (mode == Mode.SUCCESSIVE && list.size() < 2) {
            out.add(v("successive nomination needs two nominees or more; name one nominee as SIMULTANEOUS with 100%"));
        }
        BigDecimal total = BigDecimal.ZERO;
        boolean sharesComplete = true;
        for (int i = 0; i < list.size(); i++) {
            Nominee n = list.get(i);
            String who = "nominee " + (i + 1);
            if (n == null) {
                out.add(v(who + " is empty"));
                sharesComplete = false;
                continue;
            }
            if (n.name() == null || n.name().isBlank()) out.add(v(who + ": the name is required"));
            if (n.relationship() == null || n.relationship().isBlank()) out.add(v(who + ": the relationship to the depositor is required"));
            if (n.minor() && (n.guardianName() == null || n.guardianName().isBlank())) {
                out.add(v(who + " is a minor: name the person who receives the amount on the minor's behalf"));
            }
            if (!n.minor() && n.guardianName() != null && !n.guardianName().isBlank()) {
                out.add(v(who + ": a guardian is named only for a minor"));
            }
            if (mode == Mode.SIMULTANEOUS) {
                if (n.sharePercent() == null) {
                    out.add(v(who + ": the share is required"));
                    sharesComplete = false;
                } else if (n.sharePercent().signum() <= 0 || n.sharePercent().compareTo(HUNDRED) > 0) {
                    out.add(v(who + ": the share must be above 0 and at most 100"));
                    sharesComplete = false;
                } else if (n.sharePercent().stripTrailingZeros().scale() > 2) {
                    out.add(v(who + ": the share has at most two decimal places"));
                    sharesComplete = false;
                } else {
                    total = total.add(n.sharePercent());
                }
            } else if (n.sharePercent() != null) {
                out.add(v(who + ": successive nominees have no shares; the order named decides"));
            }
        }
        if (mode == Mode.SIMULTANEOUS && sharesComplete && total.compareTo(HUNDRED) != 0) {
            out.add(v("nominee shares must total 100 percent; they total " + total.stripTrailingZeros().toPlainString()));
        }
        return out;
    }

    private static DepositRules.Violation v(String message) {
        return new DepositRules.Violation("nomination", message);
    }
}
