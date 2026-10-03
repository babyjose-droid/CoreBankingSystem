package com.corebanking.integration.internal;

import java.util.List;

/**
 * Administration of API clients at the identity provider (US-120). The implementation is the Keycloak Admin REST
 * API ({@link KeycloakClientAdmin}); the port keeps the service free of it and lets another identity provider
 * (ADR-007 leaves Cognito open) be added.
 */
interface ClientAdmin {

    /** @param serviceUsername the login the client's tokens carry ({@code preferred_username}) */
    record Created(String id, String serviceUsername) {}

    Created create(String realm, String clientId, String name, List<String> scopes);

    /** Makes the client's permissions exactly {@code scopes}. */
    void setScopes(String realm, String id, List<String> scopes);

    void setEnabled(String realm, String id, String clientId, boolean enabled);

    /** A new secret; the previous one stops working. The caller shows it once and does not keep it. */
    String newSecret(String realm, String id);
}
