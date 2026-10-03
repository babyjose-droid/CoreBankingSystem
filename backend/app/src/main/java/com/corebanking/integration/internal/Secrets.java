package com.corebanking.integration.internal;

import com.corebanking.customer.PiiKeys;
import com.corebanking.kernel.PiiCipher;
import com.corebanking.platform.CurrentUser;
import org.springframework.stereotype.Component;

/**
 * Encryption at rest for everything secret or personal in the integration schema: provider secrets, webhook
 * signing secrets, bank account numbers, message recipients and texts. Uses the tenant's data key (ADR-013) with
 * the column name as associated data, so a ciphertext cannot be moved to another column.
 */
@Component
class Secrets {

    private final PiiKeys keys;

    Secrets(PiiKeys keys) {
        this.keys = keys;
    }

    private PiiCipher cipher() {
        return keys.forTenant(CurrentUser.requireTenant());
    }

    byte[] seal(String plain, String column) {
        return cipher().encrypt(plain, column);
    }

    String open(byte[] blob, String column) {
        return cipher().decrypt(blob, column);
    }

    /** Keyed hash for "is this the same account as before" without decrypting. */
    byte[] hash(String kind, String value) {
        return cipher().blindIndex(kind, value);
    }

    static String last4(String value) {
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }
}
