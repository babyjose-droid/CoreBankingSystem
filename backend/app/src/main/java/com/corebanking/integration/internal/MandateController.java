package com.corebanking.integration.internal;

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

/** e-Mandates and NACH files (US-070 – US-072). */
@RestController
@RequestMapping("/api/v1")
class MandateController {

    private final MandateService mandates;
    private final NachService nach;
    private final ProviderConfigService providers;

    MandateController(MandateService mandates, NachService nach, ProviderConfigService providers) {
        this.mandates = mandates;
        this.nach = nach;
        this.providers = providers;
    }

    @PostMapping("/loans/{id}/mandates")
    @PreAuthorize("hasAuthority('mandate:register')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> register(@PathVariable UUID id, @RequestBody MandateService.Input in) {
        return mandates.register(id, in);
    }

    @GetMapping("/loans/{id}/mandates")
    @PreAuthorize("hasAuthority('mandate:view')")
    List<Map<String, Object>> ofLoan(@PathVariable UUID id) {
        return mandates.ofLoan(id);
    }

    @GetMapping("/loans/{id}/nach-presentations")
    @PreAuthorize("hasAuthority('mandate:view')")
    List<Map<String, Object>> presentations(@PathVariable UUID id) {
        return nach.presentations(id);
    }

    @GetMapping("/mandates")
    @PreAuthorize("hasAuthority('mandate:view')")
    List<Map<String, Object>> search(@RequestParam(required = false) String status) {
        return mandates.search(status);
    }

    @GetMapping("/mandates/{id}")
    @PreAuthorize("hasAuthority('mandate:view')")
    Map<String, Object> get(@PathVariable UUID id) {
        return mandates.one(id);
    }

    @PostMapping("/mandates/{id}/status")
    @PreAuthorize("hasAuthority('mandate:admin')")
    Map<String, Object> status(@PathVariable UUID id, @RequestBody MandateService.StatusUpdate u) {
        return mandates.update(id, u, "OPERATOR");
    }

    @PostMapping(value = "/mandates/status-upload", consumes = "text/csv")
    @PreAuthorize("hasAuthority('mandate:admin')")
    List<Map<String, Object>> statusUpload(@RequestBody String csv) {
        return mandates.upload(csv);
    }

    @GetMapping("/nach/return-reasons")
    @PreAuthorize("hasAuthority('mandate:view')")
    List<Map<String, Object>> returnReasons() {
        return nach.returnReasons();
    }

    @GetMapping("/nach/files")
    @PreAuthorize("hasAuthority('nach:admin')")
    List<Map<String, Object>> files(@RequestParam(required = false) String direction) {
        return nach.files(direction);
    }

    @GetMapping(value = "/nach/files/{id}/content", produces = "text/plain")
    @PreAuthorize("hasAuthority('nach:file')")
    String content(@PathVariable UUID id) {
        return nach.content(id);
    }

    @PostMapping("/nach/presentations/generate")
    @PreAuthorize("hasAuthority('nach:admin')")
    List<Map<String, Object>> generate() {
        return nach.generateNow();
    }

    @GetMapping("/nach/presentations/pending")
    @PreAuthorize("hasAuthority('nach:admin')")
    List<Map<String, Object>> pending() {
        return nach.presentations(null);
    }

    @PostMapping(value = "/nach/responses", consumes = {"text/plain", "text/csv"})
    @PreAuthorize("hasAuthority('nach:admin')")
    Map<String, Object> response(@RequestBody String text) {
        return nach.receive(text);
    }

    @PostMapping("/nach/files/{id}/simulate-response")
    @PreAuthorize("hasAuthority('integration:simulate')")
    Map<String, Object> simulate(@PathVariable UUID id) {
        if (!providers.deploymentAllows("SIMULATOR")) {
            throw com.corebanking.platform.ApiException.conflict("the simulator is not enabled in this deployment");
        }
        return nach.simulateResponse(id);
    }
}
