package com.corebanking.platform.web;

import com.corebanking.platform.CustomFieldService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Custom field definitions (US-014): list for forms and integrations, propose through maker-checker. */
@RestController
@RequestMapping("/api/v1/custom-fields")
class CustomFieldController {

    private final CustomFieldService fields;

    CustomFieldController(CustomFieldService fields) {
        this.fields = fields;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('custom-field:view')")
    List<Map<String, Object>> list(@RequestParam(required = false) String entity) {
        return fields.list(entity);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('custom-field:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> propose(@RequestBody CustomFieldService.Input input) {
        return ApprovalController.view(fields.propose(input));
    }
}
