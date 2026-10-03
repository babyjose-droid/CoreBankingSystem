package com.corebanking.platform.tenancy;

import com.corebanking.audit.AuditLog;
import com.corebanking.kernel.SupportAccess;
import com.corebanking.platform.Json;
import com.corebanking.platform.TenantDataSources;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Binds the tenant for the request and enforces tenant isolation and module entitlements (US-002).
 * <ol>
 *   <li>The tenant comes only from the verified token's {@code tenant} claim, and must equal the Keycloak realm
 *       that issued the token — a token from realm A can never act on tenant B.</li>
 *   <li>The tenant must be ACTIVE in the control plane.</li>
 *   <li>API paths that belong to a licensable module return 403 when the module is not enabled.</li>
 * </ol>
 * Control-plane paths ({@code /platform/**}) run without a tenant and accept only platform-realm tokens;
 * health checks and the developer portal are not filtered.
 * <p>
 * <b>Support access (US-007, ADR-016).</b> The one case in which a platform-realm token reaches {@code /api/**}:
 * the token carries {@code platform:support}, the call is a GET on the support list, and the request names a grant
 * ({@code X-Support-Tenant}, {@code X-Support-Grant}) that the tenant's admin approved for this engineer and that
 * is in force at this moment. The grant is read from that tenant's own database on every request, so expiry and
 * revocation take effect at once. The decision itself is {@link SupportAccess}, plain Java with its own tests.
 * Such a request then runs with read-only authorities under the login {@code support:<engineer>}; its JSON
 * response has personal values masked, anything that is not JSON is withheld, and the call is written to the
 * tenant's audit trail before any data is returned.
 * <p>
 * Created by SecurityConfig inside the security chain (not a servlet-level bean).
 */
public class TenantFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

    private final String issuerPrefix;
    private final String platformRealm;
    private final TenantDirectory directory;
    private final TenantDataSources dataSources;
    private final AuditLog audit;
    private final Json json;
    private final ApiCallMeter meter;

    public TenantFilter(String issuerPrefix, String platformRealm, TenantDirectory directory, TenantDataSources dataSources,
                        AuditLog audit, Json json, ApiCallMeter meter) {
        this.issuerPrefix = issuerPrefix;
        this.platformRealm = platformRealm;
        this.directory = directory;
        this.dataSources = dataSources;
        this.audit = audit;
        this.json = json;
        this.meter = meter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") && !path.startsWith("/platform/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)) {
            chain.doFilter(request, response);           // unauthenticated: the security chain answers 401
            return;
        }
        Jwt jwtToken = jwt.getToken();
        String issuer = jwtToken.getIssuer() == null ? "" : jwtToken.getIssuer().toString();
        Set<String> permissions = jwt.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet());
        SupportAccess.Token token = new SupportAccess.Token(issuer.startsWith(issuerPrefix) ? issuer.substring(issuerPrefix.length()) : null,
                jwtToken.getClaimAsString("tenant"), jwtToken.getSubject(), jwtToken.getClaimAsString("preferred_username"), permissions);
        SupportAccess.Request call = new SupportAccess.Request(request.getMethod(), request.getRequestURI(),
                request.getHeader(SupportAccess.TENANT_HEADER), request.getHeader(SupportAccess.GRANT_HEADER));

        SupportAccess.Decision decision = SupportAccess.route(token, call, platformRealm);
        switch (decision.route()) {
            case CONTROL_PLANE -> chain.doFilter(request, response);
            case TENANT_USER -> {
                String tenant = decision.tenant();
                if (!entitled(tenant, request, response)) return;
                meter.count(tenant);
                try {
                    TenantContext.set(tenant);
                    MDC.put("tenant", tenant);
                    chain.doFilter(request, response);
                } finally {
                    TenantContext.clear();
                    MDC.remove("tenant");
                }
            }
            case SUPPORT -> support(decision, token, call, jwtToken, request, response, chain);
            default -> {
                if (token.realm() != null && token.realm().equals(platformRealm) && call.path().startsWith("/api/")) {
                    log.warn("support access refused: engineer={} tenant={} reason={}", token.username(), call.supportTenant(), decision.reason());
                }
                problem(response, 403, decision.reason());
            }
        }
    }

    /** The tenant is ACTIVE and the module the path belongs to is enabled; otherwise 403 is written. */
    private boolean entitled(String tenant, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!directory.isActive(tenant)) {
            problem(response, 403, "tenant is not active");
            return false;
        }
        String module = TenantDirectory.moduleForPath(request.getRequestURI());
        if (module != null && !directory.modules(tenant).contains(module)) {
            problem(response, 403, "module " + module + " is not enabled for this tenant");
            return false;
        }
        return true;
    }

    private void support(SupportAccess.Decision provisional, SupportAccess.Token token, SupportAccess.Request call, Jwt jwtToken,
                         HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String tenant = provisional.tenant();
        // One answer for "no such tenant", "no such grant", "expired", "revoked": the caller learns nothing from the difference.
        SupportAccess.Decision decision = directory.isActive(tenant)
                ? SupportAccess.authorise(provisional, token, grant(tenant, provisional.grantId()), Instant.now())
                : new SupportAccess.Decision(SupportAccess.Route.DENY, null, null, "tenant is not active");
        if (decision.denied()) {
            log.warn("support access refused: engineer={} tenant={} grant={} reason={}", token.username(), tenant, provisional.grantId(),
                    decision.reason());
            problem(response, 403, "no support access is in force for this tenant");
            return;
        }
        if (!entitled(tenant, request, response)) return;

        SecurityContext context = SecurityContextHolder.getContext();
        Authentication own = context.getAuthentication();
        List<GrantedAuthority> readOnly = SupportAccess.READ_ONLY_PERMISSIONS.stream().sorted()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        try {
            context.setAuthentication(new SupportAuthentication(jwtToken, readOnly, token.username(), decision.grantId()));
            TenantContext.set(tenant);
            MDC.put("tenant", tenant);
            chain.doFilter(request, buffered);
            // Audit first: if the trail cannot be written, nothing is returned.
            audit.record(SupportAccess.login(token.username()), "SUPPORT_READ", "SUPPORT_ACCESS", decision.grantId(),
                    SupportAccess.auditDetail(token, call, decision.grantId(), buffered.getStatus()));
            release(buffered, response);
        } catch (RuntimeException e) {
            log.error("support request failed: engineer={} tenant={} grant={}", token.username(), tenant, decision.grantId(), e);
            if (!response.isCommitted()) {
                response.reset();
                problem(response, 500, "the request could not be completed");
            }
        } finally {
            context.setAuthentication(own);
            TenantContext.clear();
            MDC.remove("tenant");
        }
    }

    /** Writes the buffered response to the client: JSON with personal values masked, nothing else. */
    private void release(ContentCachingResponseWrapper buffered, HttpServletResponse response) throws IOException {
        byte[] body = buffered.getContentAsByteArray();
        if (body.length == 0) return;                      // status and headers only; they are already on the response
        String type = buffered.getContentType() == null ? "" : buffered.getContentType().toLowerCase(java.util.Locale.ROOT);
        if (!type.startsWith("application/json") && !type.startsWith("application/problem+json")) {
            response.reset();
            problem(response, 403, "only JSON responses are available under support access");
            return;
        }
        Object masked = SupportAccess.maskResponse(json.read(new String(body, StandardCharsets.UTF_8), Object.class));
        byte[] out = json.write(masked).getBytes(StandardCharsets.UTF_8);
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(out.length);
        response.getOutputStream().write(out);
    }

    /** The grant as stored in the tenant's own database, or null when the tenant or the grant does not exist. */
    private SupportAccess.Grant grant(String tenant, String grantId) {
        JdbcTemplate jdbc;
        try {
            jdbc = new JdbcTemplate(dataSources.of(tenant));
        } catch (IllegalStateException e) {
            return null;                                   // tenant not registered on this instance
        }
        List<SupportAccess.Grant> found = jdbc.query("""
                SELECT id::text, engineer_subject, engineer, status, scope, decided_at, expires_at
                  FROM platform.support_access WHERE id = ?::uuid
                """, (rs, i) -> new SupportAccess.Grant(rs.getString(1), tenant, rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7))), grantId);
        return found.isEmpty() ? null : found.get(0);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static void problem(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        String title = status == 403 ? "Forbidden" : "Internal Server Error";
        String safe = detail == null ? "" : detail.replace("\\", "").replace("\"", "'");
        response.getWriter().write("{\"status\":" + status + ",\"title\":\"" + title + "\",\"detail\":\"" + safe + "\"}");
    }
}
