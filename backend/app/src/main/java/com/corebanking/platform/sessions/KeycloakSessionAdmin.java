package com.corebanking.platform.sessions;

import com.corebanking.kernel.KeycloakAdmin;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.SessionAdmin;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * {@link SessionAdmin} on Keycloak's Admin REST API (US-027), with the JDK's HTTP client.
 * <p>
 * Every request and every reply is built and read by the pure {@link KeycloakAdmin}; this class only sends, caches
 * the service account's token until shortly before it expires, and turns failures into API errors. It has
 * <b>not been run against a live Keycloak</b>: the calls follow the Admin REST API documentation.
 * <p>
 * Configuration (the secret is read when needed and never logged):
 * <ul>
 *   <li>{@code corebanking.keycloak.admin.base-url}: Keycloak's base URL as the application reaches it, e.g.
 *       {@code http://keycloak:8081}. When empty it is taken from {@code corebanking.oidc.jwks-base} or the issuer
 *       prefix, without the {@code /realms/} part.</li>
 *   <li>{@code corebanking.keycloak.admin.client-id} (default {@code corebanking-admin}): a confidential client
 *       <b>in each tenant realm</b> with service accounts on and the realm-management roles {@code view-users} and
 *       {@code manage-users}. One client per realm keeps the credential's reach to that tenant.</li>
 *   <li>The client secret: {@code COREBANKING_TENANT_<CODE>_KEYCLOAK_ADMIN_SECRET} per tenant, or
 *       {@code corebanking.keycloak.admin.client-secret} for all tenants (dev and standalone).</li>
 * </ul>
 * Without a secret the API answers 503 "session management is not configured".
 */
@Component
class KeycloakSessionAdmin implements SessionAdmin {

    private static final Logger log = LoggerFactory.getLogger(KeycloakSessionAdmin.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private record Cached(String token, Instant usableUntil) {}

    private final Environment env;
    private final String baseUrl;
    private final String clientId;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Cached> tokens = new ConcurrentHashMap<>();

    KeycloakSessionAdmin(Environment env,
                         @Value("${corebanking.keycloak.admin.base-url:}") String baseUrl,
                         @Value("${corebanking.keycloak.admin.client-id:corebanking-admin}") String clientId,
                         @Value("${corebanking.oidc.jwks-base:}") String jwksBase,
                         @Value("${corebanking.oidc.issuer-prefix}") String issuerPrefix) {
        this.env = env;
        this.clientId = clientId;
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : withoutRealms(jwksBase != null && !jwksBase.isBlank() ? jwksBase : issuerPrefix);
    }

    /** {@code http://host/realms/} → {@code http://host}. */
    static String withoutRealms(String prefix) {
        String p = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return p.endsWith("/realms") ? p.substring(0, p.length() - "/realms".length()) : p;
    }

    @Override
    public List<Session> sessionsOfUser(String realm, String userId) {
        String body = call(realm, token -> KeycloakAdmin.userSessions(baseUrl, realm, userId, token), false);
        return KeycloakAdmin.parseSessions(body).stream()
                .map(s -> new Session(s.id(), s.userId(), s.username(), s.ipAddress(), s.started(), s.lastAccess(), s.clients())).toList();
    }

    @Override
    public String userIdOf(String realm, String username) {
        String body = call(realm, token -> KeycloakAdmin.findUser(baseUrl, realm, username, token), false);
        return KeycloakAdmin.parseUserId(body, username);
    }

    @Override
    public void terminate(String realm, String sessionId) {
        call(realm, token -> KeycloakAdmin.deleteSession(baseUrl, realm, sessionId, token), true);
    }

    private interface Build {
        KeycloakAdmin.HttpCall with(String token);
    }

    /** Sends an admin call with the service account's token; a 401 gets one retry with a fresh token. */
    private String call(String realm, Build build, boolean notFoundIsSession) {
        for (int attempt = 0; ; attempt++) {
            KeycloakAdmin.HttpCall request;
            try {
                request = build.with(token(realm));
            } catch (IllegalArgumentException e) {
                throw ApiException.invalid(e.getMessage());
            }
            HttpResponse<String> reply = send(request);
            int status = reply.statusCode();
            if (status >= 200 && status < 300) return reply.body() == null ? "" : reply.body();
            if (status == 401 && attempt == 0) {
                tokens.remove(realm);
                continue;
            }
            if (status == 404 && notFoundIsSession) throw ApiException.notFound("session");
            log.warn("identity provider answered {} to {}", status, request);
            throw new ApiException(HttpStatus.BAD_GATEWAY, status == 401 || status == 403
                    ? "the identity provider refused the session admin client; check its roles (view-users, manage-users)"
                    : "the identity provider could not complete the request");
        }
    }

    private String token(String realm) {
        Cached cached = tokens.get(realm);
        if (cached != null && Instant.now().isBefore(cached.usableUntil())) return cached.token();
        String secret = env.getProperty("COREBANKING_TENANT_" + realm.toUpperCase().replace('-', '_') + "_KEYCLOAK_ADMIN_SECRET");
        if (secret == null || secret.isBlank()) secret = env.getProperty("corebanking.keycloak.admin.client-secret");
        if (secret == null || secret.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "session management is not configured for this tenant");
        }
        KeycloakAdmin.HttpCall request = KeycloakAdmin.tokenRequest(baseUrl, realm, clientId, secret);
        HttpResponse<String> reply = send(request);
        if (reply.statusCode() != 200) {
            log.warn("identity provider answered {} to the token request of the session admin client for realm {}", reply.statusCode(), realm);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "the identity provider did not accept the session admin client");
        }
        KeycloakAdmin.AccessToken token;
        try {
            token = KeycloakAdmin.parseToken(reply.body());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "the identity provider sent a reply that could not be read");
        }
        // Use the token for its stated life less 30 seconds; a token without a stated life is used once.
        long life = Math.max(0, token.expiresInSeconds() - 30);
        tokens.put(realm, new Cached(token.token(), Instant.now().plusSeconds(life)));
        return token.token();
    }

    private HttpResponse<String> send(KeycloakAdmin.HttpCall call) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(call.url())).timeout(TIMEOUT)
                .method(call.method(), call.body() == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(call.body(), StandardCharsets.UTF_8));
        call.headers().forEach(b::header);
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("identity provider not reachable for {}: {}", call, e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "the identity provider cannot be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "the identity provider cannot be reached");
        }
    }
}
