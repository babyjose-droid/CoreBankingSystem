package com.corebanking.integration.core.keycloak;

import com.corebanking.integration.core.MiniJson;
import com.corebanking.integration.core.provider.HttpTransport;
import com.corebanking.integration.core.provider.ProviderException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Requests to the Keycloak Admin REST API for managing API clients in a tenant's realm (US-120), and reading its
 * answers. This class only builds and parses: sending is the caller's, so everything here is tested without a
 * Keycloak. <b>It has not been run against a live Keycloak</b>; the paths and representations are the Admin REST
 * API's documented ones ({@code /admin/realms/{realm}/clients …}).
 * <p>
 * An API client is created like the realm template's {@code corebanking-service} client: confidential, service
 * account only (client-credentials grant), no browser or password flows, with the three mappers the API needs in
 * a token — the {@code tenant} claim, the {@code permissions} claim (client roles of the {@code api} client) and
 * the {@code api} audience. Scopes are client roles of {@code api} mapped to the client's service-account user
 * and added to the client's scope (it is created with {@code fullScopeAllowed=false}).
 */
public final class KeycloakAdminApi {

    /** A client role of the {@code api} client. */
    public record Role(String id, String name) {}

    private static final String UUID = "[0-9a-fA-F-]{8,64}";

    private final String base;

    /** @param baseUrl Keycloak's base URL as the app reaches it, e.g. {@code http://keycloak:8081} */
    public KeycloakAdminApi(String baseUrl) {
        if (baseUrl == null || !baseUrl.matches("https?://[A-Za-z0-9.:\\[\\]/_-]+")) throw new IllegalArgumentException("invalid Keycloak base URL");
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String realm(String realm) {
        if (realm == null || !realm.matches("[a-z][a-z0-9-]{2,30}")) throw new IllegalArgumentException("invalid realm");
        return realm;
    }

    private static String id(String id) {
        if (id == null || !id.matches(UUID)) throw new IllegalArgumentException("invalid Keycloak id");
        return id;
    }

    public static String clientId(String clientId) {
        if (clientId == null || !clientId.matches("[a-z][a-z0-9-]{2,59}")) {
            throw new IllegalArgumentException("client id must be 3 to 60 lower-case letters, digits or hyphens, starting with a letter");
        }
        return clientId;
    }

    private static Map<String, String> auth(String token, boolean json) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Accept", "application/json");
        if (json) h.put("Content-Type", "application/json");
        return h;
    }

    // ------------------------------------------------------------------------------------------------ token
    /** Client-credentials token of the admin service account. */
    public HttpTransport.Request tokenRequest(String adminRealm, String adminClientId, String adminClientSecret) {
        String form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(adminClientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(adminClientSecret, StandardCharsets.UTF_8);
        return new HttpTransport.Request("POST", base + "/realms/" + realm(adminRealm) + "/protocol/openid-connect/token",
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"), form);
    }

    public static String accessToken(HttpTransport.Response r) {
        String token = MiniJson.string(object(r, "token"), "access_token");
        if (token == null || token.isBlank()) throw new ProviderException("Keycloak returned no access token", false);
        return token;
    }

    // ------------------------------------------------------------------------------------------------ clients
    public HttpTransport.Request createClient(String realm, String token, String clientId, String name, String tenant) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("clientId", clientId(clientId));
        c.put("name", name);
        c.put("description", "API client managed by CoreBanking (US-120)");
        c.put("enabled", true);
        c.put("protocol", "openid-connect");
        c.put("publicClient", false);
        c.put("bearerOnly", false);
        c.put("clientAuthenticatorType", "client-secret");
        c.put("standardFlowEnabled", false);
        c.put("implicitFlowEnabled", false);
        c.put("directAccessGrantsEnabled", false);
        c.put("serviceAccountsEnabled", true);
        c.put("fullScopeAllowed", false);
        c.put("protocolMappers", List.of(
                mapper("tenant", "oidc-hardcoded-claim-mapper", Map.of("claim.name", "tenant", "claim.value", realm(tenant),
                        "jsonType.label", "String", "access.token.claim", "true", "id.token.claim", "true",
                        "userinfo.token.claim", "true")),
                mapper("permissions", "oidc-usermodel-client-role-mapper", Map.of("usermodel.clientRoleMapping.clientId", "api",
                        "claim.name", "permissions", "jsonType.label", "String", "multivalued", "true",
                        "access.token.claim", "true", "id.token.claim", "false", "userinfo.token.claim", "false")),
                mapper("audience-api", "oidc-audience-mapper", Map.of("included.client.audience", "api",
                        "access.token.claim", "true", "id.token.claim", "false"))));
        return new HttpTransport.Request("POST", base + "/admin/realms/" + realm(realm) + "/clients", auth(token, true), MiniJson.write(c));
    }

    private static Map<String, Object> mapper(String name, String type, Map<String, String> config) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("protocol", "openid-connect");
        m.put("protocolMapper", type);
        m.put("consentRequired", false);
        m.put("config", new java.util.TreeMap<>(config));
        return m;
    }

    /** The id Keycloak gave the new client, from the Location header of the 201. */
    public static String createdId(HttpTransport.Response r) {
        if (r.status() == 409) throw new ProviderException("a client with this id already exists in the realm", false);
        ok(r, "create client");
        String location = r.location();
        if (location == null || location.lastIndexOf('/') < 0) throw new ProviderException("Keycloak did not say where the client was created", false);
        return id(location.substring(location.lastIndexOf('/') + 1));
    }

    public HttpTransport.Request findClient(String realm, String token, String clientId) {
        return new HttpTransport.Request("GET", base + "/admin/realms/" + realm(realm) + "/clients?clientId="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8), auth(token, false), null);
    }

    /** The Keycloak id of the client with exactly this client id, or null when there is none. */
    public static String foundId(HttpTransport.Response r, String clientId) {
        ok(r, "find client");
        for (Object o : array(r, "client list")) {
            Map<String, Object> c = MiniJson.object(o);
            if (clientId.equals(MiniJson.string(c, "clientId"))) return id(MiniJson.string(c, "id"));
        }
        return null;
    }

    /** Enables or disables the client. A disabled client gets no tokens. */
    public HttpTransport.Request setEnabled(String realm, String token, String keycloakId, String clientId, boolean enabled) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id(keycloakId));
        c.put("clientId", clientId(clientId));
        c.put("enabled", enabled);
        return new HttpTransport.Request("PUT", base + "/admin/realms/" + realm(realm) + "/clients/" + id(keycloakId), auth(token, true),
                MiniJson.write(c));
    }

    /** Generates a new secret; the old one stops working at once. The secret is in the answer and nowhere else. */
    public HttpTransport.Request regenerateSecret(String realm, String token, String keycloakId) {
        return new HttpTransport.Request("POST", base + "/admin/realms/" + realm(realm) + "/clients/" + id(keycloakId) + "/client-secret",
                auth(token, true), "{}");
    }

    public static String secret(HttpTransport.Response r) {
        ok(r, "generate client secret");
        String value = MiniJson.string(object(r, "client secret"), "value");
        if (value == null || value.isBlank()) throw new ProviderException("Keycloak returned no client secret", false);
        return value;
    }

    public HttpTransport.Request serviceAccountUser(String realm, String token, String keycloakId) {
        return new HttpTransport.Request("GET", base + "/admin/realms/" + realm(realm) + "/clients/" + id(keycloakId) + "/service-account-user",
                auth(token, false), null);
    }

    /** {@code [user id, username]} of the client's service account; the username is what tokens carry as the login. */
    public static String[] serviceAccount(HttpTransport.Response r) {
        ok(r, "read service account");
        Map<String, Object> u = object(r, "service account");
        String userId = MiniJson.string(u, "id");
        String username = MiniJson.string(u, "username");
        if (username == null || username.isBlank()) throw new ProviderException("Keycloak returned no service account", false);
        return new String[] {id(userId), username};
    }

    // ------------------------------------------------------------------------------------------------ scopes
    /** The roles (permissions) defined on the {@code api} client. */
    public HttpTransport.Request listRoles(String realm, String token, String apiClientKeycloakId) {
        return new HttpTransport.Request("GET", base + "/admin/realms/" + realm(realm) + "/clients/" + id(apiClientKeycloakId) + "/roles",
                auth(token, false), null);
    }

    public static List<Role> roles(HttpTransport.Response r) {
        ok(r, "list roles");
        List<Role> out = new ArrayList<>();
        for (Object o : array(r, "role list")) {
            Map<String, Object> m = MiniJson.object(o);
            String name = MiniJson.string(m, "name");
            String roleId = MiniJson.string(m, "id");
            if (name != null && roleId != null) out.add(new Role(id(roleId), name));
        }
        return out;
    }

    /** Roles of the {@code api} client currently mapped to the service-account user. */
    public HttpTransport.Request userRoles(String realm, String token, String userId, String apiClientKeycloakId) {
        return new HttpTransport.Request("GET", userRolesUrl(realm, userId, apiClientKeycloakId), auth(token, false), null);
    }

    public HttpTransport.Request addUserRoles(String realm, String token, String userId, String apiClientKeycloakId, List<Role> roles) {
        return new HttpTransport.Request("POST", userRolesUrl(realm, userId, apiClientKeycloakId), auth(token, true), roleBody(roles));
    }

    public HttpTransport.Request removeUserRoles(String realm, String token, String userId, String apiClientKeycloakId, List<Role> roles) {
        return new HttpTransport.Request("DELETE", userRolesUrl(realm, userId, apiClientKeycloakId), auth(token, true), roleBody(roles));
    }

    /** Adds the roles to the client's scope, so that they can appear in its tokens (fullScopeAllowed is false). */
    public HttpTransport.Request addScopeRoles(String realm, String token, String keycloakId, String apiClientKeycloakId, List<Role> roles) {
        return new HttpTransport.Request("POST", scopeUrl(realm, keycloakId, apiClientKeycloakId), auth(token, true), roleBody(roles));
    }

    public HttpTransport.Request removeScopeRoles(String realm, String token, String keycloakId, String apiClientKeycloakId, List<Role> roles) {
        return new HttpTransport.Request("DELETE", scopeUrl(realm, keycloakId, apiClientKeycloakId), auth(token, true), roleBody(roles));
    }

    private String userRolesUrl(String realm, String userId, String apiClientKeycloakId) {
        return base + "/admin/realms/" + realm(realm) + "/users/" + id(userId) + "/role-mappings/clients/" + id(apiClientKeycloakId);
    }

    private String scopeUrl(String realm, String keycloakId, String apiClientKeycloakId) {
        return base + "/admin/realms/" + realm(realm) + "/clients/" + id(keycloakId) + "/scope-mappings/clients/" + id(apiClientKeycloakId);
    }

    private static String roleBody(List<Role> roles) {
        List<Map<String, Object>> body = new ArrayList<>();
        for (Role r : roles) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id(r.id()));
            m.put("name", r.name());
            body.add(m);
        }
        return MiniJson.write(body);
    }

    // ------------------------------------------------------------------------------------------------ answers
    /** Checks a call that returns no body we need (204 or 2xx). */
    public static void ok(HttpTransport.Response r, String what) {
        if (r.ok()) return;
        if (r.status() == 401 || r.status() == 403) {
            throw new ProviderException("Keycloak refused to " + what + " (" + r.status() + "): the admin service account lacks the role for it", false);
        }
        throw new ProviderException("Keycloak could not " + what + " (" + r.status() + ")", r.status() >= 500 || r.status() == 429);
    }

    private static Map<String, Object> object(HttpTransport.Response r, String what) {
        if (!r.ok()) ok(r, "return the " + what);
        try {
            return MiniJson.parseObject(r.body());
        } catch (MiniJson.JsonException e) {
            throw new ProviderException("Keycloak's " + what + " answer could not be read", false);
        }
    }

    private static List<Object> array(HttpTransport.Response r, String what) {
        try {
            return MiniJson.parseArray(r.body());
        } catch (MiniJson.JsonException e) {
            throw new ProviderException("Keycloak's " + what + " could not be read", false);
        }
    }
}
