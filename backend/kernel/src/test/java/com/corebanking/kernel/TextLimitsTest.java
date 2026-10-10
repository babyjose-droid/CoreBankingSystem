package com.corebanking.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextLimitsTest {

    @Test
    void a_field_has_the_same_limit_wherever_it_appears() {
        assertEquals(500, TextLimits.maxFor("reason"));
        assertEquals(500, TextLimits.maxFor("overrideReason"));
        assertEquals(500, TextLimits.maxFor("rejectReason"));
        assertEquals(1000, TextLimits.maxFor("note"));
        assertEquals(100, TextLimits.maxFor("firstName"));
        assertEquals(200, TextLimits.maxFor("line1"));
        assertEquals(-1, TextLimits.maxFor("csv"), "content fields are bounded by the request size, not here");
        assertEquals(-1, TextLimits.maxFor(null));
    }

    @Test
    void only_a_value_over_the_limit_is_a_violation() {
        assertNull(TextLimits.violation("reason", "x".repeat(500)));
        assertTrue(TextLimits.violation("reason", "x".repeat(501)).contains("at most 500"));
        assertNull(TextLimits.violation("reason", null));
        assertNull(TextLimits.violation("csv", "x".repeat(100_000)));
    }
}
