package com.corebanking.kernel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Role amount limits per transaction type (US-021).
 * <ul>
 *   <li>A limit belongs to a role and a transaction type and holds a per-transaction maximum and, optionally, a
 *       cumulative maximum per business day; it applies between its effective dates.</li>
 *   <li>A user with several roles gets the most permissive one: the transaction is allowed when at least one of
 *       the user's roles allows it (amount within that role's per-transaction maximum, and what the user has
 *       already put through today plus this amount within that role's per-day maximum).</li>
 *   <li>When none of the user's roles has a limit for the transaction type there is no limit, unless the tenant
 *       runs with {@code limits.default-deny}: then the transaction is refused.</li>
 * </ul>
 * The same check serves makers (what they may propose or post) and checkers (what they may approve), each with
 * their own usage total.
 */
public final class AmountLimits {

    /** Transaction types a limit can be set for. */
    public static final Set<String> TYPES = Set.of("LOAN_DISBURSEMENT", "LOAN_REPAYMENT", "LOAN_WAIVER", "VOUCHER",
            "LOAN_PRECLOSURE", "FEE_WAIVER");

    public record Limit(String role, String txnType, BigDecimal perTxnMax, BigDecimal perDayMax, LocalDate effectiveFrom,
                        LocalDate effectiveTo) {
        public Limit {
            Objects.requireNonNull(role);
            Objects.requireNonNull(txnType);
            Objects.requireNonNull(perTxnMax);
            Objects.requireNonNull(effectiveFrom);
            if (perTxnMax.signum() < 0) throw new IllegalArgumentException("perTxnMax < 0");
            if (perDayMax != null && perDayMax.compareTo(perTxnMax) < 0) throw new IllegalArgumentException("perDayMax < perTxnMax");
            if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) throw new IllegalArgumentException("effectiveTo < effectiveFrom");
        }

        boolean appliesOn(LocalDate day) {
            return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
        }

        boolean allows(BigDecimal amount, BigDecimal usedToday) {
            return amount.compareTo(perTxnMax) <= 0 && (perDayMax == null || usedToday.add(amount).compareTo(perDayMax) <= 0);
        }
    }

    public enum Reason { NO_LIMIT_SET, WITHIN_LIMIT, NO_AMOUNT, DEFAULT_DENY, PER_TRANSACTION_EXCEEDED, PER_DAY_EXCEEDED }

    /**
     * @param perTxnMax highest per-transaction maximum among the user's roles (null when no limit is set)
     * @param perDayMax highest per-day maximum among the user's roles; null when no limit is set or a role has no
     *                  per-day maximum
     */
    public record Decision(boolean allowed, Reason reason, BigDecimal perTxnMax, BigDecimal perDayMax) {}

    private final List<Limit> limits;
    private final boolean defaultDeny;

    public AmountLimits(Collection<Limit> limits, boolean defaultDeny) {
        this.limits = List.copyOf(limits);
        this.defaultDeny = defaultDeny;
    }

    /**
     * @param roles     the user's roles (exact names)
     * @param txnType   one of {@link #TYPES}
     * @param amount    the transaction amount; null means the transaction carries no amount and is not limited
     * @param usedToday what the user has already put through today for this type at this stage
     * @param day       the business date
     */
    public Decision check(Collection<String> roles, String txnType, BigDecimal amount, BigDecimal usedToday, LocalDate day) {
        if (amount == null) return new Decision(true, Reason.NO_AMOUNT, null, null);
        BigDecimal used = usedToday == null ? BigDecimal.ZERO : usedToday;
        List<Limit> mine = limits.stream()
                .filter(l -> l.txnType().equals(txnType) && roles.contains(l.role()) && l.appliesOn(day)).toList();
        if (mine.isEmpty()) {
            return defaultDeny ? new Decision(false, Reason.DEFAULT_DENY, null, null)
                    : new Decision(true, Reason.NO_LIMIT_SET, null, null);
        }
        BigDecimal bestTxn = mine.stream().map(Limit::perTxnMax).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal bestDay = mine.stream().anyMatch(l -> l.perDayMax() == null) ? null
                : mine.stream().map(Limit::perDayMax).max(BigDecimal::compareTo).orElseThrow();
        if (mine.stream().anyMatch(l -> l.allows(amount, used))) return new Decision(true, Reason.WITHIN_LIMIT, bestTxn, bestDay);
        Reason why = amount.compareTo(bestTxn) > 0 ? Reason.PER_TRANSACTION_EXCEEDED : Reason.PER_DAY_EXCEEDED;
        return new Decision(false, why, bestTxn, bestDay);
    }

    /** A sentence for the user who was refused. {@code stage} is "post", "propose" or "approve". */
    public static String message(Decision d, String txnType, BigDecimal amount, BigDecimal usedToday, String stage) {
        String type = txnType.toLowerCase(Locale.ROOT).replace('_', ' ');
        return switch (d.reason()) {
            case DEFAULT_DENY -> "no amount limit is set for your roles for " + type + ", so you cannot " + stage + " it";
            case PER_TRANSACTION_EXCEEDED -> "the amount " + plain(amount) + " is above your " + type + " limit of "
                    + plain(d.perTxnMax()) + " per transaction; a user with a higher limit must " + stage + " it";
            case PER_DAY_EXCEEDED -> "the amount " + plain(amount) + " would take today's " + type + " total to "
                    + plain((usedToday == null ? BigDecimal.ZERO : usedToday).add(amount)) + ", above your daily limit"
                    + (d.perDayMax() == null ? "" : " of " + plain(d.perDayMax()));
            case NO_LIMIT_SET, WITHIN_LIMIT, NO_AMOUNT -> "within limit";
        };
    }

    private static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
    }
}
