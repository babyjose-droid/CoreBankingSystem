package com.corebanking.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CronScheduleTest {

    private static LocalDateTime at(String text) {
        return LocalDateTime.parse(text);
    }

    private static LocalDateTime next(String cron, String after) {
        return CronSchedule.parse(cron).next(at(after));
    }

    @Test
    void daily_time() {
        assertEquals(at("2026-10-02T23:55:00"), next("0 55 23 * * *", "2026-10-02T10:00:00"));
        assertEquals(at("2026-10-03T23:55:00"), next("0 55 23 * * *", "2026-10-02T23:55:00"));   // strictly after
        assertEquals(at("2026-10-02T23:55:00"), next("0 55 23 * * *", "2026-10-02T23:54:59"));
        assertEquals(at("2027-01-01T00:30:00"), next("0 30 0 * * *", "2026-12-31T00:30:00"));     // year end
    }

    @Test
    void steps_lists_and_ranges() {
        assertEquals(at("2026-10-02T10:05:00"), next("0 */5 * * * *", "2026-10-02T10:00:00"));
        assertEquals(at("2026-10-02T11:00:00"), next("0 */5 * * * *", "2026-10-02T10:55:00"));
        assertEquals(at("2026-10-02T10:15:00"), next("0 0,15,30,45 * * * *", "2026-10-02T10:00:01"));
        assertEquals(at("2026-10-02T09:00:00"), next("0 0 9-17 * * *", "2026-10-02T03:00:00"));
        assertEquals(at("2026-10-03T09:00:00"), next("0 0 9-17 * * *", "2026-10-02T17:00:00"));
        assertEquals(at("2026-10-02T12:00:00"), next("0 0 8-18/4 * * *", "2026-10-02T08:00:00"));
        assertEquals(at("2026-10-02T10:20:00"), next("0 5/15 * * * *", "2026-10-02T10:05:00"));    // 5, 20, 35, 50
        assertEquals(at("2026-10-02T10:00:10"), next("*/10 * * * * *", "2026-10-02T10:00:00"));
    }

    @Test
    void month_days_and_months() {
        assertEquals(at("2026-11-01T06:00:00"), next("0 0 6 1 * *", "2026-10-02T00:00:00"));
        assertEquals(at("2026-10-31T06:00:00"), next("0 0 6 31 * *", "2026-10-02T00:00:00"));
        assertEquals(at("2026-12-31T06:00:00"), next("0 0 6 31 * *", "2026-10-31T06:00:00"));   // November has no 31st
        assertEquals(at("2028-02-29T00:00:00"), next("0 0 0 29 2 *", "2026-10-02T00:00:00"));   // next leap day
        assertEquals(at("2027-04-01T00:00:00"), next("0 0 0 1 APR *", "2026-10-02T00:00:00"));
        assertEquals(at("2027-01-01T00:00:00"), next("0 0 0 1 jan,jul *", "2026-10-02T00:00:00"));
        assertNull(next("0 0 0 30 2 *", "2026-10-02T00:00:00"));                                // 30 February never comes
    }

    @Test
    void weekdays() {
        // 02-Oct-2026 is a Friday
        assertEquals(at("2026-10-05T09:00:00"), next("0 0 9 * * MON", "2026-10-02T12:00:00"));
        assertEquals(at("2026-10-05T09:00:00"), next("0 0 9 * * 1", "2026-10-02T12:00:00"));
        assertEquals(at("2026-10-04T09:00:00"), next("0 0 9 * * 0", "2026-10-02T12:00:00"));    // Sunday as 0
        assertEquals(at("2026-10-04T09:00:00"), next("0 0 9 * * 7", "2026-10-02T12:00:00"));    // and as 7
        assertEquals(at("2026-10-05T09:00:00"), next("0 0 9 ? * MON-FRI", "2026-10-02T12:00:00"));
        assertEquals(at("2026-10-03T09:00:00"), next("0 0 9 * * SAT,SUN", "2026-10-02T12:00:00"));
        // both day fields restricted: the date must match both (as end of day's Spring cron reads it)
        assertEquals(at("2027-01-01T09:00:00"), next("0 0 9 1 * FRI", "2026-10-02T12:00:00"));
    }

    @Test
    void invalid_expressions_say_what_is_wrong() {
        assertTrue(CronSchedule.problem("0 30 23 * *").contains("6 fields"));
        assertTrue(CronSchedule.problem("").contains("required"));
        assertTrue(CronSchedule.problem(null).contains("required"));
        assertTrue(CronSchedule.problem("60 * * * * *").contains("second"));
        assertTrue(CronSchedule.problem("0 0 24 * * *").contains("hour"));
        assertTrue(CronSchedule.problem("0 0 0 0 * *").contains("day-of-month"));
        assertTrue(CronSchedule.problem("0 0 0 * 13 *").contains("month"));
        assertTrue(CronSchedule.problem("0 0 0 * * 8").contains("day-of-week"));
        assertTrue(CronSchedule.problem("0 0 0 L * *").contains("not supported"));
        assertTrue(CronSchedule.problem("0 0 0 * * 5#2").contains("not supported"));
        assertTrue(CronSchedule.problem("0 */0 * * * *").contains("step"));
        assertTrue(CronSchedule.problem("0 10-5 * * * *").contains("outside"));
        assertTrue(CronSchedule.problem("0 a * * * *").contains("not a number"));
        assertTrue(CronSchedule.problem("0 1,,2 * * * *").contains("empty item"));
        assertTrue(CronSchedule.problem("? * * * * *").contains("second"));       // '?' only in the day fields
        assertNull(CronSchedule.problem("0 0 6 ? * *"));
        assertNull(CronSchedule.problem("  0   30  23 * *   * "));
        assertEquals("0 30 23 * * *", CronSchedule.parse("  0   30  23 * *   * ").toString());
    }

    @Test
    void due_fire_time_is_answered_once_and_missed_ones_are_skipped() {
        CronSchedule everyFive = CronSchedule.parse("0 */5 * * * *");
        // nothing due yet
        assertNull(everyFive.due(at("2026-10-02T10:00:00"), at("2026-10-02T10:04:59")));
        // due exactly at the fire time
        assertEquals(at("2026-10-02T10:05:00"), everyFive.due(at("2026-10-02T10:00:00"), at("2026-10-02T10:05:00")));
        // answered: asking again with that fire time as "last answered" gives nothing until the next one
        assertNull(everyFive.due(at("2026-10-02T10:05:00"), at("2026-10-02T10:05:30")));
        // the application was down for two days: one run for the latest fire time, not 576 runs
        assertEquals(at("2026-10-04T10:05:00"), everyFive.due(at("2026-10-02T10:00:00"), at("2026-10-04T10:07:12")));
        // two instances that poll at different moments compute the same fire time
        assertEquals(everyFive.due(at("2026-10-02T10:00:00"), at("2026-10-02T10:10:01")),
                everyFive.due(at("2026-10-02T10:00:00"), at("2026-10-02T10:14:59")));
    }

    @Test
    void due_works_for_sparse_and_dense_schedules() {
        CronSchedule monthly = CronSchedule.parse("0 0 6 1 * *");
        assertNull(monthly.due(at("2026-10-01T06:00:00"), at("2026-10-31T23:59:59")));
        assertEquals(at("2026-11-01T06:00:00"), monthly.due(at("2026-10-01T06:00:00"), at("2026-11-01T06:00:20")));
        assertEquals(at("2027-03-01T06:00:00"), monthly.due(at("2026-10-01T06:00:00"), at("2027-03-15T00:00:00")));
        CronSchedule everySecond = CronSchedule.parse("* * * * * *");
        assertEquals(at("2026-10-09T12:00:00"), everySecond.due(at("2026-10-02T10:00:00"), at("2026-10-09T12:00:00.750")));
        CronSchedule never = CronSchedule.parse("0 0 0 30 2 *");
        assertNull(never.due(at("2026-10-02T10:00:00"), at("2030-10-02T10:00:00")));
    }
}
