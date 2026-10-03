package com.corebanking.platform.web;

import com.corebanking.audit.AuditLog;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.SessionAdmin;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session management (US-027). Every staff user sees their own login sessions and can end them; a user with
 * {@code session:admin} can list and end any user's. Sessions live in the identity provider ({@link SessionAdmin});
 * every termination is written to the audit trail with who ended whose session.
 * <p>
 * Ending a session signs the user out at the identity provider. An access token already issued stays valid until
 * it expires (minutes); it cannot be refreshed any more.
 */
@RestController
@RequestMapping("/api/v1/sessions")
class SessionController {

    private final SessionAdmin sessions;
    private final AuditLog audit;

    SessionController(SessionAdmin sessions, AuditLog audit) {
        this.sessions = sessions;
        this.audit = audit;
    }

    private record Target(String userId, String username, boolean own) {}

    /** The caller, or with {@code user} (needs session:admin) the named user. */
    private Target target(String user) {
        CurrentUser me = CurrentUser.get();
        if (me.isSupport()) throw ApiException.forbidden("sessions are not available under support access");
        if (user == null || user.isBlank() || user.equalsIgnoreCase(me.login())) return new Target(me.subject(), me.login(), true);
        if (!me.has("session:admin")) throw ApiException.forbidden("listing or ending another user's sessions needs the permission session:admin");
        String id = sessions.userIdOf(me.tenant(), user.trim());
        if (id == null) throw ApiException.notFound("user " + user.trim());
        return new Target(id, user.trim(), false);
    }

    /** openapi.yaml#/components/schemas/Session. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    List<Map<String, Object>> list(@RequestParam(required = false) String user) {
        Target t = target(user);
        String current = currentSessionId();
        return sessions.sessionsOfUser(CurrentUser.requireTenant(), t.userId()).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("username", s.username() == null ? t.username() : s.username());
            m.put("ipAddress", s.ipAddress());
            m.put("startedAt", s.startedAt() == null ? null : s.startedAt().toString());
            m.put("lastAccessAt", s.lastAccessAt() == null ? null : s.lastAccessAt().toString());
            m.put("clients", s.clients());
            m.put("current", t.own() && s.id().equals(current));
            return m;
        }).toList();
    }

    /**
     * Ends a session. Without {@code user} the session must be one of the caller's own; with {@code user}
     * (session:admin) it must be one of that user's. A session id that is not theirs is reported as not found.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void terminate(@PathVariable String id, @RequestParam(required = false) String user) {
        Target t = target(user);
        String tenant = CurrentUser.requireTenant();
        boolean theirs = sessions.sessionsOfUser(tenant, t.userId()).stream().anyMatch(s -> s.id().equals(id));
        if (!theirs) throw ApiException.notFound("session " + id);
        sessions.terminate(tenant, id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("user", t.username());
        detail.put("own", t.own());
        detail.put("current", t.own() && id.equals(currentSessionId()));
        audit.record(CurrentUser.username(), "SESSION_TERMINATED", "SESSION", id, detail);
    }

    /** The {@code sid} claim: the identity provider's id of the session this token belongs to. */
    private static String currentSessionId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString("sid") : null;
    }
}
