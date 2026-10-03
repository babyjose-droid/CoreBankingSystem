package com.corebanking.integration.core.provider;

import java.util.Map;

/**
 * The one way adapters reach a provider. The app implements it with {@code java.net.http.HttpClient}: TLS only,
 * timeouts, no redirects. Tests use a recording fake, so an adapter's requests can be checked without a network.
 */
public interface HttpTransport {

    /** A request. {@code body} is null for GET and DELETE. */
    record Request(String method, String url, Map<String, String> headers, String body) {
        public Request {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /** @param location the Location header, when the response has one */
    record Response(int status, String body, String location) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * @throws ProviderException retryable, when no response was received (timeout, connection failure)
     */
    Response send(Request request);
}
