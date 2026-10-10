package com.corebanking.lending.web;

import com.corebanking.lending.internal.InterestTableService;
import com.corebanking.platform.ApprovalView;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Interest-rate slab tables (amount and tenor bands). Module: LENDING. Changes go through maker-checker; permissions are the product's. */
@RestController
@RequestMapping("/api/v1/interest-tables")
class InterestTableController {

    private final InterestTableService tables;

    InterestTableController(InterestTableService tables) {
        this.tables = tables;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('product:view')")
    List<Map<String, Object>> list() {
        return tables.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> propose(@RequestBody InterestTableService.TableInput in) {
        return ApprovalView.of(tables.propose(in));
    }
}
