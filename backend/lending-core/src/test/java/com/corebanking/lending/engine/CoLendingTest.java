package com.corebanking.lending.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.ledger.TransactionLot;
import com.corebanking.lending.engine.CoLending.Sharing;
import com.corebanking.lending.engine.CoLending.Split;
import com.corebanking.lending.engine.CoLending.Terms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Co-lending (P5-1): the Directions' limits, the blended rate and how amounts divide between the lenders. */
class CoLendingTest {

    static BigDecimal bd(String s) { return new BigDecimal(s); }

    /** The common 20:80 arrangement: the NBFC at 18% on 20%, the bank at 9.5% on 80%. */
    static final Terms T = new Terms(bd("20"), bd("18"), bd("9.5"), bd("0.5"), Sharing.ORIGINATOR, Sharing.BY_SHARE, bd("5"));

    @Test
    void limitsOfTheDirections() {
        assertTrue(CoLending.problems(T).isEmpty());
        assertTrue(CoLending.problems(new Terms(bd("10"), bd("18"), bd("9"), null, null, null, null)).isEmpty(), "10% is the least either lender keeps");
        assertTrue(CoLending.problems(new Terms(bd("90"), bd("18"), bd("9"), null, null, null, null)).isEmpty());
        List<String> p = CoLending.problems(new Terms(bd("9.99"), bd("18"), bd("0"), bd("1"), null, null, bd("5.01")));
        assertTrue(p.stream().anyMatch(x -> x.contains("at least 10%")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("above zero")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("at most 5%")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("servicing fee")), p.toString());
        assertTrue(CoLending.problems(new Terms(bd("90.01"), bd("18"), bd("9"), null, null, null, null)).stream().anyMatch(x -> x.contains("10 to 90%")));
    }

    @Test
    void blendedRateIsTheWeightedAverage() {
        assertEquals(bd("11.20"), T.blendedRate(), "0.2 × 18 + 0.8 × 9.5");
        assertEquals(bd("80"), T.partnerShare());
        assertEquals(bd("12.33"), new Terms(bd("33.33"), bd("15"), bd("11"), null, null, null, null).blendedRate());
    }

    @Test
    void amountsDivideAndAddUp() {
        Split principal = CoLending.byShare(T, bd("100000.01"));
        assertEquals(bd("80000.01"), principal.partner());
        assertEquals(bd("20000.00"), principal.originator());
        assertEquals(bd("100000.01"), principal.total());
        // a month's interest at the blended rate on 1,00,000: each lender earns its own rate on its own share
        Split interest = CoLending.interest(T, bd("933.33"));
        assertEquals(bd("633.33"), interest.partner(), "80,000 × 9.5% / 12 = 633.33");
        assertEquals(bd("300.00"), interest.originator(), "20,000 × 18% / 12 = 300.00");
        assertEquals(bd("500"), CoLending.charges(T, Sharing.ORIGINATOR, bd("500")).originator());
        assertEquals(bd("400.00"), CoLending.charges(T, Sharing.BY_SHARE, bd("500")).partner());
    }

    @Test
    void transferWindowAndLot() {
        assertEquals(LocalDate.of(2026, 7, 16), CoLending.transferDeadline(LocalDate.of(2026, 7, 1)));
        TransactionLot lot = CoLending.transfer("HO", "1208", "1105", "10010000000017", bd("80000"), LocalDate.of(2026, 7, 10));
        assertEquals("CO_LENDING_TRANSFER", lot.type());
        assertEquals(2, lot.lines().size());
        assertThrows(IllegalArgumentException.class, () -> CoLending.transfer("HO", "1208", "1105", "x", BigDecimal.ZERO, LocalDate.of(2026, 7, 10)));
    }
}
