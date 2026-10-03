package com.corebanking.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ValueDateRuleTest {

    static final LocalDate BD = LocalDate.of(2026, 10, 20);

    @Test
    void paid_today_is_valued_today() {
        ValueDateRule.Decision d = ValueDateRule.decide(BD, BD, 3);
        assertEquals(BD, d.valueDate());
        assertFalse(d.adjusted());
        assertNull(d.note());
    }

    @Test
    void paid_within_the_back_value_limit_keeps_the_payment_date() {
        assertEquals(BD.minusDays(3), ValueDateRule.decide(BD.minusDays(3), BD, 3).valueDate());
        assertFalse(ValueDateRule.decide(BD.minusDays(1), BD, 3).adjusted());
    }

    @Test
    void paid_before_the_limit_is_valued_today_and_flagged() {
        ValueDateRule.Decision d = ValueDateRule.decide(BD.minusDays(4), BD, 3);
        assertEquals(BD, d.valueDate());
        assertTrue(d.adjusted());
        assertTrue(d.review());
    }

    @Test
    void paid_on_a_calendar_day_after_the_business_date_is_valued_on_the_business_date() {
        // e.g. paid on Sunday while the books are still on Saturday
        ValueDateRule.Decision d = ValueDateRule.decide(BD.plusDays(1), BD, 3);
        assertEquals(BD, d.valueDate());
        assertTrue(d.adjusted());
        assertFalse(d.review());
    }

    @Test
    void no_payment_date_means_the_business_date() {
        assertEquals(BD, ValueDateRule.decide(null, BD, 3).valueDate());
        assertEquals(BD, ValueDateRule.decide(BD.minusDays(1), BD, 0).valueDate());
        assertThrows(IllegalArgumentException.class, () -> ValueDateRule.decide(BD, BD, -1));
    }
}
