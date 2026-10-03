package com.corebanking.integration.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The signature on outbound webhooks (US-121), and the check a receiver runs.
 * <pre>
 *   X-CoreBanking-Signature: t=1790000000,v1=k2:5f1c…,v1=k1:9a3e…
 * </pre>
 * <ul>
 *   <li>{@code t} is the time of sending in seconds since the epoch (UTC).</li>
 *   <li>Each {@code v1} is {@code <key id>:<hex HMAC-SHA256>} over the bytes of {@code t + "." + body}, where body is
 *       the request body exactly as sent. One entry per secret in force: while a rotated secret overlaps the new
 *       one, both are present, so a receiver that still holds the old secret keeps verifying.</li>
 *   <li>A receiver accepts the request when any entry verifies with a secret it holds for that key id, and
 *       {@code t} is within its tolerance of its own clock ({@link #DEFAULT_TOLERANCE_SECONDS} is what we
 *       recommend and what the simulator's inbound check uses). The timestamp is inside the signed text, so a
 *       captured request cannot be replayed later with a fresh one.</li>
 * </ul>
 * The same scheme signs the simulator's provider callbacks, with the header named by the caller.
 */
public final class WebhookSignature {

    public static final String HEADER = "X-CoreBanking-Signature";
    public static final String EVENT_ID_HEADER = "X-CoreBanking-Event-Id";
    public static final String EVENT_TYPE_HEADER = "X-CoreBanking-Event-Type";
    public static final String DELIVERY_HEADER = "X-CoreBanking-Delivery";
    /** Recommended receiver tolerance: five minutes either side. */
    public static final long DEFAULT_TOLERANCE_SECONDS = 300;
    private static final int MAX_ENTRIES = 5;

    /** A secret in force. Key ids are short labels such as {@code k3}; they are not secret. */
    public record Key(String id, byte[] secret) {
        public Key {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,20}")) throw new IllegalArgumentException("invalid key id");
            if (secret == null || secret.length < 16) throw new IllegalArgumentException("a signing secret needs at least 16 bytes");
            secret = secret.clone();
        }

        @Override
        public byte[] secret() {
            return secret.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && id.equals(k.id) && java.util.Arrays.equals(secret, k.secret);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "Key[" + id + "]";            // never the secret
        }
    }

    public enum Verdict {
        VALID,
        /** No header, or not in the documented shape. */
        MALFORMED,
        /** The timestamp is outside the tolerance: too old (a replay) or too far in the future. */
        STALE,
        /** None of the key ids in the header is one the receiver holds. */
        UNKNOWN_KEY,
        BAD_SIGNATURE
    }

    private WebhookSignature() {}

    /** The header value for a body sent at {@code timestampSeconds}; the newest key first. */
    public static String header(List<Key> keys, long timestampSeconds, String body) {
        if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("no signing key");
        if (keys.size() > MAX_ENTRIES) throw new IllegalArgumentException("at most " + MAX_ENTRIES + " signing keys");
        StringBuilder sb = new StringBuilder("t=").append(timestampSeconds);
        for (Key k : keys) {
            sb.append(",v1=").append(k.id()).append(':').append(signature(k.secret(), timestampSeconds, body));
        }
        return sb.toString();
    }

    static String signature(byte[] secret, long timestampSeconds, String body) {
        byte[] signed = (timestampSeconds + "." + (body == null ? "" : body)).getBytes(StandardCharsets.UTF_8);
        return Hashing.hex(Hashing.hmacSha256(secret, signed));
    }

    /**
     * The receiver's check.
     *
     * @param secrets          the secrets the receiver holds, by key id
     * @param nowSeconds       the receiver's clock
     * @param toleranceSeconds how far {@code t} may be from the receiver's clock, either side
     */
    public static Verdict verify(String header, String body, Map<String, byte[]> secrets, long nowSeconds, long toleranceSeconds) {
        if (header == null || header.length() > 1000) return Verdict.MALFORMED;
        Long t = null;
        List<String[]> entries = new ArrayList<>();
        for (String part : header.split(",")) {
            String p = part.trim();
            if (p.startsWith("t=")) {
                if (t != null || !p.substring(2).matches("[0-9]{1,12}")) return Verdict.MALFORMED;
                t = Long.valueOf(p.substring(2));
            } else if (p.startsWith("v1=")) {
                int colon = p.indexOf(':');
                if (colon < 4) return Verdict.MALFORMED;
                String kid = p.substring(3, colon);
                String sig = p.substring(colon + 1);
                if (!kid.matches("[A-Za-z0-9_-]{1,20}") || !sig.matches("[0-9a-f]{64}")) return Verdict.MALFORMED;
                entries.add(new String[] {kid, sig});
            } else {
                return Verdict.MALFORMED;
            }
        }
        if (t == null || entries.isEmpty() || entries.size() > MAX_ENTRIES) return Verdict.MALFORMED;
        if (Math.abs(nowSeconds - t) > toleranceSeconds) return Verdict.STALE;
        boolean known = false;
        for (String[] e : entries) {
            byte[] secret = secrets.get(e[0]);
            if (secret == null) continue;
            known = true;
            if (Hashing.constantTimeEquals(signature(secret, t, body), e[1])) return Verdict.VALID;
        }
        return known ? Verdict.BAD_SIGNATURE : Verdict.UNKNOWN_KEY;
    }
}
