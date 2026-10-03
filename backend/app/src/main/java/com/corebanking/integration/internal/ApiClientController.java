package com.corebanking.integration.internal;

import com.corebanking.integration.core.keycloak.ApiClientScopes;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalView;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** OAuth2 API clients of the tenant (US-120). */
@RestController
@RequestMapping("/api/v1/api-clients")
class ApiClientController {

    record Scopes(List<String> scopes) {}
    record Action(String action) {}

    private final ApiClientService clients;

    ApiClientController(ApiClientService clients) {
        this.clients = clients;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('apiclient:view')")
    List<Map<String, Object>> list() {
        return clients.list();
    }

    /** The scopes an API client may hold, and which of them need two checkers. */
    @GetMapping("/scopes")
    @PreAuthorize("hasAuthority('apiclient:view')")
    Map<String, Object> scopes() {
        return Map.of("grantable", ApiClientScopes.GRANTABLE, "sensitive", ApiClientScopes.SENSITIVE.stream().sorted().toList());
    }

    @GetMapping("/{clientId}")
    @PreAuthorize("hasAuthority('apiclient:view')")
    Map<String, Object> get(@PathVariable String clientId) {
        return clients.one(clientId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('apiclient:admin')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> create(@RequestBody ApiClientService.Input in) {
        return ApprovalView.of(clients.proposeCreate(in));
    }

    @PutMapping("/{clientId}/scopes")
    @PreAuthorize("hasAuthority('apiclient:admin')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> scopes(@PathVariable String clientId, @RequestBody Scopes s) {
        return ApprovalView.of(clients.proposeScopes(clientId, s == null ? null : s.scopes()));
    }

    /** ROTATE_SECRET, DISABLE or ENABLE, through maker-checker. */
    @PostMapping("/{clientId}/actions")
    @PreAuthorize("hasAuthority('apiclient:admin')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> action(@PathVariable String clientId, @RequestBody Action a) {
        if (a == null || a.action() == null || !List.of("ROTATE_SECRET", "DISABLE", "ENABLE").contains(a.action())) {
            throw ApiException.invalid("action must be ROTATE_SECRET, DISABLE or ENABLE");
        }
        return ApprovalView.of(clients.proposeAction(clientId, a.action()));
    }

    /** The client secret, once, for the user who proposed the client or the rotation. */
    @PostMapping("/{clientId}/secret")
    @PreAuthorize("hasAuthority('apiclient:admin')")
    Map<String, Object> secret(@PathVariable String clientId) {
        return clients.claimSecret(clientId);
    }
}
