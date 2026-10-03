package com.corebanking.integration.core.provider;

import com.corebanking.integration.core.Hashing;
import com.corebanking.integration.core.Lifecycle;
import com.corebanking.integration.core.MiniJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>UNVERIFIED-AGAINST-PROVIDER</b> skeleton of Easebuzz collections (payment gateway). See {@link EasebuzzSpec}:
 * nothing here has been run against Easebuzz. What is built: create a payment (initiate link), the status
 * enquiry, and verification of the payment callback by its reverse hash. The callback parser also serves as the
 * inbound webhook parser.
 * <p>
 * Settings: {@code environment} (test | production). Secrets: {@code key}, {@code salt}.
 */
public final class EasebuzzCollectionGateway implements CollectionGateway, InboundWebhookParser {

    private final ProviderSettings config;
    private final HttpTransport http;

    public EasebuzzCollectionGateway(ProviderSettings config, HttpTransport http) {
        this.config = config;
        this.http = http;
    }

    private boolean production() {
        return "production".equalsIgnoreCase(config.setting("environment", "test"));
    }

    static String amount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** SHA-512 over the named fields joined with '|'; a field that is absent contributes an empty string. */
    static String hash(List<String> sequence, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sequence.size(); i++) {
            if (i > 0) sb.append('|');
            String v = fields.get(sequence.get(i));
            sb.append(v == null ? "" : v);
        }
        return Hashing.sha512Hex(sb.toString());
    }

    static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getValue() == null) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return out;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new WebhookRejectedException("the body is not form-encoded");
            }
        }
        return out;
    }

    /** The initiate request, separate from sending it so that it can be checked in tests. */
    HttpTransport.Request initiateRequest(Order o) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("key", config.requireSecret("key"));
        f.put("txnid", o.reference());
        f.put("amount", amount(o.amount()));
        f.put("productinfo", o.description());
        f.put("firstname", o.payerName());
        f.put("phone", o.payerMobile());
        f.put("email", o.payerEmail());
        f.put("surl", o.returnUrl());
        f.put("furl", o.returnUrl());
        Map<String, String> hashed = new LinkedHashMap<>(f);
        hashed.put("salt", config.requireSecret("salt"));
        f.put("hash", hash(EasebuzzSpec.PG_REQUEST_HASH_SEQUENCE, hashed));
        String base = production() ? EasebuzzSpec.PG_BASE_PRODUCTION : EasebuzzSpec.PG_BASE_TEST;
        return new HttpTransport.Request("POST", base + EasebuzzSpec.PG_INITIATE_PATH,
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"), form(f));
    }

    @Override
    public Created create(Order o) {
        HttpTransport.Response r = http.send(initiateRequest(o));
        if (r.status() >= 500 || r.status() == 429) throw new ProviderException("Easebuzz answered " + r.status(), true);
        if (!r.ok()) throw new ProviderException("Easebuzz refused the payment request (" + r.status() + ")", false);
        Map<String, Object> m;
        try {
            m = MiniJson.parseObject(r.body());
        } catch (MiniJson.JsonException e) {
            throw new ProviderException("Easebuzz's answer could not be read", false);
        }
        String accessKey = MiniJson.string(m, "data");
        if (!"1".equals(MiniJson.string(m, "status")) || accessKey == null || !accessKey.matches("[A-Za-z0-9]{8,200}")) {
            throw new ProviderException("Easebuzz did not return an access key", false);
        }
        String base = production() ? EasebuzzSpec.PG_BASE_PRODUCTION : EasebuzzSpec.PG_BASE_TEST;
        return new Created(accessKey, base + EasebuzzSpec.PG_PAY_PATH + accessKey, o.expiresAt());
    }

    HttpTransport.Request statusRequest(String reference) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("key", config.requireSecret("key"));
        f.put("txnid", reference);
        Map<String, String> hashed = new LinkedHashMap<>(f);
        hashed.put("salt", config.requireSecret("salt"));
        f.put("hash", hash(EasebuzzSpec.PG_STATUS_HASH_SEQUENCE, hashed));
        return new HttpTransport.Request("POST", production() ? EasebuzzSpec.PG_STATUS_URL_PRODUCTION : EasebuzzSpec.PG_STATUS_URL_TEST,
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"), form(f));
    }

    @Override
    public Payment status(String reference, String providerRef) {
        HttpTransport.Response r = http.send(statusRequest(reference));
        if (r.status() >= 500 || r.status() == 429) throw new ProviderException("Easebuzz answered " + r.status(), true);
        if (!r.ok()) throw new ProviderException("Easebuzz refused the status enquiry (" + r.status() + ")", false);
        Map<String, Object> m;
        try {
            m = MiniJson.parseObject(r.body());
        } catch (MiniJson.JsonException e) {
            throw new ProviderException("Easebuzz's answer could not be read", false);
        }
        Map<String, Object> msg = MiniJson.object(m.get("msg"));
        String status = MiniJson.string(msg, EasebuzzSpec.PG_FIELD_STATUS);
        if (status == null) return new Payment(Lifecycle.CollectionOrder.CREATED, null, null, null, null, null);
        boolean paid = EasebuzzSpec.PG_STATUS_SUCCESS.equalsIgnoreCase(status);
        return new Payment(paid ? Lifecycle.CollectionOrder.PAID : Lifecycle.CollectionOrder.FAILED,
                MiniJson.string(msg, EasebuzzSpec.PG_FIELD_PAYMENT_ID), MiniJson.decimal(msg, EasebuzzSpec.PG_FIELD_AMOUNT),
                MiniJson.string(msg, EasebuzzSpec.PG_FIELD_MODE), null, MiniJson.string(msg, EasebuzzSpec.PG_FIELD_BANK_REF));
    }

    /** The payment callback: form fields, authenticated by the reverse hash with our salt. */
    @Override
    public InboundEvent parse(Map<String, String> headers, byte[] rawBody, ProviderSettings cfg, long nowSeconds) {
        Map<String, String> f = parseForm(new String(rawBody, StandardCharsets.UTF_8));
        String given = f.get(EasebuzzSpec.PG_FIELD_HASH);
        String key;
        String salt;
        try {
            key = cfg.requireSecret("key");
            salt = cfg.requireSecret("salt");
        } catch (ProviderException e) {
            throw new WebhookRejectedException("Easebuzz key and salt are not configured");
        }
        if (!Hashing.constantTimeEquals(key, f.get("key"))) throw new WebhookRejectedException("the callback is for another merchant key");
        Map<String, String> hashed = new LinkedHashMap<>(f);
        hashed.put("salt", salt);
        String expected = hash(EasebuzzSpec.PG_RESPONSE_HASH_SEQUENCE, hashed);
        if (given == null || !Hashing.constantTimeEquals(expected, given.toLowerCase(java.util.Locale.ROOT))) {
            throw new WebhookRejectedException("hash check failed");
        }
        String status = f.get(EasebuzzSpec.PG_FIELD_STATUS);
        String paymentId = f.get(EasebuzzSpec.PG_FIELD_PAYMENT_ID);
        if (status == null || paymentId == null || paymentId.isBlank()) throw new WebhookRejectedException("status or payment id is missing");
        boolean paid = EasebuzzSpec.PG_STATUS_SUCCESS.equalsIgnoreCase(status);
        BigDecimal amount;
        try {
            amount = new BigDecimal(f.get(EasebuzzSpec.PG_FIELD_AMOUNT));
        } catch (NumberFormatException | NullPointerException e) {
            throw new WebhookRejectedException("amount is missing or not a number");
        }
        // The callback carries no event id of its own: payment id + status identifies the notification.
        return new InboundEvent(paymentId + ":" + status.toLowerCase(java.util.Locale.ROOT), InboundEvent.Kind.PAYMENT,
                f.get(EasebuzzSpec.PG_FIELD_TXN_ID), paymentId, paid ? "PAID" : "FAILED", amount,
                f.get(EasebuzzSpec.PG_FIELD_BANK_REF), f.get(EasebuzzSpec.PG_FIELD_MODE), null, paid ? null : status, null, null);
    }
}
