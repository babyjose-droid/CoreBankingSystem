package com.corebanking.ledger;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One leg of a transaction lot. {@code account} is either a GL code or a customer account number;
 * {@code glCode} is always the GL the leg rolls up to.
 */
public record PostingLine(String branch, String glCode, String account, Side side, BigDecimal amount,
                          String currency, String narration) {

    public enum Side { DR, CR;
        public Side opposite() { return this == DR ? CR : DR; }
    }

    public PostingLine {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(glCode, "glCode");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() <= 0) throw new LedgerException("posting amount must be positive: " + amount);
        if (amount.scale() > 4) throw new LedgerException("more than 4 decimals: " + amount);
    }

    public BigDecimal signed() {
        return side == Side.DR ? amount : amount.negate();
    }

    public PostingLine reversed() {
        return new PostingLine(branch, glCode, account, side.opposite(), amount, currency, "REV " + narration);
    }
}
