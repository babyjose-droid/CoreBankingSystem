package com.corebanking.integration.internal;

import com.corebanking.integration.core.keycloak.KeycloakAdminApi;
import com.corebanking.integration.core.keycloak.KeycloakAdminApi.Role;
import com.corebanking.integration.core.provider.HttpTransport;
import com.corebanking.integration.core.provider.ProviderException;
import com.corebanking.platform.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link ClientAdmin} on the Keycloak Admin REST API. Thin: every request and every answer is built and read by
 * {@link KeycloakAdminApi} (pure, tested); this class only sends them in order.
 * <p>
 * <b>Not run against a live Keycloak.</b> It needs a confidential client with a service account that may manage
 * clients and role mappings in the tenant realms ({@code corebanking.integration.keycloak-admin.*}); until that
 * exists it is disabled and every call answers 409.
 */
@Component
class KeycloakClientAdmin implements ClientAdmin {

    private final JdkHttpTransport http;
    private final boolean enabled;
    private final KeycloakAdminApi api;
    private final String adminRealm;
    private final String adminClient;
    private final String adminSecret;

    KeycloakClientAdmin(JdkHttpTransport http,
                        @Value("${corebanking.integration.keycloak-admin.enabled:false}") boolean enabled,
                        @Value("${corebanking.integration.keycloak-admin.base-url:http://localhost:8081}") String baseUrl,
                        @Value("${corebanking.integration.keycloak-admin.realm:master}") String adminRealm,
                        @Value("${corebanking.integration.keycloak-admin.client-id:corebanking-admin}") String adminClient,
                        @Value("${corebanking.integration.keycloak-admin.client-secret:}") String adminSecret) {
        this.http = http;
        this.enabled = enabled;
        this.api = new KeycloakAdminApi(baseUrl);
        this.adminRealm = adminRealm;
        this.adminClient = adminClient;
        this.adminSecret = adminSecret;
    }

    private String token() {
        if (!enabled || adminSecret.isBlank()) {
            throw ApiException.conflict("API client administration is not configured in this deployment"
                    + " (corebanking.integration.keycloak-admin)");
        }
        return KeycloakAdminApi.accessToken(http.send(api.tokenRequest(adminRealm, adminClient, adminSecret)));
    }

    private HttpTransport.Response send(HttpTransport.Request r) {
        return http.send(r);
    }

    /** The {@code api} client's roles with these names; a scope without a role in the realm is an error. */
    private List<Role> roles(String realm, String token, String apiId, List<String> scopes) {
        List<Role> all = KeycloakAdminApi.roles(send(api.listRoles(realm, token, apiId)));
        List<Role> wanted = new ArrayList<>();
        for (String s : scopes) {
            wanted.add(all.stream().filter(r -> r.name().equals(s)).findFirst()
                    .orElseThrow(() -> new ProviderException("the realm has no permission '" + s + "' on its api client", false)));
        }
        return wanted;
    }

    private String apiClient(String realm, String token) {
        String id = KeycloakAdminApi.foundId(send(api.findClient(realm, token, "api")), "api");
        if (id == null) throw new ProviderException("the realm has no api client", false);
        return id;
    }

    @Override
    public Created create(String realm, String clientId, String name, List<String> scopes) {
        String token = token();
        String id;
        try {
            id = KeycloakAdminApi.createdId(send(api.createClient(realm, token, clientId, name, realm)));
        } catch (ProviderException e) {
            // An earlier approval may have created it and then failed to commit: client ids with our prefix are ours.
            id = KeycloakAdminApi.foundId(send(api.findClient(realm, token, clientId)), clientId);
            if (id == null) throw e;
        }
        String[] account = KeycloakAdminApi.serviceAccount(send(api.serviceAccountUser(realm, token, id)));
        String apiId = apiClient(realm, token);
        List<Role> roles = roles(realm, token, apiId, scopes);
        KeycloakAdminApi.ok(send(api.addUserRoles(realm, token, account[0], apiId, roles)), "grant the permissions");
        KeycloakAdminApi.ok(send(api.addScopeRoles(realm, token, id, apiId, roles)), "add the permissions to the client's scope");
        return new Created(id, account[1]);
    }

    @Override
    public void setScopes(String realm, String id, List<String> scopes) {
        String token = token();
        String[] account = KeycloakAdminApi.serviceAccount(send(api.serviceAccountUser(realm, token, id)));
        String apiId = apiClient(realm, token);
        List<Role> wanted = roles(realm, token, apiId, scopes);
        List<Role> current = KeycloakAdminApi.roles(send(api.userRoles(realm, token, account[0], apiId)));
        List<Role> remove = current.stream().filter(r -> !scopes.contains(r.name())).toList();
        List<Role> add = wanted.stream().filter(r -> current.stream().noneMatch(c -> c.name().equals(r.name()))).toList();
        if (!remove.isEmpty()) {
            KeycloakAdminApi.ok(send(api.removeUserRoles(realm, token, account[0], apiId, remove)), "remove permissions");
            KeycloakAdminApi.ok(send(api.removeScopeRoles(realm, token, id, apiId, remove)), "remove permissions from the client's scope");
        }
        if (!add.isEmpty()) {
            KeycloakAdminApi.ok(send(api.addUserRoles(realm, token, account[0], apiId, add)), "grant the permissions");
            KeycloakAdminApi.ok(send(api.addScopeRoles(realm, token, id, apiId, add)), "add the permissions to the client's scope");
        }
    }

    @Override
    public void setEnabled(String realm, String id, String clientId, boolean enabled) {
        KeycloakAdminApi.ok(send(api.setEnabled(realm, token(), id, clientId, enabled)), enabled ? "enable the client" : "disable the client");
    }

    @Override
    public String newSecret(String realm, String id) {
        return KeycloakAdminApi.secret(send(api.regenerateSecret(realm, token(), id)));
    }
}
