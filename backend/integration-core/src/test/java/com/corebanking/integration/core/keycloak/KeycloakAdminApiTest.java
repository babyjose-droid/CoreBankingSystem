package com.corebanking.integration.core.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.integration.core.MiniJson;
import com.corebanking.integration.core.provider.HttpTransport;
import com.corebanking.integration.core.provider.ProviderException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Requests to the Keycloak Admin REST API and reading its answers (US-120). Pure: nothing here talks to a
 * Keycloak, and the adapter has not been run against a live one. Ids and secrets are fake.
 */
class KeycloakAdminApiTest {

    static final KeycloakAdminApi KC = new KeycloakAdminApi("http://keycloak:8081/");
    static final String CLIENT = "11111111-2222-3333-4444-555555555555";
    static final String API = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final String USER = "99999999-8888-7777-6666-555555555555";

    static HttpTransport.Response ok(String body) {
        return new HttpTransport.Response(200, body, null);
    }

    @Test
    void token_request_is_a_client_credentials_grant() {
        HttpTransport.Request r = KC.tokenRequest("master", "corebanking-admin", "CLAUDE-TEST secret&x=1");
        assertEquals("POST", r.method());
        assertEquals("http://keycloak:8081/realms/master/protocol/openid-connect/token", r.url());
        assertEquals("grant_type=client_credentials&client_id=corebanking-admin&client_secret=CLAUDE-TEST+secret%26x%3D1", r.body());
        assertEquals("tok", KeycloakAdminApi.accessToken(ok("{\"access_token\":\"tok\",\"expires_in\":60}")));
        assertThrows(ProviderException.class, () -> KeycloakAdminApi.accessToken(ok("{}")));
        assertThrows(ProviderException.class, () -> KeycloakAdminApi.accessToken(new HttpTransport.Response(401, "{}", null)));
    }

    @Test
    void a_new_client_is_a_confidential_service_account_with_the_three_mappers() {
        HttpTransport.Request r = KC.createClient("demo-nbfc", "tok", "ext-pilot-los", "CLAUDE-TEST pilot LOS", "demo-nbfc");
        assertEquals("POST", r.method());
        assertEquals("http://keycloak:8081/admin/realms/demo-nbfc/clients", r.url());
        assertEquals("Bearer tok", r.headers().get("Authorization"));
        Map<String, Object> c = MiniJson.parseObject(r.body());
        assertEquals("ext-pilot-los", c.get("clientId"));
        assertEquals(Boolean.FALSE, c.get("publicClient"));
        assertEquals(Boolean.TRUE, c.get("serviceAccountsEnabled"));
        assertEquals(Boolean.FALSE, c.get("standardFlowEnabled"));
        assertEquals(Boolean.FALSE, c.get("implicitFlowEnabled"));
        assertEquals(Boolean.FALSE, c.get("directAccessGrantsEnabled"));
        assertEquals(Boolean.FALSE, c.get("fullScopeAllowed"));
        assertNull(c.get("secret"));                               // Keycloak generates it; we never choose or store it
        List<Object> mappers = MiniJson.array(c.get("protocolMappers"));
        assertEquals(3, mappers.size());
        Map<String, Object> tenant = MiniJson.object(MiniJson.object(mappers.get(0)).get("config"));
        assertEquals("tenant", tenant.get("claim.name"));
        assertEquals("demo-nbfc", tenant.get("claim.value"));
        Map<String, Object> permissions = MiniJson.object(mappers.get(1));
        assertEquals("oidc-usermodel-client-role-mapper", permissions.get("protocolMapper"));
        assertEquals("api", MiniJson.object(permissions.get("config")).get("usermodel.clientRoleMapping.clientId"));
        assertEquals("api", MiniJson.object(MiniJson.object(mappers.get(2)).get("config")).get("included.client.audience"));
    }

    @Test
    void the_created_id_comes_from_the_location_header() {
        assertEquals(CLIENT, KeycloakAdminApi.createdId(new HttpTransport.Response(201, "",
                "http://keycloak:8081/admin/realms/demo-nbfc/clients/" + CLIENT)));
        ProviderException conflict = assertThrows(ProviderException.class,
                () -> KeycloakAdminApi.createdId(new HttpTransport.Response(409, "{\"errorMessage\":\"Client ext-pilot-los already exists\"}", null)));
        assertFalse(conflict.retryable());
        assertThrows(ProviderException.class, () -> KeycloakAdminApi.createdId(new HttpTransport.Response(201, "", null)));
        assertThrows(IllegalArgumentException.class,
                () -> KeycloakAdminApi.createdId(new HttpTransport.Response(201, "", "http://keycloak/admin/realms/x/clients/../../evil")));
    }

    @Test
    void find_client_matches_the_exact_client_id() {
        HttpTransport.Request r = KC.findClient("demo-nbfc", "tok", "api");
        assertEquals("GET", r.method());
        assertEquals("http://keycloak:8081/admin/realms/demo-nbfc/clients?clientId=api", r.url());
        assertNull(r.body());
        String list = "[{\"id\":\"" + CLIENT + "\",\"clientId\":\"api-old\"},{\"id\":\"" + API + "\",\"clientId\":\"api\"}]";
        assertEquals(API, KeycloakAdminApi.foundId(ok(list), "api"));
        assertNull(KeycloakAdminApi.foundId(ok("[]"), "api"));
        assertThrows(ProviderException.class, () -> KeycloakAdminApi.foundId(ok("not json"), "api"));
    }

    @Test
    void secret_rotation_and_disable() {
        HttpTransport.Request rotate = KC.regenerateSecret("demo-nbfc", "tok", CLIENT);
        assertEquals("POST", rotate.method());
        assertEquals("http://keycloak:8081/admin/realms/demo-nbfc/clients/" + CLIENT + "/client-secret", rotate.url());
        assertEquals("CLAUDE-TEST-generated", KeycloakAdminApi.secret(ok("{\"type\":\"secret\",\"value\":\"CLAUDE-TEST-generated\"}")));
        assertThrows(ProviderException.class, () -> KeycloakAdminApi.secret(ok("{\"type\":\"secret\"}")));
        HttpTransport.Request disable = KC.setEnabled("demo-nbfc", "tok", CLIENT, "ext-pilot-los", false);
        assertEquals("PUT", disable.method());
        assertEquals("http://keycloak:8081/admin/realms/demo-nbfc/clients/" + CLIENT, disable.url());
        assertEquals("{\"id\":\"" + CLIENT + "\",\"clientId\":\"ext-pilot-los\",\"enabled\":false}", disable.body());
    }

    @Test
    void service_account_and_roles() {
        assertEquals("http://keycloak:8081/admin/realms/demo-nbfc/clients/" + CLIENT + "/service-account-user",
                KC.serviceAccountUser("demo-nbfc", "tok", CLIENT).url());
        String[] sa = KeycloakAdminApi.serviceAccount(ok("{\"id\":\"" + USER + "\",\"username\":\"service-account-ext-pilot-los\"}"));
        assertEquals(USER, sa[0]);
        assertEquals("service-account-ext-pilot-los", sa[1]);
        assertEquals("http://keycloak:8081/admin/realms/demo-nbfc/clients/" + API + "/roles", KC.listRoles("demo-nbfc", "tok", API).url());
        List<KeycloakAdminApi.Role> roles = KeycloakAdminApi.roles(ok(
                "[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"loan:view\"},{\"id\":\"00000000-0000-0000-0000-000000000002\",\"name\":\"loan:stp\"}]"));
        assertEquals(2, roles.size());
        assertEquals("loan:stp", roles.get(1).name());
        HttpTransport.Request add = KC.addUserRoles("demo-nbfc", "tok", USER, API, roles);
        assertEquals("POST", add.method());
        assertEquals("http://keycloak:8081/admin/realms/demo-nbfc/users/" + USER + "/role-mappings/clients/" + API, add.url());
        assertEquals("[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"loan:view\"},"
                + "{\"id\":\"00000000-0000-0000-0000-000000000002\",\"name\":\"loan:stp\"}]", add.body());
        HttpTransport.Request remove = KC.removeUserRoles("demo-nbfc", "tok", USER, API, roles.subList(1, 2));
        assertEquals("DELETE", remove.method());
        assertEquals("[{\"id\":\"00000000-0000-0000-0000-000000000002\",\"name\":\"loan:stp\"}]", remove.body());
        assertEquals("http://keycloak:8081/admin/realms/demo-nbfc/clients/" + CLIENT + "/scope-mappings/clients/" + API,
                KC.addScopeRoles("demo-nbfc", "tok", CLIENT, API, roles).url());
        assertEquals("DELETE", KC.removeScopeRoles("demo-nbfc", "tok", CLIENT, API, roles).method());
        assertEquals("GET", KC.userRoles("demo-nbfc", "tok", USER, API).method());
    }

    @Test
    void ids_and_realms_cannot_carry_path_tricks() {
        assertThrows(IllegalArgumentException.class, () -> KC.findClient("../master", "tok", "api"));
        assertThrows(IllegalArgumentException.class, () -> KC.regenerateSecret("demo-nbfc", "tok", "x/../../users"));
        assertThrows(IllegalArgumentException.class, () -> KC.createClient("demo-nbfc", "tok", "Bad Client", "n", "demo-nbfc"));
        assertThrows(IllegalArgumentException.class, () -> KC.createClient("demo-nbfc", "tok", "ok-client", "n", "Other Realm"));
        assertThrows(IllegalArgumentException.class, () -> new KeycloakAdminApi("keycloak:8081"));
        assertThrows(IllegalArgumentException.class, () -> new KeycloakAdminApi("http://keycloak:8081/?x=<script>"));
    }

    @Test
    void failures_say_whether_to_retry() {
        assertTrue(assertThrows(ProviderException.class, () -> KeycloakAdminApi.ok(new HttpTransport.Response(503, "", null), "x")).retryable());
        assertFalse(assertThrows(ProviderException.class, () -> KeycloakAdminApi.ok(new HttpTransport.Response(403, "", null), "x")).retryable());
        assertFalse(assertThrows(ProviderException.class, () -> KeycloakAdminApi.ok(new HttpTransport.Response(404, "", null), "x")).retryable());
        KeycloakAdminApi.ok(new HttpTransport.Response(204, "", null), "x");
    }

    // ---- scopes --------------------------------------------------------------------------------------------
    @Test
    void scopes_come_from_an_allow_list() {
        assertEquals(List.of("loan:create", "loan:view"), ApiClientScopes.validate(List.of("loan:view", "loan:create", "loan:view")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ApiClientScopes.validate(List.of("loan:view", "approval:approve", "apiclient:admin")));
        assertTrue(e.getMessage().contains("apiclient:admin") && e.getMessage().contains("approval:approve"), e.getMessage());
        assertFalse(e.getMessage().contains("loan:view"));
        assertThrows(IllegalArgumentException.class, () -> ApiClientScopes.validate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ApiClientScopes.validate(null));
        for (String never : List.of("approval:approve", "staff:propose", "limit:propose", "webhook:admin", "integration:admin",
                "apiclient:admin", "loan:disburse", "loan:reverse", "loan:waive", "eod:run", "voucher:create", "master:propose")) {
            assertFalse(ApiClientScopes.GRANTABLE.contains(never), never);
        }
    }

    @Test
    void money_moving_scopes_need_two_checkers() {
        assertFalse(ApiClientScopes.sensitive(List.of("loan:view", "loan:create", "customer:create")));
        assertTrue(ApiClientScopes.sensitive(List.of("loan:view", "loan:stp")));
        assertTrue(ApiClientScopes.sensitive(List.of("loan:repay")));
        assertTrue(ApiClientScopes.sensitive(List.of("payout:beneficiary")));
        assertEquals("CREATE", ApiClientScopes.action("CREATE", List.of("loan:view")));
        assertEquals("CREATE_SENSITIVE", ApiClientScopes.action("CREATE", List.of("loan:view", "loan:stp")));
        assertEquals("SCOPE_CHANGE_SENSITIVE", ApiClientScopes.action("SCOPE_CHANGE", List.of("loan:repay")));
        assertTrue(ApiClientScopes.GRANTABLE.containsAll(ApiClientScopes.SENSITIVE));
    }
}
