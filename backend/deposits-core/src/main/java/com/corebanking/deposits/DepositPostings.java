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
 *   interest (P4-3):
 *     accrual             Dr interest expense (the product's head)   Cr interest payable
 *     credit              Dr interest payable                        Cr the account
 *     payout              Dr the term deposit                        Cr the savings or current account it is paid to
 *   tax deducted at source on interest (P4-3b):
 *                         Dr the account                             Cr TDS payable
 *   lifecycle (P4-4):
 *     charge              Dr the account (charge + GST)              Cr charge income, Cr CGST and SGST, or IGST
 *     closure payout      Dr the account (its whole balance)         Cr cash / bank / payout in transit / another account
 *     interest recovered  Dr the term deposit                        Cr the product's interest expense head
 *     renewal             Dr the matured deposit                     Cr the new deposit
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
    public static final String INTEREST_ACCRUAL = "DEPOSIT_INTEREST_ACCRUAL";
    public static final String INTEREST_CREDIT = "DEPOSIT_INTEREST_CREDIT";
    public static final String INTEREST_PAYOUT = "DEPOSIT_INTEREST_PAYOUT";
    public static final String TAX_DEDUCTED = "DEPOSIT_TAX_DEDUCTED";
    public static final String CHARGE = "DEPOSIT_CHARGE";
    public static final String CLOSURE = "DEPOSIT_CLOSURE";
    public static final String INTEREST_RECOVERY = "DEPOSIT_INTEREST_RECOVERY";
    public static final String RENEWAL = "DEPOSIT_RENEWAL";

    /** Where the proceeds of a closure go. */
    public enum PayTo { ACCOUNT, CASH, BANK_TRANSFER, GATEWAY }

    /** The GL heads a charge credits: the income head and the three GST output heads. */
    public record ChargeHeads(String income, String cgst, String sgst, String igst) {
        public ChargeHeads {
            Objects.requireNonNull(income, "income");
            Objects.requireNonNull(cgst, "cgst");
            Objects.requireNonNull(sgst, "sgst");
            Objects.requireNonNull(igst, "igst");
        }

        /** Account service charges 4107, CGST 2201, SGST 2202, IGST 2203. */
        public static ChargeHeads starter() {
            return new ChargeHeads("4107", "2201", "2202", "2203");
        }
    }

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

    /**
     * Interest accrued on an account: Dr the product's interest expense head, Cr interest payable, both in the
     * account's branch. A negative amount is the reverse (a rounding true-up, or accrual taken back).
     */
    public TransactionLot interestAccrual(String branch, String expenseGl, String payableGl, String accountNo, BigDecimal amount, String reference) {
        if (amount == null || amount.signum() == 0) throw new IllegalArgumentException("the amount must not be zero");
        BigDecimal abs = amount.abs();
        String narration = (amount.signum() > 0 ? "Interest accrued on " : "Interest accrual taken back on ") + accountNo;
        TransactionLot.Builder b = lot(INTEREST_ACCRUAL, reference);
        if (amount.signum() > 0) {
            b.debit(branch, expenseGl, expenseGl, abs, narration).credit(branch, payableGl, payableGl, abs, narration);
        } else {
            b.debit(branch, payableGl, payableGl, abs, narration).credit(branch, expenseGl, expenseGl, abs, narration);
        }
        return b.build();
    }

    /** Interest credited to the account from interest payable. */
    public TransactionLot interestCredit(Leg account, String payableGl, BigDecimal amount, String reference, String narration) {
        positive(amount);
        return lot(INTEREST_CREDIT, reference)
                .debit(account.branch(), payableGl, payableGl, amount, "Interest credited to " + account.accountNo())
                .credit(account.branch(), account.gl(), account.accountNo(), amount, text(narration, "Interest"))
                .build();
    }

    /** A term deposit's interest paid to a savings or current account; inter-branch legs when the branches differ. */
    public TransactionLot interestPayout(Leg deposit, Leg to, BigDecimal amount, String reference) {
        positive(amount);
        return lot(INTEREST_PAYOUT, reference)
                .debit(deposit.branch(), deposit.gl(), deposit.accountNo(), amount, "Interest paid to " + to.accountNo())
                .credit(to.branch(), to.gl(), to.accountNo(), amount, "Interest on deposit " + deposit.accountNo())
                .build();
    }

    /** Tax deducted at source from interest just credited: out of the account, owed to the government until paid over. */
    public TransactionLot taxDeducted(Leg account, String tdsPayableGl, BigDecimal amount, String reference, String narration) {
        positive(amount);
        return lot(TAX_DEDUCTED, reference)
                .debit(account.branch(), account.gl(), account.accountNo(), amount, text(narration, "Tax deducted at source"))
                .credit(account.branch(), tdsPayableGl, tdsPayableGl, amount, "Tax deducted at source from " + account.accountNo())
                .build();
    }

    /**
     * A charge with its GST: out of the account, to the income head and the output tax heads (all in the account's
     * branch, the place the service is supplied from). Narrations follow the loans' invoices: "CGST &lt;charge&gt;".
     */
    public TransactionLot charge(Leg account, ChargeHeads h, DepositCharges.Charge c, String reference) {
        positive(c.taxable());
        TransactionLot.Builder b = lot(CHARGE, reference)
                .debit(account.branch(), account.gl(), account.accountNo(), c.total(), c.description())
                .credit(account.branch(), h.income(), h.income(), c.taxable(), c.description() + " " + account.accountNo());
        if (c.tax().cgst().signum() > 0) b.credit(account.branch(), h.cgst(), h.cgst(), c.tax().cgst(), "CGST " + c.description() + " " + account.accountNo());
        if (c.tax().sgst().signum() > 0) b.credit(account.branch(), h.sgst(), h.sgst(), c.tax().sgst(), "SGST " + c.description() + " " + account.accountNo());
        if (c.tax().igst().signum() > 0) b.credit(account.branch(), h.igst(), h.igst(), c.tax().igst(), "IGST " + c.description() + " " + account.accountNo());
        return b.build();
    }

    /**
     * The proceeds of a closing account paid out: to cash, to another bank (recorded with its UTR), through the payment
     * gateway (to the in-transit head), or to another deposit account (inter-branch legs when the branches differ).
     *
     * @param to the receiving account; only for {@link PayTo#ACCOUNT}
     */
    public TransactionLot closurePayout(Leg account, BigDecimal amount, PayTo payTo, Leg to, String reference, String narration) {
        positive(amount);
        Objects.requireNonNull(payTo, "payTo");
        String text = text(narration, "Closure of " + account.accountNo());
        TransactionLot.Builder b = lot(CLOSURE, reference).debit(account.branch(), account.gl(), account.accountNo(), amount, text);
        switch (payTo) {
            case ACCOUNT -> {
                if (to == null) throw new IllegalArgumentException("a payout to an account names the account");
                if (to.accountNo().equals(account.accountNo())) throw new IllegalArgumentException("an account cannot pay its closure to itself");
                b.credit(to.branch(), to.gl(), to.accountNo(), amount, "Proceeds of " + account.accountNo());
            }
            case CASH -> b.credit(account.branch(), heads.cash(), heads.cash(), amount, text);
            case BANK_TRANSFER -> b.credit(account.branch(), heads.bankOut(), heads.bankOut(), amount, text);
            case GATEWAY -> b.credit(account.branch(), heads.inTransit(), heads.inTransit(), amount, "Gateway transfer from " + account.accountNo());
        }
        return b.build();
    }

    /** Interest credited earlier and not earned on a premature closure: taken back from the deposit to the expense head. */
    public TransactionLot interestRecovery(Leg deposit, String expenseGl, BigDecimal amount, String reference) {
        positive(amount);
        return lot(INTEREST_RECOVERY, reference)
                .debit(deposit.branch(), deposit.gl(), deposit.accountNo(), amount, "Interest recovered on premature closure")
                .credit(deposit.branch(), expenseGl, expenseGl, amount, "Interest recovered from " + deposit.accountNo())
                .build();
    }

    /** A matured deposit renewed: its proceeds fund the new deposit. */
    public TransactionLot renewal(Leg matured, Leg renewed, BigDecimal amount) {
        positive(amount);
        return lot(RENEWAL, matured.accountNo())
                .debit(matured.branch(), matured.gl(), matured.accountNo(), amount, "Renewed as " + renewed.accountNo())
                .credit(renewed.branch(), renewed.gl(), renewed.accountNo(), amount, "Renewal of " + matured.accountNo())
                .build();
    }
}
