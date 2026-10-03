package com.corebanking.platform;

import java.time.Instant;
import java.util.List;

/**
 * Login sessions of staff users (US-027). Sessions live in the identity provider (ADR-007), so this is a port:
 * the implementation today talks to Keycloak's Admin REST API ({@code KeycloakSessionAdmin}).
 * The realm is the tenant code.
 */
public interface SessionAdmin {

    /** One login session. {@code clients} are the applications it has been used with (console, account …). */
    record Session(String id, String userId, String username, String ipAddress, Instant startedAt, Instant lastAccessAt,
                   List<String> clients) {}

    /** The user's active sessions; {@code userId} is the token subject. */
    List<Session> sessionsOfUser(String realm, String userId);

    /** The id of the user with exactly this user name, or null when there is none. */
    String userIdOf(String realm, String username);

    /** Ends one session: the user is signed out of every application that used it. */
    void terminate(String realm, String sessionId);
}
