package com.corebanking.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Webhook payloads never carry unmasked PAN, Aadhaar or account numbers (US-121). All values are fake. */
class WebhookEventsTest {

    @Test
    void only_listed_fields_leave() {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("loanNo", "1001000000017");
        src.put("amount", "100000.00");
        src.put("customerName", "CLAUDE-TEST Borrower");
        src.put("mobile", "9000000001");
        src.put("pan", "ABCDE1234F");
        src.put("businessDate", "2026-10-20");
        src.put("externalRef", null);
        Map<String, Object> out = WebhookEvents.data(WebhookEvents.LOAN_DISBURSED, src);
        assertEquals(Map.of("loanNo", "1001000000017", "amount", "100000.00", "businessDate", "2026-10-20"), out);
    }

    @Test
    void pan_shaped_text_is_redacted_wherever_it_appears() {
        Map<String, Object> out = WebhookEvents.data(WebhookEvents.PAYMENT_BOUNCED,
                Map.of("loanNo", "1001000000017", "returnReason", "holder ABCDE1234F not found"));
        assertEquals("holder [redacted] not found", out.get("returnReason"));
        assertEquals("1001000000017", out.get("loanNo"));
    }

    @Test
    void aadhaar_shaped_numbers_are_redacted_but_ordinary_references_are_not() {
        // 999900001119 has a valid Verhoeff check digit (as Aadhaar numbers do); 999900001110 does not
        assertTrue(WebhookEvents.looksSensitive("id 9999 0000 1119 given"));
        assertTrue(WebhookEvents.looksSensitive("999900001119"));
        assertFalse(WebhookEvents.looksSensitive("999900001110"));
        assertFalse(WebhookEvents.looksSensitive("1001000000017"));          // a 13-digit loan number
        assertFalse(WebhookEvents.looksSensitive("90010000000013"));         // a 14-digit customer number
        Map<String, Object> out = WebhookEvents.data(WebhookEvents.PAYMENT_BOUNCED, Map.of("returnReason", "ref 9999-0000-1119"));
        assertEquals("ref [redacted]", out.get("returnReason"));
    }

    @Test
    void a_twelve_digit_bank_reference_survives_in_the_utr_field() {
        Map<String, Object> out = WebhookEvents.data(WebhookEvents.PAYMENT_RECEIVED, Map.of("utr", "999900001119", "amount", "5200.00"));
        assertEquals("999900001119", out.get("utr"));
    }

    @Test
    void account_fields_leave_only_when_masked() {
        assertEquals("XXXXXXXX1234", WebhookEvents.data(WebhookEvents.PAYOUT_STATUS,
                Map.of("beneficiaryAccountMasked", "XXXXXXXX1234")).get("beneficiaryAccountMasked"));
        assertEquals(WebhookEvents.REDACTED, WebhookEvents.data(WebhookEvents.PAYOUT_STATUS,
                Map.of("beneficiaryAccountMasked", "000000CLAUDE1234")).get("beneficiaryAccountMasked"));
        assertEquals(WebhookEvents.REDACTED, WebhookEvents.data(WebhookEvents.MANDATE_STATUS,
                Map.of("debitAccountMasked", "50100012345678")).get("debitAccountMasked"));
    }

    @Test
    void nested_values_are_dropped() {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("loanNo", "1001000000017");
        src.put("amount", Map.of("pan", "ABCDE1234F"));
        assertEquals(Map.of("loanNo", "1001000000017"), WebhookEvents.data(WebhookEvents.LOAN_CLOSED, src));
    }

    @Test
    void unknown_event_types_are_refused() {
        assertThrows(IllegalArgumentException.class, () -> WebhookEvents.data("customer.created", Map.of()));
        assertTrue(WebhookEvents.isType("loan.npa"));
        assertFalse(WebhookEvents.isType("loan.deleted"));
        assertEquals(7, WebhookEvents.TYPES.size());
        for (String t : WebhookEvents.TYPES) assertFalse(WebhookEvents.fields(t).isEmpty());
    }

    @Test
    void envelope_is_stable_json() {
        Map<String, Object> data = WebhookEvents.data(WebhookEvents.LOAN_CLOSED, Map.of("loanNo", "1001000000017"));
        assertEquals("{\"id\":\"e-1\",\"type\":\"loan.closed\",\"apiVersion\":\"v1\",\"tenant\":\"demo-nbfc\","
                        + "\"occurredAt\":\"2026-10-20T10:00:00Z\",\"data\":{\"loanNo\":\"1001000000017\"}}",
                WebhookEvents.envelope("e-1", WebhookEvents.LOAN_CLOSED, "demo-nbfc", "2026-10-20T10:00:00Z", data));
    }
}
