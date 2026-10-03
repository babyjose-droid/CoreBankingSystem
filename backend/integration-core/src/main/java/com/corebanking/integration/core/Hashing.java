package com.corebanking.integration.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Digests and MACs from the JDK only, with constant-time comparison for anything an attacker can supply. */
public final class Hashing {

    private Hashing() {}

    public static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    public static String hmacSha256Hex(byte[] key, String message) {
        return hex(hmacSha256(key, message.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha256Hex(byte[] content) {
        return hex(digest("SHA-256", content));
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha512Hex(String text) {
        return hex(digest("SHA-512", text.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] digest(String algorithm, byte[] content) {
        try {
            return MessageDigest.getInstance(algorithm).digest(content);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** Compares two strings without stopping at the first difference (signatures, hashes). */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
