package com.corebanking.platform;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The tenant's business date (US-016). Read-only here; only end of day moves it. */
@Service
public class BusinessDays {

    public record BusinessDay(LocalDate businessDate, String status) {}

    private final JdbcTemplate jdbc;

    public BusinessDays(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BusinessDay current() {
        return jdbc.query("SELECT business_date, status FROM platform.business_day WHERE id = 1",
                rs -> rs.next() ? new BusinessDay(rs.getObject(1, LocalDate.class), rs.getString(2)) : null);
    }

    /** The open business date, or 409 when end of day is running or failed. */
    public LocalDate requireOpen() {
        BusinessDay d = current();
        if (d == null) throw ApiException.conflict("business date not initialised");
        if (!"OPEN".equals(d.status())) throw ApiException.conflict("business day is " + d.status() + "; try after end of day");
        return d.businessDate();
    }
}
