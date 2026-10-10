package com.corebanking.integration.internal;

import com.corebanking.platform.TenantDataSources;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where providers send their callbacks: {@code POST /hooks/v1/{tenant}/{kind}/{provider}}. No access token: the
 * request is authenticated by the provider's signature, verified with that tenant's secret. The tenant in the
 * path only selects whose secret is used — a callback signed for tenant A fails verification under tenant B.
 * The body is taken as raw bytes, so the signature is checked over exactly what was sent.
 */
@RestController
class InboundWebhookController {

    private final InboundWebhookService inbound;
    private final TenantDataSources dataSources;

    InboundWebhookController(InboundWebhookService inbound, TenantDataSources dataSources) {
        this.inbound = inbound;
        this.dataSources = dataSources;
    }

    @PostMapping("/hooks/v1/{tenant}/{kind}/{provider}")
    ResponseEntity<Map<String, Object>> receive(@PathVariable String tenant, @PathVariable String kind, @PathVariable String provider,
                                                @RequestHeader Map<String, String> headers, @RequestBody(required = false) byte[] body) {
        Map<String, Object> answer = new LinkedHashMap<>();
        if (!tenant.matches("[a-z][a-z0-9-]{2,30}") || !dataSources.tenants().contains(tenant)) {
            answer.put("detail", "not found");
            return ResponseEntity.status(404).body(answer);
        }
        int[] status = new int[] {200};
        TenantDataSources.runAs(tenant, () -> {
            try {
                answer.put("received", true);
                answer.put("duplicate", inbound.receive(kind, provider, headers, body) == InboundWebhookService.Receipt.DUPLICATE);
            } catch (InboundWebhookService.Refused e) {
                answer.clear();
                answer.put("detail", e.getMessage());
                status[0] = e.status();
            }
        });
        return ResponseEntity.status(status[0]).body(answer);
    }

    /** Test and demo only: a callback of the SIMULATOR provider, through the real verification path. */
    @PostMapping("/api/v1/integrations/simulator/callbacks")
    @PreAuthorize("hasAuthority('integration:simulate')")
    Map<String, Object> simulate(@RequestBody InboundWebhookService.Simulated in) {
        return inbound.simulate(in);
    }

    /** Test and demo only: make a payout fail or come back, to exercise the failure path (reversal proposed for a checker). */
    @PostMapping("/api/v1/integrations/simulator/payouts/{id}/outcome")
    @PreAuthorize("hasAuthority('integration:simulate')")
    Map<String, Object> simulatePayoutOutcome(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
                                              @RequestBody InboundWebhookService.PayoutOutcome in) {
        return inbound.simulatePayoutOutcome(id, in);
    }
}
