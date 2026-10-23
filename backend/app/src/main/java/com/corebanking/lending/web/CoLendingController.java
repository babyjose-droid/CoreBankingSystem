package com.corebanking.lending.web;

import com.corebanking.lending.internal.CoLendingService;
import com.corebanking.platform.ApprovalView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Co-lending as the originating lender: arrangements, the loans offered to the partner and its decisions (P5-1); what is
 * due to the partner from the daily split and the settlements that pay it (P5-2).
 */
@RestController
@RequestMapping("/api/v1/co-lending")
class CoLendingController {

    private static final String KEY = "Idempotency-Key";

    /** openapi.yaml#/components/schemas/CoLendingDecisionsInput. */
    record DecisionsInput(List<CoLendingService.DecisionInput> decisions) {}

    private final CoLendingService colending;

    CoLendingController(CoLendingService colending) {
        this.colending = colending;
    }

    @GetMapping("/arrangements")
    @PreAuthorize("hasAuthority('loan:view')")
    List<Map<String, Object>> arrangements() {
        return colending.arrangements();
    }

    @GetMapping("/arrangements/{code}")
    @PreAuthorize("hasAuthority('loan:view')")
    Map<String, Object> arrangement(@PathVariable String code) {
        return colending.arrangement(code);
    }

    @PostMapping("/arrangements")
    @PreAuthorize("hasAuthority('product:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> propose(@RequestBody CoLendingService.ArrangementInput in, @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(colending.proposeArrangement(in, key));
    }

    @PostMapping("/arrangements/{code}/status")
    @PreAuthorize("hasAuthority('product:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> status(@PathVariable String code, @RequestBody CoLendingService.StatusInput in,
                               @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(colending.proposeStatus(code, in, key));
    }

    @GetMapping("/arrangements/{code}/offers")
    @PreAuthorize("hasAuthority('loan:view')")
    List<Map<String, Object>> offers(@PathVariable String code) {
        return colending.offers(code);
    }

    @GetMapping("/arrangements/{code}/offers.csv")
    @PreAuthorize("hasAuthority('loan:view')")
    ResponseEntity<byte[]> offersFile(@PathVariable String code) {
        byte[] body = colending.offersFile(code);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("co-lending-offers-" + code + ".csv").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(body.length)
                .body(body);
    }

    @PostMapping("/arrangements/{code}/decisions")
    @PreAuthorize("hasAuthority('loan:disburse')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> decisions(@PathVariable String code, @RequestBody DecisionsInput in, @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(colending.proposeDecisions(code, in == null ? null : in.decisions(), key));
    }

    @PostMapping("/arrangements/{code}/simulate-partner")
    @PreAuthorize("hasAuthority('loan:disburse')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> simulate(@PathVariable String code, @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(colending.simulatePartner(code, key));
    }
    @GetMapping("/arrangements/{code}/unsettled")
    @PreAuthorize("hasAuthority('loan:view')")
    Map<String, Object> unsettled(@PathVariable String code) {
        return colending.unsettled(code);
    }

    @GetMapping("/arrangements/{code}/settlements")
    @PreAuthorize("hasAuthority('loan:view')")
    List<Map<String, Object>> settlements(@PathVariable String code) {
        return colending.settlements(code);
    }

    @PostMapping("/arrangements/{code}/settlements")
    @PreAuthorize("hasAuthority('loan:disburse')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> settle(@PathVariable String code, @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(colending.proposeSettlement(code, key));
    }

    @GetMapping("/settlements/{id}")
    @PreAuthorize("hasAuthority('loan:view')")
    Map<String, Object> settlement(@PathVariable UUID id) {
        return colending.settlement(id);
    }

    @GetMapping("/settlements/{id}/statement.csv")
    @PreAuthorize("hasAuthority('loan:view')")
    ResponseEntity<byte[]> statement(@PathVariable UUID id) {
        byte[] body = colending.statementFile(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("co-lending-settlement-" + id + ".csv").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(body.length)
                .body(body);
    }
}
