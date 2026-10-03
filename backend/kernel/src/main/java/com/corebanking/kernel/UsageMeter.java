package com.corebanking.kernel;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Usage metering per tenant (US-004): the in-memory count of API calls, and small rules of the usage report.
 * <p>
 * Counting a call is an increment in memory, never a database write. A timer takes the counts with
 * {@link #drain()} and adds them to the control plane; if that write fails the counts are put back with
 * {@link #add}, so calls are lost only when an instance stops without flushing.
 */
public final class UsageMeter {

    /** No more than this many different keys are counted; tenant codes come from verified tokens, so this is a backstop. */
    private static final int MAX_KEYS = 10_000;
    public static final int MAX_RANGE_DAYS = 400;

    /** Calls of one tenant on one calendar day. */
    public record Key(String tenant, LocalDate day) {}

    /** An inclusive range of days. */
    public record Range(LocalDate from, LocalDate to) {}

    private final ConcurrentHashMap<Key, Long> calls = new ConcurrentHashMap<>();

    public void count(String tenant, LocalDate day) {
        add(new Key(tenant, day), 1);
    }

    public void add(Key key, long n) {
        if (n <= 0 || key.tenant() == null || key.day() == null) return;
        if (calls.size() >= MAX_KEYS && !calls.containsKey(key)) return;
        calls.merge(key, n, Long::sum);
    }

    /** Takes what has been counted since the last drain. Calls counted while this runs are kept for the next one. */
    public Map<Key, Long> drain() {
        Map<Key, Long> out = new TreeMap<>(Comparator.comparing(Key::tenant).thenComparing(Key::day));
        for (Key key : List.copyOf(calls.keySet())) {
            Long n = calls.remove(key);          // atomic with merge: a call is in this drain or in the next, never lost
            if (n != null && n > 0) out.put(key, n);
        }
        return new LinkedHashMap<>(out);
    }

    public long pending() {
        return calls.values().stream().mapToLong(Long::longValue).sum();
    }

    // ------------------------------------------------------------------------------------------------ report rules
    /** The range of a usage query: both ends given or defaulted to the last 31 days up to {@code today}; at most 400 days. */
    public static Range range(String from, String to, LocalDate today) {
        LocalDate t = to == null || to.isBlank() ? today : date(to, "to");
        LocalDate f = from == null || from.isBlank() ? t.minusDays(30) : date(from, "from");
        if (f.isAfter(t)) throw new IllegalArgumentException("'from' cannot be after 'to'");
        if (f.plusDays(MAX_RANGE_DAYS).isBefore(t)) throw new IllegalArgumentException("the range cannot be longer than " + MAX_RANGE_DAYS + " days");
        return new Range(f, t);
    }

    /** {@code YYYY-MM} of a monthly report; blank means the month before {@code today}'s. */
    public static YearMonth month(String month, LocalDate today) {
        if (month == null || month.isBlank()) return YearMonth.from(today).minusMonths(1);
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be YYYY-MM");
        }
    }

    private static LocalDate date(String text, String name) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + name + "' must be a date as YYYY-MM-DD");
        }
    }
}
