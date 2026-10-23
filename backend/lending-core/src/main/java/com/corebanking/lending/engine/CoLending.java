package com.corebanking.lending.engine;

import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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
    // ------------------------------------------------------------------------------------------------ P5-2: daily split

    /** Heads of the split and the settlement (settings {@code lending.co-lending.*}). */
    public record Heads(String partnerShare, String escrow, String partnerPayable, String servicingFeeIncome) {
        public static Heads starter() {
            return new Heads("1105", "1208", "2303", "4110");
        }
    }

    /** A fee the borrower paid since the partner took its share, without its GST, and the income head it was booked to. */
    public record FeeReceived(String incomeGl, BigDecimal amountExGst) {}

    /**
     * What the borrower has paid on a co-lent loan since the partner took its share (the loan's own figures, from
     * its state): the principal outstanding now, interest received, fees received (without GST) and penal charges
     * received.
     */
    public record Realised(BigDecimal partnerPrincipalAtTransfer, BigDecimal principalOutstanding, BigDecimal interestReceived,
                           List<FeeReceived> fees, BigDecimal penalReceived) {
        public Realised {
            fees = fees == null ? List.of() : List.copyOf(fees);
            penalReceived = penalReceived == null ? BigDecimal.ZERO : penalReceived;
        }
    }

    /**
     * What the partner has been given on one loan since it took its share, cumulative: principal repaid to it,
     * interest, fees by income head, penal charges, and the servicing fee taken from it (accrued unrounded, and
     * booked to the paisa).
     */
    public record Position(BigDecimal principal, BigDecimal interest, Map<String, BigDecimal> fees, BigDecimal penal,
                           BigDecimal servicingAccrued, BigDecimal servicing) {
        public static final Position NONE = new Position(BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        public Position {
            fees = fees == null ? Map.of() : Map.copyOf(new TreeMap<>(fees));
        }

        public BigDecimal feesTotal() {
            return fees.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** Owed to the partner: what it has been given less the servicing fee. */
        public BigDecimal net() {
            return principal.add(interest).add(feesTotal()).add(penal).subtract(servicing);
        }

        public Position minus(Position o) {
            Map<String, BigDecimal> f = new TreeMap<>(fees);
            o.fees.forEach((k, v) -> f.merge(k, v.negate(), BigDecimal::add));
            f.values().removeIf(v -> v.signum() == 0);
            return new Position(principal.subtract(o.principal), interest.subtract(o.interest), f, penal.subtract(o.penal),
                    servicingAccrued.subtract(o.servicingAccrued), servicing.subtract(o.servicing));
        }

        public boolean isZero() {
            return principal.signum() == 0 && interest.signum() == 0 && fees.values().stream().allMatch(v -> v.signum() == 0) && penal.signum() == 0
                    && servicing.signum() == 0;
        }
    }

    /** The partner's principal outstanding: its share of the loan's principal outstanding. */
    public static BigDecimal partnerOutstanding(Terms t, BigDecimal principalOutstanding) {
        return principalOutstanding.signum() <= 0 ? BigDecimal.ZERO : byShare(t, principalOutstanding).partner();
    }

    /** One day of servicing fee on the partner's principal outstanding (unrounded; % a year, 365 days). */
    public static BigDecimal servicingForDay(Terms t, BigDecimal partnerOutstanding) {
        if (t.servicingFee().signum() == 0 || partnerOutstanding.signum() <= 0) return BigDecimal.ZERO;
        return partnerOutstanding.multiply(t.servicingFee()).divide(new BigDecimal("36500"), 10, RoundingMode.HALF_UP);
    }

    /**
     * Where the partner should stand now: principal repaid to it is what it funded less its share of the principal
     * outstanding; interest received divides in the ratio share × rate; fees and penal charges as the arrangement
     * shares them. A reversal of a receipt lowers these, so the next split takes back what was given.
     */
    public static Position target(Terms t, Realised r, BigDecimal servicingAccrued) {
        BigDecimal principal = r.partnerPrincipalAtTransfer().subtract(partnerOutstanding(t, r.principalOutstanding()));
        BigDecimal interest = interest(t, r.interestReceived()).partner();
        Map<String, BigDecimal> fees = new TreeMap<>();
        if (t.fees() == Sharing.BY_SHARE) {
            Map<String, BigDecimal> byHead = new TreeMap<>();
            for (FeeReceived f : r.fees()) byHead.merge(f.incomeGl(), f.amountExGst(), BigDecimal::add);
            byHead.forEach((gl, amt) -> {
                BigDecimal part = byShare(t, amt).partner();
                if (part.signum() != 0) fees.put(gl, part);
            });
        }
        BigDecimal penal = charges(t, t.penal(), r.penalReceived()).partner();
        BigDecimal accrued = servicingAccrued == null ? BigDecimal.ZERO : servicingAccrued;
        return new Position(principal, interest, fees, penal, accrued, accrued.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * The day's split of one loan, from where the partner stood to where it stands now (null when nothing moved):
     * <ul>
     *   <li>principal repaid: Dr partner's share (contra) / Cr due to the partner;</li>
     *   <li>interest, fees and penal: Dr the income head / Cr due to the partner (the originator keeps only its own);</li>
     *   <li>servicing fee: Dr due to the partner / Cr servicing fee income.</li>
     * </ul>
     * A negative change (a receipt reversed) posts the other way round.
     */
    public static TransactionLot splitLot(String branch, Heads h, String loanNo, String interestIncomeGl, String penalIncomeGl,
                                          Position before, Position after, LocalDate businessDate) {
        Position d = after.minus(before);
        if (d.isZero()) return null;
        var b = TransactionLot.builder("CO_LENDING_SPLIT", businessDate).valueDate(businessDate).reference(loanNo);
        move(b, branch, h.partnerShare(), loanNo, h.partnerPayable(), loanNo, d.principal(), "Co-lending: partner's principal received " + loanNo);
        move(b, branch, interestIncomeGl, interestIncomeGl, h.partnerPayable(), loanNo, d.interest(), "Co-lending: partner's interest " + loanNo);
        d.fees().forEach((gl, amt) -> move(b, branch, gl, gl, h.partnerPayable(), loanNo, amt, "Co-lending: partner's share of fees " + loanNo));
        move(b, branch, penalIncomeGl, penalIncomeGl, h.partnerPayable(), loanNo, d.penal(), "Co-lending: partner's share of penal charges " + loanNo);
        move(b, branch, h.partnerPayable(), loanNo, h.servicingFeeIncome(), h.servicingFeeIncome(), d.servicing(), "Co-lending: servicing fee " + loanNo);
        return b.build();
    }

    /** Dr {@code drGl} / Cr {@code crGl} for a positive amount, the other way round for a negative one. */
    private static void move(TransactionLot.Builder b, String branch, String drGl, String drAccount, String crGl, String crAccount, BigDecimal amount,
                             String text) {
        if (amount == null || amount.signum() == 0) return;
        if (amount.signum() > 0) {
            b.debit(branch, drGl, drAccount, amount, text).credit(branch, crGl, crAccount, amount, text);
        } else {
            b.debit(branch, crGl, crAccount, amount.negate(), text).credit(branch, drGl, drAccount, amount.negate(), text);
        }
    }

    /** One loan in a settlement: what is paid to the partner for it (negative when the partner owes it back). */
    public record SettlementLine(String branch, String loanNo, BigDecimal net) {}

    /**
     * Paying the partner through the escrow account: Dr due to the partner on each loan / Cr escrow, per branch. A
     * loan whose net is negative (a receipt reversed after it was paid over) is set off against the others.
     */
    public static TransactionLot settlementLot(Heads h, List<SettlementLine> lines, String reference, LocalDate businessDate) {
        Map<String, BigDecimal> byBranch = new TreeMap<>();
        var b = TransactionLot.builder("CO_LENDING_SETTLEMENT", businessDate).valueDate(businessDate).reference(reference);
        BigDecimal total = BigDecimal.ZERO;
        for (SettlementLine l : lines) {
            if (l.net().signum() == 0) continue;
            if (l.net().signum() > 0) b.debit(l.branch(), h.partnerPayable(), l.loanNo(), l.net(), "Co-lending settlement " + reference);
            else b.credit(l.branch(), h.partnerPayable(), l.loanNo(), l.net().negate(), "Co-lending settlement " + reference);
            byBranch.merge(l.branch(), l.net(), BigDecimal::add);
            total = total.add(l.net());
        }
        if (total.signum() <= 0) throw new IllegalArgumentException("nothing is due to the partner");
        byBranch.forEach((branch, amt) -> {
            if (amt.signum() > 0) b.credit(branch, h.escrow(), h.escrow(), amt, "Co-lending settlement " + reference + ": paid from escrow");
            else if (amt.signum() < 0) b.debit(branch, h.escrow(), h.escrow(), amt.negate(), "Co-lending settlement " + reference + ": set off");
        });
        return b.build();
    }
}
