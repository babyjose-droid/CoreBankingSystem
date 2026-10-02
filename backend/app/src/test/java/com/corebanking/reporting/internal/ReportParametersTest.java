package com.corebanking.reporting.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.platform.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A report run's parameters are checked against the catalogue's schema before anything reaches the database. */
class ReportParametersTest {

    static Map<String, Object> schema(boolean required) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("from", Map.of("type", "string", "format", "date"));
        props.put("to", Map.of("type", "string", "format", "date"));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        if (required) s.put("required", List.of("from", "to"));
        return s;
    }

    @Test
    void dates_are_accepted_and_normalised() {
        assertEquals(Map.of("from", "2026-07-01", "to", "2026-09-10"),
                ReportService.parameters(schema(true), Map.of("from", " 2026-07-01 ", "to", "2026-09-10")));
    }

    @Test
    void optional_parameters_may_be_left_out() {
        assertTrue(ReportService.parameters(schema(false), null).isEmpty());
        assertTrue(ReportService.parameters(schema(false), Map.of("from", "")).isEmpty());
        assertTrue(ReportService.parameters(Map.of("type", "object", "properties", Map.of()), Map.of()).isEmpty());
        assertTrue(ReportService.parameters(null, null).isEmpty());
    }

    @Test
    void bad_requests_are_refused() {
        assertEquals("parameter 'to' is required",
                assertThrows(ApiException.class, () -> ReportService.parameters(schema(true), Map.of("from", "2026-07-01"))).getMessage());
        assertEquals("parameter 'from' must be a date as YYYY-MM-DD",
                assertThrows(ApiException.class, () -> ReportService.parameters(schema(true), Map.of("from", "01/07/2026", "to", "2026-09-10"))).getMessage());
        assertEquals("this report has no parameter 'branch'",
                assertThrows(ApiException.class, () -> ReportService.parameters(schema(false), Map.of("branch", "HO"))).getMessage());
        assertEquals("'to' cannot be before 'from'",
                assertThrows(ApiException.class, () -> ReportService.parameters(schema(true), Map.of("from", "2026-09-10", "to", "2026-07-01"))).getMessage());
        assertEquals("parameter 'from' must be a single value",
                assertThrows(ApiException.class, () -> ReportService.parameters(schema(false), Map.of("from", List.of("2026-07-01")))).getMessage());
        // SQL in a parameter is just a string that is not a date
        assertThrows(ApiException.class, () -> ReportService.parameters(schema(false), Map.of("from", "2026-07-01'; DROP TABLE x; --")));
    }
}
