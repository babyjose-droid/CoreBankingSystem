package com.corebanking.platform.web;

import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.ApprovalView;
import com.corebanking.platform.BranchScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approvals")
class ApprovalController {

    record Decision(String note) {}
    record Bulk(List<UUID> ids, String note) {}

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalService approvals;
    private final BranchScope scope;

    ApprovalController(ApprovalService approvals, BranchScope scope) {
        this.approvals = approvals;
        this.scope = scope;
    }

    /** Requests tied to a branch are visible only inside the checker's branch scope (US-020). */
    private UUID visible(UUID id) {
        String branch = approvals.get(id).branchCode();
        if (branch != null) scope.requireRecord(branch, "approval " + id);
        return id;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('approval:view')")
    List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) String entityType) {
        Set<String> branches = scope.branches();
        return approvals.list(status, entityType).stream()
                .filter(r -> r.branchCode() == null || branches.contains(r.branchCode()))
                .map(ApprovalController::view).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('approval:view')")
    Map<String, Object> get(@PathVariable UUID id) {
        return view(approvals.get(visible(id)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('approval:approve')")
    Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) Decision d) {
        return view(approvals.approve(visible(id), d == null ? null : d.note()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('approval:approve')")
    Map<String, Object> reject(@PathVariable UUID id, @RequestBody Decision d) {
        return view(approvals.reject(visible(id), d.note()));
    }

    @PostMapping("/bulk-approve")
    @PreAuthorize("hasAuthority('approval:approve')")
    List<Map<String, Object>> bulk(@RequestBody Bulk b) {
        if (b.ids() == null || b.ids().size() > 100) throw new IllegalArgumentException("1 to 100 ids");
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID id : b.ids()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", id);
            try {
                approvals.approve(visible(id), b.note());      // each in its own transaction: one failure does not undo others
                r.put("ok", true);
            } catch (ApiException e) {
                r.put("ok", false);
                r.put("error", e.getMessage());
            } catch (RuntimeException e) {
                // Never echo raw database or framework messages (SQL text, key values); details go to the log.
                log.warn("bulk approve of {} failed", id, e);
                r.put("ok", false);
                r.put("error", e instanceof org.springframework.dao.DataAccessException
                        ? "a data rule rejected this change; open the request for details" : "could not be approved");
            }
            out.add(r);
        }
        return out;
    }

    static Map<String, Object> view(ApprovalRequest r) {
        return ApprovalView.of(r);
    }
}
