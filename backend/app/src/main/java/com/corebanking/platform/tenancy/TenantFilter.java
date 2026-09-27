package com.corebanking.platform.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the tenant from the verified JWT's {@code tenant} claim (issued by the tenant's Keycloak realm).
 * A request without a valid tenant claim never reaches a tenant database.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
                String tenant = jwt.getToken().getClaimAsString("tenant");
                if (tenant != null) TenantContext.set(tenant);
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
