package com.corebanking.platform.web;

import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.ApprovalView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

@RestController
@RequestMapping("/api/v1/approvals")
class ApprovalController {

    record Decision(String note) {}
    record Bulk(List<UUID> ids, String note) {}

    private final ApprovalService approvals;

    ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('approval:view')")
    List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) String entityType) {
        return approvals.list(status, entityType).stream().map(ApprovalController::view).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('approval:view')")
    Map<String, Object> get(@PathVariable UUID id) {
        return view(approvals.get(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('approval:approve')")
    Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) Decision d) {
        return view(approvals.approve(id, d == null ? null : d.note()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('approval:approve')")
    Map<String, Object> reject(@PathVariable UUID id, @RequestBody Decision d) {
        return view(approvals.reject(id, d.note()));
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
                approvals.approve(id, b.note());      // each in its own transaction: one failure does not undo others
                r.put("ok", true);
            } catch (RuntimeException e) {
                r.put("ok", false);
                r.put("error", e.getMessage());
            }
            out.add(r);
        }
        return out;
    }

    static Map<String, Object> view(ApprovalRequest r) {
        return ApprovalView.of(r);
    }
}
