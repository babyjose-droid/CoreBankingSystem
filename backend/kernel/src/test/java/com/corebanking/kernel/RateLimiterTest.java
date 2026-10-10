package com.corebanking.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void a_key_may_burst_up_to_the_limit_then_is_refused_with_a_retry_time() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter l = new RateLimiter(30, clock::get);
        for (int i = 0; i < 30; i++) assertTrue(l.tryAcquire("u1").allowed(), "call " + i);
        RateLimiter.Decision d = l.tryAcquire("u1");
        assertFalse(d.allowed());
        assertEquals(2, d.retryAfterSeconds(), "30 per minute is one token every two seconds");
    }

    @Test
    void tokens_come_back_with_time_and_never_beyond_the_limit() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter l = new RateLimiter(60, clock::get);
        for (int i = 0; i < 60; i++) l.tryAcquire("u1");
        assertFalse(l.tryAcquire("u1").allowed());
        clock.addAndGet(SECOND);                         // 60 per minute: one token a second
        assertTrue(l.tryAcquire("u1").allowed());
        assertFalse(l.tryAcquire("u1").allowed());
        clock.addAndGet(10 * 60 * SECOND);               // a long quiet time does not bank more than the burst
        int allowed = 0;
        for (int i = 0; i < 100; i++) if (l.tryAcquire("u1").allowed()) allowed++;
        assertEquals(60, allowed);
    }

    @Test
    void keys_are_independent() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter l = new RateLimiter(1, clock::get);
        assertTrue(l.tryAcquire("a").allowed());
        assertFalse(l.tryAcquire("a").allowed());
        assertTrue(l.tryAcquire("b").allowed());
    }

    @Test
    void the_table_is_bounded() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter l = new RateLimiter(5, clock::get);
        for (int i = 0; i < RateLimiter.MAX_KEYS; i++) l.tryAcquire("k" + i);
        assertEquals(RateLimiter.MAX_KEYS, l.size());
        assertFalse(l.tryAcquire("new-key").allowed(), "a new key is refused while every bucket is in use");
        clock.addAndGet(120 * SECOND);                   // every bucket has refilled: they are dropped to make room
        assertTrue(l.tryAcquire("new-key").allowed());
        assertTrue(l.size() < RateLimiter.MAX_KEYS);
    }

    @Test
    void a_limit_below_one_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0));
    }

    @Test
    void paths_are_classified_by_prefix() {
        RateLimitPaths p = new RateLimitPaths(List.of("/api/v1/eod", "/actuator"), List.of("/api/v1/customers/dedupe-check", "/api/v1/pincodes"));
        assertEquals(RateLimitPaths.Kind.EXEMPT, p.classify("/api/v1/eod/run"));
        assertEquals(RateLimitPaths.Kind.EXEMPT, p.classify("/actuator/health"));
        assertEquals(RateLimitPaths.Kind.STRICT, p.classify("/api/v1/customers/dedupe-check"));
        assertEquals(RateLimitPaths.Kind.STRICT, p.classify("/api/v1/pincodes/682001"));
        assertEquals(RateLimitPaths.Kind.NORMAL, p.classify("/api/v1/eodx"));
        assertEquals(RateLimitPaths.Kind.NORMAL, p.classify("/api/v1/customers"));
        assertEquals(RateLimitPaths.Kind.NORMAL, p.classify(null));
    }
}
