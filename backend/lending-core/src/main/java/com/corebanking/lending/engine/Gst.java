package com.corebanking.lending.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * GST place of supply (US-106): intra-state supply → CGST + SGST (half each); inter-state → IGST. For a lender the
 * supplier state is the branch's state and the recipient state is the borrower's address state.
 */
public final class Gst {

    public record Split(BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
        public BigDecimal total() {
            return cgst.add(sgst).add(igst);
        }
    }

    private Gst() {}

    public static Split split(BigDecimal tax, String supplierState, String recipientState) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (tax == null || tax.signum() == 0) return new Split(zero, zero, zero);
        boolean intra = recipientState == null || recipientState.equals(supplierState);
        if (!intra) return new Split(zero, zero, tax.setScale(2, RoundingMode.HALF_UP));
        BigDecimal cgst = tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        return new Split(cgst, tax.setScale(2, RoundingMode.HALF_UP).subtract(cgst), zero);
    }

    /**
     * Whether a credit note issued on {@code noteDate} against an invoice of {@code invoiceDate} can still reduce the
     * output tax (CGST Act s.34(2)): it must be declared not later than 30 November following the end of the financial
     * year in which the supply was made. After that the fee can still be waived, but the tax already paid stays paid.
     */
    public static boolean creditNoteInTime(LocalDate invoiceDate, LocalDate noteDate) {
        int fyEndYear = invoiceDate.getMonthValue() >= 4 ? invoiceDate.getYear() + 1 : invoiceDate.getYear();
        return !noteDate.isAfter(LocalDate.of(fyEndYear, 11, 30));
    }

    /**
     * The taxable value and tax inside a tax-inclusive amount (a fee as charged to the account), for a credit note on
     * a waiver: taxable = amount / (1 + rate), tax = the rest, split by place of supply.
     */
    public record Inclusive(BigDecimal taxable, Split tax) {}

    public static Inclusive unbundle(BigDecimal grossAmount, BigDecimal gstRatePercent, String supplierState, String recipientState) {
        BigDecimal gross = grossAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxable = gross.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(100).add(gstRatePercent), 2, RoundingMode.HALF_UP);
        return new Inclusive(taxable, split(gross.subtract(taxable), supplierState, recipientState));
    }
}
