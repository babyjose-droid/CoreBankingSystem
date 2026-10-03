package com.corebanking.platform.tenancy;

import com.corebanking.kernel.SupportAccess;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The authentication of a request made under time-boxed support access (US-007, ADR-016). {@link TenantFilter} puts
 * it in place of the platform engineer's own authentication for that one request, after the grant was checked:
 * <ul>
 *   <li>the authorities are the read-only view permissions of {@link SupportAccess#READ_ONLY_PERMISSIONS}, never
 *       the token's own ({@code platform:support} opens nothing inside the tenant API);</li>
 *   <li>the login is {@code support:<engineer>}, which no tenant user can have, so audit entries and branch scope
 *       always tell a support engineer from staff.</li>
 * </ul>
 */
public final class SupportAuthentication extends JwtAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final String grantId;
    private final String engineer;

    SupportAuthentication(Jwt jwt, Collection<? extends GrantedAuthority> authorities, String engineer, String grantId) {
        super(jwt, authorities, SupportAccess.login(engineer));
        this.grantId = grantId;
        this.engineer = engineer;
    }

    /** {@code support:<engineer>}: what the audit trail and branch scope see. */
    public String login() {
        return SupportAccess.login(engineer);
    }

    public String engineer() {
        return engineer;
    }

    public String grantId() {
        return grantId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SupportAuthentication other && super.equals(o) && grantId.equals(other.grantId);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + grantId.hashCode();
    }
}
