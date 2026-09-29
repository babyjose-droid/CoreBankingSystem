package com.corebanking.lending.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.corebanking.calc.FeeCalculator;
import com.corebanking.lending.engine.FeeRule;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.lending.engine.LoanTerms;
import com.corebanking.lending.engine.Provisioning;
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
}
