package com.corebanking.deposits;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Freezes and liens on a deposit account (P4-2), and whether money may move in or out of it.
 * <ul>
 *   <li>A <b>debit freeze</b> stops money going out, a <b>credit freeze</b> stops money coming in, a <b>total freeze</b>
 *       stops both. A freeze has no amount.</li>
 *   <li>A <b>lien</b> marks an amount of the balance that may not be paid out. The available balance is the balance less
 *       every lien. A lien is placed only within the available balance.</li>
 *   <li>No movement may take the balance below zero, nor below the liens.</li>
 *   <li>A <b>correction</b> (the reversal of a posted transaction) is not stopped by a freeze, which is there to stop the
 *       customer's own transactions; it still may not take the balance below zero or below the liens.</li>
 * </ul>
 * Every reason is reported at once. The database holds the same rules (tenant V30).
 */
public final class AccountHolds {

    public enum Kind {
        DEBIT_FREEZE, CREDIT_FREEZE, TOTAL_FREEZE, LIEN;

        public boolean isFreeze() { return this != LIEN; }

        boolean stops(Direction d) {
            return this == TOTAL_FREEZE || (this == DEBIT_FREEZE && d == Direction.DEBIT) || (this == CREDIT_FREEZE && d == Direction.CREDIT);
        }
    }

    /** Money out of the account (DEBIT) or into it (CREDIT). */
    public enum Direction { DEBIT, CREDIT }

    /** One active hold. {@code amount} is set for a lien only. */
    public record Hold(Kind kind, BigDecimal amount, String reason) {
        public Hold {
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.LIEN && (amount == null || amount.signum() <= 0)) throw new IllegalArgumentException("a lien has an amount above zero");
            if (kind != Kind.LIEN && amount != null) throw new IllegalArgumentException("a freeze has no amount");
        }
    }

    private AccountHolds() {}

    /** The total of the liens. */
    public static BigDecimal liens(List<Hold> holds) {
        BigDecimal t = BigDecimal.ZERO;
        for (Hold h : holds) if (h.kind() == Kind.LIEN) t = t.add(h.amount());
        return t;
    }

    /** The balance less every lien; may be below zero only if the books already were. */
    public static BigDecimal available(BigDecimal balance, List<Hold> holds) {
        return balance.subtract(liens(holds));
    }

    /**
     * Why this movement may not be made; empty when it may.
     *
     * @param balance    the ledger balance before the movement
     * @param correction the reversal of a posted transaction: freezes do not stop it
     */
    public static List<String> checkMovement(BigDecimal balance, List<Hold> holds, Direction direction, BigDecimal amount, boolean correction) {
        List<String> out = new ArrayList<>();
        if (amount == null || amount.signum() <= 0) {
            out.add("the amount must be above zero");
            return out;
        }
        if (amount.stripTrailingZeros().scale() > 2) out.add("the amount has more than two decimals (paise)");
        if (!correction) {
            for (Hold h : holds) {
                if (h.kind().stops(direction)) {
                    out.add("the account is under a " + describe(h.kind()) + (h.reason() == null ? "" : " (" + h.reason() + ")"));
                }
            }
        }
        if (direction == Direction.DEBIT) {
            BigDecimal after = balance.subtract(amount);
            BigDecimal liens = liens(holds);
            if (after.signum() < 0) {
                out.add("the balance is " + plain(balance) + "; " + plain(amount) + " would take it below zero");
            } else if (after.compareTo(liens) < 0) {
                out.add("only " + plain(available(balance, holds).max(BigDecimal.ZERO)) + " is available: " + plain(liens) + " is held under lien");
            }
        }
        return out;
    }

    /**
     * Why this hold may not be placed; empty when it may. A lien must fit in the available balance; a freeze of a kind
     * already in force is refused, since releasing one of two would leave the account frozen.
     */
    public static List<String> checkNewHold(BigDecimal balance, List<Hold> holds, Kind kind, BigDecimal amount, String reason) {
        List<String> out = new ArrayList<>();
        if (kind == null) {
            out.add("the kind of hold is required");
            return out;
        }
        if (reason == null || reason.isBlank()) out.add("a reason is required");
        else if (reason.length() > 500) out.add("the reason is longer than 500 characters");
        if (kind == Kind.LIEN) {
            if (amount == null || amount.signum() <= 0) {
                out.add("a lien needs an amount above zero");
            } else {
                if (amount.stripTrailingZeros().scale() > 2) out.add("the amount has more than two decimals (paise)");
                BigDecimal free = available(balance, holds);
                if (amount.compareTo(free) > 0) {
                    out.add("a lien of " + plain(amount) + " is more than the available balance of " + plain(free.max(BigDecimal.ZERO)));
                }
            }
        } else {
            if (amount != null) out.add("a freeze has no amount");
            for (Hold h : holds) {
                if (h.kind() == kind) {
                    out.add("the account is already under a " + describe(kind));
                    break;
                }
            }
        }
        return out;
    }

    public static String describe(Kind k) {
        return switch (k) {
            case DEBIT_FREEZE -> "debit freeze";
            case CREDIT_FREEZE -> "credit freeze";
            case TOTAL_FREEZE -> "total freeze";
            case LIEN -> "lien";
        };
    }

    static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }
}
