package com.corebanking.deposits.web;

import com.corebanking.deposits.internal.DepositDocumentService;
import com.corebanking.platform.ApiException;
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

/** Depositor documents as PDF (P4-7): statements, deposit receipts and the interest certificate. */
@RestController
@RequestMapping("/api/v1")
class DepositDocumentController {

    private final DepositDocumentService documents;

    DepositDocumentController(DepositDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/casa/accounts/{id}/statement.pdf")
    @PreAuthorize("hasAuthority('casa:view')")
    ResponseEntity<byte[]> casaStatement(@PathVariable UUID id, @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return pdf(documents.statement("CASA", id, date(from, "from"), date(to, "to")));
    }

    @GetMapping("/deposits/accounts/{id}/statement.pdf")
    @PreAuthorize("hasAuthority('deposit:view')")
    ResponseEntity<byte[]> depositStatement(@PathVariable UUID id, @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return pdf(documents.statement("TERM_DEPOSIT", id, date(from, "from"), date(to, "to")));
    }

    @GetMapping("/deposits/accounts/{id}/receipt.pdf")
    @PreAuthorize("hasAuthority('deposit:view')")
    ResponseEntity<byte[]> receipt(@PathVariable UUID id) {
        return pdf(documents.receipt(id));
    }

    @GetMapping("/deposits/tax/interest-certificate.pdf")
    @PreAuthorize("hasAuthority('deposit:view')")
    ResponseEntity<byte[]> interestCertificate(@RequestParam UUID customerId, @RequestParam(required = false) Integer taxYear) {
        return pdf(documents.interestCertificate(customerId, taxYear));
    }

    private static ResponseEntity<byte[]> pdf(DepositDocumentService.Document d) {
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
