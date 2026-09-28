package com.corebanking.eod.web;

import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.ApprovalView;
import com.corebanking.platform.CurrentUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/eod")
class EodController {

    record Schedule(String mode, String cron, List<String> alertEmails) {}

    private final EodService eod;
    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;

    EodController(EodService eod, JdbcTemplate jdbc, ApprovalService approvals) {
        this.eod = eod;
        this.jdbc = jdbc;
        this.approvals = approvals;
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('eod:view')")
    List<Map<String, Object>> runs() {
        return eod.runs(CurrentUser.requireTenant());
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAuthority('eod:view')")
    Map<String, Object> run(@PathVariable long runId) {
        return eod.run(CurrentUser.requireTenant(), runId);
    }

    @PostMapping("/runs")
    @PreAuthorize("hasAuthority('eod:run')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> start() {
        return eod.start(CurrentUser.requireTenant(), CurrentUser.username());
    }

    @PostMapping("/runs/{runId}/restart")
    @PreAuthorize("hasAuthority('eod:run')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> restart(@PathVariable long runId) {
        return eod.restart(CurrentUser.requireTenant(), runId, CurrentUser.username());
    }

    @GetMapping("/schedule")
    @PreAuthorize("hasAuthority('eod:view')")
    Schedule schedule() {
        return jdbc.queryForObject("SELECT mode, cron, array_to_string(alert_emails, ',') FROM platform.eod_schedule WHERE id = 1",
                (rs, i) -> new Schedule(rs.getString(1), rs.getString(2),
                        rs.getString(3) == null || rs.getString(3).isEmpty() ? List.of() : List.of(rs.getString(3).split(","))));
    }

    @PutMapping("/schedule")
    @PreAuthorize("hasAuthority('eod:schedule')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeSchedule(@RequestBody Schedule s) {
        if (!"MANUAL".equals(s.mode()) && !"SCHEDULED".equals(s.mode())) throw ApiException.invalid("mode must be MANUAL or SCHEDULED");
        if ("SCHEDULED".equals(s.mode()) && (s.cron() == null || !CronExpression.isValidExpression(s.cron()))) {
            throw ApiException.invalid("a valid 6-field cron is required, e.g. '0 30 23 * * *'");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", s.mode());
        payload.put("cron", s.cron());
        payload.put("alertEmails", s.alertEmails() == null ? List.of() : s.alertEmails());
        Schedule current = schedule();
        Map<String, Object> cur = new LinkedHashMap<>();
        cur.put("mode", current.mode());
        cur.put("cron", current.cron());
        cur.put("alertEmails", current.alertEmails());
        return ApprovalView.of(approvals.propose("EOD_SCHEDULE", "UPDATE", "1", payload, cur, null, null, null));
    }

    @Component
    static class ScheduleApplier implements ApprovalApplier {
        private final JdbcTemplate jdbc;

        ScheduleApplier(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override public String entityType() { return "EOD_SCHEDULE"; }

        @Override
        public String apply(ApprovalRequest r) {
            List<?> emails = (List<?>) r.payload().getOrDefault("alertEmails", List.of());
            jdbc.update("UPDATE platform.eod_schedule SET mode = ?, cron = ?, alert_emails = string_to_array(?, ',') WHERE id = 1",
                    r.payload().get("mode"), r.payload().get("cron"),
                    String.join(",", emails.stream().map(String::valueOf).toList()));
            return "schedule";
        }
    }
}
