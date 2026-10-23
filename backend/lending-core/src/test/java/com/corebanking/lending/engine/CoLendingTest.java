package com.corebanking.lending.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.ledger.TransactionLot;
import com.corebanking.lending.engine.CoLending.Sharing;
import com.corebanking.lending.engine.CoLending.Split;
import com.corebanking.lending.engine.CoLending.Terms;
import com.corebanking.lending.engine.CoLending.FeeReceived;
import com.corebanking.lending.engine.CoLending.Heads;
import com.corebanking.lending.engine.CoLending.Position;
import com.corebanking.lending.engine.CoLending.Realised;
import com.corebanking.lending.engine.CoLending.SettlementLine;
import com.corebanking.ledger.PostingLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
    static BigDecimal sum(TransactionLot lot, String gl, String account, PostingLine.Side side) {
        return lot.lines().stream().filter(l -> l.glCode().equals(gl) && l.account().equals(account) && l.side() == side)
                .map(PostingLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void theDaysSplitGivesThePartnerItsShareOfWhatWasReceived() {
        Terms byShare = new Terms(bd("20"), bd("18"), bd("9.5"), bd("0.5"), Sharing.BY_SHARE, Sharing.BY_SHARE, bd("5"));
        // the partner funded 80,000; 5,000 of principal, 933.33 of interest, a 500 fee (ex GST) and 100 of penal since
        Realised r = new Realised(bd("80000"), bd("95000"), bd("933.33"), List.of(new FeeReceived("4102", bd("500"))), bd("100"));
        Position p = CoLending.target(byShare, r, bd("1.0958904110"));
        assertEquals(bd("4000.00"), p.principal(), "80,000 less 80% of 95,000");
        assertEquals(bd("633.33"), p.interest());
        assertEquals(Map.of("4102", bd("400.00")), p.fees());
        assertEquals(bd("80.00"), p.penal());
        assertEquals(bd("1.10"), p.servicing());
        assertEquals(bd("5112.23"), p.net(), "4,000 + 633.33 + 400 + 80 - 1.10");

        TransactionLot lot = CoLending.splitLot("HO", Heads.starter(), "L1", "4101", "4103", Position.NONE, p, LocalDate.of(2026, 7, 31));
        assertEquals("CO_LENDING_SPLIT", lot.type());
        assertEquals(bd("4000.00"), sum(lot, "1105", "L1", PostingLine.Side.DR));
        assertEquals(bd("633.33"), sum(lot, "4101", "4101", PostingLine.Side.DR));
        assertEquals(bd("400.00"), sum(lot, "4102", "4102", PostingLine.Side.DR));
        assertEquals(bd("80.00"), sum(lot, "4103", "4103", PostingLine.Side.DR));
        assertEquals(bd("1.10"), sum(lot, "4110", "4110", PostingLine.Side.CR));
        assertEquals(bd("5113.33"), sum(lot, "2303", "L1", PostingLine.Side.CR));
        assertEquals(bd("1.10"), sum(lot, "2303", "L1", PostingLine.Side.DR));
        assertEquals(null, CoLending.splitLot("HO", Heads.starter(), "L1", "4101", "4103", p, p, LocalDate.of(2026, 7, 31)), "nothing moved: no lot");

        // a receipt reversed: interest back to 300, principal outstanding back to 97,000: the split takes it back
        Position back = CoLending.target(byShare, new Realised(bd("80000"), bd("97000"), bd("300"), List.of(new FeeReceived("4102", bd("500"))), bd("100")),
                bd("1.0958904110"));
        TransactionLot rev = CoLending.splitLot("HO", Heads.starter(), "L1", "4101", "4103", p, back, LocalDate.of(2026, 8, 1));
        assertEquals(bd("1600.00"), sum(rev, "1105", "L1", PostingLine.Side.CR));
        assertEquals(bd("429.76"), sum(rev, "4101", "4101", PostingLine.Side.CR), "633.33 less 203.57 (the partner's part of 300)");
    }

    @Test
    void feesStayWithTheOriginatorUnlessShared() {
        Position p = CoLending.target(T, new Realised(bd("80000"), bd("100000"), bd("0"), List.of(new FeeReceived("4102", bd("500"))), bd("100")), null);
        assertTrue(p.fees().isEmpty(), "fees: ORIGINATOR");
        assertEquals(bd("80.00"), p.penal(), "penal: BY_SHARE");
        assertEquals(bd("0"), p.principal().stripTrailingZeros().setScale(0));
        assertEquals(bd("80000"), CoLending.target(T, new Realised(bd("80000"), bd("0"), bd("0"), null, null), null).principal(), "closed: all repaid");
    }

    @Test
    void servicingFeeAccruesOnThePartnersPrincipal() {
        assertEquals(new BigDecimal("1.0958904110"), CoLending.servicingForDay(T, bd("80000")), "80,000 × 0.5% / 365");
        assertEquals(BigDecimal.ZERO, CoLending.servicingForDay(T, BigDecimal.ZERO));
        assertEquals(bd("80000.00"), CoLending.partnerOutstanding(T, bd("100000")));
    }

    @Test
    void settlementPaysFromEscrowPerBranchAndSetsOffAReversal() {
        TransactionLot lot = CoLending.settlementLot(Heads.starter(), List.of(new SettlementLine("HO", "L1", bd("5112.23")),
                new SettlementLine("HO", "L2", bd("-12.23")), new SettlementLine("B2", "L3", bd("100"))), "CLS-1", LocalDate.of(2026, 8, 1));
        assertEquals(bd("5100.00"), sum(lot, "1208", "1208", PostingLine.Side.CR).subtract(bd("100")), "HO pays 5,100");
        assertEquals(bd("12.23"), sum(lot, "2303", "L2", PostingLine.Side.CR));
        assertThrows(IllegalArgumentException.class, () -> CoLending.settlementLot(Heads.starter(), List.of(new SettlementLine("HO", "L1", bd("-1"))), "x",
                LocalDate.of(2026, 8, 1)));
    }
    @Test
    void eachLenderProvisionsItsOwnShare() {
        assertEquals(bd("320.00"), CoLending.partnerProvision(T, bd("400")), "80% of a 400 provision is the partner's");
        TransactionLot up = CoLending.provisionLot("HO", "5102", "1109", "L1", BigDecimal.ZERO, bd("320.00"), LocalDate.of(2026, 9, 1));
        assertEquals(bd("320.00"), sum(up, "1109", "1109", PostingLine.Side.DR), "written back: Dr provision");
        assertEquals(bd("320.00"), sum(up, "5102", "5102", PostingLine.Side.CR));
        TransactionLot down = CoLending.provisionLot("HO", "5102", "1109", "L1", bd("320.00"), bd("20.00"), LocalDate.of(2026, 9, 2));
        assertEquals(bd("300.00"), sum(down, "1109", "1109", PostingLine.Side.CR));
        assertEquals(null, CoLending.provisionLot("HO", "5102", "1109", "L1", bd("20"), bd("20"), LocalDate.of(2026, 9, 2)));
    }

    @Test
    void defaultLossGuarantee() {
        assertEquals(bd("4000.00"), CoLending.dlgCap(T, bd("80000")), "5% of the partner's 80,000");
        CoLending.DlgHeads h = CoLending.DlgHeads.starter();
        TransactionLot cash = CoLending.dlgLodged(CoLending.DlgForm.CASH, "HO", h, "BANKX", bd("4000"), LocalDate.of(2026, 7, 1));
        assertEquals(bd("4000"), sum(cash, "1304", "BANKX", PostingLine.Side.DR));
        assertEquals(bd("4000"), sum(cash, "1202", "1202", PostingLine.Side.CR));
        assertEquals(null, CoLending.dlgLodged(CoLending.DlgForm.BANK_GUARANTEE, "HO", h, "BANKX", bd("4000"), LocalDate.of(2026, 7, 1)), "a guarantee is off the books");
        TransactionLot inv = CoLending.dlgInvoked(CoLending.DlgForm.CASH, "HO", h, "BANKX", "L1", bd("1500"), LocalDate.of(2026, 11, 1));
        assertEquals(bd("1500"), sum(inv, "5109", "5109", PostingLine.Side.DR));
        assertEquals(bd("1500"), sum(inv, "1304", "BANKX", PostingLine.Side.CR));
        TransactionLot bg = CoLending.dlgInvoked(CoLending.DlgForm.BANK_GUARANTEE, "HO", h, "BANKX", "L1", bd("1500"), LocalDate.of(2026, 11, 1));
        assertEquals(bd("1500"), sum(bg, "1202", "1202", PostingLine.Side.CR), "the bank pays under a guarantee");
        assertEquals(bd("200"), sum(CoLending.dlgRecovered("HO", h, "L1", bd("200"), LocalDate.of(2026, 12, 1)), "4111", "4111", PostingLine.Side.CR));
        assertEquals(bd("2500"), sum(CoLending.dlgReleased(CoLending.DlgForm.CASH, "HO", h, "BANKX", bd("2500"), LocalDate.of(2027, 1, 1)), "1304", "BANKX",
                PostingLine.Side.CR));
    }
}
