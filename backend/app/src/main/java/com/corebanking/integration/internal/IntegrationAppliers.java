package com.corebanking.integration.internal;

import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Maker-checker appliers of the integration module. Each takes its service lazily: the services need
 * ApprovalService, which needs every applier.
 */
final class IntegrationAppliers {

    private IntegrationAppliers() {}

    @Component
    static class ProviderConfig implements ApprovalApplier {
        private final ProviderConfigService service;

        ProviderConfig(@Lazy ProviderConfigService service) {
            this.service = service;
        }

        @Override public String entityType() { return ProviderConfigService.ENTITY; }

        @Override public String apply(ApprovalRequest r) { return service.apply(r); }
    }
}
