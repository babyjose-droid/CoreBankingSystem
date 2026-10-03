package com.corebanking.integration.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.integration.core.Lifecycle;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The simulator's outcomes are decided by the request alone. All data is fake. */
class SimulatorTest {

    static final PayoutGateway.Beneficiary OK = new PayoutGateway.Beneficiary("CLAUDE-TEST Borrower", "000000CLAUDE1234", "TEST0000001");

    static PayoutGateway.Request payout(String amount, PayoutGateway.Beneficiary b) {
        return new PayoutGateway.Request("PO1001000000017A1", new BigDecimal(amount), b, "IMPS", "Loan 1001000000017");
    }

    @Test
    void an_ordinary_payout_succeeds_with_a_utr_and_is_repeatable() {
        Simulator.Payout sim = new Simulator.Payout();
        PayoutGateway.Result a = sim.send(payout("98200.00", OK));
        PayoutGateway.Result b = sim.send(payout("98200.00", OK));
        assertEquals(Lifecycle.Payout.SUCCESS, a.status());
        assertTrue(a.utr().startsWith("SIMUTR"));
        assertEquals(a, b);                                   // same request, same answer: idempotent on the reference
        assertEquals(Lifecycle.Payout.SUCCESS, sim.status("PO1001000000017A1", a.providerRef()).status());
    }

    @Test
    void amounts_ending_in_01_fail() {
        Simulator.Payout sim = new Simulator.Payout();
        PayoutGateway.Result r = sim.send(payout("98200.01", OK));
        assertEquals(Lifecycle.Payout.FAILED, r.status());
        assertEquals("SIM_FAILED", r.failureCode());
        assertNull(r.utr());
        assertEquals(Lifecycle.Payout.FAILED, sim.status("PO1001000000017A1", r.providerRef()).status());
    }

    @Test
    void amounts_ending_in_02_are_pending_then_succeed_on_a_poll() {
        Simulator.Payout sim = new Simulator.Payout();
        PayoutGateway.Result r = sim.send(payout("98200.02", OK));
        assertEquals(Lifecycle.Payout.SENT, r.status());
        assertNull(r.utr());
        PayoutGateway.Result polled = sim.status("PO1001000000017A1", r.providerRef());
        assertEquals(Lifecycle.Payout.SUCCESS, polled.status());
        assertTrue(polled.utr().startsWith("SIMUTR"));
    }

    @Test
    void amounts_ending_in_03_succeed_and_are_later_returned() {
        Simulator.Payout sim = new Simulator.Payout();
        PayoutGateway.Result r = sim.send(payout("98200.03", OK));
        assertEquals(Lifecycle.Payout.SUCCESS, r.status());
        PayoutGateway.Result polled = sim.status("PO1001000000017A1", r.providerRef());
        assertEquals(Lifecycle.Payout.RETURNED, polled.status());
        assertEquals("SIM_RETURNED", polled.failureCode());
    }

    @Test
    void amounts_ending_in_04_never_answer_and_05_stay_pending() {
        Simulator.Payout sim = new Simulator.Payout();
        ProviderException e = assertThrows(ProviderException.class, () -> sim.send(payout("98200.04", OK)));
        assertTrue(e.retryable());
        PayoutGateway.Result r = sim.send(payout("98200.05", OK));
        assertEquals(Lifecycle.Payout.SENT, r.status());
        assertEquals(Lifecycle.Payout.SENT, sim.status("PO1001000000017A1", r.providerRef()).status());
    }

    @Test
    void account_patterns_choose_the_outcome_too() {
        Simulator.Payout sim = new Simulator.Payout();
        assertEquals(Lifecycle.Payout.FAILED,
                sim.send(payout("98200.00", new PayoutGateway.Beneficiary("CLAUDE-TEST", "000000CLAUDE9999", "TEST0000001"))).status());
        PayoutGateway.Result pending = sim.send(payout("98200.00", new PayoutGateway.Beneficiary("CLAUDE-TEST", "000000CLAUDE9998", "TEST0000001")));
        assertEquals(Lifecycle.Payout.SENT, pending.status());
        assertEquals(Lifecycle.Payout.SUCCESS, sim.status("PO1001000000017A1", pending.providerRef()).status());
        PayoutGateway.Result returned = sim.send(payout("98200.00", new PayoutGateway.Beneficiary("CLAUDE-TEST", "000000CLAUDE9997", "TEST0000001")));
        assertEquals(Lifecycle.Payout.RETURNED, sim.status("PO1001000000017A1", returned.providerRef()).status());
    }

    @Test
    void beneficiary_validation() {
        Simulator.Payout sim = new Simulator.Payout();
        PayoutGateway.Validation v = sim.validate(OK, "BV-1");
        assertEquals(PayoutGateway.Validity.VALID, v.validity());
        assertEquals("CLAUDE-TEST Borrower", v.nameAtBank());
        assertEquals(PayoutGateway.Validity.INVALID,
                sim.validate(new PayoutGateway.Beneficiary("CLAUDE-TEST", "000000CLAUDE0000", "TEST0000001"), "BV-2").validity());
        assertEquals(PayoutGateway.Validity.INVALID,
                sim.validate(new PayoutGateway.Beneficiary("CLAUDE-TEST", "000000CLAUDE1234", "FAIL0000001"), "BV-3").validity());
    }

    @Test
    void records_never_print_account_numbers_mobiles_or_secrets() {
        assertFalse(OK.toString().contains("000000CLAUDE1234"));
        assertTrue(OK.toString().contains("1234"));
        assertFalse(payout("1.00", OK).toString().contains("000000CLAUDE1234"));
        assertFalse(new SmsSender.Sms("9000000001", "x", "e", "t", "h", "r").toString().contains("9000000001"));
        assertFalse(new EmailSender.Email("claude-test@example.invalid", "s", "t", "f", "r").toString().contains("claude-test@"));
        assertFalse(new MandateProvider.Registration("M1", "CLAUDE-TEST", "000000CLAUDE1234", "TEST0000001", "SB", BigDecimal.TEN,
                "MONTHLY", LocalDate.of(2026, 11, 1), null, "TESTBANK", "TESTUTIL01").toString().contains("000000CLAUDE1234"));
        ProviderSettings s = new ProviderSettings(Map.of("environment", "test"), Map.of("salt", "CLAUDETESTSALT"));
        assertFalse(s.toString().contains("CLAUDETESTSALT"));
        assertTrue(s.toString().contains("salt"));
    }

    @Test
    void collection_orders_and_their_status() {
        Simulator.Collection sim = new Simulator.Collection();
        CollectionGateway.Order paid = new CollectionGateway.Order("CO1001000000017N1", new BigDecimal("5200.00"), List.of("UPI"),
                "Loan repayment", "CLAUDE-TEST Borrower", "9000000001", "claude-test@example.invalid", Instant.parse("2026-10-21T00:00:00Z"), null);
        CollectionGateway.Created c = sim.create(paid);
        assertTrue(c.paymentUrl().startsWith("https://pay.simulator.invalid/"));
        assertEquals(c, sim.create(paid));
        CollectionGateway.Payment p = sim.status(paid.reference(), c.providerRef());
        assertEquals(Lifecycle.CollectionOrder.PAID, p.status());
        assertTrue(p.providerPaymentId().startsWith("SIMPAY"));
        CollectionGateway.Order failing = new CollectionGateway.Order("CO1001000000017N2", new BigDecimal("5200.01"), List.of(),
                "Loan repayment", "CLAUDE-TEST Borrower", "9000000001", null, null, null);
        assertEquals(Lifecycle.CollectionOrder.FAILED, sim.status(failing.reference(), sim.create(failing).providerRef()).status());
        assertFalse(paid.toString().contains("9000000001"));
    }

    @Test
    void mandates_become_active_with_a_umrn_or_are_rejected() {
        Simulator.Mandate sim = new Simulator.Mandate();
        MandateProvider.Registration ok = new MandateProvider.Registration("MD-1", "CLAUDE-TEST", "000000CLAUDE1234", "TEST0000001", "SB",
                new BigDecimal("10000"), "MONTHLY", LocalDate.of(2026, 11, 1), LocalDate.of(2029, 11, 1), "TESTBANK", "TESTUTIL01");
        MandateProvider.State s = sim.status("MD-1", sim.register(ok).providerRef());
        assertEquals(Lifecycle.Mandate.ACTIVE, s.status());
        assertTrue(s.umrn().matches("SIMB[0-9]{16}"));
        assertEquals(s.umrn(), Simulator.Mandate.umrn("MD-1"));
        assertNotEquals(s.umrn(), Simulator.Mandate.umrn("MD-2"));
        MandateProvider.Registration bad = new MandateProvider.Registration("MD-3", "CLAUDE-TEST", "000000CLAUDE0001", "TEST0000001", "SB",
                new BigDecimal("10000"), "MONTHLY", LocalDate.of(2026, 11, 1), null, "TESTBANK", "TESTUTIL01");
        MandateProvider.State r = sim.status("MD-3", sim.register(bad).providerRef());
        assertEquals(Lifecycle.Mandate.REJECTED, r.status());
        assertEquals("SIM_REJECTED", r.rejectCode());
    }

    @Test
    void messages_are_accepted_or_refused_by_recipient() {
        Simulator.Sms sms = new Simulator.Sms();
        assertTrue(sms.send(new SmsSender.Sms("9000000002", "x", "e", "t", "h", "m1")).accepted());
        MessageResult hard = sms.send(new SmsSender.Sms("9000000000", "x", "e", "t", "h", "m2"));
        assertFalse(hard.accepted());
        assertFalse(hard.retryable());
        assertTrue(sms.send(new SmsSender.Sms("9000000001", "x", "e", "t", "h", "m3")).retryable());
        Simulator.Email email = new Simulator.Email();
        assertTrue(email.send(new EmailSender.Email("claude-test@example.invalid", "s", "t", null, "m4")).accepted());
        assertFalse(email.send(new EmailSender.Email("bounce@example.invalid", "s", "t", null, "m5")).accepted());
        assertTrue(email.send(new EmailSender.Email("retry@example.invalid", "s", "t", null, "m6")).retryable());
    }

    // ---- simulated provider callbacks ----------------------------------------------------------------------
    static final String SIGNING = "CLAUDE-TEST-simulator-secret-01";
    static final String OTHER_SIGNING = "CLAUDE-TEST-another-secret-0001";
    static final ProviderSettings CONFIG = new ProviderSettings(Map.of(), Map.of(Simulator.SECRET, SIGNING));
    static final long NOW = 1_790_000_000L;

    static InboundEvent event() {
        return new InboundEvent("sim-evt-1", InboundEvent.Kind.PAYMENT, "CO1001000000017N1", "SIMPAY0001", "PAID",
                new BigDecimal("5200.00"), "SIMRRN0001", "UPI", Instant.parse("2026-10-20T06:30:00Z"), null, null, null);
    }

    static Map<String, String> headers(String body, long at) {
        Map<String, String> h = new HashMap<>();
        h.put(Simulator.SIGNATURE_HEADER, Simulator.Webhooks.signature(SIGNING, at, body));
        return h;
    }

    @Test
    void a_signed_callback_is_read_back_exactly() {
        String body = Simulator.Webhooks.body(event());
        InboundEvent e = new Simulator.Webhooks().parse(headers(body, NOW), body.getBytes(StandardCharsets.UTF_8), CONFIG, NOW + 5);
        assertEquals(event(), e);
    }

    @Test
    void a_tampered_unsigned_or_stale_callback_is_rejected() {
        String body = Simulator.Webhooks.body(event());
        Simulator.Webhooks w = new Simulator.Webhooks();
        byte[] tampered = body.replace("5200.00", "52000.00").getBytes(StandardCharsets.UTF_8);
        assertThrows(WebhookRejectedException.class, () -> w.parse(headers(body, NOW), tampered, CONFIG, NOW));
        assertThrows(WebhookRejectedException.class, () -> w.parse(Map.of(), body.getBytes(StandardCharsets.UTF_8), CONFIG, NOW));
        assertThrows(WebhookRejectedException.class, () -> w.parse(headers(body, NOW), body.getBytes(StandardCharsets.UTF_8), CONFIG, NOW + 301));
        ProviderSettings other = new ProviderSettings(Map.of(), Map.of(Simulator.SECRET, OTHER_SIGNING));
        assertThrows(WebhookRejectedException.class, () -> w.parse(headers(body, NOW), body.getBytes(StandardCharsets.UTF_8), other, NOW));
        assertThrows(WebhookRejectedException.class,
                () -> w.parse(headers(body, NOW), body.getBytes(StandardCharsets.UTF_8), ProviderSettings.empty(), NOW));
    }

    @Test
    void a_signed_callback_with_an_unreadable_body_is_rejected() {
        Simulator.Webhooks w = new Simulator.Webhooks();
        for (String body : List.of("not json", "[1]", "{\"eventId\":\"e\",\"kind\":\"REFUND\",\"status\":\"PAID\"}",
                "{\"kind\":\"PAYMENT\",\"status\":\"PAID\"}", "{\"eventId\":\"e\",\"kind\":\"PAYMENT\"}",
                "{\"eventId\":\"e\",\"kind\":\"PAYMENT\",\"status\":\"PAID\",\"amount\":\"abc\"}",
                "{\"eventId\":\"e\",\"kind\":\"PAYMENT\",\"status\":\"PAID\",\"occurredAt\":\"yesterday\"}")) {
            assertThrows(WebhookRejectedException.class, () -> w.parse(headers(body, NOW), body.getBytes(StandardCharsets.UTF_8), CONFIG, NOW), body);
        }
    }

    @Test
    void the_catalogue_knows_every_provider_and_which_are_unverified() {
        assertTrue(ProviderCatalog.spec(ProviderKind.PAYOUT, "SIMULATOR").verified());
        assertFalse(ProviderCatalog.spec(ProviderKind.PAYOUT, "EASEBUZZ").verified());
        assertFalse(ProviderCatalog.spec(ProviderKind.COLLECTION, "EASEBUZZ").verified());
        assertFalse(ProviderCatalog.spec(ProviderKind.SMS, "GENERIC_HTTP").verified());
        assertTrue(ProviderCatalog.spec(ProviderKind.PAYOUT, "EASEBUZZ").note().startsWith("UNVERIFIED-AGAINST-PROVIDER"));
        assertEquals(java.util.Set.of("key", "salt"), ProviderCatalog.spec(ProviderKind.COLLECTION, "EASEBUZZ").secrets());
        assertThrows(IllegalArgumentException.class, () -> ProviderCatalog.spec(ProviderKind.MANDATE, "EASEBUZZ"));
        assertThrows(IllegalArgumentException.class, () -> ProviderCatalog.spec(ProviderKind.PAYOUT, "RAZORPAY"));
        for (ProviderKind k : ProviderKind.values()) assertEquals("SIMULATOR", ProviderCatalog.spec(k, "SIMULATOR").code());
        // no name is both a plain setting and a secret
        for (ProviderCatalog.Spec s : ProviderCatalog.all()) {
            for (String secret : s.secrets()) assertFalse(s.settings().contains(secret), s.code() + " " + secret);
            assertTrue(s.secrets().containsAll(s.requiredSecrets()));
        }
        FakeTransport http = new FakeTransport();
        assertTrue(ProviderCatalog.payout("SIMULATOR", ProviderSettings.empty(), http) instanceof Simulator.Payout);
        assertTrue(ProviderCatalog.payout("EASEBUZZ", ProviderSettings.empty(), http) instanceof EasebuzzPayoutGateway);
        assertTrue(ProviderCatalog.collection("EASEBUZZ", ProviderSettings.empty(), http) instanceof EasebuzzCollectionGateway);
        assertTrue(ProviderCatalog.sms("GENERIC_HTTP", ProviderSettings.empty(), http) instanceof GenericHttpSmsSender);
        assertTrue(ProviderCatalog.inbound(ProviderKind.MANDATE, "SIMULATOR", ProviderSettings.empty(), http) instanceof Simulator.Webhooks);
        assertThrows(WebhookRejectedException.class, () -> ProviderCatalog.inbound(ProviderKind.SMS, "GENERIC_HTTP", ProviderSettings.empty(), http));
    }
}
