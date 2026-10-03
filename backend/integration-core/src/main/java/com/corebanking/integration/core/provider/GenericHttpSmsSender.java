package com.corebanking.integration.core.provider;

import com.corebanking.integration.core.MiniJson;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>UNVERIFIED-AGAINST-PROVIDER</b> skeleton for an SMS aggregator with a JSON-over-HTTPS API. No aggregator has
 * been chosen (decision D-09); this shows the shape and carries the three DLT ids. A real aggregator's field
 * names, authentication and delivery reports will differ and need their own adapter or a mapping added here.
 * <p>
 * Settings: {@code url} (https; checked by the SSRF guard when registered and again before each call),
 * {@code authHeader} (default Authorization), {@code idField} (default id). Secret: {@code apiKey}.
 * Request body: {@code {"to","text","entityId","templateId","header","reference"}}.
 */
public final class GenericHttpSmsSender implements SmsSender {

    public static final String CODE = "GENERIC_HTTP";

    private final ProviderSettings config;
    private final HttpTransport http;

    public GenericHttpSmsSender(ProviderSettings config, HttpTransport http) {
        this.config = config;
        this.http = http;
    }

    HttpTransport.Request request(Sms sms) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", sms.to());
        body.put("text", sms.text());
        body.put("entityId", sms.dltEntityId());
        body.put("templateId", sms.dltTemplateId());
        body.put("header", sms.header());
        body.put("reference", sms.reference());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put(config.setting("authHeader", "Authorization"), config.requireSecret("apiKey"));
        return new HttpTransport.Request("POST", config.requireSetting("url"), headers, MiniJson.write(body));
    }

    @Override
    public MessageResult send(Sms sms) {
        HttpTransport.Response r;
        try {
            r = http.send(request(sms));
        } catch (ProviderException e) {
            return MessageResult.refused(e.getMessage(), e.retryable());
        }
        if (r.status() >= 500 || r.status() == 429) return MessageResult.refused("the SMS gateway answered " + r.status(), true);
        if (!r.ok()) return MessageResult.refused("the SMS gateway refused the message (" + r.status() + ")", false);
        String id = null;
        try {
            id = MiniJson.string(MiniJson.parseObject(r.body()), config.setting("idField", "id"));
        } catch (MiniJson.JsonException e) {
            // accepted without a readable id: the delivery log keeps our own reference
        }
        return MessageResult.accepted(id);
    }
}
