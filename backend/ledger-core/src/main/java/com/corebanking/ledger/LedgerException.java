package com.corebanking.ledger;

/** A request that would break a ledger invariant (unbalanced lot, bad amount …). Reported to the caller as 422. */
public class LedgerException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public LedgerException(String message) {
        super(message);
    }
}
