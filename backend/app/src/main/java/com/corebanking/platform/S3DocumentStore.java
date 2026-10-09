package com.corebanking.platform;

import com.corebanking.kernel.DocumentKey;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * {@link DocumentStore} on Amazon S3 ({@code corebanking.documents.store=s3}), with AWS SDK for Java v2. All AWS code
 * of the document store is in this class.
 * <ul>
 *   <li>One bucket per environment (infra/terraform/modules/s3-documents: private, versioned, TLS only); keys are the
 *       store's keys ({@code tenants/<code>/…}) under an optional {@code key-prefix}, so each tenant has its own prefix.</li>
 *   <li>Every object is written with SSE-KMS under the tenant's own key ({@code control.tenant.kms_key_arn}, which the
 *       tenant's IAM policy requires for writes to its prefix, infra/terraform/modules/tenant), else under
 *       {@code kms-key-id}, else the bucket's default key; {@code sse=AES256} or {@code none} only for a local MinIO
 *       without a KMS.</li>
 *   <li>The content type is set on the object and S3 checks a SHA-256 checksum of the upload; callers keep their own
 *       SHA-256 and content type in their records, as with the directory store.</li>
 *   <li>Credentials come from the default provider chain (IRSA role in EKS; access keys in the local stack).
 *       {@code endpoint} and {@code path-style} point the client at MinIO locally.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "corebanking.documents.store", havingValue = "s3")
public class S3DocumentStore implements DocumentStore {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final String sse;
    private final String kmsKeyId;
    private final JdbcTemplate control;
    private final Map<String, Optional<String>> tenantKeys = new ConcurrentHashMap<>();

    public S3DocumentStore(@Value("${corebanking.documents.s3.bucket}") String bucket,
                           @Value("${corebanking.documents.s3.key-prefix:}") String keyPrefix,
                           @Value("${corebanking.documents.s3.region:ap-south-1}") String region,
                           @Value("${corebanking.documents.s3.endpoint:}") String endpoint,
                           @Value("${corebanking.documents.s3.path-style:false}") boolean pathStyle,
                           @Value("${corebanking.documents.s3.sse:aws:kms}") String sse,
                           @Value("${corebanking.documents.s3.kms-key-id:}") String kmsKeyId,
                           @Qualifier("controlJdbc") JdbcTemplate control) {
        this.control = control;
        if (bucket == null || bucket.isBlank()) throw new IllegalStateException("corebanking.documents.s3.bucket is required");
        this.bucket = bucket;
        this.prefix = keyPrefix == null || keyPrefix.isBlank() ? "" : DocumentKey.validate(keyPrefix.replaceAll("/+$", "")) + "/";
        this.sse = sse == null ? "aws:kms" : sse.trim().toLowerCase(Locale.ROOT);
        if (!java.util.List.of("aws:kms", "aes256", "none").contains(this.sse)) {
            throw new IllegalStateException("corebanking.documents.s3.sse must be aws:kms, AES256 or none");
        }
        this.kmsKeyId = kmsKeyId == null || kmsKeyId.isBlank() ? null : kmsKeyId.trim();
        var builder = S3Client.builder().region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        this.s3 = builder.build();
    }

    private String object(String key) {
        return prefix + DocumentKey.validate(key);
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(object(key))
                .contentType(contentType == null ? "application/octet-stream" : contentType)
                .checksumAlgorithm(ChecksumAlgorithm.SHA256);
        if (sse.equals("aws:kms")) {
            req.serverSideEncryption(ServerSideEncryption.AWS_KMS);
            String keyId = keyFor(key);
            if (keyId != null) req.ssekmsKeyId(keyId);
        } else if (sse.equals("aes256")) {
            req.serverSideEncryption(ServerSideEncryption.AES256);
        }
        s3.putObject(req.build(), RequestBody.fromBytes(content));
    }

    /** The KMS key of the tenant whose prefix the key is under; the configured default otherwise. */
    private String keyFor(String key) {
        if (!key.startsWith("tenants/")) return kmsKeyId;
        int end = key.indexOf('/', "tenants/".length());
        if (end < 0) return kmsKeyId;
        String tenant = key.substring("tenants/".length(), end);
        Optional<String> own = tenantKeys.computeIfAbsent(tenant, t -> Optional.ofNullable(control.query(
                "SELECT kms_key_arn FROM control.tenant WHERE code = ?", (rs, i) -> rs.getString(1), t).stream()
                .filter(v -> v != null && !v.isBlank()).findFirst().orElse(null)));
        return own.orElse(kmsKeyId);
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(object(key)).build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw ApiException.notFound("document");
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object(key)).build());
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(object(key)).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;       // HEAD carries no error code, only the status
            throw e;
        }
    }

    @Override
    public long bytesUnder(String keyPrefix) {
        String p = prefix + DocumentKey.validate(keyPrefix) + "/";
        return s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(p).build())
                .contents().stream().mapToLong(S3Object::size).sum();
    }
}
