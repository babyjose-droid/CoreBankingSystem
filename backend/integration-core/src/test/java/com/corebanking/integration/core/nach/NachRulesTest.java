package com.corebanking.integration.core.nach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.integration.core.Lifecycle;
import com.corebanking.kernel.BusinessCalendar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class NachRulesTest {

    static final NachRules.Policy POLICY = new NachRules.Policy(3, 2);

    @Test
    void a_bounce_for_insufficient_funds_is_presented_again_until_the_limit() {
        assertTrue(NachRules.afterBounce(1, true, Lifecycle.Mandate.ACTIVE, POLICY).represent());
        assertTrue(NachRules.afterBounce(2, true, Lifecycle.Mandate.ACTIVE, POLICY).represent());
        NachRules.Representation last = NachRules.afterBounce(3, true, Lifecycle.Mandate.ACTIVE, POLICY);
        assertFalse(last.represent());
        assertEquals("presented 3 time(s): the limit is 3", last.reason());
    }

    @Test
    void a_reason_that_will_not_change_or_a_dead_mandate_stops_presentations() {
        assertFalse(NachRules.afterBounce(1, false, Lifecycle.Mandate.ACTIVE, POLICY).represent());
        assertFalse(NachRules.afterBounce(1, true, Lifecycle.Mandate.CANCELLED, POLICY).represent());
        assertFalse(NachRules.afterBounce(1, true, Lifecycle.Mandate.SUSPENDED, POLICY).represent());
        assertFalse(NachRules.afterBounce(1, true, Lifecycle.Mandate.ACTIVE, new NachRules.Policy(1, 2)).represent());
        assertThrows(IllegalArgumentException.class, () -> new NachRules.Policy(0, 2));
        assertThrows(IllegalArgumentException.class, () -> new NachRules.Policy(3, 0));
    }

    @Test
    void working_days_skip_sundays_and_holidays() {
        // Friday 30-Oct-2026; Sunday 1-Nov off; Monday 2-Nov a holiday
        BusinessCalendar cal = BusinessCalendar.nbfc(List.of(LocalDate.of(2026, 11, 2)));
        LocalDate friday = LocalDate.of(2026, 10, 30);
        assertEquals(friday, NachRules.workingDaysAfter(friday, 0, cal::isWorkingDay));
        assertEquals(LocalDate.of(2026, 10, 31), NachRules.workingDaysAfter(friday, 1, cal::isWorkingDay));
        assertEquals(LocalDate.of(2026, 11, 3), NachRules.workingDaysAfter(friday, 2, cal::isWorkingDay));
        assertEquals(LocalDate.of(2026, 11, 4), NachRules.workingDaysAfter(friday, 3, cal::isWorkingDay));
        assertThrows(IllegalArgumentException.class, () -> NachRules.workingDaysAfter(friday, -1, cal::isWorkingDay));
        assertThrows(IllegalStateException.class, () -> NachRules.workingDaysAfter(friday, 1, d -> false));
    }

    @Test
    void a_debit_must_be_covered_by_the_mandate() {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2029, 10, 31);
        assertNull(NachRules.coverage(new BigDecimal("5200"), LocalDate.of(2026, 11, 5), new BigDecimal("10000"), start, end));
        assertNull(NachRules.coverage(new BigDecimal("10000"), start, new BigDecimal("10000"), start, null));
        assertEquals("amount 10000.01 is above the mandate's maximum 10000",
                NachRules.coverage(new BigDecimal("10000.01"), LocalDate.of(2026, 11, 5), new BigDecimal("10000"), start, end));
        assertEquals("the mandate starts on 2026-11-01", NachRules.coverage(BigDecimal.ONE, LocalDate.of(2026, 10, 31), BigDecimal.TEN, start, end));
        assertEquals("the mandate ended on 2029-10-31", NachRules.coverage(BigDecimal.ONE, LocalDate.of(2029, 11, 1), BigDecimal.TEN, start, end));
    }
}
