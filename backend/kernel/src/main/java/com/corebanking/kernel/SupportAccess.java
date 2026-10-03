package com.corebanking.kernel;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Who may call what, decided from the verified token and the request (ADR-007, ADR-016). This is the one place that
 * says which realm reaches which API, so it is plain Java and tested case by case.
 * <ol>
 *   <li><b>Control plane</b> ({@code /platform/**}): only a token of the operators' realm.</li>
 *   <li><b>Tenant API</b> ({@code /api/**}) as a tenant user: the token's realm equals its {@code tenant} claim and is
 *       not the operators' realm. Such a request must not carry the support headers.</li>
 *   <li><b>Tenant API under support access</b> (US-007): a token of the operators' realm with the permission
 *       {@code platform:support}, a read ({@code GET}) of a path on the support list, and a grant that the tenant's
 *       admin approved for this engineer, for this tenant, that has not expired and was not revoked. The grant is
 *       named by the request ({@code X-Support-Tenant}, {@code X-Support-Grant}) and looked up in that tenant's own
 *       database; the headers locate it, they never authorise anything by themselves.</li>
 *   <li>Everything else is refused. A platform token without a grant in force can never act as a tenant user, and a
 *       tenant token can never reach the control plane.</li>
 * </ol>
 */
public final class SupportAccess {

    public static final String PERMISSION = "platform:support";
    public static final String TENANT_HEADER = "X-Support-Tenant";
    public static final String GRANT_HEADER = "X-Support-Grant";
    /** The engineer acts under this login, which no tenant user can have. */
    public static final String LOGIN_PREFIX = "support:";
    public static final int MIN_MINUTES = 15;
    public static final int MAX_MINUTES = 480;
    public static final String READ_ONLY = "READ_ONLY";

    /**
     * What a READ_ONLY support session may do inside the tenant API: view permissions only. Not here on purpose:
     * anything that writes, {@code kyc:view-document} (document content), {@code bureau:export} and
     * {@code report:run} (files with personal data), {@code session:*} and {@code support-access:*}.
     */
    public static final Set<String> READ_ONLY_PERMISSIONS = Set.of(
            "approval:view", "audit:view", "branch:view", "consent:view", "custom-field:view", "customer:view", "dashboard:view",
            "eod:view", "gl:view", "holiday:view", "job:view", "limit:view", "loan:view", "master:view", "product:view",
            "staff:view", "tax:view");

    /**
     * Paths open to a READ_ONLY support session ({@code *} is one path segment). A new endpoint is closed to support
     * until it is listed here. Left out on purpose: KYC document content, loan documents and invoices (PDFs with
     * the borrower's name and address), report files and the bureau export, sessions, and the support-access
     * endpoints themselves.
     */
    private static final List<String> READ_ONLY_PATHS = List.of(
            "/api/v1/me", "/api/v1/business-day", "/api/v1/branches", "/api/v1/holidays", "/api/v1/tax-rates", "/api/v1/amount-limits",
            "/api/v1/enumerations", "/api/v1/enumerations/*", "/api/v1/system-properties", "/api/v1/branch-sets", "/api/v1/staff",
            "/api/v1/states", "/api/v1/pincodes/*", "/api/v1/custom-fields",
            "/api/v1/approvals", "/api/v1/approvals/*",
            "/api/v1/customers", "/api/v1/customers/*", "/api/v1/customers/*/relationships", "/api/v1/customers/*/exposure",
            "/api/v1/customers/*/consents", "/api/v1/customers/*/kyc-documents",
            "/api/v1/gl/heads", "/api/v1/gl/vouchers", "/api/v1/gl/trial-balance", "/api/v1/gl/entries", "/api/v1/gl/profit-and-loss",
            "/api/v1/gl/balance-sheet",
            "/api/v1/eod/runs", "/api/v1/eod/runs/*", "/api/v1/eod/schedule",
            "/api/v1/jobs", "/api/v1/jobs/runs",
            "/api/v1/audit/events", "/api/v1/audit/verify",
            "/api/v1/loan-products", "/api/v1/loan-products/*", "/api/v1/loan-products/*/custom",
            "/api/v1/loans", "/api/v1/loans/*", "/api/v1/loans/*/schedule", "/api/v1/loans/*/transactions", "/api/v1/loans/*/parties",
            "/api/v1/loans/*/kfs", "/api/v1/loans/*/preclosure-quote", "/api/v1/loans/*/cancellation-quote", "/api/v1/loans/*/amendments",
            "/api/v1/loans/*/deferred-receipts", "/api/v1/deferred-receipts",
            "/api/v1/reports", "/api/v1/reports/runs",
            "/api/v1/dashboard", "/api/v1/dashboard/trend");

    private static final Pattern TENANT = Pattern.compile("[a-z][a-z0-9-]{2,30}");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern ENGINEER = Pattern.compile("[A-Za-z0-9._@-]{2,80}");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._~-]{1,100}");
    private static final Duration MAX_DURATION = Duration.ofMinutes(MAX_MINUTES);

    public enum Route { TENANT_USER, CONTROL_PLANE, SUPPORT, DENY }

    /**
     * The verified token.
     *
     * @param realm the realm that issued it: the part of the issuer after the trusted prefix, or null when the
     *              issuer is not under that prefix
     */
    public record Token(String realm, String tenantClaim, String subject, String username, Set<String> permissions) {
        public Token {
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }

    /** @param path the raw request path (no query string), exactly as received */
    public record Request(String method, String path, String supportTenant, String supportGrant) {}

    /** A support access record as stored in the tenant's database ({@code tenant} is the database it was read from). */
    public record Grant(String id, String tenant, String engineerSubject, String engineer, String status, String scope,
                        Instant approvedAt, Instant expiresAt) {}

    /**
     * @param tenant  the tenant to bind for TENANT_USER and SUPPORT
     * @param grantId the grant to look up (SUPPORT)
     * @param reason  why the request was refused (DENY); for the log, not all of it is for the caller
     */
    public record Decision(Route route, String tenant, String grantId, String reason) {
        public boolean denied() {
            return route == Route.DENY;
        }
    }

    private SupportAccess() {}

    private static Decision deny(String reason) {
        return new Decision(Route.DENY, null, null, reason);
    }

    /**
     * First step, from the token and the request alone. A SUPPORT result is provisional: the caller reads the grant
     * from the tenant's database and must then pass {@link #authorise}.
     */
    public static Decision route(Token token, Request request, String platformRealm) {
        if (token == null || token.realm() == null || token.realm().isBlank()) return deny("the token was not issued by a trusted realm");
        boolean platformToken = token.realm().equals(platformRealm);
        String path = request.path() == null ? "" : request.path();
        if (path.startsWith("/platform/")) {
            return platformToken ? new Decision(Route.CONTROL_PLANE, null, null, null)
                    : deny("control-plane calls need a token from the platform realm");
        }
        if (!path.startsWith("/api/")) return deny("not an API path");
        boolean supportHeaders = request.supportTenant() != null || request.supportGrant() != null;
        if (!platformToken) {
            String tenant = token.tenantClaim();
            if (tenant == null || tenant.equals(platformRealm) || !tenant.equals(token.realm())) {
                return deny("token tenant does not match its issuing realm");
            }
            if (supportHeaders) return deny("support access is for platform engineers only");
            if (token.username() != null && token.username().toLowerCase(Locale.ROOT).startsWith(LOGIN_PREFIX)) {
                return deny("this user name is reserved for support access");
            }
            return new Decision(Route.TENANT_USER, tenant, null, null);
        }
        // A token of the operators' realm on the tenant API: only as support access.
        if (!token.permissions().contains(PERMISSION)) return deny("a platform token cannot call the tenant API");
        if (request.supportTenant() == null || request.supportGrant() == null) {
            return deny("support access needs the headers " + TENANT_HEADER + " and " + GRANT_HEADER);
        }
        if (!TENANT.matcher(request.supportTenant()).matches() || request.supportTenant().equals(platformRealm)) {
            return deny("the tenant named for support access is not valid");
        }
        if (!UUID.matcher(request.supportGrant()).matches()) return deny("the support grant id is not valid");
        if (token.subject() == null || token.subject().isBlank() || token.username() == null || !ENGINEER.matcher(token.username()).matches()) {
            return deny("the platform token does not identify an engineer");
        }
        if (!"GET".equals(request.method())) return deny("support access is read-only");
        if (!readable(path)) return deny("this part of the API is not open to support access");
        return new Decision(Route.SUPPORT, request.supportTenant(), request.supportGrant(), null);
    }

    /**
     * Second step for a provisional SUPPORT decision: the grant read from the tenant's database must be this
     * engineer's, for this tenant, approved, read-only and inside its time window, on this very request.
     */
    public static Decision authorise(Decision provisional, Token token, Grant grant, Instant now) {
        if (provisional.route() != Route.SUPPORT) return provisional;
        if (grant == null) return deny("no such support grant for this tenant");
        if (!provisional.grantId().equals(grant.id()) || !provisional.tenant().equals(grant.tenant())) {
            return deny("the support grant belongs to another tenant");
        }
        if (grant.engineerSubject() == null || !grant.engineerSubject().equals(token.subject())) {
            return deny("the support grant was given to another engineer");
        }
        if (!"APPROVED".equals(grant.status())) return deny("the support grant is " + grant.status());
        if (!READ_ONLY.equals(grant.scope())) return deny("the scope of the support grant is not supported");
        if (grant.approvedAt() == null || grant.expiresAt() == null) return deny("the support grant has no time window");
        if (now.isBefore(grant.approvedAt())) return deny("the support grant is not in force yet");
        if (!now.isBefore(grant.expiresAt())) return deny("the support grant has expired");
        if (Duration.between(grant.approvedAt(), grant.expiresAt()).compareTo(MAX_DURATION) > 0) {
            return deny("the support grant runs longer than 8 hours");
        }
        return provisional;
    }

    /** The login a support engineer acts under inside the tenant: it shows in the audit trail and in branch scope. */
    public static String login(String engineer) {
        return LOGIN_PREFIX + engineer;
    }

    /**
     * True when a READ_ONLY session may read the path. Only canonical paths are considered: anything with an empty,
     * dot or encoded segment, a matrix parameter or a backslash is refused rather than interpreted.
     */
    public static boolean readable(String path) {
        if (path == null || !path.startsWith("/") || path.length() > 300) return false;
        String[] segments = path.substring(1).split("/", -1);
        for (String s : segments) {
            if (!SEGMENT.matcher(s).matches() || s.equals(".") || s.equals("..")) return false;
        }
        for (String allowed : READ_ONLY_PATHS) {
            String[] pattern = allowed.substring(1).split("/");
            if (pattern.length != segments.length) continue;
            boolean match = true;
            for (int i = 0; i < pattern.length && match; i++) {
                match = pattern[i].equals("*") || pattern[i].equals(segments[i]);
            }
            if (match) return true;
        }
        return false;
    }

    /** Request details that may be written to the audit trail: never the query string (it can hold a PAN or a name). */
    public static Map<String, Object> auditDetail(Token token, Request request, String grantId, int responseStatus) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("grantId", grantId);
        m.put("engineer", token.username());
        m.put("engineerSubject", token.subject());
        m.put("method", request.method());
        m.put("path", request.path());
        m.put("status", responseStatus);
        return m;
    }

    // ------------------------------------------------------------------------------------------------ masking
    private static final Set<String> PERSONAL_KEYS = Set.of("dateofbirth", "dob", "email", "mobile", "phone", "pan", "aadhaar", "address",
            "addressline1", "addressline2", "line1", "line2", "city", "pincode");
    private static final Pattern PERSONAL_NAME = Pattern.compile(
            ".*(customer|display|first|middle|last|related|nominee|guarantor|borrower|applicant|party|holder|signatory|guardian|father|mother|spouse)name");

    /** True for a JSON property that holds personal data of a customer or a staff member. */
    public static boolean personal(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(Locale.ROOT);
        return PERSONAL_KEYS.contains(k) || PERSONAL_NAME.matcher(k).matches();
    }

    /**
     * The response body a support engineer receives: the same JSON with personal values masked. The API already
     * returns PAN, mobile and e-mail masked to everyone; names, dates of birth and address parts are clear for
     * staff (SEC-03) and are masked here. The input is a tree of maps, lists and scalars and is not modified.
     */
    public static Object maskResponse(Object tree) {
        if (tree instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, personal(key) ? maskValue(key, e.getValue()) : maskResponse(e.getValue()));
            }
            return out;
        }
        if (tree instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(maskResponse(o));
            return out;
        }
        return tree;
    }

    private static Object maskValue(String key, Object value) {
        if (value == null) return null;
        if (!(value instanceof String s)) return "****";
        String k = key.toLowerCase(Locale.ROOT);
        if (k.endsWith("name")) return name(s);
        if (k.equals("email")) return Masking.email(s);
        if (k.equals("pincode")) return Masking.pincode(s);
        return "****";
    }

    /** "Asha Nair" → "A*** N***": enough to tell two records apart on a call, not enough to identify the person. */
    static String name(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.appendCodePoint(part.codePointAt(0)).append("***");
        }
        return sb.length() == 0 ? "***" : sb.toString();
    }
}
