package com.corebanking.integration.internal;

import com.corebanking.platform.ApprovalView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Message templates, the delivery log and channel opt-outs (US-123). */
@RestController
@RequestMapping("/api/v1")
class MessagingController {

    record OptOut(String channel, Boolean optOut, String source) {}

    private final MessagingService messaging;

    MessagingController(MessagingService messaging) {
        this.messaging = messaging;
    }

    @GetMapping("/message-templates")
    @PreAuthorize("hasAuthority('message:view')")
    List<Map<String, Object>> templates() {
        return messaging.templates();
    }

    /** Template codes (events) and the placeholders each may use. */
    @GetMapping("/message-templates/variables")
    @PreAuthorize("hasAuthority('message:view')")
    Map<String, Object> variables() {
        return messaging.variables();
    }

    @PostMapping("/message-templates")
    @PreAuthorize("hasAuthority('message:admin')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> propose(@RequestBody MessagingService.Input in) {
        return ApprovalView.of(messaging.propose(in));
    }

    @GetMapping("/messages")
    @PreAuthorize("hasAuthority('message:view')")
    List<Map<String, Object>> log(@RequestParam(required = false) UUID customerId, @RequestParam(required = false) UUID loanId,
                                  @RequestParam(required = false) String status) {
        return messaging.log(customerId, loanId, status);
    }

    @PostMapping("/customers/{id}/message-opt-outs")
    @PreAuthorize("hasAuthority('message:admin')")
    Map<String, Object> optOut(@PathVariable UUID id, @RequestBody OptOut o) {
        return messaging.optOut(id, o.channel(), !Boolean.FALSE.equals(o.optOut()), o.source());
    }
}
