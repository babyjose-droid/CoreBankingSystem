package com.corebanking.kernel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Maker-checker rules (US-022). A rule says how many distinct checkers an entity/action needs, optionally only
 * from a minimum amount upward. The most specific matching rule wins: exact entity + exact action + highest
 * amount threshold not above the request amount. With no matching rule, one checker is required — the safe
 * default for a regulated ledger. Zero checkers is allowed only when a rule says so explicitly.
 */
public final class ApprovalPolicy {

    public static final String ANY = "*";

    public record Rule(String entityType, String action, BigDecimal minAmount, int checkersRequired) {
        public Rule {
            Objects.requireNonNull(entityType);
            Objects.requireNonNull(action);
            if (checkersRequired < 0 || checkersRequired > 3) throw new IllegalArgumentException("checkers 0..3");
            if (minAmount != null && minAmount.signum() < 0) throw new IllegalArgumentException("minAmount < 0");
        }

        boolean matches(String entity, String act, BigDecimal amount) {
            if (!entityType.equals(ANY) && !entityType.equals(entity)) return false;
            if (!action.equals(ANY) && !action.equals(act)) return false;
            if (minAmount == null) return true;
            return amount != null && amount.compareTo(minAmount) >= 0;
        }

        int specificity() {
            return (entityType.equals(ANY) ? 0 : 4) + (action.equals(ANY) ? 0 : 2) + (minAmount == null ? 0 : 1);
        }
    }

    private final List<Rule> rules;

    public ApprovalPolicy(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public int checkersRequired(String entityType, String action, BigDecimal amount) {
        return rules.stream()
                .filter(r -> r.matches(entityType, action, amount))
                .max(Comparator.comparingInt(Rule::specificity)
                        .thenComparing(r -> r.minAmount() == null ? BigDecimal.ZERO : r.minAmount()))
                .map(Rule::checkersRequired)
                .orElse(1);
    }

    /** Outcome of one checker's approval. */
    public enum Outcome { APPLY, NEED_MORE_APPROVALS }

    /**
     * Validates a checker decision and says whether the change can now be applied.
     *
     * @param maker            who raised the request
     * @param priorApprovers   checkers who already approved this request
     * @param checker          who is approving now
     * @param checkersRequired from {@link #checkersRequired}
     */
    public static Outcome approve(String maker, Set<String> priorApprovers, String checker, int checkersRequired) {
        if (checker == null || checker.isBlank()) throw new ApprovalException("checker required");
        if (checker.equalsIgnoreCase(maker)) throw new ApprovalException("Maker cannot approve own request");
        for (String p : priorApprovers) {
            if (p.equalsIgnoreCase(checker)) throw new ApprovalException("Checker has already approved this request");
        }
        return priorApprovers.size() + 1 >= Math.max(1, checkersRequired) ? Outcome.APPLY : Outcome.NEED_MORE_APPROVALS;
    }

    public static void reject(String maker, String checker, String note) {
        if (checker.equalsIgnoreCase(maker)) throw new ApprovalException("Maker cannot reject own request; withdraw it instead");
        if (note == null || note.isBlank()) throw new ApprovalException("A reason is required to reject");
    }

    /** Default rules for a new tenant (starter kit). */
    public static List<Rule> starterRules() {
        List<Rule> r = new ArrayList<>();
        r.add(new Rule(ANY, ANY, null, 1));
        r.add(new Rule("VOUCHER", "CREATE", new BigDecimal("1000000"), 2));   // ≥ ₹10 lakh needs two checkers
        r.add(new Rule("VOUCHER", "REVERSE", null, 1));
        r.add(new Rule("GL_HEAD", ANY, null, 1));
        return r;
    }

    public static final class ApprovalException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ApprovalException(String m) { super(m); }
    }
}
