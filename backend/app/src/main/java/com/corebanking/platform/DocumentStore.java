package com.corebanking.platform;

/**
 * Binary document storage by key (KYC documents, generated reports). Keys are relative paths such as
 * {@code tenants/<code>/kyc/<customer>/<uuid>}; a key never contains {@code ..} and never starts with {@code /}.
 * <p>
 * Implementations: {@link FileDocumentStore} (a directory; dev, standalone installs and a mounted encrypted
 * volume). The S3 implementation (SSE-KMS, one prefix per tenant) is pending: it needs the AWS SDK dependency,
 * which is not on the classpath yet.
 */
public interface DocumentStore {

    /** Stores the content under the key, replacing any earlier content. */
    void put(String key, byte[] content, String contentType);

    /** @throws ApiException 404 when nothing is stored under the key */
    byte[] get(String key);

    /** Removes the content; does nothing when the key does not exist. */
    void delete(String key);

    boolean exists(String key);
}
