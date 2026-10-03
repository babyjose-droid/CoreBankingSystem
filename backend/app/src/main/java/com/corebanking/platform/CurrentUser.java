package com.corebanking.platform;

import com.corebanking.platform.tenancy.SupportAuthentication;
import com.corebanking.platform.tenancy.TenantContext;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The authenticated staff user of the current request. Batch jobs run as the service account. A platform engineer
 * under support access (US-007) appears as {@code support:<name>}.
 *
 * @param permissions fine-grained permissions (the {@code permissions} claim), checked by {@code @PreAuthorize}
 * @param roles       realm roles (MAKER, CHECKER …) used only for role amount limits (US-021). Read from a
 *                    {@code roles} claim and from Keycloak's standard {@code realm_access.roles}; empty when the
 *                    token carries neither.
 */
public record CurrentUser(String subject, String login, String displayName, String tenant, Set<String> permissions,
                          Set<String> roles) {

    public static final String SYSTEM = "system";

    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof SupportAuthentication support) {
            // Time-boxed support access (US-007): the engineer acts as support:<name> with read-only authorities
            // and no roles; the token's own name and permissions are not used inside the tenant.
            return new CurrentUser(support.getToken().getSubject(), support.login(), "Support engineer " + support.engineer(),
                    TenantContext.currentOrNull(), authorities(auth.getAuthorities()), Set.of());
        }
        if (auth instanceof JwtAuthenticationToken jwt) {
            Jwt t = jwt.getToken();
            String username = t.getClaimAsString("preferred_username");
            String name = t.getClaimAsString("name");
            return new CurrentUser(t.getSubject(), username == null ? t.getSubject() : username,
                    name == null ? username : name, TenantContext.currentOrNull(), authorities(auth.getAuthorities()),
                    roles(t.getClaims()));
        }
        return new CurrentUser(SYSTEM, SYSTEM, "System", TenantContext.currentOrNull(), Set.of(), Set.of());
    }

    /** The tenant bound to this request or batch job; fails when none is bound. */
    public static String requireTenant() {
        return TenantContext.require();
    }

    /** Login name of the current user (Keycloak preferred_username), or "system" for batch work. */
    public static String username() {
        return get().login();
    }

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    /** True for a platform engineer acting under time-boxed support access. */
    public boolean isSupport() {
        return login != null && login.startsWith(com.corebanking.kernel.SupportAccess.LOGIN_PREFIX);
    }

    /** True for batch work (end of day, schedulers): no person is acting. */
    public boolean isSystem() {
        return SYSTEM.equals(subject);
    }

    /** Role names from {@code roles: [...]} and {@code realm_access: {roles: [...]}}; anything else is ignored. */
    static Set<String> roles(Map<String, Object> claims) {
        Set<String> out = new LinkedHashSet<>();
        add(out, claims.get("roles"));
        if (claims.get("realm_access") instanceof Map<?, ?> realm) add(out, realm.get("roles"));
        return Set.copyOf(out);
    }

    private static void add(Set<String> out, Object claim) {
        if (claim instanceof Collection<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) out.add(s);
            }
        }
    }

    private static Set<String> authorities(Collection<? extends GrantedAuthority> a) {
        return a.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet());
    }
}
