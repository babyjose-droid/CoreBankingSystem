package com.corebanking.platform;

/**
 * Binary document storage by key (KYC documents, generated reports). Keys are relative paths such as
 * {@code tenants/<code>/kyc/<customer>/<uuid>}; a key never contains {@code ..} and never starts with {@code /}.
 * <p>
 * Implementations, chosen by {@code corebanking.documents.store}: {@link FileDocumentStore} ({@code directory}, the
 * default: dev, standalone installs, a mounted encrypted volume) and {@link S3DocumentStore} ({@code s3}: SSE-KMS,
 * one prefix per tenant; MinIO in the local stack).
 */
public interface DocumentStore {

    /** Stores the content under the key, replacing any earlier content. */
    void put(String key, byte[] content, String contentType);

    /** @throws ApiException 404 when nothing is stored under the key */
    byte[] get(String key);

    /** Removes the content; does nothing when the key does not exist. */
    void delete(String key);

    boolean exists(String key);

    /**
     * Total size in bytes of everything stored under a prefix such as {@code tenants/<code>}; 0 when nothing is.
     * Used for usage metering (US-004), so it may be approximate while files are being written.
     */
    long bytesUnder(String prefix);
}
