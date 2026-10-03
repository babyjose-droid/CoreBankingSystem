package com.corebanking.kernel;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The period of a scheduled report (US-113). A schedule cannot carry fixed dates, so it names a period relative to
 * the business date on which it runs; the dates are worked out at run time and passed to the report as its
 * {@code from}, {@code to} or {@code asOf} parameters, whichever the report has.
 */
public final class ReportPeriod {

    public static final List<String> PERIODS = List.of("BUSINESS_DATE", "PREVIOUS_DAY", "MONTH_TO_DATE", "PREVIOUS_MONTH");

    private ReportPeriod() {}

    /**
     * @param period        one of {@link #PERIODS}; null means BUSINESS_DATE
     * @param businessDate  the tenant's business date when the job runs
     * @param reportAccepts the names of the report's parameters
     * @return the date parameters to pass, only those the report accepts
     */
    public static Map<String, String> resolve(String period, LocalDate businessDate, Set<String> reportAccepts) {
        String p = period == null || period.isBlank() ? "BUSINESS_DATE" : period;
        LocalDate from;
        LocalDate to;
        switch (p) {
            case "BUSINESS_DATE" -> {
                from = businessDate;
                to = businessDate;
            }
            case "PREVIOUS_DAY" -> {
                from = businessDate.minusDays(1);
                to = from;
            }
            case "MONTH_TO_DATE" -> {
                from = businessDate.withDayOfMonth(1);
                to = businessDate;
            }
            case "PREVIOUS_MONTH" -> {
                from = businessDate.withDayOfMonth(1).minusMonths(1);
                to = businessDate.withDayOfMonth(1).minusDays(1);
            }
            default -> throw new IllegalArgumentException("period must be one of " + String.join(", ", PERIODS));
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (reportAccepts.contains("from")) out.put("from", from.toString());
        if (reportAccepts.contains("to")) out.put("to", to.toString());
        if (reportAccepts.contains("asOf")) out.put("asOf", to.toString());
        return out;
    }
}
