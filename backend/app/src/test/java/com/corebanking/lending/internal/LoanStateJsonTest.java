package com.corebanking.lending.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.FeeCalculator;
import com.corebanking.lending.engine.Amendment;
import com.corebanking.lending.engine.FeeRule;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.lending.engine.LoanTerms;
import com.corebanking.lending.engine.Provisioning;
import com.corebanking.lending.engine.RestructureTerms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Loan state is persisted as JSON; a round trip must be exact or reversals would not restore the loan. */
class LoanStateJsonTest {

    final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void params_terms_and_snapshot_round_trip_exactly() {
        LocalDate open = LocalDate.of(2026, 6, 30);
        var pf = new FeeRule("PF", "Processing fee", FeeRule.Event.DISBURSEMENT, FeeRule.CalcType.SLAB, null, null,
                List.of(new FeeRule.Slab(new BigDecimal("0"), new BigDecimal("200000"), new BigDecimal("750"))), null, null,
                new BigDecimal("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, true);
        var params = new LoanAccount.Params("10010000000017", "HO", "32", "27", new BigDecimal("18"), new BigDecimal("24"),
                null, null, null, null, 3, BigDecimal.ZERO, null, List.of(pf));
        var terms = LoanTerms.equated(new BigDecimal("100000"), new BigDecimal("18"), 12, open);
        var book = LoanAccount.disburse(params, terms, open);
        LoanAccount a = book.account();
        for (LocalDate d = open; !d.isAfter(LocalDate.of(2026, 8, 3)); d = d.plusDays(1)) a.endOfDay(d, Provisioning.starter());

        LoanAccount.Params p2 = mapper.readValue(mapper.writeValueAsString(params), LoanAccount.Params.class);
        LoanTerms t2 = mapper.readValue(mapper.writeValueAsString(terms), LoanTerms.class);
        LoanAccount.Snapshot s2 = mapper.readValue(mapper.writeValueAsString(a.snapshot()), LoanAccount.Snapshot.class);
        assertEquals(params, p2);
        assertEquals(terms, t2);
        assertEquals(a.snapshot(), s2);

        LoanAccount b = LoanAccount.restore(p2, s2);
        var r1 = a.endOfDay(LocalDate.of(2026, 8, 4), Provisioning.starter());
        var r2 = b.endOfDay(LocalDate.of(2026, 8, 4), Provisioning.starter());
        assertEquals(r1.summary(), r2.summary());
        assertEquals(a.snapshot(), b.snapshot());
    }

    /** P2-3 fields: current rate, rescheduled/capitalised demand amounts, capitalised suspense, restructured flag. */
    @Test
    void amended_and_restructured_state_round_trips_exactly() {
        LocalDate open = LocalDate.of(2026, 6, 30);
        var params = new LoanAccount.Params("10010000000017", "HO", "32", "32", new BigDecimal("18"), new BigDecimal("24"),
                null, null, null, null, 3, BigDecimal.ZERO, null, List.of());
        LoanAccount a = LoanAccount.disburse(params, LoanTerms.equated(new BigDecimal("100000"), new BigDecimal("18"), 12, open), open).account();
        for (LocalDate d = open; !d.isAfter(LocalDate.of(2026, 7, 31)); d = d.plusDays(1)) a.endOfDay(d, Provisioning.starter());
        a.pay(new BigDecimal("9168"), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), "EMI");
        for (LocalDate d = LocalDate.of(2026, 8, 1); !d.isAfter(LocalDate.of(2026, 8, 9)); d = d.plusDays(1)) a.endOfDay(d, Provisioning.starter());
        a.amend(Amendment.rate(new BigDecimal("20"), Amendment.RateResetOption.KEEP_TENURE_CHANGE_EMI, 24), LocalDate.of(2026, 8, 10));
        for (LocalDate d = LocalDate.of(2026, 8, 10); !d.isAfter(LocalDate.of(2026, 9, 9)); d = d.plusDays(1)) a.endOfDay(d, Provisioning.starter());
        a.restructure(new RestructureTerms(null, 18, 2, RestructureTerms.OverdueInterest.CAPITALISE, 36, "hardship"), LocalDate.of(2026, 9, 10));

        LoanAccount.Snapshot s2 = mapper.readValue(mapper.writeValueAsString(a.snapshot()), LoanAccount.Snapshot.class);
        assertEquals(a.snapshot(), s2);
        LoanAccount b = LoanAccount.restore(params, s2);
        assertEquals(new BigDecimal("20"), b.ratePercent());
        assertEquals(a.restructureStatus(), b.restructureStatus());
        var r1 = a.endOfDay(LocalDate.of(2026, 9, 10), Provisioning.starter());
        var r2 = b.endOfDay(LocalDate.of(2026, 9, 10), Provisioning.starter());
        assertEquals(r1.summary(), r2.summary());
        assertEquals(a.snapshot(), b.snapshot());
    }

    /** State stored before P2-3 has none of the new fields: it must still load, at the booked rate. */
    @Test
    void state_stored_before_amendments_still_loads() {
        LocalDate open = LocalDate.of(2026, 6, 30);
        var params = new LoanAccount.Params("10010000000017", "HO", "32", "32", new BigDecimal("18"), new BigDecimal("24"),
                null, null, null, null, 3, BigDecimal.ZERO, null, List.of());
        LoanAccount a = LoanAccount.disburse(params, LoanTerms.equated(new BigDecimal("100000"), new BigDecimal("18"), 12, open), open).account();
        for (LocalDate d = open; !d.isAfter(LocalDate.of(2026, 8, 3)); d = d.plusDays(1)) a.endOfDay(d, Provisioning.starter());
        // state stored before P2-6 has no terms, sanctioned amount, tranches, pre-EMI flag, advance interest or override
        java.util.Map<?, ?> old = mapper.readValue(mapper.writeValueAsString(a.snapshot()), java.util.Map.class);
        for (String key : List.of("terms", "sanctioned", "tranches", "preEmi", "interestInAdvance", "classFloor", "classFloorUntil")) {
            assertTrue(old.containsKey(key), key);
            old.remove(key);
        }
        String json = mapper.writeValueAsString(old)
                .replace(",\"ratePercent\":18", "").replace(",\"capitalisedSuspense\":0", "").replace(",\"restructure\":null", "")
                .replace(",\"principalRescheduled\":0", "").replace(",\"interestCapitalised\":0", "");
        assertTrue(!json.contains("ratePercent") && !json.contains("principalRescheduled") && !json.contains("restructure"), json);
        assertTrue(!json.contains("terms") && !json.contains("tranches") && !json.contains("classFloor"), json);
        LoanAccount b = LoanAccount.restore(params, mapper.readValue(json, LoanAccount.Snapshot.class));
        assertEquals(new BigDecimal("18"), b.ratePercent());
        assertEquals(a.demands(), b.demands());
        assertEquals(a.principalOutstanding(), b.principalOutstanding());
        assertEquals(new BigDecimal("100000"), b.sanctioned());
        assertTrue(b.fullyDrawn() && b.tranches().isEmpty() && b.terms() == null);
    }

    /** P2-6 fields: terms with their options, tranches, pre-EMI flag, interest in advance and the class override. */
    @Test
    void tranche_and_override_state_round_trips_exactly() {
        LocalDate open = LocalDate.of(2026, 6, 30);
        var params = new LoanAccount.Params("10010000000018", "HO", "32", "32", new BigDecimal("12"), new BigDecimal("24"),
                null, null, null, null, 3, BigDecimal.ZERO, null, List.of());
        LoanTerms terms = new LoanTerms(new BigDecimal("300000"), new BigDecimal("12"), 24, open, LocalDate.of(2026, 8, 5),
                com.corebanking.lending.engine.RepaymentMethod.EQUATED, 0, null, null, null, false,
                LoanTerms.Options.NONE.withBpi(LoanTerms.BpiMode.DEDUCT_AT_DISBURSAL));
        LoanTerms t2 = mapper.readValue(mapper.writeValueAsString(terms), LoanTerms.class);
        assertEquals(terms, t2);
        LoanAccount a = LoanAccount.open(params, terms, new BigDecimal("100000"), true, open).account();
        a.endOfDay(open, Provisioning.starter());
        a.drawTranche(new BigDecimal("50000"), open.plusDays(1));
        a.overrideAssetClass(com.corebanking.lending.engine.Delinquency.AssetClass.SUBSTANDARD, open.plusDays(90), open.plusDays(1));

        LoanAccount.Snapshot s2 = mapper.readValue(mapper.writeValueAsString(a.snapshot()), LoanAccount.Snapshot.class);
        assertEquals(a.snapshot(), s2);
        LoanAccount b = LoanAccount.restore(params, s2);
        assertEquals(2, b.tranches().size());
        assertEquals(new BigDecimal("150000"), b.undrawn());
        assertTrue(b.preEmi());
        assertEquals(a.interestInAdvance(), b.interestInAdvance());
        assertEquals(a.classFloor(), b.classFloor());
        var r1 = a.endOfDay(open.plusDays(1), Provisioning.starter());
        var r2 = b.endOfDay(open.plusDays(1), Provisioning.starter());
        assertEquals(r1.summary(), r2.summary());
        assertEquals(a.snapshot(), b.snapshot());
    }
}
