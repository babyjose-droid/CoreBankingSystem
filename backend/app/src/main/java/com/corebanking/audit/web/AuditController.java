package com.corebanking.audit.web;

import com.corebanking.audit.AuditLog;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/audit")
class AuditController {

    private final AuditLog audit;
    private final JsonMapper mapper;

    AuditController(AuditLog audit, JsonMapper mapper) {
        this.audit = audit;
        this.mapper = mapper;
    }

    @GetMapping("/events")
    @PreAuthorize("hasAuthority('audit:view')")
    List<Map<String, Object>> events(@RequestParam(required = false) String entityType,
                                     @RequestParam(required = false) String entityId,
                                     @RequestParam(defaultValue = "100") int limit) {
        return audit.recent(entityType, entityId, limit).stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.id());
            m.put("at", e.at());
            m.put("actor", e.actor());
            m.put("action", e.action());
            m.put("entityType", e.entityType());
            m.put("entityId", e.entityId());
            m.put("detail", e.detailJson() == null ? null : mapper.readValue(e.detailJson(), Map.class));
            return m;
        }).toList();
    }

    @GetMapping("/verify")
    @PreAuthorize("hasAuthority('audit:view')")
    Map<String, Object> verify() {
        Long broken = audit.verifyChain();
        Map<String, Object> m = new HashMap<>();
        m.put("intact", broken == null);
        m.put("firstBrokenId", broken);
        return m;
    }
}
