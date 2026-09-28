package com.corebanking.customer.web;

import com.corebanking.platform.ApprovalView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
class CustomerController {

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer:view')")
    List<Map<String, Object>> search(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return customers.search(q, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:view')")
    Map<String, Object> get(@PathVariable UUID id) {
        return customers.summary(id);
    }

    @PostMapping("/dedupe-check")
    @PreAuthorize("hasAuthority('customer:create')")
    List<CustomerService.Match> dedupe(@RequestBody CustomerService.Input in) {
        return customers.duplicates(in);
    }

    /** 200 with the existing customer when the PAN is already on file (idempotent create), else 202 approval. */
    @PostMapping
    @PreAuthorize("hasAuthority('customer:create')")
    ResponseEntity<Map<String, Object>> create(@RequestBody CustomerService.Input in,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        CustomerService.Created c = customers.create(in, key);
        if (c.existingCustomer() != null) return ResponseEntity.ok(c.existingCustomer());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApprovalView.of(c.approval()));
    }
}
