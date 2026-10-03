package com.corebanking.integration.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Tenant properties of the integrations (platform.system_property; defaults and ranges are listed in V20). */
@Component
class TenantProps {

    private final JdbcTemplate jdbc;

    TenantProps(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String text(String key, String fallback) {
        return jdbc.queryForObject("SELECT integration.property(?, ?)", String.class, key, fallback);
    }

    /** A whole number within the range; anything else stored under the key gives the default. */
    int number(String key, int fallback, int min, int max) {
        Integer v = jdbc.queryForObject("SELECT integration.int_property(?, ?, ?, ?)", Integer.class, key, fallback, min, max);
        return v == null ? fallback : v;
    }
}
