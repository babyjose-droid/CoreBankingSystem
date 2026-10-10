package com.corebanking.lending.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.lending.internal.InterestTableService.RowInput;
import com.corebanking.lending.internal.InterestTableService.TableInput;
import com.corebanking.platform.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InterestTableValidationTest {

    private static RowInput row(String minA, String maxA, int minT, int maxT, String rate) {
        return new RowInput(new BigDecimal(minA), new BigDecimal(maxA), minT, maxT, new BigDecimal(rate));
    }

    private static TableInput table(String mode, RowInput... rows) {
        return new TableInput("CLAUDE_TEST_T1", "CLAUDE-TEST table", mode, null, null, List.of(rows));
    }

    private static String refusal(TableInput in) {
        return assertThrows(ApiException.class, () -> InterestTableService.normalise(in)).getMessage();
    }

    @Test
    void a_valid_table_is_normalised_and_sorted_and_fixed_is_absolute() {
        Map<String, Object> p = InterestTableService.normalise(table("fixed",
                row("100001", "500000", 12, 60, "13.5"), row("0", "100000", 12, 60, "14")));
        assertEquals("ABSOLUTE", p.get("mode"));
        assertEquals("0", ((List<?>) p.get("rows")).isEmpty() ? "" : ((Map<?, ?>) ((List<?>) p.get("rows")).get(0)).get("minAmount"));
    }

    @Test
    void overlapping_bands_are_refused_but_touching_in_one_dimension_is_fine() {
        assertTrue(refusal(table("ABSOLUTE", row("0", "100000", 12, 36, "14"), row("100000", "200000", 24, 48, "13"))).contains("overlap"));
        // same amount band, tenor bands that do not meet: fine
        InterestTableService.normalise(table("ABSOLUTE", row("0", "100000", 12, 36, "14"), row("0", "100000", 37, 60, "13")));
    }

    @Test
    void rates_spreads_and_bands_have_sane_bounds() {
        assertTrue(refusal(table("ABSOLUTE", row("0", "10", 1, 12, "61"))).contains("0 to 60"));
        assertTrue(refusal(table("SPREAD", row("0", "10", 1, 12, "31"))).contains("0 to 30"));
        assertTrue(refusal(table("SPREAD", row("0", "10", 1, 12, "-1"))).contains("rate"));
        assertTrue(refusal(table("ABSOLUTE", row("0", "10", 1, 12, "10.12345"))).contains("four decimals"));
        assertTrue(refusal(table("ABSOLUTE", row("10", "5", 1, 12, "10"))).contains("amounts"));
        assertTrue(refusal(table("ABSOLUTE", row("0", "10", 0, 12, "10"))).contains("tenor"));
        assertTrue(refusal(table("ABSOLUTE", row("0", "10", 12, 6, "10"))).contains("tenor"));
        assertTrue(refusal(table("ABSOLUTE", row("0", "10", 1, 601, "10"))).contains("tenor"));
        assertTrue(refusal(table("BOGUS", row("0", "10", 1, 12, "10"))).contains("mode"));
        assertTrue(refusal(table("ABSOLUTE")).contains("at least one row"));
        assertTrue(refusal(new TableInput("bad code", "n", "ABSOLUTE", null, null, List.of(row("0", "10", 1, 12, "10")))).contains("code"));
        assertTrue(refusal(new TableInput("OK_CODE", "n", "SPREAD", BigDecimal.ONE, null, List.of(row("0", "10", 1, 12, "2")))).contains("baseRate"));
    }
}
