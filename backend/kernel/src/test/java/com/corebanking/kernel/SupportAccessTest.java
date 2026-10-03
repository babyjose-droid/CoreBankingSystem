package com.corebanking.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.kernel.SupportAccess.Decision;
import com.corebanking.kernel.SupportAccess.Grant;
import com.corebanking.kernel.SupportAccess.Request;
import com.corebanking.kernel.SupportAccess.Route;
import com.corebanking.kernel.SupportAccess.Token;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The authorisation matrix of ADR-016: which realm reaches which API, and when support access is in force. */
class SupportAccessTest {

    private static final String PLATFORM = "platform";
    private static final String TENANT = "claude-test";
    private static final String GRANT_ID = "00000000-0000-0000-0000-0000000005a1";
    private static final Instant APPROVED = Instant.parse("2026-10-02T04:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-02T05:00:00Z");

    private static final Token TENANT_USER = new Token(TENANT, TENANT, "kc-sub-maker", "maker1", Set.of("customer:view", "loan:view"));
    private static final Token ENGINEER = new Token(PLATFORM, null, "kc-sub-eng-1", "eng.one", Set.of(SupportAccess.PERMISSION));
    private static final Token OPERATOR = new Token(PLATFORM, null, "kc-sub-op-1", "op.one", Set.of("platform:operator"));

    private static Request get(String path) {
        return new Request("GET", path, TENANT, GRANT_ID);
    }

    private static Grant grant(String status, Instant expires) {
        return new Grant(GRANT_ID, TENANT, "kc-sub-eng-1", "eng.one", status, "READ_ONLY", APPROVED, expires);
    }

    private static final Grant GOOD = grant("APPROVED", APPROVED.plusSeconds(2 * 3600));

    private static Decision full(Token token, Request request, Grant grant, Instant now) {
        return SupportAccess.authorise(SupportAccess.route(token, request, PLATFORM), token, grant, now);
    }

    // ------------------------------------------------------------------------------------- guarantees kept from ASVS D1
    @Test
    void a_tenant_realm_never_reaches_the_control_plane() {
        Decision d = SupportAccess.route(TENANT_USER, new Request("GET", "/platform/v1/tenants", null, null), PLATFORM);
        assertEquals(Route.DENY, d.route());
        assertTrue(d.reason().contains("platform realm"));
        // not even when the tenant realm hands out the operator's or the support permission
        Token forged = new Token(TENANT, TENANT, "s", "admin", Set.of("platform:operator", SupportAccess.PERMISSION));
        assertTrue(SupportAccess.route(forged, new Request("POST", "/platform/v1/tenants", null, null), PLATFORM).denied());
        assertTrue(SupportAccess.route(forged, new Request("POST", "/platform/v1/support-access", null, null), PLATFORM).denied());
        // nor with a tenant claim that says "platform"
        Token claims = new Token(TENANT, PLATFORM, "s", "admin", Set.of("platform:operator"));
        assertTrue(SupportAccess.route(claims, new Request("GET", "/platform/v1/tenants", null, null), PLATFORM).denied());
    }

    @Test
    void the_platform_realm_reaches_the_control_plane() {
        assertEquals(Route.CONTROL_PLANE, SupportAccess.route(OPERATOR, new Request("GET", "/platform/v1/tenants", null, null), PLATFORM).route());
        assertEquals(Route.CONTROL_PLANE, SupportAccess.route(ENGINEER, new Request("POST", "/platform/v1/support-access", null, null), PLATFORM).route());
    }

    @Test
    void a_tenant_user_is_bound_to_the_realm_that_issued_the_token() {
        Decision d = SupportAccess.route(TENANT_USER, new Request("POST", "/api/v1/customers", null, null), PLATFORM);
        assertEquals(Route.TENANT_USER, d.route());
        assertEquals(TENANT, d.tenant());
        // realm A with tenant claim B
        Token crossed = new Token("claude-other", TENANT, "s", "maker1", Set.of("customer:view"));
        assertTrue(SupportAccess.route(crossed, new Request("GET", "/api/v1/customers", null, null), PLATFORM).denied());
        Token noClaim = new Token(TENANT, null, "s", "maker1", Set.of());
        assertTrue(SupportAccess.route(noClaim, new Request("GET", "/api/v1/customers", null, null), PLATFORM).denied());
        Token untrusted = new Token(null, TENANT, "s", "maker1", Set.of());
        assertTrue(SupportAccess.route(untrusted, new Request("GET", "/api/v1/customers", null, null), PLATFORM).denied());
        assertTrue(SupportAccess.route(null, new Request("GET", "/api/v1/customers", null, null), PLATFORM).denied());
    }

    @Test
    void a_platform_token_never_acts_as_a_tenant_user() {
        // operator without the support permission
        assertTrue(SupportAccess.route(OPERATOR, get("/api/v1/customers"), PLATFORM).denied());
        // a platform token that claims a tenant
        Token claims = new Token(PLATFORM, TENANT, "kc-sub-op-1", "op.one", Set.of("platform:operator", "customer:view"));
        assertTrue(SupportAccess.route(claims, new Request("GET", "/api/v1/customers", null, null), PLATFORM).denied());
        // the support permission alone, without naming a grant
        assertTrue(SupportAccess.route(ENGINEER, new Request("GET", "/api/v1/customers", null, null), PLATFORM).denied());
        assertTrue(SupportAccess.route(ENGINEER, new Request("GET", "/api/v1/customers", TENANT, null), PLATFORM).denied());
        assertTrue(SupportAccess.route(ENGINEER, new Request("GET", "/api/v1/customers", null, GRANT_ID), PLATFORM).denied());
        // naming a grant that does not exist
        assertTrue(full(ENGINEER, get("/api/v1/customers"), null, NOW).denied());
        // "platform" is not a tenant
        assertTrue(SupportAccess.route(ENGINEER, new Request("GET", "/api/v1/customers", PLATFORM, GRANT_ID), PLATFORM).denied());
        assertTrue(SupportAccess.route(ENGINEER, new Request("GET", "/api/v1/customers", "Bad Tenant", GRANT_ID), PLATFORM).denied());
        assertTrue(SupportAccess.route(ENGINEER, new Request("GET", "/api/v1/customers", TENANT, "1 OR 1=1"), PLATFORM).denied());
    }

    @Test
    void a_tenant_token_cannot_use_the_support_route() {
        assertTrue(SupportAccess.route(TENANT_USER, get("/api/v1/customers"), PLATFORM).denied());
        Token tenantWithPermission = new Token(TENANT, TENANT, "kc-sub-eng-1", "eng.one", Set.of(SupportAccess.PERMISSION));
        assertTrue(SupportAccess.route(tenantWithPermission, get("/api/v1/customers"), PLATFORM).denied());
        // and no tenant user can carry the support login
        Token impostor = new Token(TENANT, TENANT, "s", "support:eng.one", Set.of("customer:view"));
        assertTrue(SupportAccess.route(impostor, new Request("GET", "/api/v1/customers", null, null), PLATFORM).denied());
        Token impostorCase = new Token(TENANT, TENANT, "s", "Support:eng.one", Set.of("customer:view"));
        assertTrue(SupportAccess.route(impostorCase, new Request("GET", "/api/v1/customers", null, null), PLATFORM).denied());
    }

    // ------------------------------------------------------------------------------------- support access
    @Test
    void an_approved_unexpired_grant_lets_the_engineer_read() {
        Decision d = full(ENGINEER, get("/api/v1/loans/3fa85f64-5717-4562-b3fc-2c963f66afa6/transactions"), GOOD, NOW);
        assertEquals(Route.SUPPORT, d.route());
        assertEquals(TENANT, d.tenant());
        assertEquals(GRANT_ID, d.grantId());
        assertEquals("support:eng.one", SupportAccess.login("eng.one"));
    }

    @Test
    void support_access_is_read_only() {
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "get")) {
            Decision d = SupportAccess.route(ENGINEER, new Request(method, "/api/v1/customers", TENANT, GRANT_ID), PLATFORM);
            assertTrue(d.denied(), method);
        }
        assertFalse(SupportAccess.READ_ONLY_PERMISSIONS.stream().anyMatch(p -> !p.endsWith(":view")), "view permissions only");
        for (String never : List.of("kyc:view-document", "bureau:export", "report:run", "session:admin", "support-access:approve",
                "platform:operator", SupportAccess.PERMISSION, "loan:repay", "approval:approve")) {
            assertFalse(SupportAccess.READ_ONLY_PERMISSIONS.contains(never), never);
        }
    }

    @Test
    void expiry_is_judged_on_every_request() {
        Instant expires = APPROVED.plusSeconds(2 * 3600);
        assertEquals(Route.SUPPORT, full(ENGINEER, get("/api/v1/me"), GOOD, expires.minusMillis(1)).route());
        assertTrue(full(ENGINEER, get("/api/v1/me"), GOOD, expires).denied(), "at the expiry instant");
        assertTrue(full(ENGINEER, get("/api/v1/me"), GOOD, expires.plusSeconds(1)).reason().contains("expired"));
        assertTrue(full(ENGINEER, get("/api/v1/me"), GOOD, APPROVED.minusSeconds(1)).denied(), "before the approval");
    }

    @Test
    void only_an_approved_grant_counts() {
        Instant expires = APPROVED.plusSeconds(3600 * 2);
        for (String status : List.of("REQUESTED", "REJECTED", "REVOKED", "EXPIRED", "")) {
            assertTrue(full(ENGINEER, get("/api/v1/me"), grant(status, expires), NOW).denied(), status);
        }
        assertTrue(full(ENGINEER, get("/api/v1/me"), grant("REVOKED", expires), NOW).reason().contains("REVOKED"));
        // a grant with no window, or a longer window than the rule allows, is refused whatever its status says
        assertTrue(full(ENGINEER, get("/api/v1/me"), grant("APPROVED", null), NOW).denied());
        assertTrue(full(ENGINEER, get("/api/v1/me"), grant("APPROVED", APPROVED.plusSeconds(8 * 3600 + 1)), NOW).denied());
        assertEquals(Route.SUPPORT, full(ENGINEER, get("/api/v1/me"), grant("APPROVED", APPROVED.plusSeconds(8 * 3600)), NOW).route());
        Grant writeScope = new Grant(GRANT_ID, TENANT, "kc-sub-eng-1", "eng.one", "APPROVED", "READ_WRITE", APPROVED, expires);
        assertTrue(full(ENGINEER, get("/api/v1/me"), writeScope, NOW).denied());
    }

    @Test
    void a_grant_is_for_one_engineer_and_one_tenant() {
        Token other = new Token(PLATFORM, null, "kc-sub-eng-2", "eng.two", Set.of(SupportAccess.PERMISSION));
        assertTrue(full(other, get("/api/v1/me"), GOOD, NOW).reason().contains("another engineer"));
        // same user name, different account
        Token sameName = new Token(PLATFORM, null, "kc-sub-eng-9", "eng.one", Set.of(SupportAccess.PERMISSION));
        assertTrue(full(sameName, get("/api/v1/me"), GOOD, NOW).denied());
        // the grant was read from another tenant's database, or is another grant
        Grant elsewhere = new Grant(GRANT_ID, "claude-other", "kc-sub-eng-1", "eng.one", "APPROVED", "READ_ONLY", APPROVED, APPROVED.plusSeconds(3600 * 2));
        assertTrue(full(ENGINEER, get("/api/v1/me"), elsewhere, NOW).reason().contains("another tenant"));
        Grant otherId = new Grant("00000000-0000-0000-0000-0000000005a2", TENANT, "kc-sub-eng-1", "eng.one", "APPROVED", "READ_ONLY", APPROVED,
                APPROVED.plusSeconds(3600 * 2));
        assertTrue(full(ENGINEER, get("/api/v1/me"), otherId, NOW).denied());
        // an engineer whose token has no usable identity
        Token noSubject = new Token(PLATFORM, null, " ", "eng.one", Set.of(SupportAccess.PERMISSION));
        assertTrue(SupportAccess.route(noSubject, get("/api/v1/me"), PLATFORM).denied());
        Token oddName = new Token(PLATFORM, null, "kc-sub-eng-1", "eng one; drop", Set.of(SupportAccess.PERMISSION));
        assertTrue(SupportAccess.route(oddName, get("/api/v1/me"), PLATFORM).denied());
    }

    @Test
    void only_listed_paths_are_open_and_files_with_personal_data_never_are() {
        String loan = "/api/v1/loans/3fa85f64-5717-4562-b3fc-2c963f66afa6";
        String customer = "/api/v1/customers/3fa85f64-5717-4562-b3fc-2c963f66afa6";
        for (String ok : List.of("/api/v1/me", "/api/v1/customers", customer, customer + "/kyc-documents", customer + "/consents", loan,
                loan + "/schedule", loan + "/transactions", "/api/v1/eod/runs", "/api/v1/eod/runs/42", "/api/v1/jobs/runs", "/api/v1/approvals",
                "/api/v1/gl/trial-balance", "/api/v1/enumerations/consent-purpose", "/api/v1/reports/runs", "/api/v1/dashboard")) {
            assertTrue(SupportAccess.readable(ok), ok);
        }
        for (String closed : List.of(
                customer + "/kyc-documents/3fa85f64-5717-4562-b3fc-2c963f66afa6/content",       // KYC document content
                "/api/v1/reports/runs/3fa85f64-5717-4562-b3fc-2c963f66afa6/download",             // report files, bureau export
                loan + "/documents/kfs.pdf", loan + "/documents/statement.pdf", loan + "/documents/noc.pdf", loan + "/charges/C1/invoice.pdf",
                "/api/v1/sessions", "/api/v1/support-access", "/api/v1/support-access/" + GRANT_ID + "/approve",
                "/api/v1/unknown-new-endpoint", "/api/v2/customers", "/api", "/api/v1", "/api/v1/", "/actuator/health", "/developer")) {
            assertFalse(SupportAccess.readable(closed), closed);
        }
    }

    @Test
    void odd_paths_are_refused_not_interpreted() {
        for (String odd : List.of("/api/v1/loans/../customers", "/api/v1/customers/x/../../reports/runs/y/download", "/api/v1//customers",
                "/api/v1/customers/", "/api/v1/customers;jsessionid=1", "/api/v1/customers/%2e%2e/x", "/api/v1/loans/x%2Fdocuments",
                "/api/v1/customers\\x", "/api/v1/customers?x=1", "/API/V1/customers", "/api/v1/customers/a b", "/api/v1/customers/\u0000",
                "api/v1/customers", "", "/api/v1/customers/" + "x".repeat(400))) {
            assertFalse(SupportAccess.readable(odd), odd);
            assertTrue(SupportAccess.route(ENGINEER, get(odd), PLATFORM).denied(), odd);
        }
        assertFalse(SupportAccess.readable(null));
    }

    // ------------------------------------------------------------------------------------- audit and masking
    @Test
    void audit_detail_names_the_engineer_and_the_grant_and_has_no_query_string() {
        Map<String, Object> detail = SupportAccess.auditDetail(ENGINEER, get("/api/v1/customers"), GRANT_ID, 200);
        assertEquals(GRANT_ID, detail.get("grantId"));
        assertEquals("eng.one", detail.get("engineer"));
        assertEquals("kc-sub-eng-1", detail.get("engineerSubject"));
        assertEquals("GET", detail.get("method"));
        assertEquals("/api/v1/customers", detail.get("path"));
        assertEquals(Integer.valueOf(200), detail.get("status"));
        assertEquals(6, detail.size());
    }

    @Test
    void personal_values_in_a_response_are_masked() {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", "3fa85f64-5717-4562-b3fc-2c963f66afa6");
        customer.put("customerNo", "90010000000013");
        customer.put("displayName", "CLAUDE-TEST Asha Nair");
        customer.put("dateOfBirth", "1990-04-12");
        customer.put("panMasked", "XXXXX1234X");
        customer.put("homeBranch", "HO");
        customer.put("email", "asha@example.com");
        customer.put("address", Map.of("line1", "12 MG Road", "city", "Kochi", "pincode", "682001", "stateCode", "32"));
        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("loanNo", "10010000000017");
        loan.put("customerName", "CLAUDE-TEST Asha Nair");
        loan.put("productName", "Personal loan");
        loan.put("amount", "100000.00");
        loan.put("dpd", 3);
        loan.put("parties", List.of(Map.of("relatedCustomerName", "CLAUDE-TEST Ravi", "role", "GUARANTOR")));
        Object masked = SupportAccess.maskResponse(List.of(customer, loan));

        Map<?, ?> c = (Map<?, ?>) ((List<?>) masked).get(0);
        assertEquals("C*** A*** N***", c.get("displayName"));
        assertEquals("****", c.get("dateOfBirth"));
        assertEquals("a***@example.com", c.get("email"));
        assertEquals("****", c.get("address"), "a personal value that is not text is hidden whole");
        assertEquals("90010000000013", c.get("customerNo"));
        assertEquals("XXXXX1234X", c.get("panMasked"));
        assertEquals("HO", c.get("homeBranch"));
        Map<?, ?> l = (Map<?, ?>) ((List<?>) masked).get(1);
        assertEquals("C*** A*** N***", l.get("customerName"));
        assertEquals("Personal loan", l.get("productName"));
        assertEquals("100000.00", l.get("amount"));
        assertEquals(Integer.valueOf(3), l.get("dpd"));
        assertEquals("C*** R***", ((Map<?, ?>) ((List<?>) l.get("parties")).get(0)).get("relatedCustomerName"));
        assertFalse(masked.toString().contains("Asha"));
        assertFalse(masked.toString().contains("MG Road"));
        // the input is untouched
        assertEquals("CLAUDE-TEST Asha Nair", customer.get("displayName"));
    }

    @Test
    void masking_handles_scalars_nulls_and_nesting() {
        assertEquals("text", SupportAccess.maskResponse("text"));
        assertNull(SupportAccess.maskResponse(null));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("firstName", null);
        m.put("mobile", "9876543210");
        m.put("pincode", "682001");
        m.put("city", "Kochi");
        m.put("payload", Map.of("displayName", "CLAUDE-TEST X", "ageBand", "26-35"));
        Map<?, ?> out = (Map<?, ?>) SupportAccess.maskResponse(m);
        assertNull(out.get("firstName"));
        assertEquals("****", out.get("mobile"));
        assertEquals("682XXX", out.get("pincode"));
        assertEquals("****", out.get("city"));
        assertEquals("C*** X***", ((Map<?, ?>) out.get("payload")).get("displayName"));
        assertEquals("26-35", ((Map<?, ?>) out.get("payload")).get("ageBand"));
        for (String notPersonal : List.of("name", "productName", "glName", "roleName", "stateName", "tenantName", "legalName", "username",
                "fileName", "loanNo", "status")) {
            assertFalse(SupportAccess.personal(notPersonal), notPersonal);
        }
        for (String personal : List.of("displayName", "customerName", "relatedCustomerName", "firstName", "DateOfBirth", "addressLine1",
                "nomineeName", "guarantorName")) {
            assertTrue(SupportAccess.personal(personal), personal);
        }
        assertEquals("***", SupportAccess.name("  "));
    }
}
