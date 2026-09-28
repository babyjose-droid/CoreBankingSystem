package com.corebanking.ledger;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Tenant ledger settings from platform.system_property (set by the starter kit). */
@Component
public class LedgerSettings {

    private final JdbcTemplate jdbc;

    public LedgerSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String interBranchGl() {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = 'ledger.inter-branch-gl'", String.class);
        if (v.isEmpty()) throw new IllegalStateException("ledger.inter-branch-gl is not configured; load the starter kit");
        return v.get(0);
    }
}
