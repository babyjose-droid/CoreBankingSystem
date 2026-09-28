package com.corebanking.kernel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Application-layer encryption for personal data (ADR-013, US-133).
 * <ul>
 *   <li>{@link #encrypt}: AES-256-GCM, random 96-bit IV, the column name as associated data so a ciphertext cannot
 *       be moved to another column. Format: version(1) | keyId(4) | iv(12) | ciphertext+tag.</li>
 *   <li>{@link #blindIndex}: HMAC-SHA256 of the normalised value with a separate key — exact-match search and
 *       dedupe without decrypting.</li>
 * </ul>
 * Keys are per tenant data keys unwrapped from KMS by the caller; this class never sees KMS.
 */
public final class PiiCipher {

    private static final byte VERSION = 1;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int keyId;
    private final SecretKeySpec encKey;
    private final SecretKeySpec macKey;

    public PiiCipher(int keyId, byte[] encryptionKey, byte[] indexKey) {
        if (encryptionKey.length != 32) throw new IllegalArgumentException("encryption key must be 256 bits");
        if (indexKey.length < 32) throw new IllegalArgumentException("index key must be >= 256 bits");
        this.keyId = keyId;
        this.encKey = new SecretKeySpec(encryptionKey.clone(), "AES");
        this.macKey = new SecretKeySpec(indexKey.clone(), "HmacSHA256");
    }

    public byte[] encrypt(String plaintext, String column) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(column.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + 4 + IV_LEN + ct.length).put(VERSION).putInt(keyId).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(byte[] blob, String column) {
        if (blob == null) return null;
        ByteBuffer b = ByteBuffer.wrap(blob);
        if (blob.length < 1 + 4 + IV_LEN + 16 || b.get() != VERSION) throw new IllegalArgumentException("unknown ciphertext format");
        int kid = b.getInt();
        if (kid != keyId) throw new IllegalArgumentException("ciphertext uses key " + kid + ", cipher holds key " + keyId);
        byte[] iv = new byte[IV_LEN];
        b.get(iv);
        byte[] ct = new byte[b.remaining()];
        b.get(ct);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(column.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("ciphertext failed authentication", e);
        }
    }

    /** Keyed hash of a normalised value, namespaced by kind so a PAN hash never equals a mobile hash. */
    public byte[] blindIndex(String kind, String value) {
        if (value == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            mac.update(kind.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(normalise(value).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("hash failed", e);
        }
    }

    static String normalise(String v) {
        return Normalizer.normalize(v, Normalizer.Form.NFKC).trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public int keyId() {
        return keyId;
    }
}
