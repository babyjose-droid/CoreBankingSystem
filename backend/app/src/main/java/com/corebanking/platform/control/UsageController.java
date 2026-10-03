package com.corebanking.platform.control;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Usage metering for the platform operator (US-004): daily figures, monthly figures and the monthly CSV for billing. */
@RestController
@RequestMapping("/platform/v1/usage")
@PreAuthorize("hasAuthority('platform:operator')")
class UsageController {

    private final UsageService usage;

    UsageController(UsageService usage) {
        this.usage = usage;
    }

    /** Daily figures; without dates, the last 31 days. */
    @GetMapping
    List<Map<String, Object>> daily(@RequestParam(required = false) String tenant, @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to) {
        return usage.daily(tenant, from, to);
    }

    @GetMapping("/monthly")
    List<Map<String, Object>> monthly(@RequestParam(required = false) String month, @RequestParam(required = false) String tenant) {
        return usage.monthlyJson(month, tenant);
    }

    @GetMapping("/monthly.csv")
    ResponseEntity<byte[]> monthlyCsv(@RequestParam(required = false) String month, @RequestParam(required = false) String tenant) {
        byte[] body = usage.monthlyCsv(month, tenant).getBytes(StandardCharsets.UTF_8);
        String name = "usage-" + (month == null || month.isBlank() ? "last-month" : month.replaceAll("[^0-9-]", "")) + ".csv";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(body.length)
                .body(body);
    }

    /** Takes the snapshot now for one tenant, or for every tenant when none is named. */
    @PostMapping("/snapshot")
    List<Map<String, Object>> snapshot(@RequestParam(required = false) String tenant) {
        return usage.snapshotNow(tenant);
    }
}
