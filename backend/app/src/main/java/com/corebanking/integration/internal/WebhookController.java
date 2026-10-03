package com.corebanking.integration.internal;

import com.corebanking.integration.core.RetrySchedule;
import com.corebanking.integration.core.WebhookEvents;
import com.corebanking.integration.core.WebhookSignature;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Webhook endpoints, the delivery log and the replay console (US-121). */
@RestController
@RequestMapping("/api/v1/webhooks")
class WebhookController {

    record Action(String action) {}

    private final WebhookService webhooks;

    WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    /** What a subscriber needs to know: event types and their fields, the signature header, the retry schedule. */
    @GetMapping("/event-types")
    @PreAuthorize("hasAuthority('webhook:view')")
    Map<String, Object> eventTypes() {
        Map<String, Object> types = new LinkedHashMap<>();
        for (String t : WebhookEvents.TYPES) types.put(t, WebhookEvents.fields(t).stream().sorted().toList());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventTypes", types);
        m.put("signatureHeader", WebhookSignature.HEADER);
        m.put("eventIdHeader", WebhookSignature.EVENT_ID_HEADER);
        m.put("recommendedToleranceSeconds", WebhookSignature.DEFAULT_TOLERANCE_SECONDS);
        m.put("retryWaitsSeconds", RetrySchedule.WEBHOOK.nominalWaits());
        return m;
    }

    @GetMapping("/endpoints")
    @PreAuthorize("hasAuthority('webhook:view')")
    List<Map<String, Object>> endpoints() {
        return webhooks.endpoints();
    }

    @PostMapping("/endpoints")
    @PreAuthorize("hasAuthority('webhook:admin')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> create(@RequestBody WebhookService.Input in) {
        return ApprovalView.of(webhooks.proposeCreate(in));
    }

    @PutMapping("/endpoints/{id}")
    @PreAuthorize("hasAuthority('webhook:admin')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> update(@PathVariable UUID id, @RequestBody WebhookService.Input in) {
        return ApprovalView.of(webhooks.proposeUpdate(id, in));
    }

    /** DISABLE, ENABLE or ROTATE_SECRET, through maker-checker. */
    @PostMapping("/endpoints/{id}/actions")
    @PreAuthorize("hasAuthority('webhook:admin')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> action(@PathVariable UUID id, @RequestBody Action a) {
        if (a == null || a.action() == null || !List.of("DISABLE", "ENABLE", "ROTATE_SECRET").contains(a.action())) {
            throw ApiException.invalid("action must be DISABLE, ENABLE or ROTATE_SECRET");
        }
        return ApprovalView.of(webhooks.proposeAction(id, a.action()));
    }

    /** The signing secret, once, for the user who proposed the endpoint or the rotation. */
    @PostMapping("/endpoints/{id}/secret")
    @PreAuthorize("hasAuthority('webhook:admin')")
    Map<String, Object> secret(@PathVariable UUID id) {
        return webhooks.claimSecret(id);
    }

    @GetMapping("/deliveries")
    @PreAuthorize("hasAuthority('webhook:view')")
    List<Map<String, Object>> deliveries(@RequestParam(required = false) UUID endpointId, @RequestParam(required = false) String status) {
        return webhooks.deliveries(endpointId, status);
    }

    @GetMapping("/deliveries/{id}")
    @PreAuthorize("hasAuthority('webhook:view')")
    Map<String, Object> delivery(@PathVariable UUID id) {
        return webhooks.delivery(id);
    }

    @PostMapping("/deliveries/{id}/replay")
    @PreAuthorize("hasAuthority('webhook:admin')")
    Map<String, Object> replay(@PathVariable UUID id) {
        return webhooks.replay(id);
    }

    @PostMapping("/endpoints/{id}/replay-dead")
    @PreAuthorize("hasAuthority('webhook:admin')")
    Map<String, Object> replayDead(@PathVariable UUID id) {
        return webhooks.replayDead(id);
    }
}
