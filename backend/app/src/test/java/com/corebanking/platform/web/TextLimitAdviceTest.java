package com.corebanking.platform.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.platform.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextLimitAdviceTest {

    record Address(String line1, String pincode) {}

    record Body(String firstName, String reason, String csv, List<Address> addresses) {}

    @Test
    void a_long_free_text_field_is_refused_naming_the_field_but_not_the_text() {
        String secret = "CLAUDE-TEST-" + "x".repeat(600);
        ApiException e = assertThrows(ApiException.class, () -> TextLimitAdvice.check(new Body("A", secret, null, List.of()), 0));
        assertTrue(e.getMessage().contains("reason is at most 500"));
        assertFalse(e.getMessage().contains("CLAUDE-TEST-x"), "the text is never echoed");
    }

    @Test
    void nested_records_are_checked_and_content_fields_are_not() {
        assertThrows(ApiException.class, () -> TextLimitAdvice.check(new Body("A", "ok", null, List.of(new Address("y".repeat(201), "682001"))), 0));
        assertDoesNotThrow(() -> TextLimitAdvice.check(new Body("A", "ok", "z".repeat(100_000), List.of(new Address("y".repeat(200), "682001"))), 0));
    }
}
