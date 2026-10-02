package com.corebanking.customer.web;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * What hangs off a customer (P2-5): relationships and exposure (US-034), consent records (US-036) and KYC
 * documents (US-032). Every endpoint is inside the caller's branch scope: a customer of another branch is 404.
 */
@RestController
@RequestMapping("/api/v1/customers/{id}")
class CustomerRecordsController {

    record Verification(Boolean maskingConfirmed, String note) {}
    record Rejection(String reason) {}

    private final AssociatesService associates;
    private final ConsentService consents;
    private final KycDocumentService documents;

    CustomerRecordsController(AssociatesService associates, ConsentService consents, KycDocumentService documents) {
        this.associates = associates;
        this.consents = consents;
        this.documents = documents;
    }

    // ---- relationships and exposure (US-034) ----------------------------------------------------
    @GetMapping("/relationships")
    @PreAuthorize("hasAuthority('customer:view')")
    List<Map<String, Object>> relationships(@PathVariable UUID id) {
        return associates.relationships(id);
    }

    @PostMapping("/relationships")
    @PreAuthorize("hasAuthority('customer:create')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeRelationships(@PathVariable UUID id, @RequestBody AssociatesService.RelationshipsRequest request,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApprovalView.of(associates.propose(id, request, key));
    }

    @GetMapping("/exposure")
    @PreAuthorize("hasAuthority('customer:view')")
    Map<String, Object> exposure(@PathVariable UUID id) {
        return associates.exposure(id);
    }

    @PostMapping("/exposure-limit")
    @PreAuthorize("hasAuthority('limit:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeExposureLimit(@PathVariable UUID id, @RequestBody AssociatesService.ExposureLimitInput in) {
        return ApprovalView.of(associates.proposeExposureLimit(id, in));
    }

    // ---- consent (US-036) -----------------------------------------------------------------------
    @GetMapping("/consents")
    @PreAuthorize("hasAuthority('consent:view')")
    List<Map<String, Object>> consents(@PathVariable UUID id) {
        return consents.list(id);
    }

    @PostMapping("/consents")
    @PreAuthorize("hasAuthority('consent:record')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> recordConsent(@PathVariable UUID id, @RequestBody ConsentService.Input in) {
        return consents.record(id, in);
    }

    @PostMapping("/consents/{consentId}/withdraw")
    @PreAuthorize("hasAuthority('consent:record')")
    Map<String, Object> withdrawConsent(@PathVariable UUID id, @PathVariable UUID consentId, @RequestBody ConsentService.Withdrawal w) {
        return consents.withdraw(id, consentId, w);
    }

    // ---- KYC documents (US-032) -----------------------------------------------------------------
    @GetMapping("/kyc-documents")
    @PreAuthorize("hasAuthority('customer:view')")
    List<Map<String, Object>> kycDocuments(@PathVariable UUID id) {
        return documents.list(id);
    }

    /**
     * The file is the request body (no multipart); metadata travels in the query string, and the document number
     * in a header so that it never lands in an access log.
     */
    @PostMapping(value = "/kyc-documents", consumes = {MediaType.APPLICATION_PDF_VALUE, MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    @PreAuthorize("hasAuthority('kyc:upload')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> uploadKycDocument(@PathVariable UUID id, @RequestParam String docType,
                                          @RequestParam(required = false) String issueDate,
                                          @RequestParam(required = false) String expiryDate,
                                          @RequestHeader(value = "X-Document-Number", required = false) String documentNumber,
                                          @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
                                          @RequestBody byte[] body) {
        return documents.upload(id, docType, documentNumber, issueDate, expiryDate, contentType, body);
    }

    @GetMapping("/kyc-documents/{docId}/content")
    @PreAuthorize("hasAuthority('kyc:view-document')")
    ResponseEntity<byte[]> kycDocumentContent(@PathVariable UUID id, @PathVariable UUID docId) {
        KycDocumentService.Content c = documents.content(id, docId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(c.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(c.bytes());
    }

    @PostMapping("/kyc-documents/{docId}/verify")
    @PreAuthorize("hasAuthority('kyc:verify')")
    Map<String, Object> verifyKycDocument(@PathVariable UUID id, @PathVariable UUID docId, @RequestBody(required = false) Verification v) {
        return documents.verify(id, docId, v != null && Boolean.TRUE.equals(v.maskingConfirmed()), v == null ? null : v.note());
    }

    @PostMapping("/kyc-documents/{docId}/reject")
    @PreAuthorize("hasAuthority('kyc:verify')")
    Map<String, Object> rejectKycDocument(@PathVariable UUID id, @PathVariable UUID docId, @RequestBody Rejection r) {
        return documents.reject(id, docId, r == null ? null : r.reason());
    }
}
