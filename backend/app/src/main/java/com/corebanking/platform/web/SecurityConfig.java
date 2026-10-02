package com.corebanking.platform.web;

import com.corebanking.platform.tenancy.TenantDirectory;
import com.corebanking.platform.tenancy.TenantFilter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT security.
 * <ul>
 *   <li>One Keycloak realm per tenant (ADR-007): tokens are accepted from the realms of ACTIVE tenants and the
 *       operators' {@code platform} realm under the trusted issuer prefix; {@link TenantFilter} then requires the
 *       realm to equal the token's {@code tenant} claim on {@code /api/**}, and the platform realm on
 *       {@code /platform/**}, so a tenant realm admin can never mint a control-plane operator.</li>
 *   <li>Authorities come from the {@code permissions} claim (client roles of the {@code api} client), e.g.
 *       {@code customer:create}; controllers check them with {@code @PreAuthorize} (US-020).</li>
 *   <li>Signing keys can be fetched from an internal URL (e.g. {@code http://keycloak:8081}) while the issuer
 *       stays the public one — needed in Docker and inside a cluster.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, TenantDirectory directory,
                            @Value("${corebanking.oidc.issuer-prefix}") String issuerPrefix,
                            @Value("${corebanking.oidc.jwks-base:}") String jwksBase,
                            @Value("${corebanking.oidc.audience:api}") String audience,
                            @Value("${corebanking.oidc.platform-realm:platform}") String platformRealm) throws Exception {
        String prefix = issuerPrefix.endsWith("/") ? issuerPrefix : issuerPrefix + "/";
        String keysBase = jwksBase == null || jwksBase.isBlank() ? prefix : (jwksBase.endsWith("/") ? jwksBase : jwksBase + "/");
        Map<String, AuthenticationManager> managers = new ConcurrentHashMap<>();
        AuthenticationManagerResolver<String> byIssuer = issuer -> {
            if (issuer == null || !issuer.startsWith(prefix)) return null;
            String realm = issuer.substring(prefix.length());
            if (!realm.matches("[a-z][a-z0-9-]{2,30}")) return null;
            // Only the operators' realm and ACTIVE tenants: an unknown issuer never creates a decoder or a JWKS
            // fetch, so forged tokens cannot grow this map (ASVS V9).
            if (!realm.equals(platformRealm) && !directory.isActive(realm)) return null;
            return managers.computeIfAbsent(issuer, iss -> {
                NimbusJwtDecoder decoder = NimbusJwtDecoder
                        .withJwkSetUri(keysBase + realm + "/protocol/openid-connect/certs").build();
                OAuth2TokenValidator<Jwt> audienceCheck = new JwtClaimValidator<List<String>>("aud",
                        aud -> aud != null && aud.contains(audience));
                decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(JwtValidators.createDefaultWithIssuer(iss), audienceCheck)));
                JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
                provider.setJwtAuthenticationConverter(new PermissionsConverter());
                return provider::authenticate;
            });
        };

        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/platform/v1/**").hasAuthority("platform:operator")
                    .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.authenticationManagerResolver(new JwtIssuerAuthenticationManagerResolver(byIssuer)))
            .addFilterAfter(new TenantFilter(prefix, platformRealm, directory), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Maps the {@code permissions} claim to authorities, with no prefix. */
    static final class PermissionsConverter implements Converter<Jwt, AbstractAuthenticationToken> {
        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            List<GrantedAuthority> authorities = permissions(jwt).stream()
                    .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getClaimAsString("preferred_username"));
        }

        static Collection<String> permissions(Jwt jwt) {
            List<String> p = jwt.getClaimAsStringList("permissions");
            return p == null ? List.of() : p;
        }
    }
}
