package com.corebanking.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** US-121 acceptance: "HMAC verified by sample client". The receiver's side is {@link WebhookSignature#verify}. */
class WebhookSignatureTest {

    static final byte[] SIGNING = "CLAUDE-TEST-secret-0123456789abcd".getBytes(StandardCharsets.UTF_8);
    static final byte[] OLD = "CLAUDE-TEST-old-secret-0123456789".getBytes(StandardCharsets.UTF_8);
    static final String BODY = "{\"id\":\"evt_1\",\"type\":\"loan.disbursed\"}";
    static final long T = 1_790_000_000L;

    @Test
    void hashing_matches_published_vectors() {
        // RFC 4231 test case 2, and the FIPS 180 "abc" vectors
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                Hashing.hmacSha256Hex("Jefe".getBytes(StandardCharsets.UTF_8), "what do ya want for nothing?"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256Hex("abc"));
        assertEquals("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                Hashing.sha512Hex("abc"));
    }

    @Test
    void header_is_hmac_sha256_over_timestamp_dot_body() {
        // expected value computed independently (Python hmac/hashlib) over "1790000000." + body
        String header = WebhookSignature.header(List.of(new WebhookSignature.Key("k1", SIGNING)), T, BODY);
        assertEquals("t=1790000000,v1=k1:70191006db4c9a5a3af9d8db80e8f2575170add7745a62480cd4799a280ffc24", header);
    }

    @Test
    void sample_client_verifies_a_genuine_delivery() {
        String header = WebhookSignature.header(List.of(new WebhookSignature.Key("k1", SIGNING)), T, BODY);
        assertEquals(WebhookSignature.Verdict.VALID, WebhookSignature.verify(header, BODY, Map.of("k1", SIGNING), T + 12, 300));
    }

    @Test
    void a_changed_body_or_a_wrong_secret_fails() {
        String header = WebhookSignature.header(List.of(new WebhookSignature.Key("k1", SIGNING)), T, BODY);
        assertEquals(WebhookSignature.Verdict.BAD_SIGNATURE,
                WebhookSignature.verify(header, BODY.replace("evt_1", "evt_2"), Map.of("k1", SIGNING), T, 300));
        assertEquals(WebhookSignature.Verdict.BAD_SIGNATURE, WebhookSignature.verify(header, BODY, Map.of("k1", OLD), T, 300));
        assertEquals(WebhookSignature.Verdict.UNKNOWN_KEY, WebhookSignature.verify(header, BODY, Map.of("k9", SIGNING), T, 300));
    }

    @Test
    void a_replayed_delivery_is_stale_and_the_timestamp_cannot_be_refreshed() {
        String header = WebhookSignature.header(List.of(new WebhookSignature.Key("k1", SIGNING)), T, BODY);
        assertEquals(WebhookSignature.Verdict.STALE, WebhookSignature.verify(header, BODY, Map.of("k1", SIGNING), T + 301, 300));
        assertEquals(WebhookSignature.Verdict.STALE, WebhookSignature.verify(header, BODY, Map.of("k1", SIGNING), T - 301, 300));
        assertEquals(WebhookSignature.Verdict.VALID, WebhookSignature.verify(header, BODY, Map.of("k1", SIGNING), T + 300, 300));
        // the timestamp is part of the signed text: moving it forward breaks the signature
        String refreshed = header.replace("t=1790000000", "t=1790000900");
        assertEquals(WebhookSignature.Verdict.BAD_SIGNATURE, WebhookSignature.verify(refreshed, BODY, Map.of("k1", SIGNING), T + 900, 300));
    }

    @Test
    void during_rotation_both_secrets_sign_and_either_receiver_verifies() {
        String header = WebhookSignature.header(
                List.of(new WebhookSignature.Key("k2", SIGNING), new WebhookSignature.Key("k1", OLD)), T, BODY);
        assertTrue(header.startsWith("t=1790000000,v1=k2:"));
        assertTrue(header.contains(",v1=k1:"));
        assertEquals(WebhookSignature.Verdict.VALID, WebhookSignature.verify(header, BODY, Map.of("k1", OLD), T, 300));
        assertEquals(WebhookSignature.Verdict.VALID, WebhookSignature.verify(header, BODY, Map.of("k2", SIGNING), T, 300));
        // a receiver holding the right id with a wrong secret, and the other one right, still verifies
        assertEquals(WebhookSignature.Verdict.VALID, WebhookSignature.verify(header, BODY, Map.of("k2", OLD, "k1", OLD), T, 300));
    }

    @Test
    void malformed_headers_are_refused() {
        Map<String, byte[]> secrets = Map.of("k1", SIGNING);
        for (String bad : new String[] {null, "", "t=1790000000", "v1=k1:" + "a".repeat(64), "t=abc,v1=k1:" + "a".repeat(64),
                "t=1790000000,v1=k1:short", "t=1790000000,v1=:" + "a".repeat(64), "t=1790000000,t=1790000000,v1=k1:" + "a".repeat(64),
                "t=1790000000,v2=k1:" + "a".repeat(64), "t=1790000000,v1=k1:" + "A".repeat(64), "x".repeat(1001)}) {
            assertEquals(WebhookSignature.Verdict.MALFORMED, WebhookSignature.verify(bad, BODY, secrets, T, 300), String.valueOf(bad));
        }
    }

    @Test
    void keys_need_real_secrets_and_never_print_them() {
        assertThrows(IllegalArgumentException.class, () -> new WebhookSignature.Key("k1", "short".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> new WebhookSignature.Key("bad id", SIGNING));
        assertThrows(IllegalArgumentException.class, () -> WebhookSignature.header(List.of(), T, BODY));
        assertFalse(new WebhookSignature.Key("k1", SIGNING).toString().contains("CLAUDE"));
    }

    @Test
    void comparison_is_constant_time_and_null_safe() {
        assertTrue(Hashing.constantTimeEquals("abc", "abc"));
        assertFalse(Hashing.constantTimeEquals("abc", "abd"));
        assertFalse(Hashing.constantTimeEquals("abc", null));
        assertFalse(Hashing.constantTimeEquals(null, null));
    }
}
