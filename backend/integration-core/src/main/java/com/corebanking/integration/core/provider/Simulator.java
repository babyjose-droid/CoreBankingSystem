package com.corebanking.integration.core.provider;

import com.corebanking.integration.core.Hashing;
import com.corebanking.integration.core.Lifecycle;
import com.corebanking.integration.core.MiniJson;
import com.corebanking.integration.core.WebhookSignature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The built-in SIMULATOR provider: every port, no network, no money. Outcomes are decided by the request alone, so
 * a test (or a demo) chooses the outcome by choosing the amount or the account, and the same request always gives
 * the same answer.
 *
 * <table>
 *   <caption>Outcome rules</caption>
 *   <tr><th>Port</th><th>Trigger</th><th>Outcome</th></tr>
 *   <tr><td>Payout</td><td>amount ends in .01, or account ends in 9999</td><td>FAILED (SIM_FAILED)</td></tr>
 *   <tr><td>Payout</td><td>amount ends in .02, or account ends in 9998</td><td>SENT; a status poll then says SUCCESS</td></tr>
 *   <tr><td>Payout</td><td>amount ends in .03, or account ends in 9997</td><td>SUCCESS; a status poll then says RETURNED</td></tr>
 *   <tr><td>Payout</td><td>amount ends in .04</td><td>no answer (retryable error) on every attempt</td></tr>
 *   <tr><td>Payout</td><td>amount ends in .05</td><td>SENT and stays SENT (waits for a callback)</td></tr>
 *   <tr><td>Payout</td><td>anything else</td><td>SUCCESS with a UTR</td></tr>
 *   <tr><td>Beneficiary check</td><td>account ends in 0000, or IFSC starts with FAIL</td><td>INVALID</td></tr>
 *   <tr><td>Collection</td><td>amount ends in .01</td><td>status poll says FAILED; otherwise PAID</td></tr>
 *   <tr><td>Mandate</td><td>account ends in 0001</td><td>status poll says REJECTED (SIM_REJECTED); otherwise ACTIVE with a UMRN</td></tr>
 *   <tr><td>SMS</td><td>number ends in 0000 / 0001</td><td>refused permanently / temporarily</td></tr>
 *   <tr><td>E-mail</td><td>address starts with bounce@ / retry@</td><td>refused permanently / temporarily</td></tr>
 * </table>
 * Provider callbacks are simulated too: {@link Webhooks} signs and verifies them with the tenant's
 * {@code webhookSecret}, using the same scheme as our outbound webhooks.
 * <p>
 * The simulator must never be the active provider of a production tenant: a simulated payout reports SUCCESS
 * without any money moving. The app only offers it when the deployment enables it.
 */
public final class Simulator {

    public static final String CODE = "SIMULATOR";
    public static final String SIGNATURE_HEADER = "x-simulator-signature";
    public static final String SECRET = "webhookSecret";

    private Simulator() {}

    static int paise(BigDecimal amount) {
        return amount.movePointRight(2).remainder(BigDecimal.valueOf(100)).intValue();
    }

    static String token(String prefix, String reference) {
        return prefix + Hashing.sha256Hex(reference).substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private static boolean endsWith(String value, String suffix) {
        return value != null && value.endsWith(suffix);
    }

    // ------------------------------------------------------------------------------------------------ payout
    /**
     * The simulator keeps no state. The reference it returns from {@code send} carries the outcome a later poll
     * will report (its sixth character), so {@code status} answers from that reference alone.
     */
    public static final class Payout implements PayoutGateway {

        @Override
        public Validation validate(Beneficiary b, String reference) {
            if (endsWith(b.accountNumber(), "0000") || (b.ifsc() != null && b.ifsc().startsWith("FAIL"))) {
                return new Validation(Validity.INVALID, null, token("SIMBV", reference), "account not found at the bank (simulated)");
            }
            return new Validation(Validity.VALID, b.holderName(), token("SIMBV", reference), null);
        }

        /** F failed, R returned after success, P pending for ever, T no answer, S success. */
        private static char outcome(Request r) {
            int p = paise(r.amount());
            String account = r.beneficiary().accountNumber();
            if (p == 4) return 'T';
            if (p == 1 || endsWith(account, "9999")) return 'F';
            if (p == 3 || endsWith(account, "9997")) return 'R';
            if (p == 5) return 'P';
            return 'S';
        }

        @Override
        public Result send(Request r) {
            char outcome = outcome(r);
            String ref = "SIMPO" + outcome + token("", r.reference());
            if (outcome == 'T') throw new ProviderException("the simulated gateway did not answer", true);
            if (outcome == 'F') return new Result(Lifecycle.Payout.FAILED, ref, null, "SIM_FAILED", "payout failed (simulated)");
            if (outcome == 'P' || paise(r.amount()) == 2 || endsWith(r.beneficiary().accountNumber(), "9998")) {
                return new Result(Lifecycle.Payout.SENT, ref, null, null, null);
            }
            return new Result(Lifecycle.Payout.SUCCESS, ref, token("SIMUTR", r.reference()), null, null);
        }

        @Override
        public Result status(String reference, String providerRef) {
            char outcome = providerRef != null && providerRef.length() > 5 ? providerRef.charAt(5) : 'S';
            if (outcome == 'T') throw new ProviderException("the simulated gateway did not answer", true);
            return switch (outcome) {
                case 'F' -> new Result(Lifecycle.Payout.FAILED, providerRef, null, "SIM_FAILED", "payout failed (simulated)");
                case 'R' -> new Result(Lifecycle.Payout.RETURNED, providerRef, token("SIMUTR", reference), "SIM_RETURNED",
                        "returned by the beneficiary bank (simulated)");
                case 'P' -> new Result(Lifecycle.Payout.SENT, providerRef, null, null, null);
                default -> new Result(Lifecycle.Payout.SUCCESS, providerRef, token("SIMUTR", reference), null, null);
            };
        }
    }

    // ------------------------------------------------------------------------------------------------ collection
    public static final class Collection implements CollectionGateway {

        @Override
        public Created create(Order o) {
            String ref = "SIMORD" + (paise(o.amount()) == 1 ? 'F' : 'P') + token("", o.reference());
            // .invalid never resolves (RFC 2606): the link is a placeholder, the payment arrives as a simulated callback
            return new Created(ref, "https://pay.simulator.invalid/" + ref, o.expiresAt());
        }

        /** The amount and time are not known to the stateless simulator (null): the caller uses the order's own. */
        @Override
        public Payment status(String reference, String providerRef) {
            if (providerRef != null && providerRef.length() > 6 && providerRef.charAt(6) == 'F') {
                return new Payment(Lifecycle.CollectionOrder.FAILED, null, null, null, null, null);
            }
            return new Payment(Lifecycle.CollectionOrder.PAID, token("SIMPAY", reference), null, "UPI", null, token("SIMRRN", reference));
        }
    }

    // ------------------------------------------------------------------------------------------------ mandate
    public static final class Mandate implements MandateProvider {

        @Override
        public Submitted register(Registration r) {
            String ref = "SIMMD" + (endsWith(r.accountNumber(), "0001") ? 'R' : 'A') + token("", r.reference());
            return new Submitted(ref, "https://mandate.simulator.invalid/" + ref);
        }

        @Override
        public State status(String reference, String providerRef) {
            if (providerRef != null && providerRef.length() > 5 && providerRef.charAt(5) == 'R') {
                return new State(Lifecycle.Mandate.REJECTED, null, "SIM_REJECTED", "rejected by the destination bank (simulated)");
            }
            return new State(Lifecycle.Mandate.ACTIVE, umrn(reference), null, null);
        }

        /** A UMRN-shaped value: 20 characters, four letters then sixteen digits. */
        public static String umrn(String reference) {
            String hex = Hashing.sha256Hex(reference);
            StringBuilder digits = new StringBuilder();
            for (int i = 0; digits.length() < 16; i++) digits.append(Character.digit(hex.charAt(i), 16) % 10);
            return "SIMB" + digits;
        }
    }

    // ------------------------------------------------------------------------------------------------ messages
    public static final class Sms implements SmsSender {
        @Override
        public MessageResult send(SmsSender.Sms sms) {
            if (endsWith(sms.to(), "0000")) return MessageResult.refused("number not reachable (simulated)", false);
            if (endsWith(sms.to(), "0001")) return MessageResult.refused("operator busy (simulated)", true);
            return MessageResult.accepted(token("SIMSMS", sms.reference()));
        }
    }

    public static final class Email implements EmailSender {
        @Override
        public MessageResult send(EmailSender.Email email) {
            String to = email.to() == null ? "" : email.to().toLowerCase(Locale.ROOT);
            if (to.startsWith("bounce@")) return MessageResult.refused("mailbox does not exist (simulated)", false);
            if (to.startsWith("retry@")) return MessageResult.refused("mail server busy (simulated)", true);
            return MessageResult.accepted(token("SIMEML", email.reference()));
        }
    }

    // ------------------------------------------------------------------------------------------------ callbacks
    /** The simulated provider's callbacks: a JSON body signed like our own outbound webhooks. */
    public static final class Webhooks implements InboundWebhookParser {

        /** The body of a simulated callback. */
        public static String body(InboundEvent e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventId", e.eventId());
            m.put("kind", e.kind().name());
            m.put("reference", e.reference());
            m.put("providerRef", e.providerRef());
            m.put("status", e.status());
            m.put("amount", e.amount() == null ? null : e.amount().toPlainString());
            m.put("utr", e.utr());
            m.put("method", e.method());
            m.put("occurredAt", e.occurredAt() == null ? null : e.occurredAt().toString());
            m.put("reasonCode", e.reasonCode());
            m.put("reason", e.reason());
            m.put("umrn", e.umrn());
            return MiniJson.write(m);
        }

        public static String signature(String secret, long nowSeconds, String body) {
            return WebhookSignature.header(List.of(new WebhookSignature.Key("sim", secret.getBytes(StandardCharsets.UTF_8))),
                    nowSeconds, body);
        }

        @Override
        public InboundEvent parse(Map<String, String> headers, byte[] rawBody, ProviderSettings config, long nowSeconds) {
            String secret;
            try {
                secret = config.requireSecret(SECRET);
            } catch (ProviderException e) {
                throw new WebhookRejectedException("the simulator's webhookSecret is not configured");
            }
            String body = new String(rawBody, StandardCharsets.UTF_8);
            WebhookSignature.Verdict v = WebhookSignature.verify(headers.get(SIGNATURE_HEADER), body,
                    Map.of("sim", secret.getBytes(StandardCharsets.UTF_8)), nowSeconds, WebhookSignature.DEFAULT_TOLERANCE_SECONDS);
            if (v != WebhookSignature.Verdict.VALID) throw new WebhookRejectedException("signature check failed: " + v);
            Map<String, Object> m;
            try {
                m = MiniJson.parseObject(body);
            } catch (MiniJson.JsonException e) {
                throw new WebhookRejectedException("the body is not a JSON object");
            }
            InboundEvent.Kind kind;
            try {
                kind = InboundEvent.Kind.valueOf(String.valueOf(m.get("kind")));
            } catch (IllegalArgumentException e) {
                throw new WebhookRejectedException("unknown kind");
            }
            Instant at = null;
            String when = MiniJson.string(m, "occurredAt");
            if (when != null) {
                try {
                    at = Instant.parse(when);
                } catch (DateTimeParseException e) {
                    throw new WebhookRejectedException("occurredAt is not a timestamp");
                }
            }
            BigDecimal amount;
            try {
                amount = MiniJson.decimal(m, "amount");
            } catch (MiniJson.JsonException e) {
                throw new WebhookRejectedException("amount is not a number");
            }
            return new InboundEvent(MiniJson.string(m, "eventId"), kind, MiniJson.string(m, "reference"),
                    MiniJson.string(m, "providerRef"), MiniJson.string(m, "status"), amount, MiniJson.string(m, "utr"),
                    MiniJson.string(m, "method"), at, MiniJson.string(m, "reasonCode"), MiniJson.string(m, "reason"),
                    MiniJson.string(m, "umrn"));
        }
    }
}
