package com.corebanking.platform.control;

import com.corebanking.platform.CurrentUser;
import java.util.List;
import java.util.Map;
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

/** Control-plane API for the platform operator (tokens from the operator realm with platform:operator). */
@RestController
@RequestMapping("/platform/v1")
@PreAuthorize("hasAuthority('platform:operator')")
class TenantAdminController {

    private final TenantProvisioner provisioner;

    TenantAdminController(TenantProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @GetMapping("/tenants")
    List<Map<String, Object>> tenants() {
        return provisioner.tenants();
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> provision(@RequestBody TenantProvisioner.Request request) {
        return provisioner.provision(request, CurrentUser.username());
    }

    @PutMapping("/tenants/{code}/modules")
    Map<String, Object> modules(@PathVariable String code, @RequestBody List<String> modules) {
        return provisioner.setModules(code, modules, CurrentUser.username());
    }

    @PostMapping("/migrations")
    List<TenantProvisioner.MigrationResult> migrate(@RequestParam(defaultValue = "true") boolean dryRun) {
        return provisioner.migrateAll(dryRun, CurrentUser.username());
    }
}
