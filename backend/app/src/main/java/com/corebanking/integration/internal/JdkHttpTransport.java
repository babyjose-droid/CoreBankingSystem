package com.corebanking.integration.internal;

import com.corebanking.integration.core.EndpointGuard;
import com.corebanking.integration.core.provider.HttpTransport;
import com.corebanking.integration.core.provider.ProviderException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link HttpTransport} on the JDK's HTTP client: connect timeout 5 s, request timeout 15 s, redirects never
 * followed, at most 1 MB of response read. No response (timeout, connection failure) is a retryable
 * {@link ProviderException}; the caller's retry schedule takes over.
 * <p>
 * {@link #guarded()} is for URLs a tenant supplied (webhook endpoints, the generic SMS gateway): the URL is
 * checked and its host resolved to public addresses immediately before every request (SSRF guard, see
 * {@link EndpointGuard} for what that does and does not cover).
 */
@Component
class JdkHttpTransport implements HttpTransport {

    private static final int MAX_BODY = 1_000_000;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Override
    public Response send(Request request) {
        return send(request, false);
    }

    /** The same transport with the SSRF guard applied to every request. */
    HttpTransport guarded() {
        return request -> send(request, true);
    }

    private Response send(Request request, boolean guard) {
        URI uri;
        if (guard) {
            try {
                uri = EndpointGuard.checkUrl(request.url(), EndpointGuard.DEFAULT_PORTS);
                EndpointGuard.resolvePublic(uri.getHost(), InetAddress::getAllByName);
            } catch (EndpointGuard.BlockedException e) {
                throw new ProviderException("blocked: " + e.getMessage(), false);
            }
        } else {
            uri = URI.create(request.url());
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15));
        for (Map.Entry<String, String> h : request.headers().entrySet()) b.header(h.getKey(), h.getValue());
        b.method(request.method(), request.body() == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
        try {
            HttpResponse<InputStream> r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream in = r.body()) {
                body = new String(in.readNBytes(MAX_BODY), StandardCharsets.UTF_8);
            }
            return new Response(r.statusCode(), body, r.headers().firstValue("Location").orElse(null));
        } catch (IOException e) {
            throw new ProviderException("no response (" + e.getClass().getSimpleName() + ")", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("interrupted", true);
        }
    }
}
