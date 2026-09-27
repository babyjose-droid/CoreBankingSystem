package com.corebanking.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class LedgerCoreTest {

    static final LocalDate BD = LocalDate.of(2026, 9, 27);
    static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test void balanced_single_branch_lot_has_no_ibr_leg() {
        var lot = TransactionLot.builder("DISBURSEMENT", BD)
                .debit("HO", "LOAN_PRINCIPAL", "10010000000017", bd("100000"), "Disbursal")
                .credit("HO", "BANK_HDFC", "BANK_HDFC", bd("99115"), "Net to borrower")
                .credit("HO", "FEE_INCOME", "FEE_INCOME", bd("750"), "Processing fee")
                .credit("HO", "GST_OUTPUT", "GST_OUTPUT", bd("135"), "GST 18%")
                .build();
        assertEquals(4, lot.lines().size());
    }

    @Test void unbalanced_lot_is_rejected() {
        var b = TransactionLot.builder("X", BD)
                .debit("HO", "A", "A", bd("100"), "")
                .credit("HO", "B", "B", bd("99.99"), "");
        assertThrows(LedgerException.class, b::build);
    }

    @Test void single_line_and_non_positive_amounts_rejected() {
        assertThrows(LedgerException.class, () -> TransactionLot.builder("X", BD).debit("HO", "A", "A", bd("1"), "").build());
        assertThrows(LedgerException.class, () -> TransactionLot.builder("X", BD).debit("HO", "A", "A", bd("0"), ""));
        assertThrows(LedgerException.class, () -> TransactionLot.builder("X", BD).debit("HO", "A", "A", bd("-5"), ""));
        assertThrows(LedgerException.class, () -> TransactionLot.builder("X", BD).debit("HO", "A", "A", bd("1.00001"), ""));
    }

    @Test void cross_branch_lot_gets_ibr_legs_and_each_branch_balances() {
        var lot = TransactionLot.builder("CASA_TRANSFER", BD)
                .debit("KOCHI", "CASA_SB", "20010000000014", bd("5000"), "Transfer out")
                .credit("MUMBAI", "CASA_SB", "20010000000022", bd("5000"), "Transfer in")
                .build();
        assertEquals(4, lot.lines().size());
        Map<String, BigDecimal> net = new TreeMap<>();
        for (var l : lot.lines()) net.merge(l.branch(), l.signed(), BigDecimal::add);
        net.values().forEach(v -> assertEquals(0, v.signum()));
        long ibr = lot.lines().stream().filter(l -> l.glCode().equals("IBR")).count();
        assertEquals(2, ibr);
    }

    @Test void reversal_mirrors_every_leg_and_nets_to_zero() {
        var lot = TransactionLot.builder("CHARGE", BD)
                .debit("HO", "CASA_SB", "20010000000014", bd("500"), "SMS charge")
                .credit("HO", "FEE_INCOME", "FEE_INCOME", bd("500"), "SMS charge")
                .build();
        var rev = TransactionLot.reversal(lot, BD.plusDays(1), "Customer complaint");
        assertEquals(lot.id(), rev.reverses());
        assertEquals(lot.lines().size(), rev.lines().size());
        Map<String, BigDecimal> net = new TreeMap<>();
        for (var l : lot.lines()) net.merge(l.account(), l.signed(), BigDecimal::add);
        for (var l : rev.lines()) net.merge(l.account(), l.signed(), BigDecimal::add);
        net.values().forEach(v -> assertEquals(0, v.signum()));
    }

    @Test void number_series_luhn_and_ownership() {
        var loans = new NumberSeries("1001", 9);
        String n = loans.format(1);
        assertEquals(14, n.length());
        assertTrue(NumberSeries.isValid(n));
        assertTrue(loans.owns(n));
        String typo = n.substring(0, 13) + ((n.charAt(13) - '0' + 1) % 10);
        assertFalse(NumberSeries.isValid(typo));
        assertEquals("79927398713", "7992739871" + NumberSeries.luhnDigit("7992739871")); // textbook Luhn example
    }

    @Test void default_family_prefixes_never_overlap() {
        var prefixes = NumberSeries.DEFAULT_PREFIX.values().stream().toList();
        for (int i = 0; i < prefixes.size(); i++)
            for (int j = i + 1; j < prefixes.size(); j++)
                assertTrue(NumberSeries.nonOverlapping(prefixes.get(i), prefixes.get(j)));
        var casa = new NumberSeries(NumberSeries.DEFAULT_PREFIX.get(NumberSeries.Family.CASA), 9);
        var fd = new NumberSeries(NumberSeries.DEFAULT_PREFIX.get(NumberSeries.Family.TERM_DEPOSIT), 9);
        Set<String> seen = new HashSet<>();
        for (long s = 1; s <= 2000; s++) {
            assertTrue(seen.add(casa.format(s)));
            assertTrue(seen.add(fd.format(s)));
        }
        assertFalse(casa.owns(fd.format(14)));
    }
}
