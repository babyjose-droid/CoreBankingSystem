package com.corebanking.platform.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the tenant for the request and enforces tenant isolation and module entitlements (US-002).
 * <ol>
 *   <li>The tenant comes only from the verified token's {@code tenant} claim, and must equal the Keycloak realm
 *       that issued the token — a token from realm A can never act on tenant B.</li>
 *   <li>The tenant must be ACTIVE in the control plane.</li>
 *   <li>API paths that belong to a licensable module return 403 when the module is not enabled.</li>
 * </ol>
 * Control-plane paths ({@code /platform/**}) run without a tenant and accept only platform-realm tokens;
 * health checks are not filtered.
 * Created by SecurityConfig inside the security chain (not a servlet-level bean).
 */
public class TenantFilter extends OncePerRequestFilter {

    private final String issuerPrefix;
    private final String platformRealm;
    private final TenantDirectory directory;

    public TenantFilter(String issuerPrefix, String platformRealm, TenantDirectory directory) {
        this.issuerPrefix = issuerPrefix;
        this.platformRealm = platformRealm;
        this.directory = directory;
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
        String tenant = jwt.getToken().getClaimAsString("tenant");
        String issuer = jwt.getToken().getIssuer() == null ? "" : jwt.getToken().getIssuer().toString();
        if (request.getRequestURI().startsWith("/platform/")) {
            // Control plane: only tokens of the operators' realm, whatever permissions a tenant realm grants.
            if (!issuer.equals(issuerPrefix + platformRealm)) {
                problem(response, 403, "control-plane calls need a token from the platform realm");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (tenant == null || tenant.equals(platformRealm) || !issuer.equals(issuerPrefix + tenant)) {
            problem(response, 403, "token tenant does not match its issuing realm");
            return;
        }
        if (!directory.isActive(tenant)) {
            problem(response, 403, "tenant is not active");
            return;
        }
        String module = TenantDirectory.moduleForPath(request.getRequestURI());
        if (module != null && !directory.modules(tenant).contains(module)) {
            problem(response, 403, "module " + module + " is not enabled for this tenant");
            return;
        }
        try {
            TenantContext.set(tenant);
            MDC.put("tenant", tenant);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove("tenant");
        }
    }

    private static void problem(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":" + status + ",\"title\":\"Forbidden\",\"detail\":\"" + detail + "\"}");
    }
}
