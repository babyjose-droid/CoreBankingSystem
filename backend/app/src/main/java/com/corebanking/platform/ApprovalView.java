package com.corebanking.platform;

import java.util.LinkedHashMap;
import java.util.Map;

/** API representation of an approval (openapi.yaml#/components/schemas/Approval). */
public final class ApprovalView {

    private ApprovalView() {}

    public static Map<String, Object> of(ApprovalRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("entityType", r.entityType());
        m.put("entityId", r.entityId());
        m.put("action", r.action());
        m.put("status", r.status());
        m.put("maker", r.maker());
        m.put("madeAt", r.madeAt());
        m.put("checker", r.checker());
        m.put("checkedAt", r.checkedAt());
        m.put("note", r.note());
        m.put("amount", r.amount() == null ? null : r.amount().toPlainString());
        m.put("ageHours", Math.round(r.ageHours() * 10) / 10.0);
        m.put("checkersRequired", r.checkersRequired());
        m.put("approvalsSoFar", r.approvalsSoFar());
        m.put("appliedRef", r.appliedRef());
        m.put("current", r.currentState());
        Map<String, Object> proposed = new LinkedHashMap<>(r.payload());
        proposed.remove("sealed");            // encrypted personal data never leaves the server
        m.put("proposed", proposed);
        return m;
    }
}
