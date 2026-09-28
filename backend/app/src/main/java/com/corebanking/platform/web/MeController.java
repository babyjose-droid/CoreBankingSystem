package com.corebanking.platform.web;

import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.tenancy.TenantDirectory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

    private final BusinessDays days;
    private final TenantDirectory directory;
    private final JdbcTemplate jdbc;

    MeController(BusinessDays days, TenantDirectory directory, JdbcTemplate jdbc) {
        this.days = days;
        this.directory = directory;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/me")
    Map<String, Object> me() {
        CurrentUser u = CurrentUser.get();
        List<String> branch = jdbc.queryForList(
                "SELECT home_branch FROM platform.staff_user WHERE user_id = ? OR username = ? ORDER BY (user_id = ?) DESC LIMIT 1",
                String.class, u.subject(), u.login(), u.subject());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", u.login());
        m.put("displayName", u.displayName());
        m.put("tenant", u.tenant());
        m.put("tenantName", directory.legalName(u.tenant()));
        m.put("homeBranch", branch.isEmpty() ? null : branch.get(0));
        m.put("businessDate", days.current() == null ? null : days.current().businessDate());
        m.put("permissions", u.permissions().stream().sorted().toList());
        m.put("modules", directory.modules(u.tenant()).stream().sorted().toList());
        return m;
    }

    @GetMapping("/api/v1/business-day")
    BusinessDays.BusinessDay businessDay() {
        return days.current();
    }
}
