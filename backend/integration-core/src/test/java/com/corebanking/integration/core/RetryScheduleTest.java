package com.corebanking.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RetryScheduleTest {

    @Test
    void waits_double_until_the_cap() {
        RetrySchedule s = new RetrySchedule(60, 21_600, 0.2, 12);
        assertEquals(List.of(60L, 120L, 240L, 480L, 960L, 1920L, 3840L, 7680L, 15360L, 21600L, 21600L), s.nominalWaits());
        assertEquals(21_600, s.nominalDelaySeconds(1000));
    }

    @Test
    void the_webhook_schedule_spans_most_of_a_day() {
        long total = RetrySchedule.WEBHOOK.nominalWaits().stream().mapToLong(Long::longValue).sum();
        assertEquals(73_860, total);                    // 20 h 31 min
        assertEquals(11, RetrySchedule.WEBHOOK.nominalWaits().size());
    }

    @Test
    void jitter_spreads_the_wait_within_its_band() {
        RetrySchedule s = new RetrySchedule(100, 10_000, 0.2, 5);
        assertEquals(80, s.delaySeconds(1, 0.0));
        assertEquals(100, s.delaySeconds(1, 0.5));
        assertEquals(120, s.delaySeconds(1, 0.9999999));
        assertEquals(400, s.delaySeconds(3, 0.5));
        for (int i = 0; i < 100; i++) {
            long d = s.delaySeconds(2, i / 100.0);
            assertTrue(d >= 160 && d <= 240, "delay " + d);
        }
    }

    @Test
    void no_jitter_gives_the_nominal_wait_and_never_less_than_a_second() {
        assertEquals(30, new RetrySchedule(30, 3600, 0, 3).delaySeconds(1, 0.73));
        assertEquals(1, new RetrySchedule(1, 1, 0.5, 3).delaySeconds(1, 0.0));
    }

    @Test
    void dead_letters_after_the_last_attempt() {
        RetrySchedule s = new RetrySchedule(30, 3600, 0.2, 3);
        assertFalse(s.exhausted(1));
        assertFalse(s.exhausted(2));
        assertTrue(s.exhausted(3));
        assertTrue(s.exhausted(4));
        assertTrue(s.withMaxAttempts(1).exhausted(1));
    }

    @Test
    void refuses_nonsense_parameters() {
        assertThrows(IllegalArgumentException.class, () -> new RetrySchedule(0, 10, 0.1, 3));
        assertThrows(IllegalArgumentException.class, () -> new RetrySchedule(20, 10, 0.1, 3));
        assertThrows(IllegalArgumentException.class, () -> new RetrySchedule(10, 10, 0.6, 3));
        assertThrows(IllegalArgumentException.class, () -> new RetrySchedule(10, 10, 0.1, 0));
        assertThrows(IllegalArgumentException.class, () -> RetrySchedule.PROVIDER.delaySeconds(0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> RetrySchedule.PROVIDER.delaySeconds(1, 1.0));
    }
}
