package com.corebanking.integration.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Exponential backoff with jitter for everything sent to another system (webhooks, payouts, messages).
 * <p>
 * After the n-th failed attempt the nominal wait is {@code base × 2^(n-1)}, capped at {@code cap}; the actual wait
 * is the nominal one spread by ±{@code jitter} so that many deliveries that failed together do not all come back
 * together. After {@code maxAttempts} failed attempts the item is dead-lettered: it is not retried again until a
 * person replays it.
 *
 * @param baseSeconds wait after the first failure
 * @param capSeconds  the longest nominal wait
 * @param jitter      spread as a fraction of the nominal wait, 0 to 0.5
 * @param maxAttempts attempts in total, the first one included
 */
public record RetrySchedule(long baseSeconds, long capSeconds, double jitter, int maxAttempts) {

    /** Outbound webhooks: 1, 2, 4 … minutes, capped at 6 hours; 12 attempts, about 20 hours in all. */
    public static final RetrySchedule WEBHOOK = new RetrySchedule(60, 21_600, 0.2, 12);
    /** Provider calls (payout, mandate, messages): 30 seconds doubling to 1 hour; 8 attempts. */
    public static final RetrySchedule PROVIDER = new RetrySchedule(30, 3_600, 0.2, 8);
    /** Posting a received payment when the books are not open (end of day running): every few minutes for a day. */
    public static final RetrySchedule POSTING = new RetrySchedule(120, 1_800, 0.1, 60);

    public RetrySchedule {
        if (baseSeconds < 1 || capSeconds < baseSeconds) throw new IllegalArgumentException("need 1 <= base <= cap");
        if (jitter < 0 || jitter > 0.5) throw new IllegalArgumentException("jitter must be between 0 and 0.5");
        if (maxAttempts < 1 || maxAttempts > 100) throw new IllegalArgumentException("maxAttempts must be 1 to 100");
    }

    public RetrySchedule withMaxAttempts(int attempts) {
        return new RetrySchedule(baseSeconds, capSeconds, jitter, attempts);
    }

    /** True when no further attempt may be made after {@code attemptsMade} failed attempts. */
    public boolean exhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    /** The wait without jitter after {@code attemptsMade} failed attempts (1 or more). */
    public long nominalDelaySeconds(int attemptsMade) {
        if (attemptsMade < 1) throw new IllegalArgumentException("attemptsMade starts at 1");
        int doublings = Math.min(attemptsMade - 1, 40);
        long d = baseSeconds;
        for (int i = 0; i < doublings && d < capSeconds; i++) d *= 2;
        return Math.min(d, capSeconds);
    }

    /**
     * The wait before the next attempt.
     *
     * @param random a number in [0, 1), from the caller's random source (a fixed value in tests)
     */
    public long delaySeconds(int attemptsMade, double random) {
        if (random < 0 || random >= 1) throw new IllegalArgumentException("random must be in [0, 1)");
        long nominal = nominalDelaySeconds(attemptsMade);
        double factor = 1 - jitter + 2 * jitter * random;
        return Math.max(1, Math.round(nominal * factor));
    }

    /** The nominal waits between attempts, for documentation and the delivery log. */
    public List<Long> nominalWaits() {
        List<Long> out = new ArrayList<>();
        for (int n = 1; n < maxAttempts; n++) out.add(nominalDelaySeconds(n));
        return out;
    }
}
