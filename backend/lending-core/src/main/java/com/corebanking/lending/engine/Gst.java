package com.corebanking.lending.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
}
