package com.corebanking.deposits;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Whether a cheque drawn on one of our accounts is paid when another bank presents it (inward clearing, P4-6), and
 * why not. The checks run in the order a paying banker reads the cheque: the account, the leaf, the date, the money.
 * A cheque is good for {@code validMonths} months from its date (three: RBI, 1-Apr-2012).
 */
public final class Cheques {

    /** Why a cheque is returned unpaid. */
    public enum ReturnReason {
        NO_SUCH_ACCOUNT("no such account"),
        ACCOUNT_CLOSED("account closed"),
        NOT_ISSUED("cheque not issued to this account"),
        ALREADY_PAID("cheque already paid"),
        PAYMENT_STOPPED("payment stopped by the drawer"),
        POST_DATED("cheque post-dated"),
        STALE("cheque out of date (stale)"),
        ACCOUNT_FROZEN("account frozen for debits"),
        ACCOUNT_INOPERATIVE("account inoperative"),
        AMOUNT_HELD("funds held under lien"),
        INSUFFICIENT_FUNDS("funds insufficient");

        private final String text;

        ReturnReason(String text) { this.text = text; }

        public String text() { return text; }
    }

    /** The state of a leaf: null when the number was never issued on this account. */
    public enum Leaf { UNUSED, STOPPED, PAID }

    /**
     * @param balance   the account's balance
     * @param available what may be taken out: the balance less the liens (never below zero)
     */
    public record Presentment(boolean accountActive, Leaf leaf, LocalDate chequeDate, BigDecimal amount, LocalDate today, int validMonths,
                              boolean debitFrozen, boolean inoperative, BigDecimal balance, BigDecimal available) {}

    private Cheques() {}

    /** Empty: pay it. */
    public static Optional<ReturnReason> decide(Presentment p) {
        if (!p.accountActive()) return Optional.of(ReturnReason.ACCOUNT_CLOSED);
        if (p.leaf() == null) return Optional.of(ReturnReason.NOT_ISSUED);
        if (p.leaf() == Leaf.PAID) return Optional.of(ReturnReason.ALREADY_PAID);
        if (p.leaf() == Leaf.STOPPED) return Optional.of(ReturnReason.PAYMENT_STOPPED);
        if (p.chequeDate().isAfter(p.today())) return Optional.of(ReturnReason.POST_DATED);
        if (p.today().isAfter(p.chequeDate().plusMonths(p.validMonths()))) return Optional.of(ReturnReason.STALE);
        if (p.debitFrozen()) return Optional.of(ReturnReason.ACCOUNT_FROZEN);
        if (p.inoperative()) return Optional.of(ReturnReason.ACCOUNT_INOPERATIVE);
        if (p.amount().compareTo(p.available()) > 0) {
            return Optional.of(p.amount().compareTo(p.balance()) <= 0 ? ReturnReason.AMOUNT_HELD : ReturnReason.INSUFFICIENT_FUNDS);
        }
        return Optional.empty();
    }
}
