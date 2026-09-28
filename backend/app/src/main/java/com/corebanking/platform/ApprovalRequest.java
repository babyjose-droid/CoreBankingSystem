package com.corebanking.platform;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** A maker-checker request as seen by the module that will apply it. */
public record ApprovalRequest(UUID id, String entityType, String entityId, String action, Map<String, Object> payload,
                              Map<String, Object> currentState, String maker, OffsetDateTime madeAt, String branchCode,
                              BigDecimal amount, int checkersRequired, String status, String checker,
                              OffsetDateTime checkedAt, String note, String appliedRef, double ageHours,
                              int approvalsSoFar) {}
