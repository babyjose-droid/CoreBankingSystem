package com.corebanking.integration.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Gateway collections (US-073): payment orders, the unmatched receipts queue, settlements and reconciliation. */
@RestController
@RequestMapping("/api/v1")
class CollectionController {

    private final CollectionService collections;

    CollectionController(CollectionService collections) {
        this.collections = collections;
    }

    @PostMapping("/loans/{id}/collection-orders")
    @PreAuthorize("hasAuthority('collection:create')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@PathVariable UUID id, @RequestBody CollectionService.OrderInput in,
                               @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return collections.createOrder(id, in, key);
    }

    @GetMapping("/loans/{id}/collection-orders")
    @PreAuthorize("hasAuthority('collection:view')")
    List<Map<String, Object>> orders(@PathVariable UUID id) {
        return collections.orders(id);
    }

    @PostMapping("/casa/accounts/{id}/collection-orders")
    @PreAuthorize("hasAuthority('casa:transact')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createForAccount(@PathVariable UUID id, @RequestBody CollectionService.DepositOrderInput in,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return collections.createDepositOrder("CASA", id, in, key);
    }

    @GetMapping("/casa/accounts/{id}/collection-orders")
    @PreAuthorize("hasAuthority('casa:view')")
    List<Map<String, Object>> accountOrders(@PathVariable UUID id) {
        return collections.depositOrders("CASA", id);
    }

    @PostMapping("/deposits/accounts/{id}/collection-orders")
    @PreAuthorize("hasAuthority('deposit:fund')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createForDeposit(@PathVariable UUID id, @RequestBody CollectionService.DepositOrderInput in,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return collections.createDepositOrder("TERM_DEPOSIT", id, in, key);
    }

    @GetMapping("/deposits/accounts/{id}/collection-orders")
    @PreAuthorize("hasAuthority('deposit:view')")
    List<Map<String, Object>> depositOrders(@PathVariable UUID id) {
        return collections.depositOrders("TERM_DEPOSIT", id);
    }

    @GetMapping("/collection-orders/{id}")
    @PreAuthorize("hasAuthority('collection:view')")
    Map<String, Object> order(@PathVariable UUID id) {
        return collections.order(id);
    }

    @GetMapping("/gateway-payments/reconciliation")
    @PreAuthorize("hasAuthority('collection:view')")
    List<Map<String, Object>> reconciliation(@RequestParam(required = false) String category, @RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to) {
        return collections.reconciliation(category, from, to);
    }

    @PostMapping("/gateway-payments/{id}/resolve")
    @PreAuthorize("hasAuthority('collection:admin')")
    Map<String, Object> resolve(@PathVariable UUID id, @RequestBody CollectionService.Resolution r) {
        return collections.resolve(id, r);
    }

    @PostMapping(value = "/gateway-settlements/upload", consumes = "text/csv")
    @PreAuthorize("hasAuthority('collection:admin')")
    Map<String, Object> settlements(@RequestParam String fileRef, @RequestBody String csv) {
        return collections.importSettlements(fileRef, csv);
    }
}
