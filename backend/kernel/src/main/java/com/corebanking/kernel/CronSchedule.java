package com.corebanking.kernel;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * A six-field cron schedule for tenant jobs (US-112): {@code second minute hour day-of-month month day-of-week},
 * read in India Standard Time by the caller (IST has no daylight saving, so local times never repeat or vanish).
 * <ul>
 *   <li>Each field takes {@code *}, a value, a list {@code a,b}, a range {@code a-b} and a step: every n-th value
 *       (an asterisk, a slash and n), {@code a-b/n}, or {@code a/n} (from a to the end of the field).</li>
 *   <li>Months may be named JAN–DEC and weekdays SUN–SAT; weekday 0 and 7 are both Sunday.</li>
 *   <li>{@code ?} means "any" in the two day fields. When both day fields are restricted, a date must match both,
 *       which is how the end-of-day schedule (Spring's cron) reads them.</li>
 *   <li>{@code L}, {@code W} and {@code #} are not supported and are refused with a message.</li>
 * </ul>
 * This is plain Java so the fire times can be tested; end of day keeps using Spring's parser.
 */
public final class CronSchedule {

    private static final List<String> MONTHS = List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final List<String> DAYS = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
    /** A schedule that can never fire (30 February) gives up after this many years. */
    private static final int SEARCH_YEARS = 8;

    private final String expression;
    private final BitSet seconds;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;      // 0 = Sunday … 6 = Saturday

    private CronSchedule(String expression, BitSet[] f) {
        this.expression = expression;
        this.seconds = f[0];
        this.minutes = f[1];
        this.hours = f[2];
        this.daysOfMonth = f[3];
        this.months = f[4];
        this.daysOfWeek = f[5];
    }

    /** @throws IllegalArgumentException with a message for the user when the expression is not valid */
    public static CronSchedule parse(String expression) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("a cron expression is required");
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 6) {
            throw new IllegalArgumentException("a cron expression has 6 fields: second minute hour day-of-month month day-of-week, e.g. '0 30 6 * * *'");
        }
        BitSet[] f = new BitSet[6];
        f[0] = field(parts[0], 0, 59, "second", null, false);
        f[1] = field(parts[1], 0, 59, "minute", null, false);
        f[2] = field(parts[2], 0, 23, "hour", null, false);
        f[3] = field(parts[3], 1, 31, "day-of-month", null, true);
        f[4] = field(parts[4], 1, 12, "month", MONTHS, false);
        BitSet dow = field(parts[5], 0, 7, "day-of-week", DAYS, true);
        if (dow.get(7)) {                 // 7 is Sunday too
            dow.set(0);
            dow.clear(7);
        }
        f[5] = dow;
        return new CronSchedule(String.join(" ", parts), f);
    }

    /** Null when the expression is valid, otherwise what is wrong with it. */
    public static String problem(String expression) {
        try {
            parse(expression);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static BitSet field(String text, int min, int max, String name, List<String> names, boolean questionMark) {
        BitSet set = new BitSet(max + 1);
        for (String item : text.split(",", -1)) {
            if (item.isEmpty()) throw new IllegalArgumentException(name + ": empty item in '" + text + "'");
            String range = item;
            int step = 1;
            int slash = item.indexOf('/');
            if (slash >= 0) {
                range = item.substring(0, slash);
                step = number(item.substring(slash + 1), name, null, 0);
                if (step < 1) throw new IllegalArgumentException(name + ": the step in '" + item + "' must be 1 or more");
            }
            int from;
            int to;
            if (range.equals("*") || (questionMark && range.equals("?"))) {
                from = min;
                to = max;
                if (name.equals("day-of-week")) to = 6;
            } else {
                int dash = range.indexOf('-');
                if (dash > 0) {
                    from = number(range.substring(0, dash), name, names, namesBase(name));
                    to = number(range.substring(dash + 1), name, names, namesBase(name));
                } else {
                    from = number(range, name, names, namesBase(name));
                    to = slash >= 0 ? (name.equals("day-of-week") ? 6 : max) : from;
                }
            }
            if (from < min || to > max || from > to) {
                throw new IllegalArgumentException(name + ": '" + item + "' is outside " + min + "-" + max);
            }
            for (int v = from; v <= to; v += step) set.set(v);
        }
        return set;
    }

    private static int namesBase(String name) {
        return name.equals("month") ? 1 : 0;
    }

    private static int number(String text, String name, List<String> names, int namesBase) {
        if (names != null) {
            int i = names.indexOf(text.toUpperCase(Locale.ROOT));
            if (i >= 0) return i + namesBase;
        }
        if (!text.matches("[0-9]{1,2}")) {
            if (text.matches(".*[LW#].*")) throw new IllegalArgumentException(name + ": L, W and # are not supported ('" + text + "')");
            throw new IllegalArgumentException(name + ": '" + text + "' is not a number");
        }
        return Integer.parseInt(text);
    }

    /**
     * The first fire time strictly after {@code after}, or null when the schedule never fires (for example
     * 30 February).
     */
    public LocalDateTime next(LocalDateTime after) {
        LocalDateTime t = after.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        LocalDateTime limit = t.plusYears(SEARCH_YEARS);
        while (t.isBefore(limit)) {
            if (!months.get(t.getMonthValue())) {
                t = t.plusMonths(1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            } else if (!daysOfMonth.get(t.getDayOfMonth()) || !daysOfWeek.get(t.getDayOfWeek().getValue() % 7)) {
                t = t.plusDays(1).truncatedTo(ChronoUnit.DAYS);
            } else if (!hours.get(t.getHour())) {
                t = t.plusHours(1).truncatedTo(ChronoUnit.HOURS);
            } else if (!minutes.get(t.getMinute())) {
                t = t.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES);
            } else if (!seconds.get(t.getSecond())) {
                t = t.plusSeconds(1);
            } else {
                return t;
            }
        }
        return null;
    }

    /**
     * The fire time a scheduler should answer now: the latest fire time after {@code lastAnswered} and not after
     * {@code now}, or null when nothing is due. Fire times missed while the application was down are skipped, so a
     * job runs once after an outage, not once per missed time.
     */
    public LocalDateTime due(LocalDateTime lastAnswered, LocalDateTime now) {
        LocalDateTime candidate = next(lastAnswered);
        if (candidate == null || candidate.isAfter(now)) return null;
        // Jump close to now, then walk: an every-minute job that was off for a month must not loop a month of minutes.
        LocalDateTime near = next(now.minusHours(1));
        if (near != null && !near.isAfter(now) && near.isAfter(candidate)) candidate = near;
        for (int i = 0; i < 4000; i++) {
            LocalDateTime following = next(candidate);
            if (following == null || following.isAfter(now)) break;
            candidate = following;
        }
        return candidate;
    }

    @Override
    public String toString() {
        return expression;
    }
}
