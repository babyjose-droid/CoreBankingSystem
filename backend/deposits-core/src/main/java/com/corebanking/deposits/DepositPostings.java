package com.corebanking.deposits;

import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Posting rules for deposit money movements (P4-2): every movement is one balanced lot, handed to the single posting
 * engine. Pure. The deposit account's leg carries the account number and rolls up to the product's liability head; the
 * other leg carries a GL code. A transfer between branches gets its inter-branch legs from the lot builder.
 * <pre>
 *   cash deposit          Dr cash in hand            Cr deposit account
 *   cash withdrawal       Dr deposit account         Cr cash in hand
 *   bank transfer in      Dr bank (collection)       Cr deposit account
 *   bank transfer out     Dr deposit account         Cr bank (disbursement)
 *   transfer              Dr paying account          Cr receiving account
 *   term deposit funding  Dr account / cash / bank   Cr term deposit
 *   transfer out through the payment gateway (P4-2b):
 *     sent                Dr deposit account         Cr payout in transit
 *     paid                Dr payout in transit       Cr bank (disbursement)
 *     failed or returned  the reversal of what was posted (the paid lot first, then the sent one)
 * </pre>
 * The reversal of any of them is the mirror lot ({@link TransactionLot#reversal}).
 */
public final class DepositPostings {

    /** Lot types written by deposit movements. */
    public static final String CASH_DEPOSIT = "DEPOSIT_CASH_IN";
    public static final String CASH_WITHDRAWAL = "DEPOSIT_CASH_OUT";
    public static final String BANK_TRANSFER_IN = "DEPOSIT_BANK_IN";
    public static final String BANK_TRANSFER_OUT = "DEPOSIT_BANK_OUT";
    public static final String TRANSFER = "DEPOSIT_TRANSFER";
    public static final String FUNDING = "DEPOSIT_FUNDING";
    public static final String GATEWAY_TRANSFER_OUT = "DEPOSIT_GATEWAY_OUT";
    public static final String GATEWAY_PAID = "DEPOSIT_GATEWAY_PAID";

    /**
     * The cash and bank heads a deposit movement settles against, the inter-branch head a transfer between branches uses,
     * and the head money sent through the payment gateway waits on until the bank pays it (defaults from the starter charts).
     */
    public record Heads(String cash, String bankIn, String bankOut, String interBranch, String inTransit) {
        public Heads {
            Objects.requireNonNull(cash, "cash");
            Objects.requireNonNull(bankIn, "bankIn");
            Objects.requireNonNull(bankOut, "bankOut");
            Objects.requireNonNull(interBranch, "interBranch");
            Objects.requireNonNull(inTransit, "inTransit");
        }

        /** Cash in hand 1201, collection bank 1203, disbursement bank 1202, inter-branch account 1900, payout in transit 2408. */
        public static Heads starter() {
            return new Heads("1201", "1203", "1202", "1900", "2408");
        }
    }

    /** A deposit account as a posting leg: its branch, the liability head of its product, its number. */
    public record Leg(String branch, String gl, String accountNo) {
        public Leg {
            Objects.requireNonNull(branch, "branch");
            Objects.requireNonNull(gl, "gl");
            Objects.requireNonNull(accountNo, "accountNo");
        }
    }

    /** How a term deposit is funded. */
    public enum FundingSource { ACCOUNT, CASH, BANK_TRANSFER, GATEWAY }

    private final Heads heads;
    private final LocalDate businessDate;

    public DepositPostings(Heads heads, LocalDate businessDate) {
        this.heads = Objects.requireNonNull(heads);
        this.businessDate = Objects.requireNonNull(businessDate);
    }

    private TransactionLot.Builder lot(String type, String reference) {
        return TransactionLot.builder(type, businessDate).valueDate(businessDate).reference(reference).interBranchGl(heads.interBranch());
    }

    private static BigDecimal positive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("the amount must be above zero");
        return amount;
    }

    public TransactionLot cashDeposit(Leg account, BigDecimal amount, String reference, String narration) {
        positive(amount);
        return lot(CASH_DEPOSIT, reference)
                .debit(account.branch(), heads.cash(), heads.cash(), amount, text(narration, "Cash deposit " + account.accountNo()))
                .credit(account.branch(), account.gl(), account.accountNo(), amount, text(narration, "Cash deposit"))
                .build();
    }

    public TransactionLot cashWithdrawal(Leg account, BigDecimal amount, String reference, String narration) {
        positive(amount);
        return lot(CASH_WITHDRAWAL, reference)
                .debit(account.branch(), account.gl(), account.accountNo(), amount, text(narration, "Cash withdrawal"))
                .credit(account.branch(), heads.cash(), heads.cash(), amount, text(narration, "Cash withdrawal " + account.accountNo()))
                .build();
    }

    /** Money received from another bank (the bank's reference, the UTR, is the lot's reference). */
    public TransactionLot bankTransferIn(Leg account, BigDecimal amount, String utr, String narration) {
        positive(amount);
        return lot(BANK_TRANSFER_IN, utr)
                .debit(account.branch(), heads.bankIn(), heads.bankIn(), amount, text(narration, "Bank transfer in " + account.accountNo()))
                .credit(account.branch(), account.gl(), account.accountNo(), amount, text(narration, "Bank transfer in"))
                .build();
    }

    /** Money sent to another bank. */
    public TransactionLot bankTransferOut(Leg account, BigDecimal amount, String utr, String narration) {
        positive(amount);
        return lot(BANK_TRANSFER_OUT, utr)
                .debit(account.branch(), account.gl(), account.accountNo(), amount, text(narration, "Bank transfer out"))
                .credit(account.branch(), heads.bankOut(), heads.bankOut(), amount, text(narration, "Bank transfer out " + account.accountNo()))
                .build();
    }

    /** From one deposit account to another; inter-branch legs are added when the branches differ. */
    public TransactionLot transfer(Leg from, Leg to, BigDecimal amount, String reference, String narration) {
        positive(amount);
        if (from.accountNo().equals(to.accountNo())) throw new IllegalArgumentException("an account cannot transfer to itself");
        return lot(TRANSFER, reference)
                .debit(from.branch(), from.gl(), from.accountNo(), amount, text(narration, "Transfer to " + to.accountNo()))
                .credit(to.branch(), to.gl(), to.accountNo(), amount, text(narration, "Transfer from " + from.accountNo()))
                .build();
    }

    /**
     * Funding a term deposit with its principal.
     *
     * @param from the paying savings or current account; only for {@link FundingSource#ACCOUNT}
     */
    public TransactionLot funding(Leg deposit, BigDecimal amount, FundingSource source, Leg from, String reference) {
        positive(amount);
        Objects.requireNonNull(source, "source");
        var b = lot(FUNDING, reference);
        switch (source) {
            case ACCOUNT -> {
                if (from == null) throw new IllegalArgumentException("funding from an account names the account");
                b.debit(from.branch(), from.gl(), from.accountNo(), amount, "Funding of term deposit " + deposit.accountNo());
            }
            case CASH -> b.debit(deposit.branch(), heads.cash(), heads.cash(), amount, "Cash for term deposit " + deposit.accountNo());
            case BANK_TRANSFER -> b.debit(deposit.branch(), heads.bankIn(), heads.bankIn(), amount, "Bank transfer for term deposit " + deposit.accountNo());
            case GATEWAY -> b.debit(deposit.branch(), heads.bankIn(), heads.bankIn(), amount, "Gateway payment for term deposit " + deposit.accountNo());
        }
        b.credit(deposit.branch(), deposit.gl(), deposit.accountNo(), amount, "Principal");
        return b.build();
    }

    private static String text(String narration, String fallback) {
        return narration == null || narration.isBlank() ? fallback : narration.trim();
    }

    /** A transfer out sent through the payment gateway: the money leaves the account and waits on the in-transit head. */
    public TransactionLot gatewayTransferOut(Leg account, BigDecimal amount, String reference, String narration) {
        positive(amount);
        return lot(GATEWAY_TRANSFER_OUT, reference)
                .debit(account.branch(), account.gl(), account.accountNo(), amount, text(narration, "Bank transfer out (gateway)"))
                .credit(account.branch(), heads.inTransit(), heads.inTransit(), amount, "Gateway transfer from " + account.accountNo())
                .build();
    }

    /** The bank paid a gateway transfer: the in-transit head is cleared against the disbursement bank. No account leg. */
    public TransactionLot gatewayPaid(String branch, String accountNo, BigDecimal amount, String reference) {
        positive(amount);
        return lot(GATEWAY_PAID, reference)
                .debit(branch, heads.inTransit(), heads.inTransit(), amount, "Gateway transfer paid, from " + accountNo)
                .credit(branch, heads.bankOut(), heads.bankOut(), amount, "Gateway transfer paid, from " + accountNo)
                .build();
    }
}
