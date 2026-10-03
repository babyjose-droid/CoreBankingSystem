package com.corebanking.platform.web;

import com.corebanking.kernel.Csv;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.Json;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1 gap closure: branch sets (US-010), staff branch scope (US-020), holiday upload (US-011), territory
 * upload and lookup (US-012), enumeration and system-property edits (US-013). Every change goes through
 * maker-checker; the appliers are in {@code platform.masters.ReferenceDataAppliers}.
 */
@RestController
@RequestMapping("/api/v1")
class ReferenceDataController {

    /** Uploads are read in memory; 5 MB is ample for a year of holidays or a state's pincodes. */
    static final int MAX_UPLOAD_CHARS = 5_000_000;

    record BranchSet(String code, String name, List<String> branches) {}
    /** userId is the stored key; it is derived by the server (never taken from the request) so an update is always
     *  shown to the checker as an update of the existing profile. */
    record Staff(String userId, String username, String displayName, String homeBranch, Boolean allBranches,
                 List<String> branches, List<String> branchSets, String status) {}
    record EnumValue(String code, String label, Integer sortOrder, Boolean active) {}
    record Property(String key, String value, String description, String updatedBy, String updatedAt) {}
    record PropertyChange(String value, String description) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final Json json;
    private final BranchScope scope;

    ReferenceDataController(JdbcTemplate jdbc, ApprovalService approvals, Json json, BranchScope scope) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.json = json;
        this.scope = scope;
    }

    // ---- branch sets (US-010) -----------------------------------------------------------------
    @GetMapping("/branch-sets")
    @PreAuthorize("hasAuthority('branch:view')")
    List<BranchSet> branchSets() {
        return jdbc.query("""
                SELECT s.code, s.name, coalesce(array_agg(m.branch_code ORDER BY m.branch_code)
                                                FILTER (WHERE m.branch_code IS NOT NULL), '{}')
                  FROM platform.branch_set s LEFT JOIN platform.branch_set_member m ON m.set_code = s.code
                 GROUP BY s.code, s.name ORDER BY s.code
                """, (rs, i) -> new BranchSet(rs.getString(1), rs.getString(2), textArray(rs.getArray(3))));
    }

    @PostMapping("/branch-sets")
    @PreAuthorize("hasAuthority('branch:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeBranchSet(@RequestBody BranchSet s) {
        if (s.code() == null || !s.code().matches("[A-Z0-9_]{2,20}")) throw ApiException.invalid("code must be 2-20 capitals, digits or _");
        if (s.name() == null || s.name().isBlank()) throw ApiException.invalid("name is required");
        List<String> branches = distinct(s.branches());
        if (branches.isEmpty()) throw ApiException.invalid("a branch set needs at least one branch");
        requireBranches(branches);
        List<BranchSet> existing = branchSets().stream().filter(x -> x.code().equals(s.code())).toList();
        BranchSet normalised = new BranchSet(s.code(), s.name().trim(), branches);
        return ApprovalController.view(approvals.propose("BRANCH_SET", existing.isEmpty() ? "CREATE" : "UPDATE", s.code(),
                json.toMap(normalised), existing.isEmpty() ? null : json.toMap(existing.get(0)), null, null, null));
    }

    // ---- staff profiles and branch scope (US-020) ---------------------------------------------
    @GetMapping("/staff")
    @PreAuthorize("hasAuthority('staff:view')")
    List<Staff> staff() {
        return jdbc.query("""
                SELECT u.user_id, u.username, u.display_name, u.home_branch, u.all_branches, u.status,
                       coalesce((SELECT array_agg(branch_code ORDER BY branch_code) FROM platform.staff_branch_scope s
                                  WHERE s.user_id = u.user_id), '{}'),
                       coalesce((SELECT array_agg(set_code ORDER BY set_code) FROM platform.staff_branch_set_scope s
                                  WHERE s.user_id = u.user_id), '{}')
                  FROM platform.staff_user u ORDER BY u.username
                """, (rs, i) -> new Staff(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), textArray(rs.getArray(7)), textArray(rs.getArray(8)), rs.getString(6)));
    }

    @PostMapping("/staff")
    @PreAuthorize("hasAuthority('staff:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeStaff(@RequestBody Staff s) {
        if (s.username() == null || !s.username().matches("[A-Za-z0-9._@-]{2,80}")) throw ApiException.invalid("username is required");
        if (s.displayName() == null || s.displayName().isBlank()) throw ApiException.invalid("displayName is required");
        if (s.homeBranch() == null) throw ApiException.invalid("homeBranch is required");
        String status = s.status() == null ? "ACTIVE" : s.status();
        if (!status.matches("ACTIVE|SUSPENDED|EXITED")) throw ApiException.invalid("status must be ACTIVE, SUSPENDED or EXITED");
        List<String> branches = distinct(s.branches());
        List<String> sets = distinct(s.branchSets());
        List<String> check = new ArrayList<>(branches);
        check.add(s.homeBranch());
        requireBranches(check);
        // A maker can grant only what they can see themselves (US-020).
        if (Boolean.TRUE.equals(s.allBranches())) scope.requireAll();
        for (String b : check) scope.require(b);
        for (String set : sets) {
            for (String b : jdbc.queryForList("SELECT branch_code FROM platform.branch_set_member WHERE set_code = ?", String.class, set)) {
                scope.require(b);
            }
        }
        for (String set : sets) {
            if (jdbc.queryForList("SELECT 1 FROM platform.branch_set WHERE code = ?", set).isEmpty()) {
                throw ApiException.invalid("branch set " + set + " does not exist");
            }
        }
        List<Staff> existing = staff().stream().filter(x -> x.username().equalsIgnoreCase(s.username())).toList();
        String userId = existing.isEmpty() ? s.username().toLowerCase() : existing.get(0).userId();
        Staff normalised = new Staff(userId, s.username().toLowerCase(), s.displayName().trim(), s.homeBranch(),
                Boolean.TRUE.equals(s.allBranches()), branches, sets, status);
        return ApprovalController.view(approvals.propose("STAFF", existing.isEmpty() ? "CREATE" : "UPDATE", normalised.username(),
                json.toMap(normalised), existing.isEmpty() ? null : json.toMap(existing.get(0)), null, s.homeBranch(), null));
    }

    // ---- holiday upload (US-011) --------------------------------------------------------------
    /** CSV with columns {@code day,reason} and optionally {@code branchCode} (blank = every branch). */
    @PostMapping(path = "/holidays/upload", consumes = {"text/csv", "text/plain"})
    @PreAuthorize("hasAuthority('holiday:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> uploadHolidays(@RequestBody String csv) {
        Csv.Table t = parse(csv, List.of("day", "reason"), 366);
        List<Map<String, Object>> holidays = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Csv.Row r : t.rows()) {
            String branch = r.get("branchCode");
            LocalDate day;
            try {
                day = LocalDate.parse(r.get("day") == null ? "" : r.get("day"));
            } catch (DateTimeParseException e) {
                throw ApiException.invalid("line " + r.line() + ": day must be YYYY-MM-DD");
            }
            if (r.get("reason") == null) throw ApiException.invalid("line " + r.line() + ": reason is required");
            if (branch != null && jdbc.queryForList("SELECT 1 FROM platform.branch WHERE code = ?", branch).isEmpty()) {
                throw ApiException.invalid("line " + r.line() + ": branch " + branch + " does not exist");
            }
            if (branch == null) scope.requireAll(); else scope.require(branch);
            if (!seen.add(branch + "|" + day)) throw ApiException.invalid("line " + r.line() + ": " + day + " appears twice");
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("branchCode", branch);
            h.put("day", day.toString());
            h.put("reason", r.get("reason"));
            holidays.add(h);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("holidays", holidays);
        payload.put("source", "upload");
        return ApprovalController.view(approvals.propose("HOLIDAY", "CREATE", null, payload, null, null, null, null));
    }

    // ---- territory (US-012) -------------------------------------------------------------------
    @GetMapping("/states")
    List<Map<String, Object>> states() {
        return jdbc.queryForList("""
                SELECT code, name, gst_state_code AS "gstStateCode", is_union_territory AS "unionTerritory"
                  FROM platform.state ORDER BY name
                """);
    }

    @GetMapping("/pincodes/{pincode}")
    List<Map<String, Object>> pincode(@PathVariable String pincode) {
        if (!pincode.matches("[1-9][0-9]{5}")) throw ApiException.invalid("a pincode is six digits and does not start with 0");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT pincode, city, district, state_code AS "stateCode", state_name AS "stateName",
                       gst_state_code AS "gstStateCode"
                  FROM platform.pincode_lookup WHERE pincode = ? ORDER BY city
                """, pincode);
        if (rows.isEmpty()) throw ApiException.notFound("pincode " + pincode);
        return rows;
    }

    /** CSV with columns {@code state,district,city,pincode}; state is the state code (KL) or GST code (32). */
    @PostMapping(path = "/territory/upload", consumes = {"text/csv", "text/plain"})
    @PreAuthorize("hasAuthority('master:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> uploadTerritory(@RequestBody String csv) {
        Csv.Table t = parse(csv, List.of("state", "district", "city", "pincode"), 20_000);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Csv.Row r : t.rows()) {
            String pin = r.get("pincode");
            if (pin == null || !pin.matches("[1-9][0-9]{5}")) throw ApiException.invalid("line " + r.line() + ": pincode must be six digits");
            if (r.get("state") == null || r.get("district") == null || r.get("city") == null) {
                throw ApiException.invalid("line " + r.line() + ": state, district and city are required");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", r.get("state"));
            m.put("district", r.get("district"));
            m.put("city", r.get("city"));
            m.put("pincode", pin);
            rows.add(m);
        }
        Set<String> states = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) states.add(String.valueOf(r.get("state")));
        for (String s : states) {
            if (jdbc.queryForList("SELECT 1 FROM platform.state WHERE code = upper(?) OR gst_state_code = lpad(?, 2, '0')", s, s).isEmpty()) {
                throw ApiException.invalid("unknown state '" + s + "' (use the state code, e.g. KL, or GST code, e.g. 32)");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rows", rows);
        payload.put("rowCount", rows.size());
        return ApprovalController.view(approvals.propose("TERRITORY", "CREATE", null, payload, null, null, null, null));
    }

    // ---- enumerations and system properties (US-013) ------------------------------------------
    @GetMapping("/enumerations")
    List<Map<String, Object>> enumerationTypes() {
        return jdbc.queryForList("""
                SELECT enum_type AS type, count(*) AS "valueCount", count(*) FILTER (WHERE active) AS "activeCount"
                  FROM platform.enumeration GROUP BY enum_type ORDER BY enum_type
                """);
    }

    /** Adds or changes values of one type. Values are never deleted (records may refer to them); deactivate instead. */
    @PostMapping("/enumerations/{type}")
    @PreAuthorize("hasAuthority('master:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeEnumeration(@PathVariable String type, @RequestBody List<EnumValue> values) {
        if (!type.matches("[a-z][a-z0-9-]{1,40}")) throw ApiException.invalid("type must be lower-case letters, digits or -, e.g. customer-type");
        if (values == null || values.isEmpty() || values.size() > 500) throw ApiException.invalid("send 1 to 500 values");
        Set<String> codes = new LinkedHashSet<>();
        List<EnumValue> normalised = new ArrayList<>();
        for (EnumValue v : values) {
            if (v.code() == null || !v.code().matches("[A-Z0-9_]{1,40}")) throw ApiException.invalid("code '" + v.code() + "' must be capitals, digits or _");
            if (v.label() == null || v.label().isBlank()) throw ApiException.invalid("label is required for " + v.code());
            if (!codes.add(v.code())) throw ApiException.invalid(v.code() + " appears twice");
            normalised.add(new EnumValue(v.code(), v.label().trim(), v.sortOrder() == null ? 0 : v.sortOrder(), !Boolean.FALSE.equals(v.active())));
        }
        List<Map<String, Object>> current = jdbc.queryForList("""
                SELECT code, label, sort_order AS "sortOrder", active FROM platform.enumeration WHERE enum_type = ? ORDER BY code
                """, type);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("values", normalised.stream().map(json::toMap).toList());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("type", type);
        before.put("values", current);
        return ApprovalController.view(approvals.propose("ENUMERATION", current.isEmpty() ? "CREATE" : "UPDATE", type, payload,
                current.isEmpty() ? null : before, null, null, null));
    }

    @GetMapping("/system-properties")
    @PreAuthorize("hasAuthority('master:view')")
    List<Property> properties() {
        return jdbc.query("SELECT key, value, description, updated_by, to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') "
                        + "FROM platform.system_property ORDER BY key",
                (rs, i) -> new Property(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    @PutMapping("/system-properties/{key}")
    @PreAuthorize("hasAuthority('master:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeProperty(@PathVariable String key, @RequestBody PropertyChange c) {
        if (!key.matches("[a-z][a-z0-9_.-]+")) throw ApiException.invalid("key must be lower-case, e.g. ledger.suspense-gl");
        if (c.value() == null || c.value().isBlank()) throw ApiException.invalid("value is required");
        if (key.endsWith("-gl") && jdbc.queryForList(
                "SELECT 1 FROM ledger.gl_head WHERE code = ? AND is_posting AND status = 'ACTIVE'", c.value().trim()).isEmpty()) {
            throw ApiException.invalid("GL head " + c.value().trim() + " is not an active posting head");
        }
        List<Property> existing = properties().stream().filter(p -> p.key().equals(key)).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("value", c.value().trim());
        payload.put("description", c.description() != null ? c.description()
                : existing.isEmpty() ? null : existing.get(0).description());
        Map<String, Object> before = null;
        if (!existing.isEmpty()) {
            before = new LinkedHashMap<>();
            before.put("key", key);
            before.put("value", existing.get(0).value());
            before.put("description", existing.get(0).description());
        }
        return ApprovalController.view(approvals.propose("SYSTEM_PROPERTY", existing.isEmpty() ? "CREATE" : "UPDATE", key,
                payload, before, null, null, null));
    }

    // ---- helpers ----------------------------------------------------------------------------------
    static Csv.Table parse(String csv, List<String> required, int maxRows) {
        if (csv != null && csv.length() > MAX_UPLOAD_CHARS) throw ApiException.invalid("the file is larger than 5 MB");
        try {
            return Csv.parse(csv, required, maxRows);
        } catch (Csv.CsvException e) {
            throw ApiException.invalid(e.getMessage());
        }
    }

    private void requireBranches(List<String> branches) {
        for (String b : branches) {
            if (jdbc.queryForList("SELECT 1 FROM platform.branch WHERE code = ? AND status = 'ACTIVE'", b).isEmpty()) {
                throw ApiException.invalid("branch " + b + " is not an active branch");
            }
        }
    }

    private static List<String> distinct(List<String> list) {
        if (list == null) return List.of();
        return List.copyOf(new LinkedHashSet<>(list.stream().filter(x -> x != null && !x.isBlank()).map(String::trim).toList()));
    }

    private static List<String> textArray(java.sql.Array a) throws java.sql.SQLException {
        if (a == null) return List.of();
        Object[] values = (Object[]) a.getArray();
        List<String> out = new ArrayList<>(values.length);
        for (Object v : values) out.add(String.valueOf(v));
        return out;
    }
}
