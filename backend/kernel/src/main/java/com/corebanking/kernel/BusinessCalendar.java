package com.corebanking.kernel;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Working-day calendar for one branch (US-011): weekly offs, the RBI "second and fourth Saturday" rule for banks,
 * and dated holidays. Used for due-date adjustment and for rolling the business date at end of day.
 */
public final class BusinessCalendar {

    public enum HolidayMode {
        /** Keep the date even if it is a holiday. */
        NONE,
        NEXT_DAY,
        PREVIOUS_DAY,
        /** Next working day unless that crosses into the next month, then previous working day. */
        MODIFIED_FOLLOWING
    }

    private final Set<DayOfWeek> weeklyOffs;
    private final boolean secondAndFourthSaturdayOff;
    private final Set<LocalDate> holidays;

    public BusinessCalendar(Set<DayOfWeek> weeklyOffs, boolean secondAndFourthSaturdayOff, Collection<LocalDate> holidays) {
        this.weeklyOffs = weeklyOffs.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(weeklyOffs);
        this.secondAndFourthSaturdayOff = secondAndFourthSaturdayOff;
        this.holidays = Set.copyOf(new HashSet<>(holidays));
    }

    /** NBFC default: Sunday off. */
    public static BusinessCalendar nbfc(Collection<LocalDate> holidays) {
        return new BusinessCalendar(EnumSet.of(DayOfWeek.SUNDAY), false, holidays);
    }

    /** Bank default: Sunday plus second and fourth Saturday off. */
    public static BusinessCalendar bank(Collection<LocalDate> holidays) {
        return new BusinessCalendar(EnumSet.of(DayOfWeek.SUNDAY), true, holidays);
    }

    public boolean isWorkingDay(LocalDate d) {
        if (weeklyOffs.contains(d.getDayOfWeek())) return false;
        if (secondAndFourthSaturdayOff && d.getDayOfWeek() == DayOfWeek.SATURDAY) {
            int nth = (d.getDayOfMonth() - 1) / 7 + 1;
            if (nth == 2 || nth == 4) return false;
        }
        return !holidays.contains(d);
    }

    public LocalDate nextWorkingDay(LocalDate d) {
        LocalDate x = d.plusDays(1);
        for (int guard = 0; !isWorkingDay(x); guard++) {
            if (guard > 366) throw new IllegalStateException("no working day within a year after " + d);
            x = x.plusDays(1);
        }
        return x;
    }

    public LocalDate previousWorkingDay(LocalDate d) {
        LocalDate x = d.minusDays(1);
        for (int guard = 0; !isWorkingDay(x); guard++) {
            if (guard > 366) throw new IllegalStateException("no working day within a year before " + d);
            x = x.minusDays(1);
        }
        return x;
    }

    public LocalDate adjust(LocalDate d, HolidayMode mode) {
        if (mode == HolidayMode.NONE || isWorkingDay(d)) return d;
        return switch (mode) {
            case NEXT_DAY -> nextWorkingDay(d);
            case PREVIOUS_DAY -> previousWorkingDay(d);
            case MODIFIED_FOLLOWING -> {
                LocalDate n = nextWorkingDay(d);
                yield n.getMonth() == d.getMonth() ? n : previousWorkingDay(d);
            }
            case NONE -> d;
        };
    }
}
