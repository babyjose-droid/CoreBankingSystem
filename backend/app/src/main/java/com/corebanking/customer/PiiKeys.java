package com.corebanking.customer;

import com.corebanking.kernel.PiiCipher;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Per-tenant data keys for personal data (ADR-013). Dev and standalone installs read base64 keys from
 * COREBANKING_TENANT_&lt;CODE&gt;_PII_KEY (32 bytes) and _PII_INDEX_KEY (≥ 32 bytes); cloud tiers inject the
 * KMS-unwrapped data keys into the same variables at start-up (the plaintext key never touches disk).
 */
@Component
public class PiiKeys {

    private final Environment env;
    private final Map<String, PiiCipher> ciphers = new ConcurrentHashMap<>();

    public PiiKeys(Environment env) {
        this.env = env;
    }

    public PiiCipher forTenant(String tenant) {
        return ciphers.computeIfAbsent(tenant, t -> {
            String base = "COREBANKING_TENANT_" + t.toUpperCase().replace('-', '_');
            byte[] enc = decode(env.getProperty(base + "_PII_KEY"), base + "_PII_KEY");
            byte[] idx = decode(env.getProperty(base + "_PII_INDEX_KEY"), base + "_PII_INDEX_KEY");
            return new PiiCipher(1, enc, idx);
        });
    }

    private static byte[] decode(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalStateException(name + " is not configured");
        return Base64.getDecoder().decode(v.trim());
    }
}
