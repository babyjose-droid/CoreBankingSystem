package com.corebanking.reporting.web;

import com.corebanking.reporting.internal.ReportService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Report catalogue and runs (US-113, US-114). Module: REPORTING. The calls are open to {@code report:run} or
 * {@code bureau:export}; the service then checks the permission of the report itself, so the bureau file is
 * reachable only with {@code bureau:export} and the other reports only with {@code report:run}.
 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportController {

    /** openapi.yaml#/components/schemas/ReportRunRequest. */
    record RunRequest(Map<String, Object> parameters) {}

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('report:run') or hasAuthority('bureau:export')")
    List<ReportService.Definition> catalogue() {
        return reports.catalogue();
    }

    /** Runs the report now (synchronously) and stores its file; fetch the file with the download call. */
    @PostMapping("/{code}/runs")
    @PreAuthorize("hasAuthority('report:run') or hasAuthority('bureau:export')")
    @ResponseStatus(HttpStatus.CREATED)
    ReportService.Run run(@PathVariable String code, @RequestBody(required = false) RunRequest request) {
        return reports.run(code, request == null ? null : request.parameters());
    }

    /** The caller's runs; every user's runs with report:admin. */
    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('report:run') or hasAuthority('bureau:export') or hasAuthority('report:admin')")
    List<ReportService.Run> runs(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reports.runs(page, size);
    }

    @GetMapping("/runs/{id}/download")
    @PreAuthorize("hasAuthority('report:run') or hasAuthority('bureau:export')")
    ResponseEntity<byte[]> download(@PathVariable UUID id, @RequestParam(required = false) String part) {
        ReportService.Download d = reports.download(id, part);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(d.contentType()))
                .contentLength(d.content().length)
                .body(d.content());
    }
}
