package com.corebanking.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageTemplateTest {

    static final String BODY = "Dear {{name}}, Rs {{amount}} for loan {{loan_no}} is due on {{ due_date }}. - CLAUDE-TEST NBFC";

    static Map<String, String> values() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", "CLAUDE-TEST Borrower");
        v.put("amount", "5,200.00");
        v.put("loan_no", "1001000000017");
        v.put("due_date", "05-Nov-2026");
        return v;
    }

    @Test
    void renders_every_placeholder() {
        assertEquals("Dear CLAUDE-TEST Borrower, Rs 5,200.00 for loan 1001000000017 is due on 05-Nov-2026. - CLAUDE-TEST NBFC",
                MessageTemplate.render(BODY, values(), MessageTemplate.SMS_VALUE_LIMIT));
    }

    @Test
    void lists_placeholders_in_order_of_first_use() {
        assertEquals(List.of("name", "amount", "loan_no", "due_date"), List.copyOf(MessageTemplate.placeholders(BODY)));
        assertEquals(List.of("a"), List.copyOf(MessageTemplate.placeholders("{{a}} and {{a}} again")));
    }

    @Test
    void a_missing_value_is_an_error_not_a_blank() {
        Map<String, String> v = values();
        v.remove("amount");
        MessageTemplate.TemplateException e = assertThrows(MessageTemplate.TemplateException.class,
                () -> MessageTemplate.render(BODY, v, MessageTemplate.SMS_VALUE_LIMIT));
        assertEquals("no value for placeholder 'amount'", e.getMessage());
        assertThrows(MessageTemplate.TemplateException.class, () -> MessageTemplate.render(BODY, null, 30));
    }

    @Test
    void a_value_the_template_does_not_use_is_an_error() {
        Map<String, String> v = values();
        v.put("otp", "123456");
        MessageTemplate.TemplateException e = assertThrows(MessageTemplate.TemplateException.class,
                () -> MessageTemplate.render(BODY, v, MessageTemplate.SMS_VALUE_LIMIT));
        assertEquals("the template has no placeholder 'otp'", e.getMessage());
    }

    @Test
    void values_are_length_limited_and_the_error_never_shows_the_value() {
        Map<String, String> v = values();
        v.put("name", "CLAUDE-TEST A Very Long Borrower Name Indeed");
        MessageTemplate.TemplateException e = assertThrows(MessageTemplate.TemplateException.class,
                () -> MessageTemplate.render(BODY, v, MessageTemplate.SMS_VALUE_LIMIT));
        assertEquals("the value for 'name' is longer than 30 characters", e.getMessage());
        assertFalse(e.getMessage().contains("Borrower"));
        MessageTemplate.render(BODY, v, MessageTemplate.EMAIL_VALUE_LIMIT);
    }

    @Test
    void values_cannot_inject_placeholders_or_control_characters() {
        for (String bad : List.of("{{loan_no}}", "a{b", "a}b", "line\nbreak", "tab\there", "nul\u0000")) {
            Map<String, String> v = values();
            v.put("name", bad);
            assertThrows(MessageTemplate.TemplateException.class, () -> MessageTemplate.render(BODY, v, 30), bad);
        }
    }

    @Test
    void malformed_templates_are_refused_when_proposed() {
        for (String bad : List.of("", "   ", "Hello {{name", "Hello name}}", "Hello {{}}", "Hello {{na me}}", "Hello {{1name}}",
                "Hello {{name|upper}}", "{{#if x}}y{{/if}}", "Hello {{{name}}}", "{{a}} }} {{b}}", "x".repeat(4001))) {
            assertThrows(MessageTemplate.TemplateException.class, () -> MessageTemplate.validate(bad), bad);
        }
        MessageTemplate.validate("No placeholders at all, and a single { brace } is plain text");
    }

    @Test
    void dlt_form_shows_what_must_be_registered_with_the_operator() {
        assertEquals("Dear {#var#}, Rs {#var#} for loan {#var#} is due on {#var#}. - CLAUDE-TEST NBFC", MessageTemplate.dltForm(BODY));
    }
}
