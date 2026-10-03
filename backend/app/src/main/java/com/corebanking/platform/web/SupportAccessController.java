package com.corebanking.platform.web;

import com.corebanking.platform.control.SupportAccessService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant side of support access (US-007): the tenant's admin sees who asked for access and why, approves or
 * rejects, and can revoke a grant at any time. Not on the support read list, so an engineer can neither see nor
 * decide these.
 */
@RestController
@RequestMapping("/api/v1/support-access")
@PreAuthorize("hasAuthority('support-access:approve')")
class SupportAccessController {

    record Note(String note) {}

    private final SupportAccessService support;

    SupportAccessController(SupportAccessService support) {
        this.support = support;
    }

    @GetMapping
    List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return support.list(status);
    }

    @PostMapping("/{id}/approve")
    Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) Note body) {
        return support.approve(id, body == null ? null : body.note());
    }

    @PostMapping("/{id}/reject")
    Map<String, Object> reject(@PathVariable UUID id, @RequestBody(required = false) Note body) {
        return support.reject(id, body == null ? null : body.note());
    }

    @PostMapping("/{id}/revoke")
    Map<String, Object> revoke(@PathVariable UUID id, @RequestBody(required = false) Note body) {
        return support.revoke(id, body == null ? null : body.note());
    }
}
