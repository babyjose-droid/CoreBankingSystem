package com.corebanking.kernel;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A token-bucket rate limiter keyed by a string (a user, an address): each key may make {@code perMinute} calls per
 * minute on average and burst up to {@code perMinute} at once. Pure logic with an injected clock; in memory, so each
 * application instance counts on its own (with several instances the effective limit is the limit times the number of
 * instances; a shared store such as Redis is needed for an exact figure).
 * <p>
 * Memory is bounded: buckets that have refilled completely are dropped when the table grows past
 * {@link #MAX_KEYS}, and a flood of new keys beyond that is answered "not allowed" rather than stored (the keys come
 * from verified tokens and client addresses, so this is a backstop, not a normal state).
 */
public final class RateLimiter {

    public static final int MAX_KEYS = 100_000;
    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    /** The answer for one call: whether it may proceed, and when to retry if not. */
    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }

    private final int perMinute;
    private final LongSupplier nanos;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute, LongSupplier nanos) {
        if (perMinute < 1) throw new IllegalArgumentException("perMinute must be at least 1");
        this.perMinute = perMinute;
        this.nanos = nanos;
    }

    public RateLimiter(int perMinute) {
        this(perMinute, System::nanoTime);
    }

    public int perMinute() {
        return perMinute;
    }

    /** Takes one token for the key if there is one. */
    public Decision tryAcquire(String key) {
        long now = nanos.getAsLong();
        if (buckets.size() >= MAX_KEYS && !buckets.containsKey(key)) {
            evictFull(now);
            if (buckets.size() >= MAX_KEYS) return new Decision(false, 60);
        }
        Decision[] out = new Decision[1];
        buckets.compute(key, (k, b) -> {
            if (b == null) b = new Bucket(perMinute, now);
            double refill = (now - b.lastNanos) * (perMinute / (double) NANOS_PER_MINUTE);
            b.tokens = Math.min(perMinute, b.tokens + Math.max(0, refill));
            b.lastNanos = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                out[0] = new Decision(true, 0);
            } else {
                double missing = 1 - b.tokens;
                long seconds = (long) Math.ceil(missing * 60.0 / perMinute);
                out[0] = new Decision(false, Math.max(1, seconds));
            }
            return b;
        });
        return out[0];
    }

    /** Drops buckets that would be full again; they hold no information. */
    private void evictFull(long now) {
        for (Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator(); it.hasNext();) {
            Bucket b = it.next().getValue();
            double refill = (now - b.lastNanos) * (perMinute / (double) NANOS_PER_MINUTE);
            if (b.tokens + refill >= perMinute) it.remove();
        }
    }

    /** Number of keys held (for tests and metrics). */
    public int size() {
        return buckets.size();
    }
}
