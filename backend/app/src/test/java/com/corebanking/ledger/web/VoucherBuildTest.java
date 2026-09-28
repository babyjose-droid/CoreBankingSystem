package com.corebanking.ledger.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.corebanking.ledger.LedgerException;
import com.corebanking.platform.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class VoucherBuildTest {

    static final LocalDate BD = LocalDate.of(2026, 6, 30);

    static VoucherService.Input voucher(VoucherService.Line... lines) {
        return new VoucherService.Input("JOURNAL", BD, "CLAUDE-TEST", "test voucher", List.of(lines));
    }

    @Test
    void balanced_voucher_builds_a_lot() {
        var lot = VoucherService.build(voucher(
                new VoucherService.Line("HO", "1202", null, "DR", "885", null),
                new VoucherService.Line("HO", "4102", null, "CR", "750", null),
                new VoucherService.Line("HO", "2201", null, "CR", "67.50", null),
                new VoucherService.Line("HO", "2202", null, "CR", "67.50", null)), BD, BD, "1900");
        assertEquals(4, lot.lines().size());
        assertEquals("1202", lot.lines().get(0).account());   // account defaults to the GL code
    }

    @Test
    void unbalanced_voucher_is_rejected() {
        assertThrows(LedgerException.class, () -> VoucherService.build(voucher(
                new VoucherService.Line("HO", "1202", null, "DR", "100", null),
                new VoucherService.Line("HO", "4102", null, "CR", "99.99", null)), BD, BD, "1900"));
    }

    @Test
    void cross_branch_voucher_gets_inter_branch_legs() {
        var lot = VoucherService.build(voucher(
                new VoucherService.Line("HO", "1202", null, "DR", "5000", null),
                new VoucherService.Line("MUM", "1203", null, "CR", "5000", null)), BD, BD, "1900");
        assertEquals(2, lot.lines().stream().filter(l -> l.glCode().equals("1900")).count());
    }

    @Test
    void non_decimal_amount_is_a_client_error() {
        assertThrows(ApiException.class, () -> VoucherService.build(voucher(
                new VoucherService.Line("HO", "1202", null, "DR", "1,000", null),
                new VoucherService.Line("HO", "4102", null, "CR", "1000", null)), BD, BD, "1900"));
    }
}
