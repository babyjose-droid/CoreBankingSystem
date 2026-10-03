package com.corebanking.platform.web;

import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.jobs.JobService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tenant job catalogue (US-112): list, run now, schedule (maker-checker) and the run history. */
@RestController
@RequestMapping("/api/v1/jobs")
class JobController {

    private final JobService jobs;

    JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('job:view')")
    List<Map<String, Object>> list() {
        return jobs.list(CurrentUser.requireTenant());
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('job:view')")
    List<Map<String, Object>> runs(@RequestParam(required = false) String job, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return jobs.runs(CurrentUser.requireTenant(), job, page, size);
    }

    /** Runs the job now and answers when it has finished, with the run. 409 when the job is already running. */
    @PostMapping("/{code}/run")
    @PreAuthorize("hasAuthority('job:run')")
    Map<String, Object> run(@PathVariable String code) {
        return jobs.runNow(CurrentUser.requireTenant(), code);
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAuthority('job:schedule')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> schedule(@PathVariable String code, @RequestBody JobService.ScheduleInput input) {
        return ApprovalController.view(jobs.proposeSchedule(CurrentUser.requireTenant(), code, input));
    }
}
