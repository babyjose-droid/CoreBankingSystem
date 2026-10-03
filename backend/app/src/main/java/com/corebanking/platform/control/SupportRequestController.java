package com.corebanking.platform.control;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Control-plane side of support access (US-007): a platform engineer asks for time-boxed access to a tenant and
 * follows the state of the request. The decision is the tenant admin's, in the tenant API.
 */
@RestController
@RequestMapping("/platform/v1/support-access")
@PreAuthorize("hasAuthority('platform:support') or hasAuthority('platform:operator')")
class SupportRequestController {

    private final SupportAccessService support;

    SupportRequestController(SupportAccessService support) {
        this.support = support;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> request(@RequestBody SupportAccessService.Request request) {
        return support.request(request);
    }

    /** The caller's own requests; an operator sees every engineer's. */
    @GetMapping
    List<Map<String, Object>> requests(@RequestParam(required = false) String tenant) {
        return support.requests(tenant);
    }
}
