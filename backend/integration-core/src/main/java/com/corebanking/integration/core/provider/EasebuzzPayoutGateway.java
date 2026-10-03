package com.corebanking.integration.core.provider;

import com.corebanking.integration.core.Hashing;
import com.corebanking.integration.core.Lifecycle;
import com.corebanking.integration.core.MiniJson;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * <b>UNVERIFIED-AGAINST-PROVIDER</b> skeleton of Easebuzz payouts ("Wire" quick transfer). See {@link EasebuzzSpec}:
 * nothing here has been run against Easebuzz. Only the transfer request is built. Status enquiry, beneficiary
 * validation and callbacks are refused, because their details must come from the provider.
 * <p>
 * Settings: {@code wireBaseUrl} (required: the host for the environment, from the provider), {@code mode} (IMPS by
 * default). Secrets: {@code key}, {@code salt}.
 * <p>
 * Reading the answer: only an explicit success or failure is taken as final. Anything else — including an answer
 * we cannot read — is SENT (outcome pending), never SUCCESS, so a payout is not reported as paid on a guess.
 */
public final class EasebuzzPayoutGateway implements PayoutGateway, InboundWebhookParser {

    private final ProviderSettings config;
    private final HttpTransport http;

    public EasebuzzPayoutGateway(ProviderSettings config, HttpTransport http) {
        this.config = config;
        this.http = http;
    }

    @Override
    public Validation validate(Beneficiary beneficiary, String reference) {
        return new Validation(Validity.UNAVAILABLE, null, null, "beneficiary validation is not built for EASEBUZZ (endpoint to be confirmed)");
    }

    HttpTransport.Request transferRequest(Request r) {
        String key = config.requireSecret("key");
        Map<String, String> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("beneficiary_type", "bank_account");
        f.put("beneficiary_name", r.beneficiary().holderName());
        f.put("account_number", r.beneficiary().accountNumber());
        f.put("ifsc", r.beneficiary().ifsc());
        f.put("upi_handle", "");
        f.put("unique_request_number", r.reference());
        f.put("payment_mode", r.mode() == null ? config.setting("mode", "IMPS") : r.mode());
        f.put("amount", EasebuzzCollectionGateway.amount(r.amount()));
        f.put("narration", r.narration());
        Map<String, String> hashed = new LinkedHashMap<>(f);
        hashed.put("salt", config.requireSecret("salt"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put(EasebuzzSpec.WIRE_AUTH_HEADER, EasebuzzCollectionGateway.hash(EasebuzzSpec.WIRE_HASH_SEQUENCE, hashed));
        headers.put(EasebuzzSpec.WIRE_KEY_HEADER, key);
        String base = config.requireSetting("wireBaseUrl");
        if (!base.startsWith("https://")) throw new ProviderException("wireBaseUrl must use https", false);
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return new HttpTransport.Request("POST", base + EasebuzzSpec.WIRE_QUICK_TRANSFER_PATH, headers, MiniJson.write(f));
    }

    @Override
    public Result send(Request r) {
        HttpTransport.Response resp = http.send(transferRequest(r));
        if (resp.status() >= 500 || resp.status() == 429) throw new ProviderException("Easebuzz answered " + resp.status(), true);
        Map<String, Object> m;
        try {
            m = MiniJson.parseObject(resp.body());
        } catch (MiniJson.JsonException e) {
            if (!resp.ok()) return new Result(Lifecycle.Payout.FAILED, null, null, "HTTP_" + resp.status(), "refused by the gateway");
            return new Result(Lifecycle.Payout.SENT, null, null, null, null);       // accepted, answer unreadable: pending
        }
        Map<String, Object> transfer = MiniJson.object(MiniJson.object(m.get("data")).get("transfer_request"));
        String status = String.valueOf(MiniJson.string(transfer, "status")).toLowerCase(Locale.ROOT);
        String id = MiniJson.string(transfer, "id");
        if (!resp.ok() || "failure".equals(status) || "failed".equals(status) || "rejected".equals(status)) {
            String reason = MiniJson.string(transfer, "failure_reason");
            if (reason == null) reason = MiniJson.string(m, "message");
            return new Result(Lifecycle.Payout.FAILED, id, null, resp.ok() ? "PROVIDER_FAILED" : "HTTP_" + resp.status(),
                    reason == null ? "refused by the gateway" : reason);
        }
        if ("success".equals(status)) {
            return new Result(Lifecycle.Payout.SUCCESS, id, MiniJson.string(transfer, "unique_transaction_reference"), null, null);
        }
        return new Result(Lifecycle.Payout.SENT, id, null, null, null);
    }

    @Override
    public Result status(String reference, String providerRef) {
        throw ProviderException.unsupported("EASEBUZZ payout status enquiry (endpoint to be confirmed with the provider)");
    }

    @Override
    public InboundEvent parse(Map<String, String> headers, byte[] rawBody, ProviderSettings cfg, long nowSeconds) {
        throw new WebhookRejectedException("EASEBUZZ payout callbacks are not accepted: their signature scheme is to be confirmed with the provider");
    }
}
