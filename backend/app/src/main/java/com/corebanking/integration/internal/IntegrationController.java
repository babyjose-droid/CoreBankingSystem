package com.corebanking.integration.internal;

import com.corebanking.platform.ApprovalView;
import java.util.List;
import java.util.Map;
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

/** Provider configuration (P2-2). Secrets go in and never come out. */
@RestController
@RequestMapping("/api/v1/integrations")
class IntegrationController {

    private final ProviderConfigService providers;

    IntegrationController(ProviderConfigService providers) {
        this.providers = providers;
    }

    @GetMapping("/providers/catalogue")
    @PreAuthorize("hasAuthority('integration:view')")
    List<Map<String, Object>> catalogue() {
        return providers.catalogue();
    }

    @GetMapping("/providers")
    @PreAuthorize("hasAuthority('integration:view')")
    List<Map<String, Object>> providers(@RequestParam(defaultValue = "false") boolean history) {
        return providers.list(history);
    }

    @PostMapping("/providers")
    @PreAuthorize("hasAuthority('integration:admin')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> propose(@RequestBody ProviderConfigService.Input in) {
        return ApprovalView.of(providers.propose(in));
    }

    @PostMapping("/providers/{kind}/deactivate")
    @PreAuthorize("hasAuthority('integration:admin')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> deactivate(@PathVariable String kind) {
        return ApprovalView.of(providers.proposeDeactivation(kind));
    }
}
