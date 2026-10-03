package com.corebanking.lending.web;

import com.corebanking.lending.internal.BenchmarkService;
import com.corebanking.platform.ApprovalView;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Benchmarks of floating-rate products and their rates. Module: LENDING. Changes go through maker-checker. */
@RestController
@RequestMapping("/api/v1/benchmarks")
class BenchmarkController {

    private final BenchmarkService benchmarks;

    BenchmarkController(BenchmarkService benchmarks) {
        this.benchmarks = benchmarks;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('benchmark:view')")
    List<Map<String, Object>> list() {
        return benchmarks.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('benchmark:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> propose(@RequestBody BenchmarkService.BenchmarkInput in) {
        return ApprovalView.of(benchmarks.propose(in));
    }

    @PostMapping("/{code}/rates")
    @PreAuthorize("hasAuthority('benchmark:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeRate(@PathVariable String code, @RequestBody BenchmarkService.RateInput in) {
        return ApprovalView.of(benchmarks.proposeRate(code, in));
    }
}
