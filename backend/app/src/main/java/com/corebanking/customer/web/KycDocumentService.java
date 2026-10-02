package com.corebanking.customer.web;

import com.corebanking.audit.AuditLog;
import com.corebanking.customer.PiiKeys;
import com.corebanking.kernel.FileSignatures;
import com.corebanking.kernel.KycDocuments;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.DocumentStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KYC documents (US-032): the file goes to the {@link DocumentStore}, the metadata to customer.kyc_document.
 * <ul>
 *   <li>Accepted: PDF, JPEG, PNG up to 5 MB; the leading bytes must match the declared type.</li>
 *   <li>The document number is never stored: only its last four characters and a keyed hash. For Aadhaar not
 *       even a hash: a full Aadhaar number is refused, only the last four digits are kept, and the verifier must
 *       confirm that the uploaded copy is the masked one (UIDAI masked Aadhaar; RBI KYC Master Direction).</li>
 *   <li>A document is verified or rejected by someone other than the uploader. The customer's KYC status follows
 *       from the documents (database function customer.kyc_complete and its triggers, V17).</li>
 *   <li>Every download is audited; the SHA-256 recorded at upload is checked before the file is served.</li>
 * </ul>
 * Every method takes the customer id and reports a customer outside the caller's branch scope as not found.
 */
@Service
class KycDocumentService {

    static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(KycDocumentService.class);

    /** A stored file with what the response needs. */
    record Content(byte[] bytes, String contentType, String fileName) {}

    private final JdbcTemplate jdbc;
    private final CustomerService customers;
    private final DocumentStore store;
    private final PiiKeys keys;
    private final AuditLog audit;

    KycDocumentService(JdbcTemplate jdbc, CustomerService customers, DocumentStore store, PiiKeys keys, AuditLog audit) {
        this.jdbc = jdbc;
        this.customers = customers;
        this.store = store;
        this.keys = keys;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT id, customer_id, doc_type, number_last4, issue_date, expiry_date, status, status_reason, verified_by, verified_at,
                   masking_confirmed, content_type, size_bytes, sha256, uploaded_by, uploaded_at,
                   (expiry_date IS NOT NULL AND expiry_date < current_date) AS expired
              FROM customer.kyc_document""";

    List<Map<String, Object>> list(UUID customerId) {
        customers.visibleBranch(customerId);
        return jdbc.query(SELECT + " WHERE customer_id = ? ORDER BY uploaded_at DESC, id", KycDocumentService::row, customerId);
    }

    @Transactional
    Map<String, Object> upload(UUID customerId, String docType, String documentNumber, String issueDate, String expiryDate,
                               String contentType, byte[] body) {
        customers.visibleBranch(customerId);
        String type = KycDocuments.normaliseType(docType);
        if (jdbc.queryForList("SELECT 1 FROM platform.enumeration WHERE enum_type = 'kyc-document-type' AND code = ? AND active", type).isEmpty()) {
            throw ApiException.invalid("docType " + docType + " is not an active KYC document type (enumeration kyc-document-type)");
        }
        String declared = FileSignatures.baseType(contentType);
        if (declared == null || !FileSignatures.KYC_TYPES.contains(declared)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "send the file as application/pdf, image/jpeg or image/png");
        }
        if (body == null || body.length == 0) throw ApiException.invalid("the file is empty");
        if (body.length > MAX_BYTES) throw new ApiException(HttpStatus.valueOf(413), "the file is larger than 5 MB");
        if (!FileSignatures.matches(declared, body)) {
            throw ApiException.invalid("the file content is not " + declared + "; send a real PDF, JPEG or PNG with the matching Content-Type");
        }
        LocalDate issued = date(issueDate, "issueDate");
        LocalDate expires = date(expiryDate, "expiryDate");
        if (issued != null && issued.isAfter(LocalDate.now())) throw ApiException.invalid("issueDate cannot be in the future");
        if (issued != null && expires != null && !expires.isAfter(issued)) throw ApiException.invalid("expiryDate must be after issueDate");
        KycDocuments.Reference ref = KycDocuments.reference(type, documentNumber);   // refuses a full Aadhaar number
        byte[] hash = ref.hashInput() == null ? null
                : keys.forTenant(CurrentUser.requireTenant()).blindIndex("KYCDOC:" + type, ref.hashInput());
        UUID id = UUID.randomUUID();
        String key = "tenants/" + CurrentUser.requireTenant() + "/kyc/" + customerId + "/" + id;
        String user = CurrentUser.username();
        store.put(key, body, declared);
        try {
            jdbc.update("""
                    INSERT INTO customer.kyc_document (id, customer_id, doc_type, number_last4, number_hash, issue_date, expiry_date,
                                                       store_key, content_type, size_bytes, sha256, uploaded_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, customerId, type, ref.last4(), hash, issued, expires, key, declared, body.length, sha256(body), user);
        } catch (RuntimeException e) {
            store.delete(key);                 // no metadata, so no orphan file
            throw e;
        }
        audit.record(user, "KYC_DOCUMENT_UPLOAD", "CUSTOMER", customerId.toString(),
                Map.of("documentId", id.toString(), "docType", type, "sizeBytes", body.length));
        return get(customerId, id);
    }

    /** The file itself. Audited: who looked at which document. */
    Content content(UUID customerId, UUID documentId) {
        customers.visibleBranch(customerId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT doc_type, store_key, content_type, sha256 FROM customer.kyc_document WHERE id = ? AND customer_id = ?",
                documentId, customerId);
        if (rows.isEmpty()) throw ApiException.notFound("document " + documentId);
        Map<String, Object> d = rows.get(0);
        byte[] bytes = store.get((String) d.get("store_key"));
        if (!sha256(bytes).equals(d.get("sha256"))) {
            log.error("KYC document {} failed its integrity check", documentId);
            throw ApiException.conflict("the stored file does not match its recorded checksum; it has been reported");
        }
        audit.record(CurrentUser.username(), "KYC_DOCUMENT_VIEW", "CUSTOMER", customerId.toString(),
                Map.of("documentId", documentId.toString(), "docType", String.valueOf(d.get("doc_type"))));
        String contentType = (String) d.get("content_type");
        String extension = switch (contentType) {
            case FileSignatures.PDF -> "pdf";
            case FileSignatures.PNG -> "png";
            default -> "jpg";
        };
        String name = "kyc-" + String.valueOf(d.get("doc_type")).toLowerCase(java.util.Locale.ROOT).replace('_', '-') + "-" + documentId + "." + extension;
        return new Content(bytes, contentType, name);
    }

    @Transactional
    Map<String, Object> verify(UUID customerId, UUID documentId, boolean maskingConfirmed, String note) {
        Map<String, Object> d = pending(customerId, documentId);
        String user = CurrentUser.username();
        if (KycDocuments.AADHAAR_MASKED.equals(d.get("doc_type")) && !maskingConfirmed) {
            throw ApiException.invalid("confirm that the copy shows only the last four Aadhaar digits (maskingConfirmed); if the full number"
                    + " is visible, reject the document and ask for a masked copy");
        }
        LocalDate expiry = d.get("expiry_date") == null ? null : ((java.sql.Date) d.get("expiry_date")).toLocalDate();
        if (expiry != null && expiry.isBefore(LocalDate.now())) throw ApiException.conflict("the document expired on " + expiry);
        jdbc.update("""
                UPDATE customer.kyc_document SET status = 'VERIFIED', status_reason = ?, verified_by = ?, verified_at = now(),
                       masking_confirmed = ? WHERE id = ?
                """, note == null || note.isBlank() ? null : note.trim(), user, maskingConfirmed, documentId);
        audit.record(user, "KYC_DOCUMENT_VERIFY", "CUSTOMER", customerId.toString(), Map.of("documentId", documentId.toString()));
        return get(customerId, documentId);
    }

    @Transactional
    Map<String, Object> reject(UUID customerId, UUID documentId, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.invalid("a reason is required to reject a document");
        pending(customerId, documentId);
        String user = CurrentUser.username();
        jdbc.update("""
                UPDATE customer.kyc_document SET status = 'REJECTED', status_reason = ?, verified_by = ?, verified_at = now() WHERE id = ?
                """, reason.trim(), user, documentId);
        audit.record(user, "KYC_DOCUMENT_REJECT", "CUSTOMER", customerId.toString(), Map.of("documentId", documentId.toString()));
        return get(customerId, documentId);
    }

    /** Locks a PENDING document that the caller did not upload. */
    private Map<String, Object> pending(UUID customerId, UUID documentId) {
        customers.visibleBranch(customerId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT doc_type, status, uploaded_by, expiry_date FROM customer.kyc_document WHERE id = ? AND customer_id = ? FOR UPDATE",
                documentId, customerId);
        if (rows.isEmpty()) throw ApiException.notFound("document " + documentId);
        Map<String, Object> d = rows.get(0);
        if (!"PENDING".equals(d.get("status"))) throw ApiException.conflict("the document is already " + d.get("status"));
        if (String.valueOf(d.get("uploaded_by")).equalsIgnoreCase(CurrentUser.username())) {
            throw ApiException.conflict("the person who uploaded a document cannot verify or reject it");
        }
        return d;
    }

    private Map<String, Object> get(UUID customerId, UUID documentId) {
        List<Map<String, Object>> l = jdbc.query(SELECT + " WHERE id = ? AND customer_id = ?", KycDocumentService::row, documentId, customerId);
        if (l.isEmpty()) throw ApiException.notFound("document " + documentId);
        Map<String, Object> m = l.get(0);
        m.put("customerKycStatus", jdbc.queryForObject("SELECT kyc_status FROM customer.customer WHERE id = ?", String.class, customerId));
        return m;
    }

    private static Map<String, Object> row(ResultSet rs, int i) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        String type = rs.getString("doc_type");
        m.put("id", rs.getObject("id", UUID.class));
        m.put("customerId", rs.getObject("customer_id", UUID.class));
        m.put("docType", type);
        m.put("numberMasked", KycDocuments.masked(type, rs.getString("number_last4")));
        m.put("issueDate", rs.getObject("issue_date", LocalDate.class));
        m.put("expiryDate", rs.getObject("expiry_date", LocalDate.class));
        m.put("expired", rs.getBoolean("expired"));
        m.put("status", rs.getString("status"));
        m.put("statusReason", rs.getString("status_reason"));
        m.put("verifiedBy", rs.getString("verified_by"));
        m.put("verifiedAt", rs.getObject("verified_at", OffsetDateTime.class));
        m.put("maskingConfirmed", rs.getBoolean("masking_confirmed"));
        m.put("contentType", rs.getString("content_type"));
        m.put("sizeBytes", rs.getLong("size_bytes"));
        m.put("sha256", rs.getString("sha256"));
        m.put("uploadedBy", rs.getString("uploaded_by"));
        m.put("uploadedAt", rs.getObject("uploaded_at", OffsetDateTime.class));
        return m;
    }

    private static LocalDate date(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.invalid(name + " must be YYYY-MM-DD");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
