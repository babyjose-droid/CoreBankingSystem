package com.corebanking.kernel;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Keycloak Admin REST calls behind session management (US-027), as data: which HTTP request to send and how to
 * read the reply. The application's HTTP layer only sends what this builds, so everything that can go wrong in
 * building a URL or reading a reply is tested here without a server.
 * <p>
 * Calls used (Keycloak Admin REST API, realm = the tenant code, ADR-007):
 * <ul>
 *   <li>{@code POST /realms/{realm}/protocol/openid-connect/token} with {@code grant_type=client_credentials}: the
 *       service account's access token. The client needs the realm-management roles {@code view-users} (to list
 *       sessions and look up a user) and {@code manage-users} (to end a session).</li>
 *   <li>{@code GET /admin/realms/{realm}/users/{id}/sessions}: the user's sessions.</li>
 *   <li>{@code GET /admin/realms/{realm}/users?username=…&exact=true}: the user id for a user name.</li>
 *   <li>{@code DELETE /admin/realms/{realm}/sessions/{session}}: end one session.</li>
 * </ul>
 */
public final class KeycloakAdmin {

    private static final Pattern REALM = Pattern.compile("[a-z][a-z0-9-]{2,30}");
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9._-]{8,80}");
    private static final Pattern USERNAME = Pattern.compile("[^\\s/\\\\?#%&]{1,255}");

    /**
     * One HTTP request. {@link #toString()} never shows the body or the Authorization header, so a request can be
     * logged without leaking the client secret or a token.
     */
    public record HttpCall(String method, String url, Map<String, String> headers, String body) {
        public HttpCall {
            headers = Map.copyOf(headers);
        }

        @Override
        public String toString() {
            return method + " " + url;
        }
    }

    /** @param expiresInSeconds lifetime the server stated; 0 when it gave none */
    public record AccessToken(String token, long expiresInSeconds) {
        @Override
        public String toString() {
            return "AccessToken[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    /** One login session of a user. {@code clients} are the applications the session has been used with. */
    public record Session(String id, String userId, String username, String ipAddress, Instant started, Instant lastAccess,
                          List<String> clients) {
        public Session {
            clients = List.copyOf(clients);
        }
    }

    private KeycloakAdmin() {}

    // ------------------------------------------------------------------------------------------------ requests
    public static HttpCall tokenRequest(String baseUrl, String realm, String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("the identity provider admin client is not configured");
        }
        String body = "grant_type=client_credentials&client_id=" + form(clientId) + "&client_secret=" + form(clientSecret);
        return new HttpCall("POST", base(baseUrl) + "/realms/" + realm(realm) + "/protocol/openid-connect/token",
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"), body);
    }

    public static HttpCall userSessions(String baseUrl, String realm, String userId, String bearer) {
        return new HttpCall("GET", admin(baseUrl, realm) + "/users/" + segment(userId, "user id") + "/sessions", auth(bearer), null);
    }

    public static HttpCall findUser(String baseUrl, String realm, String username, String bearer) {
        if (username == null || !USERNAME.matcher(username).matches()) throw new IllegalArgumentException("the user name is not valid");
        return new HttpCall("GET", admin(baseUrl, realm) + "/users?username=" + form(username) + "&exact=true", auth(bearer), null);
    }

    public static HttpCall deleteSession(String baseUrl, String realm, String sessionId, String bearer) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) throw new IllegalArgumentException("the session id is not valid");
        return new HttpCall("DELETE", admin(baseUrl, realm) + "/sessions/" + sessionId, auth(bearer), null);
    }

    private static String admin(String baseUrl, String realm) {
        return base(baseUrl) + "/admin/realms/" + realm(realm);
    }

    private static String base(String baseUrl) {
        if (baseUrl == null || !baseUrl.matches("https?://[^\\s?#]+")) throw new IllegalArgumentException("the identity provider URL is not configured");
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String realm(String realm) {
        if (realm == null || !REALM.matcher(realm).matches()) throw new IllegalArgumentException("the realm name is not valid");
        return realm;
    }

    private static Map<String, String> auth(String bearer) {
        if (bearer == null || bearer.isBlank() || bearer.chars().anyMatch(c -> c <= ' ' || c > '~')) {
            throw new IllegalArgumentException("no access token for the identity provider");
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + bearer);
        h.put("Accept", "application/json");
        return h;
    }

    private static String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** A value used as one path segment: percent-encoded, and never a dot segment. */
    private static String segment(String value, String what) {
        if (value == null || value.isBlank() || value.length() > 255 || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("the " + what + " is not valid");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ------------------------------------------------------------------------------------------------ replies
    public static AccessToken parseToken(String json) {
        Map<?, ?> o = MiniJson.object(MiniJson.parse(json), "token reply");
        String token = MiniJson.string(o, "access_token");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token reply: no access_token");
        Long expires = MiniJson.whole(o, "expires_in");
        return new AccessToken(token, expires == null || expires < 0 ? 0 : expires);
    }

    /** Sessions from {@code GET …/users/{id}/sessions}. Entries without an id are left out. */
    public static List<Session> parseSessions(String json) {
        List<Session> out = new ArrayList<>();
        for (Object item : MiniJson.array(MiniJson.parse(json), "sessions reply")) {
            if (!(item instanceof Map<?, ?> s)) continue;
            String id = MiniJson.string(s, "id");
            if (id == null || id.isBlank()) continue;
            List<String> clients = new ArrayList<>();
            if (s.get("clients") instanceof Map<?, ?> c) {
                for (Object name : c.values()) {
                    if (name instanceof String n) clients.add(n);
                }
            }
            clients.sort(null);
            out.add(new Session(id, MiniJson.string(s, "userId"), MiniJson.string(s, "username"), MiniJson.string(s, "ipAddress"),
                    millis(MiniJson.whole(s, "start")), millis(MiniJson.whole(s, "lastAccess")), clients));
        }
        return out;
    }

    /**
     * The id of the user with exactly this user name from {@code GET …/users?username=…&exact=true}, or null when
     * there is none. The name is compared again here (ignoring case, as Keycloak does), so a server that ignores
     * {@code exact} cannot hand us somebody else.
     */
    public static String parseUserId(String json, String username) {
        for (Object item : MiniJson.array(MiniJson.parse(json), "users reply")) {
            if (item instanceof Map<?, ?> u && username.equalsIgnoreCase(MiniJson.string(u, "username"))) {
                String id = MiniJson.string(u, "id");
                if (id != null && !id.isBlank()) return id;
            }
        }
        return null;
    }

    private static Instant millis(Long epochMillis) {
        return epochMillis == null || epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis);
    }
}
