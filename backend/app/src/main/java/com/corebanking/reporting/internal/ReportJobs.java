package com.corebanking.reporting.internal;

import com.corebanking.kernel.ReportPeriod;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.InternalRecipients;
import com.corebanking.platform.JobHandler;
import com.corebanking.platform.Mail;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reporting jobs of the tenant job catalogue (US-112, US-113).
 * <ul>
 *   <li><b>REPORT</b> — a scheduled report run. Parameters of the job: {@code reportCode} (fixed), {@code period}
 *       (BUSINESS_DATE, PREVIOUS_DAY, MONTH_TO_DATE or PREVIOUS_MONTH: the report's dates are worked out from the
 *       business date at run time), {@code parameters} (the report's other parameters), {@code emailTo}
 *       (recipients) and {@code runAs}. {@code runAs} is always the user who proposed the schedule: they had to
 *       hold the report's permission, a checker approved, and the run uses their branch scope and is theirs to
 *       download. The file is e-mailed to the recipients on an internal domain (tenant property
 *       {@code mail.internal-domains}): a report can hold personal data, so never to anyone else, and a credit-bureau
 *       file is never e-mailed. The run's artifact says how delivery went (SENT, NOT_CONFIGURED, NO_INTERNAL_RECIPIENT,
 *       NOT_EMAILED, FAILED) and how many recipients were left out — never the addresses.</li>
 *   <li><b>DASHBOARD_REFRESH</b> — stores the whole-book figures of the business date for the dashboard trend.</li>
 *   <li><b>EXPORT_CLEANUP</b> — removes report files older than the retention period: tenant property
 *       {@code reports.retention-days}, else {@code corebanking.reports.retention-days} (default 90).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class ReportJobs {

    @Bean
    JobHandler scheduledReportJob(ReportService reports, JdbcTemplate jdbc, Mail mail) {
        return new JobHandler() {
            @Override public String kind() { return "REPORT"; }

            @Override public int minimumIntervalSeconds() { return 300; }

            @Override
            public Map<String, Object> checkParameters(String jobCode, Map<String, Object> current, Map<String, Object> proposed) {
                String report = String.valueOf(current.get("reportCode"));
                Map<String, Object> in = proposed == null ? Map.of() : proposed;
                if (in.get("reportCode") != null && !report.equals(in.get("reportCode"))) {
                    throw ApiException.invalid("job " + jobCode + " runs report " + report + "; the report of a job cannot change");
                }
                String period = in.get("period") == null ? "BUSINESS_DATE" : String.valueOf(in.get("period"));
                if (!ReportPeriod.PERIODS.contains(period)) throw ApiException.invalid("period must be one of " + ReportPeriod.PERIODS);
                Map<String, Object> own = new LinkedHashMap<>();
                if (in.get("parameters") instanceof Map<?, ?> m) m.forEach((k, v) -> own.put(String.valueOf(k), v));
                for (String date : List.of("from", "to", "asOf")) {
                    if (own.containsKey(date)) throw ApiException.invalid("a schedule cannot fix '" + date + "'; choose a period instead");
                }
                // The maker must be allowed to run it, and the parameters must be valid with the dates of a sample period.
                Map<String, Object> sample = new LinkedHashMap<>(own);
                sample.putAll(ReportPeriod.resolve(period, LocalDate.now(), reports.parameterNames(report)));
                reports.checkSchedulable(report, sample);
                List<String> emails = new ArrayList<>();
                if (in.get("emailTo") instanceof List<?> l) {
                    for (Object o : l) {
                        String e = String.valueOf(o).trim();
                        if (!e.matches("[^@\\s,;]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,}")) throw ApiException.invalid("emailTo: '" + e + "' is not an e-mail address");
                        emails.add(e);
                    }
                }
                if (emails.size() > 20) throw ApiException.invalid("emailTo: at most 20 recipients");
                if (!emails.isEmpty()) {
                    // Refused when proposed, not only skipped at send time (which stays as a second guard).
                    Set<String> internal = InternalRecipients.domains(jdbc);
                    List<String> external = InternalRecipients.externalDomains(emails, internal);
                    if (!external.isEmpty()) {
                        throw ApiException.invalid("emailTo: reports are e-mailed only to the lender's own domains; not allowed: "
                                + String.join(", ", external) + ". Allowed domains (tenant property mail.internal-domains): "
                                + (internal.isEmpty() ? "none configured" : String.join(", ", new java.util.TreeSet<>(internal))));
                    }
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("reportCode", report);
                out.put("period", period);
                out.put("parameters", own);
                out.put("emailTo", emails);
                out.put("runAs", CurrentUser.username());
                return out;
            }

            @Override
            public Result run(Context c) {
                Map<String, Object> p = c.parameters();
                String report = String.valueOf(p.get("reportCode"));
                if (p.get("runAs") == null) throw ApiException.conflict("report job " + c.jobCode() + " has not been scheduled yet: propose its schedule first");
                LocalDate businessDate = jdbc.queryForObject("SELECT business_date FROM platform.business_day WHERE id = 1", LocalDate.class);
                Map<String, Object> params = new LinkedHashMap<>();
                if (p.get("parameters") instanceof Map<?, ?> m) m.forEach((k, v) -> params.put(String.valueOf(k), v));
                params.putAll(ReportPeriod.resolve((String) p.get("period"), businessDate, reports.parameterNames(report)));
                ReportService.Run run = reports.runScheduled(report, params, String.valueOf(p.get("runAs")), c.schedule());
                if (!"COMPLETED".equals(run.status())) throw ApiException.conflict("report " + report + " failed: " + run.error());
                Map<String, Object> artifact = new LinkedHashMap<>();
                artifact.put("reportRunId", run.id().toString());
                artifact.put("fileName", run.fileName());
                artifact.put("rows", run.rowCount());
                artifact.put("requestedBy", run.requestedBy());
                artifact.put("emailTo", p.get("emailTo") == null ? List.of() : p.get("emailTo"));
                int failed = deliver(run, p.get("emailTo"), artifact);
                return new Result(run.rowCount() == null ? 0 : run.rowCount(), failed, artifact);
            }

            /** E-mails the file to the internal recipients; records the outcome on the artifact. Returns 1 on failure. */
            private int deliver(ReportService.Run run, Object emailTo, Map<String, Object> artifact) {
                List<String> to = new ArrayList<>();
                if (emailTo instanceof List<?> l) l.forEach(o -> to.add(String.valueOf(o)));
                if (to.isEmpty()) {
                    artifact.put("delivery", "NO_RECIPIENT");
                    return 0;
                }
                if (!mail.enabled()) {
                    artifact.put("delivery", "NOT_CONFIGURED");     // the file is stored; the deployment has no mail relay
                    return 0;
                }
                InternalRecipients.Split split = InternalRecipients.split(to, InternalRecipients.domains(jdbc));
                artifact.put("recipientsLeftOut", split.skipped());
                if (split.internal().isEmpty()) {
                    artifact.put("delivery", "NO_INTERNAL_RECIPIENT");
                    return 0;
                }
                ReportService.Download file = reports.fileForDelivery(run.id());
                if (file == null) {
                    artifact.put("delivery", "NOT_EMAILED");        // a credit-bureau file, or no file
                    return 0;
                }
                try {
                    mail.send(split.internal(), "Report " + run.reportCode() + " for " + run.businessDate(),
                            "The scheduled report " + run.reportCode() + " is attached (" + run.rowCount() + " rows).\n"
                                    + "It may contain personal data: keep it within the lender and delete it when no longer needed.",
                            List.of(new Mail.Attachment(file.fileName(), file.contentType(), file.content())));
                    artifact.put("delivery", "SENT");
                    artifact.put("recipientsSent", split.internal().size());
                    return 0;
                } catch (IllegalStateException e) {
                    artifact.put("delivery", "FAILED");
                    artifact.put("deliveryError", e.getMessage());
                    return 1;
                }
            }
        };
    }

    @Bean
    JobHandler dashboardRefreshJob(JdbcTemplate jdbc) {
        return new JobHandler() {
            @Override public String kind() { return "DASHBOARD_REFRESH"; }

            @Override
            public Result run(Context c) {
                String day = jdbc.queryForObject("SELECT reporting.refresh_dashboard_snapshot()::text", String.class);
                return new Result(day == null ? 0 : 1, 0, day == null ? null : Map.of("businessDate", day));
            }
        };
    }

    @Bean
    JobHandler exportCleanupJob(ReportService reports, JdbcTemplate jdbc,
                                @Value("${corebanking.reports.retention-days:90}") int defaultRetentionDays) {
        return new JobHandler() {
            @Override public String kind() { return "EXPORT_CLEANUP"; }

            @Override
            public Result run(Context c) {
                int days = defaultRetentionDays;
                List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = 'reports.retention-days'", String.class);
                if (!v.isEmpty() && v.get(0).trim().matches("[0-9]{1,5}")) days = Integer.parseInt(v.get(0).trim());
                days = Math.max(days, 1);
                int[] r = reports.purge(days);
                return new Result(r[0], r[1], Map.of("retentionDays", days));
            }
        };
    }
}
