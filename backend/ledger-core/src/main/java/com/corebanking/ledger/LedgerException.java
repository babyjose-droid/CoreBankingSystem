package com.corebanking.ledger;

/** Any violation of ledger invariants. Always a programming or configuration error: never swallow. */
public class LedgerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LedgerException(String message) {
        super(message);
    }
}
