package com.corebanking.integration.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.integration.core.Lifecycle;
import com.corebanking.integration.core.MiniJson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contract tests of the EASEBUZZ adapter skeletons (US-122) against a recording transport. They pin down what the
 * adapters send and how they read answers <b>as written from recollection of the public documentation</b>
 * ({@link EasebuzzSpec}); they do not show that Easebuzz accepts these requests. Expected hashes were computed
 * independently (Python hashlib) over the documented field sequences. Keys and salts are fake.
 */
class EasebuzzAdapterTest {

    static final ProviderSettings PG = new ProviderSettings(Map.of("environment", "test"),
            Map.of("key", "CLAUDETESTKEY", "salt", "CLAUDETESTSALT"));
    static final ProviderSettings WIRE = new ProviderSettings(Map.of("wireBaseUrl", "https://wire.example.invalid/"),
            Map.of("key", "CLAUDETESTKEY", "salt", "CLAUDETESTSALT"));

    static CollectionGateway.Order order() {
        return new CollectionGateway.Order("CO1001000000017N1", new BigDecimal("5200.00"), List.of("UPI"), "Loan repayment 1001000000017",
                "CLAUDE-TEST Borrower", "9000000001", "claude-test@example.invalid", null, "https://los.example.invalid/return");
    }

    @Test
    void initiate_request_is_a_form_post_with_the_documented_hash() {
        FakeTransport http = new FakeTransport().answer(200, "{\"status\":1,\"data\":\"abcdef0123456789abcdef\"}");
        CollectionGateway.Created c = new EasebuzzCollectionGateway(PG, http).create(order());
        HttpTransport.Request r = http.requests.get(0);
        assertEquals("POST", r.method());
        assertEquals("https://testpay.easebuzz.in/payment/initiateLink", r.url());
        assertEquals("application/x-www-form-urlencoded", r.headers().get("Content-Type"));
        Map<String, String> f = EasebuzzCollectionGateway.parseForm(r.body());
        assertEquals("CLAUDETESTKEY", f.get("key"));
        assertEquals("CO1001000000017N1", f.get("txnid"));
        assertEquals("5200.00", f.get("amount"));
        assertEquals("CLAUDE-TEST Borrower", f.get("firstname"));
        assertEquals("c3acba649d886a511ff7271f9b6e00d2fa764be7a4203ecab7a3b0ea89ecfadd92c944891952ed93d5795b9f55d64ce9a263cb3ccd6f21ebe5641a8547ea3ab4",
                f.get("hash"));
        assertFalse(r.body().contains("CLAUDETESTSALT"));            // the salt is hashed, never sent
        assertNull(f.get("salt"));
        assertEquals("abcdef0123456789abcdef", c.providerRef());
        assertEquals("https://testpay.easebuzz.in/pay/abcdef0123456789abcdef", c.paymentUrl());
    }

    @Test
    void production_environment_uses_the_production_host() {
        ProviderSettings prod = new ProviderSettings(Map.of("environment", "production"), PG.secrets());
        assertEquals("https://pay.easebuzz.in/payment/initiateLink", new EasebuzzCollectionGateway(prod, new FakeTransport()).initiateRequest(order()).url());
    }

    @Test
    void initiate_failures_are_retryable_only_when_the_outcome_is_unknown() {
        assertTrue(assertThrows(ProviderException.class,
                () -> new EasebuzzCollectionGateway(PG, new FakeTransport().answer(503, "")).create(order())).retryable());
        assertTrue(assertThrows(ProviderException.class,
                () -> new EasebuzzCollectionGateway(PG, new FakeTransport().answer(429, "")).create(order())).retryable());
        assertFalse(assertThrows(ProviderException.class,
                () -> new EasebuzzCollectionGateway(PG, new FakeTransport().answer(400, "{}")).create(order())).retryable());
        assertFalse(assertThrows(ProviderException.class,
                () -> new EasebuzzCollectionGateway(PG, new FakeTransport().answer(200, "{\"status\":0,\"data\":\"bad hash\"}")).create(order())).retryable());
        assertFalse(assertThrows(ProviderException.class,
                () -> new EasebuzzCollectionGateway(PG, new FakeTransport().answer(200, "<html>")).create(order())).retryable());
        assertFalse(assertThrows(ProviderException.class,
                () -> new EasebuzzCollectionGateway(ProviderSettings.empty(), new FakeTransport()).create(order())).retryable());
    }

    @Test
    void status_request_and_answers() {
        FakeTransport http = new FakeTransport()
                .answer(200, "{\"status\":true,\"msg\":{\"status\":\"success\",\"easepayid\":\"E2610CLAUDE01\",\"amount\":\"5200.0\",\"mode\":\"UPI\",\"bank_ref_num\":\"627300000001\"}}")
                .answer(200, "{\"status\":true,\"msg\":{\"status\":\"failure\",\"easepayid\":\"E2610CLAUDE02\",\"amount\":\"5200.0\"}}")
                .answer(200, "{\"status\":false,\"msg\":\"Transaction not found\"}");
        EasebuzzCollectionGateway g = new EasebuzzCollectionGateway(PG, http);
        CollectionGateway.Payment paid = g.status("CO1001000000017N1", null);
        HttpTransport.Request r = http.requests.get(0);
        assertEquals("https://testdashboard.easebuzz.in/transaction/v2.1/retrieve", r.url());
        assertEquals("0581c630fff2ec1a33ad0fe83db8ac305983def6070704aa4ab47631cd30bd3f65aba46df64c2c9da59804545278b1cb3dfff28f1f989c62eb4742b02054f954",
                EasebuzzCollectionGateway.parseForm(r.body()).get("hash"));
        assertEquals(Lifecycle.CollectionOrder.PAID, paid.status());
        assertEquals("E2610CLAUDE01", paid.providerPaymentId());
        assertEquals(0, new BigDecimal("5200").compareTo(paid.amount()));
        assertEquals("UPI", paid.method());
        assertEquals(Lifecycle.CollectionOrder.FAILED, g.status("CO1001000000017N1", null).status());
        assertEquals(Lifecycle.CollectionOrder.CREATED, g.status("CO1001000000017N1", null).status());     // not paid yet
    }

    static Map<String, String> callback(String status, String hash) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("key", "CLAUDETESTKEY");
        f.put("txnid", "CO1001000000017N1");
        f.put("amount", "5200.00");
        f.put("productinfo", "Loan repayment 1001000000017");
        f.put("firstname", "CLAUDE-TEST Borrower");
        f.put("email", "claude-test@example.invalid");
        f.put("status", status);
        f.put("easepayid", "E2610CLAUDE01");
        f.put("mode", "UPI");
        f.put("bank_ref_num", "627300000001");
        f.put("hash", hash);
        return f;
    }

    static final String SUCCESS_HASH = "c5ef82a8f9a6d41359cba884f8872bc2ddf49acbd7df963efcdb6f5887ac9c964c15fde0f21354fa1d00f173b7f78ca10767fea82394983d868773c2ddbac99d";
    static final String FAILURE_HASH = "2ae2477a4056cc585470e9fede47390890ba8ebe59e647ebf9d1d06471ea2eda470b40bb4b98c315109e22f74534b80047067ef4b2a72300c0704922e86e5941";

    static byte[] raw(Map<String, String> fields) {
        return EasebuzzCollectionGateway.form(fields).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void a_callback_with_the_right_reverse_hash_is_a_payment() {
        InboundEvent e = new EasebuzzCollectionGateway(PG, new FakeTransport()).parse(Map.of(), raw(callback("success", SUCCESS_HASH)), PG, 0);
        assertEquals(InboundEvent.Kind.PAYMENT, e.kind());
        assertEquals("PAID", e.status());
        assertEquals("E2610CLAUDE01:success", e.eventId());
        assertEquals("CO1001000000017N1", e.reference());
        assertEquals("E2610CLAUDE01", e.providerRef());
        assertEquals(new BigDecimal("5200.00"), e.amount());
        assertEquals("UPI", e.method());
        assertEquals("627300000001", e.utr());
        // upper-case hex is the same hash
        assertEquals("PAID", new EasebuzzCollectionGateway(PG, new FakeTransport())
                .parse(Map.of(), raw(callback("success", SUCCESS_HASH.toUpperCase(java.util.Locale.ROOT))), PG, 0).status());
    }

    @Test
    void a_failure_callback_is_a_failed_payment_with_its_own_event_id() {
        InboundEvent e = new EasebuzzCollectionGateway(PG, new FakeTransport()).parse(Map.of(), raw(callback("failure", FAILURE_HASH)), PG, 0);
        assertEquals("FAILED", e.status());
        assertEquals("E2610CLAUDE01:failure", e.eventId());
        assertEquals("failure", e.reasonCode());
    }

    @Test
    void a_callback_cannot_be_forged_or_altered() {
        EasebuzzCollectionGateway g = new EasebuzzCollectionGateway(PG, new FakeTransport());
        // a failure notice replayed with the status flipped to success: the hash covers the status
        assertThrows(WebhookRejectedException.class, () -> g.parse(Map.of(), raw(callback("success", FAILURE_HASH)), PG, 0));
        Map<String, String> more = callback("success", SUCCESS_HASH);
        more.put("amount", "52000.00");
        assertThrows(WebhookRejectedException.class, () -> g.parse(Map.of(), raw(more), PG, 0));
        Map<String, String> otherLoan = callback("success", SUCCESS_HASH);
        otherLoan.put("txnid", "CO1001000000025N1");
        assertThrows(WebhookRejectedException.class, () -> g.parse(Map.of(), raw(otherLoan), PG, 0));
        Map<String, String> noHash = callback("success", SUCCESS_HASH);
        noHash.remove("hash");
        assertThrows(WebhookRejectedException.class, () -> g.parse(Map.of(), raw(noHash), PG, 0));
        Map<String, String> otherMerchant = callback("success", SUCCESS_HASH);
        otherMerchant.put("key", "SOMEONEELSE");
        assertThrows(WebhookRejectedException.class, () -> g.parse(Map.of(), raw(otherMerchant), PG, 0));
        ProviderSettings otherSalt = new ProviderSettings(Map.of(), Map.of("key", "CLAUDETESTKEY", "salt", "ANOTHERSALT"));
        assertThrows(WebhookRejectedException.class, () -> g.parse(Map.of(), raw(callback("success", SUCCESS_HASH)), otherSalt, 0));
        assertThrows(WebhookRejectedException.class, () -> g.parse(Map.of(), new byte[0], PG, 0));
        assertThrows(WebhookRejectedException.class, () -> g.parse(Map.of(), "a=%zz".getBytes(StandardCharsets.UTF_8), PG, 0));
        assertThrows(WebhookRejectedException.class, () -> g.parse(Map.of(), raw(callback("success", SUCCESS_HASH)), ProviderSettings.empty(), 0));
    }

    // ---- payouts -------------------------------------------------------------------------------------------
    static PayoutGateway.Request payout() {
        return new PayoutGateway.Request("PO1001000000017A1", new BigDecimal("98200.00"),
                new PayoutGateway.Beneficiary("CLAUDE-TEST Borrower", "000000CLAUDE1234", "TEST0000001"), "IMPS", "Loan 1001000000017");
    }

    @Test
    void transfer_request_carries_the_documented_authorization_hash() {
        FakeTransport http = new FakeTransport().answer(200,
                "{\"success\":true,\"data\":{\"transfer_request\":{\"id\":\"TRCLAUDE01\",\"status\":\"success\",\"unique_transaction_reference\":\"627300000002\"}}}");
        PayoutGateway.Result res = new EasebuzzPayoutGateway(WIRE, http).send(payout());
        HttpTransport.Request r = http.requests.get(0);
        assertEquals("https://wire.example.invalid/api/v1/quick_transfers/initiate/", r.url());
        assertEquals("b316860a32af3177a692efeb82532be487a721396bcd4196737ec5fc5f12d3b5a4e6b5f77fe93498dbe1aee974755ae6bcc2cff91a986fd6f78f79b49e22e1ea",
                r.headers().get("Authorization"));
        assertEquals("CLAUDETESTKEY", r.headers().get("WIRE-API-KEY"));
        Map<String, Object> body = MiniJson.parseObject(r.body());
        assertEquals("PO1001000000017A1", body.get("unique_request_number"));
        assertEquals("98200.00", body.get("amount"));
        assertEquals("IMPS", body.get("payment_mode"));
        assertNull(body.get("salt"));
        assertEquals(Lifecycle.Payout.SUCCESS, res.status());
        assertEquals("627300000002", res.utr());
        assertEquals("TRCLAUDE01", res.providerRef());
    }

    @Test
    void only_an_explicit_success_is_success() {
        String pending = "{\"success\":true,\"data\":{\"transfer_request\":{\"id\":\"TR1\",\"status\":\"pending\"}}}";
        String odd = "{\"success\":true,\"data\":{\"transfer_request\":{\"id\":\"TR1\",\"status\":\"accepted_for_processing\"}}}";
        String failed = "{\"success\":true,\"data\":{\"transfer_request\":{\"id\":\"TR1\",\"status\":\"failure\",\"failure_reason\":\"Invalid IFSC\"}}}";
        assertEquals(Lifecycle.Payout.SENT, new EasebuzzPayoutGateway(WIRE, new FakeTransport().answer(200, pending)).send(payout()).status());
        assertEquals(Lifecycle.Payout.SENT, new EasebuzzPayoutGateway(WIRE, new FakeTransport().answer(200, odd)).send(payout()).status());
        assertEquals(Lifecycle.Payout.SENT, new EasebuzzPayoutGateway(WIRE, new FakeTransport().answer(200, "{}")).send(payout()).status());
        assertEquals(Lifecycle.Payout.SENT, new EasebuzzPayoutGateway(WIRE, new FakeTransport().answer(200, "<html>ok</html>")).send(payout()).status());
        PayoutGateway.Result f = new EasebuzzPayoutGateway(WIRE, new FakeTransport().answer(200, failed)).send(payout());
        assertEquals(Lifecycle.Payout.FAILED, f.status());
        assertEquals("Invalid IFSC", f.failureReason());
        PayoutGateway.Result refused = new EasebuzzPayoutGateway(WIRE, new FakeTransport().answer(401, "{\"message\":\"Unauthorized\"}")).send(payout());
        assertEquals(Lifecycle.Payout.FAILED, refused.status());
        assertEquals("HTTP_401", refused.failureCode());
    }

    @Test
    void an_unknown_outcome_is_retryable_and_never_a_failure() {
        assertTrue(assertThrows(ProviderException.class,
                () -> new EasebuzzPayoutGateway(WIRE, new FakeTransport().answer(502, "")).send(payout())).retryable());
        assertTrue(assertThrows(ProviderException.class,
                () -> new EasebuzzPayoutGateway(WIRE, new FakeTransport().fail(new ProviderException("timeout", true))).send(payout())).retryable());
    }

    @Test
    void what_is_not_confirmed_is_refused_not_guessed() {
        EasebuzzPayoutGateway g = new EasebuzzPayoutGateway(WIRE, new FakeTransport());
        assertFalse(assertThrows(ProviderException.class, () -> g.status("PO1", "TR1")).retryable());
        assertEquals(PayoutGateway.Validity.UNAVAILABLE, g.validate(payout().beneficiary(), "BV1").validity());
        assertThrows(WebhookRejectedException.class, () -> g.parse(Map.of(), "{}".getBytes(StandardCharsets.UTF_8), WIRE, 0));
        // no sandbox host is assumed: the base URL must be configured, and must be https
        assertThrows(ProviderException.class, () -> new EasebuzzPayoutGateway(PG, new FakeTransport()).send(payout()));
        ProviderSettings plain = new ProviderSettings(Map.of("wireBaseUrl", "http://wire.example.invalid"), WIRE.secrets());
        assertThrows(ProviderException.class, () -> new EasebuzzPayoutGateway(plain, new FakeTransport()).send(payout()));
    }

    // ---- generic SMS ---------------------------------------------------------------------------------------
    @Test
    void generic_sms_request_carries_the_dlt_ids() {
        ProviderSettings cfg = new ProviderSettings(Map.of("url", "https://sms.example.invalid/v1/send", "authHeader", "X-Api-Key"),
                Map.of("apiKey", "CLDTESTAPIKEY"));
        FakeTransport http = new FakeTransport().answer(202, "{\"id\":\"MSGCLAUDE01\"}").answer(500, "").answer(400, "{}")
                .answer(200, "accepted");
        GenericHttpSmsSender s = new GenericHttpSmsSender(cfg, http);
        SmsSender.Sms sms = new SmsSender.Sms("9000000001", "Dear CLAUDE-TEST, Rs 5200 is due.", "1101000000000000001",
                "1107000000000000001", "CLDTST", "m-1");
        MessageResult ok = s.send(sms);
        assertTrue(ok.accepted());
        assertEquals("MSGCLAUDE01", ok.providerRef());
        HttpTransport.Request r = http.requests.get(0);
        assertEquals("https://sms.example.invalid/v1/send", r.url());
        assertEquals("CLDTESTAPIKEY", r.headers().get("X-Api-Key"));
        Map<String, Object> body = MiniJson.parseObject(r.body());
        assertEquals("1101000000000000001", body.get("entityId"));
        assertEquals("1107000000000000001", body.get("templateId"));
        assertEquals("CLDTST", body.get("header"));
        assertEquals("m-1", body.get("reference"));
        assertTrue(s.send(sms).retryable());
        MessageResult refused = s.send(sms);
        assertFalse(refused.accepted());
        assertFalse(refused.retryable());
        MessageResult noId = s.send(sms);
        assertTrue(noId.accepted());
        assertNull(noId.providerRef());
        assertTrue(new GenericHttpSmsSender(cfg, new FakeTransport().fail(new ProviderException("timeout", true))).send(sms).retryable());
    }
}
