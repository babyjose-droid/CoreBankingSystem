package com.corebanking.lending.engine;

import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Co-lending (P5-1), RBI (Co-Lending Arrangements) Directions, 2025, in force from 1-Jan-2026. Pure: the rules a
 * co-lending arrangement and a co-lent loan must keep, and how amounts are divided between the two lenders.
 *
 * <ul>
 *   <li>Each lender keeps at least 10% of every loan (para 10).</li>
 *   <li>The borrower pays the blended rate: each lender's rate weighted by its share of the funding (para 17).</li>
 *   <li>The partner's share is in both lenders' books within 15 calendar days of disbursement; otherwise the loan
 *       stays with the originator (paras 22, 24).</li>
 *   <li>The originator's default loss guarantee is at most 5% of loans outstanding (para 32).</li>
 * </ul>
 *
 * Shares are percentages of the loan (originator + partner = 100). Interest divides in the ratio share × rate, so each
 * lender earns its own rate on its own share and the two parts add up to the interest at the blended rate.
 */
public final class CoLending {

    private CoLending() {}

    public static final BigDecimal MIN_SHARE = new BigDecimal("10");
    public static final BigDecimal MAX_DLG = new BigDecimal("5");
    public static final int TRANSFER_DAYS = 15;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** How fees or penal charges paid by the borrower are divided. */
    public enum Sharing { ORIGINATOR, BY_SHARE }

    /**
     * The terms of an arrangement as they bind a loan.
     *
     * @param originatorShare the originator's percentage of each loan; the partner holds the rest
     * @param servicingFee    the originator's fee for servicing the partner's share, % a year on the partner's
     *                        principal outstanding (0 for none)
     * @param dlgPercent      the default loss guarantee, % of loans outstanding (0 for none)
     */
    public record Terms(BigDecimal originatorShare, BigDecimal originatorRate, BigDecimal partnerRate, BigDecimal servicingFee,
                        Sharing fees, Sharing penal, BigDecimal dlgPercent) {
        public Terms {
            Objects.requireNonNull(originatorShare, "originator share");
            Objects.requireNonNull(originatorRate, "originator rate");
            Objects.requireNonNull(partnerRate, "partner rate");
            servicingFee = servicingFee == null ? BigDecimal.ZERO : servicingFee;
            fees = fees == null ? Sharing.ORIGINATOR : fees;
            penal = penal == null ? Sharing.BY_SHARE : penal;
            dlgPercent = dlgPercent == null ? BigDecimal.ZERO : dlgPercent;
        }

        public BigDecimal partnerShare() {
            return HUNDRED.subtract(originatorShare);
        }

        /** The rate the borrower pays: the lenders' rates weighted by their shares, to 2 decimals (half up). */
        public BigDecimal blendedRate() {
            return originatorShare.multiply(originatorRate).add(partnerShare().multiply(partnerRate)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }
    }

    /** Why the terms cannot be agreed; empty when they can. */
    public static List<String> problems(Terms t) {
        List<String> out = new ArrayList<>();
        if (t.originatorShare().compareTo(MIN_SHARE) < 0 || t.originatorShare().compareTo(HUNDRED.subtract(MIN_SHARE)) > 0) {
            out.add("each lender keeps at least " + MIN_SHARE.toPlainString() + "% of every loan: the originator's share is 10 to 90%");
        }
        if (t.originatorShare().stripTrailingZeros().scale() > 2) out.add("the shares are percentages to 2 decimals");
        if (t.originatorRate().signum() <= 0 || t.partnerRate().signum() <= 0) out.add("each lender's rate is above zero");
        if (t.originatorRate().compareTo(HUNDRED) >= 0 || t.partnerRate().compareTo(HUNDRED) >= 0) out.add("a rate is a percentage a year, below 100");
        if (t.servicingFee().signum() < 0 || t.servicingFee().compareTo(t.partnerRate()) >= 0) {
            out.add("the servicing fee is at least zero and below the partner's rate");
        }
        if (t.dlgPercent().signum() < 0 || t.dlgPercent().compareTo(MAX_DLG) > 0) {
            out.add("the default loss guarantee is at most " + MAX_DLG.toPlainString() + "% of loans outstanding");
        }
        return out;
    }

    /** The last day the partner's share can be booked: 15 calendar days from disbursement. */
    public static LocalDate transferDeadline(LocalDate disbursedOn) {
        return disbursedOn.plusDays(TRANSFER_DAYS);
    }

    /** An amount divided by share: the partner's part rounded to the paisa, the originator's the rest (so they add up). */
    public record Split(BigDecimal originator, BigDecimal partner) {
        public BigDecimal total() {
            return originator.add(partner);
        }
    }

    public static Split byShare(Terms t, BigDecimal amount) {
        BigDecimal partner = amount.multiply(t.partnerShare()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        return new Split(amount.subtract(partner), partner);
    }

    /** Interest divided in the ratio share × rate: each lender's own rate on its own share. */
    public static Split interest(Terms t, BigDecimal amount) {
        BigDecimal o = t.originatorShare().multiply(t.originatorRate());
        BigDecimal p = t.partnerShare().multiply(t.partnerRate());
        BigDecimal partner = amount.multiply(p).divide(o.add(p), 2, RoundingMode.HALF_UP);
        return new Split(amount.subtract(partner), partner);
    }

    /** Fees or penal charges as the arrangement shares them. */
    public static Split charges(Terms t, Sharing how, BigDecimal amount) {
        return how == Sharing.BY_SHARE ? byShare(t, amount) : new Split(amount, BigDecimal.ZERO);
    }

    /**
     * The partner's share booked: it funds its part of the principal outstanding into the escrow account, and the
     * originator's loan book is reduced to its own share by the contra head. Dr escrow / Cr partner's share (contra).
     */
    public static TransactionLot transfer(String branch, String escrowGl, String partnerShareGl, String loanNo, BigDecimal partnerPrincipal,
                                          LocalDate businessDate) {
        if (partnerPrincipal == null || partnerPrincipal.signum() <= 0) throw new IllegalArgumentException("the partner's principal is above zero");
        return TransactionLot.builder("CO_LENDING_TRANSFER", businessDate).valueDate(businessDate).reference(loanNo)
                .debit(branch, escrowGl, escrowGl, partnerPrincipal, "Co-lending: partner's share funded")
                .credit(branch, partnerShareGl, loanNo, partnerPrincipal, "Co-lending: partner's share of the loan")
                .build();
    }
}
