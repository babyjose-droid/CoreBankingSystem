package com.corebanking.platform;

import com.corebanking.platform.tenancy.TenantContext;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The authenticated staff user of the current request. Batch jobs run as the service account. */
public record CurrentUser(String subject, String login, String displayName, String tenant, Set<String> permissions) {

    public static final String SYSTEM = "system";

    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            var t = jwt.getToken();
            String username = t.getClaimAsString("preferred_username");
            String name = t.getClaimAsString("name");
            return new CurrentUser(t.getSubject(), username == null ? t.getSubject() : username,
                    name == null ? username : name, TenantContext.currentOrNull(), authorities(auth.getAuthorities()));
        }
        return new CurrentUser(SYSTEM, SYSTEM, "System", TenantContext.currentOrNull(), Set.of());
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

    private static Set<String> authorities(Collection<? extends GrantedAuthority> a) {
        return a.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet());
    }
}
