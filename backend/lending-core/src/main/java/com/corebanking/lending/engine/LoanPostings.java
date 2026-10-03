package com.corebanking.lending.engine;

import com.corebanking.ledger.PostingLine;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Posting rules for loan events (FSD §7): every lending event becomes one balanced lot. Pure — the lending service
 * hands the lot to the posting engine. Customer-side legs carry the loan number as account; GL-side legs carry
 * the GL code.
 */
public final class LoanPostings {

    /** GL heads a product posts to (defaults from the NBFC starter chart). */
    public record GlMap(String principal, String interestReceivable, String feeReceivable, String penalReceivable,
                        String interestIncome, String feeIncome, String penalIncome, String cgst, String sgst, String igst,
                        String disbursementBank, String collectionBank, String excessReceipts, String interestSuspense,
                        String provisionExpense, String provisionContra, String writeOffExpense) {

        public static GlMap starter() {
            return new GlMap("1101", "1102", "1103", "1104", "4101", "4102", "4103", "2201", "2202", "2203",
                    "1202", "1203", "2302", "2305", "5102", "1109", "5103");
        }
    }

    private final GlMap gl;
    private final String branch;
    private final String loanNo;
    private final LocalDate businessDate;

    public LoanPostings(GlMap gl, String branch, String loanNo, LocalDate businessDate) {
        this.gl = Objects.requireNonNull(gl);
        this.branch = Objects.requireNonNull(branch);
        this.loanNo = Objects.requireNonNull(loanNo);
        this.businessDate = Objects.requireNonNull(businessDate);
    }

    private TransactionLot.Builder lot(String type, LocalDate valueDate) {
        return TransactionLot.builder(type, businessDate).valueDate(valueDate).reference(loanNo);
    }

    private static void dr(TransactionLot.Builder b, String branch, String gl, String account, BigDecimal amt, String text) {
        if (amt != null && amt.signum() > 0) b.line(new PostingLine(branch, gl, account, PostingLine.Side.DR, amt, "INR", text));
    }

    private static void cr(TransactionLot.Builder b, String branch, String gl, String account, BigDecimal amt, String text) {
        if (amt != null && amt.signum() > 0) b.line(new PostingLine(branch, gl, account, PostingLine.Side.CR, amt, "INR", text));
    }

    private void gst(TransactionLot.Builder b, Gst.Split g, String text) {
        cr(b, branch, gl.cgst(), gl.cgst(), g.cgst(), "CGST " + text);
        cr(b, branch, gl.sgst(), gl.sgst(), g.sgst(), "SGST " + text);
        cr(b, branch, gl.igst(), gl.igst(), g.igst(), "IGST " + text);
    }

    /**
     * Disbursement: principal booked gross; fees marked "deduct" are taken from the payout with their GST.
     * Dr loan principal (gross) / Cr disbursement bank (net) / Cr fee income / Cr GST.
     */
    public TransactionLot disbursement(BigDecimal gross, List<FeeRule.Charge> deducted, LocalDate valueDate) {
        return disbursement(gross, deducted, BigDecimal.ZERO, valueDate);
    }

    /**
     * As {@link #disbursement(BigDecimal, List, LocalDate)}, also deducting broken-period interest collected in
     * advance: it is held with the borrower's advances (Cr excess receipts) until its demand date, so the interest
     * is earned by the daily accrual and not on the day of disbursal.
     */
    public TransactionLot disbursement(BigDecimal gross, List<FeeRule.Charge> deducted, BigDecimal interestInAdvance, LocalDate valueDate) {
        var b = lot("DISBURSEMENT", valueDate);
        BigDecimal net = gross;
        dr(b, branch, gl.principal(), loanNo, gross, "Disbursement");
        for (FeeRule.Charge c : deducted) {
            cr(b, branch, gl.feeIncome(), gl.feeIncome(), c.fee(), c.name() + " " + loanNo);
            gst(b, c.gst(), c.name() + " " + loanNo);
            net = net.subtract(c.total());
        }
        cr(b, branch, gl.excessReceipts(), loanNo, interestInAdvance, "Broken-period interest collected in advance");
        net = net.subtract(interestInAdvance == null ? BigDecimal.ZERO : interestInAdvance);
        if (net.signum() < 0) throw new IllegalArgumentException("deducted fees exceed the disbursement");
        cr(b, branch, gl.disbursementBank(), gl.disbursementBank(), net, "Net disbursal " + loanNo);
        return b.build();
    }

    /** Fee charged to the borrower's account (not deducted): Dr fee receivable / Cr fee income / Cr GST. */
    public TransactionLot feeCharge(FeeRule.Charge c, LocalDate valueDate) {
        var b = lot("FEE_CHARGE", valueDate);
        dr(b, branch, gl.feeReceivable(), loanNo, c.total(), c.name());
        cr(b, branch, gl.feeIncome(), gl.feeIncome(), c.fee(), c.name() + " " + loanNo);
        gst(b, c.gst(), c.name() + " " + loanNo);
        return b.build();
    }

    /** Daily interest accrual. NPA accounts accrue to interest suspense, not income (income recognition). */
    public TransactionLot accrual(BigDecimal amount, boolean npa, LocalDate valueDate) {
        var b = lot("ACCRUAL", valueDate);
        if (amount.signum() >= 0) {
            dr(b, branch, gl.interestReceivable(), loanNo, amount, "Interest accrual");
            cr(b, branch, npa ? gl.interestSuspense() : gl.interestIncome(), npa ? gl.interestSuspense() : gl.interestIncome(), amount, "Interest accrual " + loanNo);
        } else {  // negative true-up at the demand date
            BigDecimal a = amount.negate();
            dr(b, branch, npa ? gl.interestSuspense() : gl.interestIncome(), npa ? gl.interestSuspense() : gl.interestIncome(), a, "Interest true-up " + loanNo);
            cr(b, branch, gl.interestReceivable(), loanNo, a, "Interest true-up");
        }
        return b.build();
    }

    /** Penal charge for the day (no GST — see decision D-08 note). NPA: to suspense. */
    public TransactionLot penal(BigDecimal amount, boolean npa, LocalDate valueDate) {
        var b = lot("PENAL_CHARGE", valueDate);
        dr(b, branch, gl.penalReceivable(), loanNo, amount, "Penal charge");
        cr(b, branch, npa ? gl.interestSuspense() : gl.penalIncome(), npa ? gl.interestSuspense() : gl.penalIncome(), amount, "Penal charge " + loanNo);
        return b.build();
    }

    /**
     * When a loan turns NPA, interest and charges recognised as income but not yet received are reversed into
     * suspense (US-078).
     */
    public TransactionLot npaIncomeReversal(BigDecimal unrealisedInterest, BigDecimal unrealisedPenal) {
        var b = lot("NPA_INCOME_REVERSAL", businessDate);
        dr(b, branch, gl.interestIncome(), gl.interestIncome(), unrealisedInterest, "Unrealised interest to suspense " + loanNo);
        dr(b, branch, gl.penalIncome(), gl.penalIncome(), unrealisedPenal, "Unrealised penal to suspense " + loanNo);
        cr(b, branch, gl.interestSuspense(), gl.interestSuspense(), unrealisedInterest.add(unrealisedPenal), "NPA suspense " + loanNo);
        return b.build();
    }

    /**
     * Receipt appropriated to principal, interest, fees and penal; any excess parked. For an NPA account the
     * interest and penal actually realised move from suspense to income.
     */
    public TransactionLot repayment(BigDecimal amount, Appropriation.Result split, boolean npa, LocalDate valueDate, String narration) {
        var b = lot("REPAYMENT", valueDate);
        dr(b, branch, gl.collectionBank(), gl.collectionBank(), amount, narration + " " + loanNo);
        BigDecimal principal = split.total(Appropriation.Component.PRINCIPAL);
        BigDecimal interest = split.total(Appropriation.Component.INTEREST);
        BigDecimal fee = split.total(Appropriation.Component.FEE);
        BigDecimal penal = split.total(Appropriation.Component.PENAL);
        cr(b, branch, gl.principal(), loanNo, principal, "Principal");
        cr(b, branch, gl.interestReceivable(), loanNo, interest, "Interest");
        cr(b, branch, gl.feeReceivable(), loanNo, fee, "Fees");
        cr(b, branch, gl.penalReceivable(), loanNo, penal, "Penal charges");
        cr(b, branch, gl.excessReceipts(), loanNo, split.excess(), "Excess receipt");
        if (npa) {
            dr(b, branch, gl.interestSuspense(), gl.interestSuspense(), interest.add(penal), "Realised from suspense " + loanNo);
            cr(b, branch, gl.interestIncome(), gl.interestIncome(), interest, "Interest realised " + loanNo);
            cr(b, branch, gl.penalIncome(), gl.penalIncome(), penal, "Penal realised " + loanNo);
        }
        return b.build();
    }

    /** Interest or penal held in suspense becomes income (realised on an NPA, or the account is upgraded). */
    public TransactionLot suspenseToIncome(BigDecimal amount) {
        var b = lot("SUSPENSE_TO_INCOME", businessDate);
        dr(b, branch, gl.interestSuspense(), gl.interestSuspense(), amount, "Realised from suspense " + loanNo);
        cr(b, branch, gl.interestIncome(), gl.interestIncome(), amount, "Interest realised " + loanNo);
        return b.build();
    }

    /**
     * Restructuring: overdue interest capitalised into principal. The receivable becomes principal; the income side
     * stays in interest suspense (the account is NPA on restructuring) until the principal is repaid.
     */
    public TransactionLot interestCapitalisation(BigDecimal amount) {
        var b = lot("INTEREST_CAPITALISATION", businessDate);
        dr(b, branch, gl.principal(), loanNo, amount, "Interest capitalised on restructuring");
        cr(b, branch, gl.interestReceivable(), loanNo, amount, "Interest capitalised on restructuring");
        return b.build();
    }

    /** Principal prepayment straight from the bank (part-prepayment / pre-closure principal). */
    public TransactionLot principalPrepayment(BigDecimal amount, LocalDate valueDate) {
        var b = lot("PREPAYMENT", valueDate);
        dr(b, branch, gl.collectionBank(), gl.collectionBank(), amount, "Prepayment " + loanNo);
        cr(b, branch, gl.principal(), loanNo, amount, "Principal prepayment");
        return b.build();
    }

    /** Waiver of an unpaid charge or interest: income (or suspense for NPA) reduced, receivable cleared. */
    public TransactionLot waiver(Appropriation.Component component, BigDecimal amount, boolean npa) {
        var b = lot("WAIVER", businessDate);
        String receivable = switch (component) {
            case INTEREST -> gl.interestReceivable();
            case FEE -> gl.feeReceivable();
            case PENAL -> gl.penalReceivable();
            case PRINCIPAL -> throw new IllegalArgumentException("principal is written off, not waived");
        };
        String income = npa && component != Appropriation.Component.FEE ? gl.interestSuspense()
                : switch (component) {
                    case INTEREST -> gl.interestIncome();
                    case FEE -> gl.feeIncome();
                    default -> gl.penalIncome();
                };
        dr(b, branch, income, income, amount, component + " waiver " + loanNo);
        cr(b, branch, receivable, loanNo, amount, component + " waiver");
        return b.build();
    }

    /**
     * Waiver of an unpaid fee that carried GST, with a credit note (CGST Act s.34): the taxable part reduces fee
     * income and the tax part reduces the output tax. The receivable line names the charge so that the credit note
     * can be issued from the ledger (V18 lending.issue_fee_credit_notes).
     */
    public TransactionLot feeWaiver(String chargeId, String feeName, BigDecimal amount, Gst.Inclusive parts) {
        var b = lot("WAIVER", businessDate);
        dr(b, branch, gl.feeIncome(), gl.feeIncome(), parts.taxable(), feeName + " " + loanNo);
        dr(b, branch, gl.cgst(), gl.cgst(), parts.tax().cgst(), "CGST " + feeName + " " + loanNo);
        dr(b, branch, gl.sgst(), gl.sgst(), parts.tax().sgst(), "SGST " + feeName + " " + loanNo);
        dr(b, branch, gl.igst(), gl.igst(), parts.tax().igst(), "IGST " + feeName + " " + loanNo);
        cr(b, branch, gl.feeReceivable(), loanNo, amount, "FEE waiver " + chargeId);
        return b.build();
    }

    /** Broken-period interest collected at disbursal settles its demand: Dr advance / Cr interest receivable. */
    public TransactionLot advanceInterestAdjustment(BigDecimal amount, LocalDate valueDate) {
        var b = lot("EXCESS_ADJUSTMENT", valueDate);
        dr(b, branch, gl.excessReceipts(), loanNo, amount, "Broken-period interest collected in advance");
        cr(b, branch, gl.interestReceivable(), loanNo, amount, "Interest");
        return b.build();
    }

    /** Change in provision held: positive = more provision (expense), negative = write-back. */
    public TransactionLot provision(BigDecimal delta) {
        var b = lot("PROVISION", businessDate);
        if (delta.signum() > 0) {
            dr(b, branch, gl.provisionExpense(), gl.provisionExpense(), delta, "Provision " + loanNo);
            cr(b, branch, gl.provisionContra(), loanNo, delta, "Provision");
        } else {
            dr(b, branch, gl.provisionContra(), loanNo, delta.negate(), "Provision write-back");
            cr(b, branch, gl.provisionExpense(), gl.provisionExpense(), delta.negate(), "Provision write-back " + loanNo);
        }
        return b.build();
    }

    /** Refund of an excess receipt to the borrower's bank account. */
    public TransactionLot excessRefund(BigDecimal amount) {
        var b = lot("EXCESS_REFUND", businessDate);
        dr(b, branch, gl.excessReceipts(), loanNo, amount, "Excess refund");
        cr(b, branch, gl.disbursementBank(), gl.disbursementBank(), amount, "Excess refund " + loanNo);
        return b.build();
    }

    /** Adjust parked excess against dues (e.g. advance EMI applied on the due date). */
    public TransactionLot excessAdjustment(Appropriation.Result split) {
        var b = lot("EXCESS_ADJUSTMENT", businessDate);
        BigDecimal used = split.allocations().stream().map(Appropriation.Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        dr(b, branch, gl.excessReceipts(), loanNo, used, "Advance adjusted");
        cr(b, branch, gl.principal(), loanNo, split.total(Appropriation.Component.PRINCIPAL), "Principal");
        cr(b, branch, gl.interestReceivable(), loanNo, split.total(Appropriation.Component.INTEREST), "Interest");
        cr(b, branch, gl.feeReceivable(), loanNo, split.total(Appropriation.Component.FEE), "Fees");
        cr(b, branch, gl.penalReceivable(), loanNo, split.total(Appropriation.Component.PENAL), "Penal charges");
        return b.build();
    }
}
