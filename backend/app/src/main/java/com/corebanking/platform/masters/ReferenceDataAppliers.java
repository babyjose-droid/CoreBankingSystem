package com.corebanking.platform.masters;

import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.Json;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Applies approved branch-set, staff-scope, territory, enumeration and system-property changes. */
@Configuration(proxyBeanMethods = false)
class ReferenceDataAppliers {

    @Bean
    ApprovalApplier branchSetApplier(JdbcTemplate jdbc) {
        return new ApprovalApplier() {
            @Override public String entityType() { return "BRANCH_SET"; }
            @Override public String apply(ApprovalRequest r) {
                Map<String, Object> p = r.payload();
                String code = String.valueOf(p.get("code"));
                jdbc.update("""
                        INSERT INTO platform.branch_set (code, name) VALUES (?, ?)
                        ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
                        """, code, p.get("name"));
                jdbc.update("DELETE FROM platform.branch_set_member WHERE set_code = ?", code);
                for (String b : strings(p.get("branches"))) {
                    jdbc.update("INSERT INTO platform.branch_set_member (set_code, branch_code) VALUES (?, ?)", code, b);
                }
                return code;
            }
        };
    }

    /** Replaces the user's profile and scope as a whole, so the approved request is exactly what applies. */
    @Bean
    ApprovalApplier staffApplier(JdbcTemplate jdbc) {
        return new ApprovalApplier() {
            @Override public String entityType() { return "STAFF"; }
            @Override public String apply(ApprovalRequest r) {
                Map<String, Object> p = r.payload();
                String userId = String.valueOf(p.get("userId"));
                jdbc.update("""
                        INSERT INTO platform.staff_user (user_id, username, display_name, home_branch, all_branches, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (user_id) DO UPDATE SET username = EXCLUDED.username, display_name = EXCLUDED.display_name,
                            home_branch = EXCLUDED.home_branch, all_branches = EXCLUDED.all_branches, status = EXCLUDED.status
                        """, userId, p.get("username"), p.get("displayName"), p.get("homeBranch"),
                        Boolean.TRUE.equals(p.get("allBranches")), p.getOrDefault("status", "ACTIVE"));
                jdbc.update("DELETE FROM platform.staff_branch_scope WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM platform.staff_branch_set_scope WHERE user_id = ?", userId);
                for (String b : strings(p.get("branches"))) {
                    jdbc.update("INSERT INTO platform.staff_branch_scope (user_id, branch_code) VALUES (?, ?)", userId, b);
                }
                for (String s : strings(p.get("branchSets"))) {
                    jdbc.update("INSERT INTO platform.staff_branch_set_scope (user_id, set_code) VALUES (?, ?)", userId, s);
                }
                return String.valueOf(p.get("username"));
            }
        };
    }

    /** The database function validates every row and loads all or nothing (V13). */
    @Bean
    ApprovalApplier territoryApplier(JdbcTemplate jdbc, Json json) {
        return new ApprovalApplier() {
            @Override public String entityType() { return "TERRITORY"; }
            @Override public String apply(ApprovalRequest r) {
                Integer added = jdbc.queryForObject("SELECT platform.load_territory(?::jsonb)", Integer.class,
                        json.write(r.payload().get("rows")));
                return (added == null ? 0 : added) + " new pincodes";
            }
        };
    }

    @Bean
    ApprovalApplier enumerationApplier(JdbcTemplate jdbc) {
        return new ApprovalApplier() {
            @Override public String entityType() { return "ENUMERATION"; }
            @Override public String apply(ApprovalRequest r) {
                String type = String.valueOf(r.payload().get("type"));
                List<?> values = (List<?>) r.payload().get("values");
                for (Object o : values) {
                    Map<?, ?> v = (Map<?, ?>) o;
                    Object sort = v.get("sortOrder");
                    jdbc.update("""
                            INSERT INTO platform.enumeration (enum_type, code, label, sort_order, active) VALUES (?, ?, ?, ?, ?)
                            ON CONFLICT (enum_type, code) DO UPDATE SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order,
                                active = EXCLUDED.active
                            """, type, v.get("code"), v.get("label"), sort == null ? 0 : ((Number) sort).intValue(),
                            !Boolean.FALSE.equals(v.get("active")));
                }
                return type + ": " + values.size() + " values";
            }
        };
    }

    @Bean
    ApprovalApplier systemPropertyApplier(JdbcTemplate jdbc) {
        return new ApprovalApplier() {
            @Override public String entityType() { return "SYSTEM_PROPERTY"; }
            @Override public String apply(ApprovalRequest r) {
                Map<String, Object> p = r.payload();
                jdbc.update("""
                        INSERT INTO platform.system_property (key, value, description, updated_by, updated_at)
                        VALUES (?, ?, ?, ?, now())
                        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, description = EXCLUDED.description,
                            updated_by = EXCLUDED.updated_by, updated_at = now()
                        """, p.get("key"), p.get("value"), p.get("description"), r.maker());
                return String.valueOf(p.get("key"));
            }
        };
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
}
