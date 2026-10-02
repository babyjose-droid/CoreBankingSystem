package com.corebanking.lending.web;

import com.corebanking.lending.internal.LoanDocumentService;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.BranchScope;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loan documents as PDF (P2-4). Module: LENDING. Each response is a download ({@code Content-Disposition: attachment})
 * and is never cached ({@code Cache-Control: no-store}): it holds personal data. Generation is audited by the service.
 */
@RestController
@RequestMapping("/api/v1/loans/{id}")
class LoanDocumentController {

    private final LoanDocumentService documents;
    private final BranchScope scope;

    LoanDocumentController(LoanDocumentService documents, BranchScope scope) {
        this.documents = documents;
        this.scope = scope;
    }

    /** A loan outside the caller's branch scope is reported as not found (US-020). */
    private UUID visible(UUID id) {
        scope.requireRecord(documents.branchOf(id), "loan " + id);
        return id;
    }

    @GetMapping("/documents/kfs.pdf")
    @PreAuthorize("hasAuthority('loan:view')")
    ResponseEntity<byte[]> kfs(@PathVariable UUID id) {
        return pdf(documents.kfs(visible(id)));
    }

    @GetMapping("/documents/statement.pdf")
    @PreAuthorize("hasAuthority('loan:view')")
    ResponseEntity<byte[]> statement(@PathVariable UUID id, @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        return pdf(documents.statement(visible(id), date(from, "from"), date(to, "to")));
    }

    @GetMapping("/documents/schedule.pdf")
    @PreAuthorize("hasAuthority('loan:view')")
    ResponseEntity<byte[]> schedule(@PathVariable UUID id) {
        return pdf(documents.schedule(visible(id)));
    }

    /** 409 when the loan is not closed. */
    @GetMapping("/documents/noc.pdf")
    @PreAuthorize("hasAuthority('loan:view')")
    ResponseEntity<byte[]> noc(@PathVariable UUID id) {
        return pdf(documents.noc(visible(id)));
    }

    @GetMapping("/charges/{chargeId}/invoice.pdf")
    @PreAuthorize("hasAuthority('loan:view')")
    ResponseEntity<byte[]> invoice(@PathVariable UUID id, @PathVariable String chargeId) {
        return pdf(documents.invoice(visible(id), chargeId));
    }

    private static ResponseEntity<byte[]> pdf(LoanDocumentService.Document d) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(d.content().length)
                .body(d.content());
    }

    private static LocalDate date(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.invalid(name + " must be a date as YYYY-MM-DD");
        }
    }
}
