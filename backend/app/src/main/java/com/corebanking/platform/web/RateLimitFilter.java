package com.corebanking.platform.web;

import com.corebanking.kernel.RateLimitPaths;
import com.corebanking.kernel.RateLimiter;
import com.corebanking.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-user and per-address request limits (SEC-05, ASVS V2/V11): a token bucket for each signed-in user and each client
 * address ({@code corebanking.rate-limit.*}), and a tighter one for lookups that can be used to enumerate data (the
 * duplicate check, the pincode lookup). A refused call gets 429 {@code application/problem+json} with {@code Retry-After}.
 * <p>
 * Runs after authentication and the tenant filter, so the user is known; a call without a token (public paths) is
 * limited by address only. Exempt paths (day-end and other batch endpoints, health, provider callbacks) are never
 * limited. Buckets are in memory per instance: with several instances the effective limit is multiplied by their
 * number, and an exact limit needs a shared store. The address is the servlet's remote address, so behind a proxy the
 * deployment must set {@code server.forward-headers-strategy} (the proxy's header is never read here, as a client could
 * forge it). Nothing personal is logged.
 */
final class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitPaths paths;
    private final RateLimiter users;
    private final RateLimiter addresses;
    private final RateLimiter strictUsers;
    private final RateLimiter strictAddresses;

    RateLimitFilter(RateLimitPaths paths, int perUserPerMinute, int perIpPerMinute, int strictPerMinute) {
        this.paths = paths;
        this.users = new RateLimiter(perUserPerMinute);
        this.addresses = new RateLimiter(perIpPerMinute);
        this.strictUsers = new RateLimiter(strictPerMinute);
        this.strictAddresses = new RateLimiter(strictPerMinute);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        RateLimitPaths.Kind kind = paths.classify(path);
        if (kind == RateLimitPaths.Kind.EXEMPT || "OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String address = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String user = auth instanceof JwtAuthenticationToken && auth.isAuthenticated() ? TenantContext.currentOrNull() + ":" + auth.getName() : null;
        long retry = 0;
        String by = null;
        if (user != null) {
            RateLimiter.Decision d = users.tryAcquire(user);
            if (!d.allowed()) { retry = d.retryAfterSeconds(); by = "user"; }
        }
        if (by == null) {
            RateLimiter.Decision d = addresses.tryAcquire(address);
            if (!d.allowed()) { retry = d.retryAfterSeconds(); by = "address"; }
        }
        if (by == null && kind == RateLimitPaths.Kind.STRICT) {
            RateLimiter.Decision d = user != null ? strictUsers.tryAcquire(user) : strictAddresses.tryAcquire(address);
            if (d.allowed() && user != null) d = strictAddresses.tryAcquire(address);
            if (!d.allowed()) { retry = d.retryAfterSeconds(); by = user != null ? "user (lookup)" : "address (lookup)"; }
        }
        if (by == null) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("rate limit exceeded: {} on {}", by, path);
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retry));
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\",\"detail\":\"too many requests: try again in "
                + retry + " second(s)\"}");
    }
}
