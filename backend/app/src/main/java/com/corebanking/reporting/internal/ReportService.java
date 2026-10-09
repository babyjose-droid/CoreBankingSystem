package com.corebanking.reporting.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.customer.PiiKeys;
import com.corebanking.kernel.Csv;
import com.corebanking.kernel.DocumentKey;
import com.corebanking.kernel.PiiCipher;
import com.corebanking.kernel.UcrfConsumerFile;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.DocumentStore;
import com.corebanking.platform.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

/**
 * Report catalogue and runs (US-113) and the consumer-bureau export (US-114).
 *
 * <ul>
 *   <li>A report is a row of {@code reporting.report_definition}: a SQL function
 *       {@code reporting.<name>(user, parameters)} that returns the rows, already limited to the branches the user
 *       may see, plus the permission needed to run it. Nothing here builds SQL from request input: the function name
 *       comes from the catalogue (set by migrations) and is checked again before use.</li>
 *   <li>A run is synchronous for now: the rows are written as CSV (cells neutralised against spreadsheet formulas by
 *       {@link Csv}), stored in the {@link DocumentStore} (a directory today; the S3 store is pending) under {@code tenants/<code>/reports/…}, and recorded in
 *       {@code reporting.report_run}. The file is fetched afterwards with the download call; it is never part of a
 *       list. Scheduling and e-mail delivery are not built yet.</li>
 *   <li>The bureau file needs {@code bureau:export} and all-branch access. It is the only report whose personal data
 *       is decrypted, and that happens only here, for the file. Every run and every download is audited.</li>
 * </ul>
 */
@Service
public class ReportService {

    /** openapi.yaml#/components/schemas/ReportDefinition. */
    public record Definition(String code, String name, String description, Map<String, Object> parameters, String permission,
                             String outputFormat, boolean containsPersonalData, boolean allBranchesOnly, String schedule) {}

    /** openapi.yaml#/components/schemas/ReportRun. The artifact's storage key is never exposed. */
    public record Run(UUID id, String reportCode, String requestedBy, String requestedAt, LocalDate businessDate,
                      Map<String, Object> parameters, String status, Integer rowCount, int rejectedCount, String fileName,
                      String contentType, Long bytes, String error, String finishedAt) {}

    public record Download(String fileName, String contentType, byte[] content) {}

    private record Def(Definition api, String sqlFunction) {}
    private record Built(byte[] content, int rows, byte[] rejections, int rejected) {}
    private record Stored(String key, String sha256, String contentType, String reportCode, String status, String requestedBy,
                          String fileName, int rejected) {}

    static final int MAX_ROWS = 500_000;
    private static final String CSV_TYPE = "text/csv; charset=UTF-8";
    private static final String TEXT_TYPE = "text/plain; charset=UTF-8";
    private static final String UTC = "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'";

    private final JdbcTemplate jdbc;
    private final Json json;
    private final AuditLog audit;
    private final BusinessDays days;
    private final BranchScope scope;
    private final DocumentStore store;
    private final PiiKeys keys;

    public ReportService(JdbcTemplate jdbc, Json json, AuditLog audit, BusinessDays days, BranchScope scope, DocumentStore store,
                         PiiKeys keys) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.days = days;
        this.scope = scope;
        this.store = store;
        this.keys = keys;
    }

    // ------------------------------------------------------------------------------------------------ catalogue
    /** The reports the caller may run (those whose permission they hold). */
    public List<Definition> catalogue() {
        CurrentUser user = CurrentUser.get();
        return definitions(null).stream().map(Def::api).filter(d -> user.has(d.permission())).toList();
    }

    private List<Def> definitions(String code) {
        return jdbc.query("""
                SELECT code, name, description, parameters::text, permission, output_format, contains_pii, all_branches_only, schedule,
                       sql_function
                  FROM reporting.report_definition
                 WHERE active AND (?::text IS NULL OR code = ?)
                 ORDER BY sort_order, code
                """, (rs, i) -> new Def(new Definition(rs.getString(1), rs.getString(2), rs.getString(3), json.readMap(rs.getString(4)),
                        rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8), rs.getString(9)), rs.getString(10)),
                code, code);
    }

    private Def definition(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,40}")) throw ApiException.notFound("report " + code);
        List<Def> l = definitions(code);
        if (l.isEmpty()) throw ApiException.notFound("report " + code);
        return l.get(0);
    }

    // ------------------------------------------------------------------------------------------------ run
    /** Runs a report now and stores its file. Returns the run; a run that fails is returned with status FAILED. */
    public Run run(String code, Map<String, Object> requested) {
        Def def = definition(code);
        CurrentUser user = CurrentUser.get();
        requirePermission(def.api(), user);
        if (def.api().allBranchesOnly()) scope.requireAll();
        return execute(def, requested, user.login(), null);
    }

    /**
     * Runs a report for its schedule (US-113), with the branch scope of the staff user who scheduled it. That user
     * held the report's permission when the schedule was proposed and a checker approved it; the run is recorded
     * under their name, so they can download the file. E-mail delivery: ReportJobs (internal recipients only).
     *
     * @param schedule the cron expression that fired, kept on the run
     */
    public Run runScheduled(String code, Map<String, Object> requested, String asUser, String schedule) {
        Def def = definition(code);
        if (asUser == null || asUser.isBlank()) throw ApiException.conflict("the schedule of report " + code + " names no user to run as");
        if (def.api().allBranchesOnly()
                && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT platform.sees_all_branches(?)", Boolean.class, asUser))) {
            throw ApiException.conflict("report " + code + " covers every branch and " + asUser + " no longer has all-branch access");
        }
        return execute(def, requested, asUser, schedule == null ? "manual run of the schedule" : schedule);
    }

    /** For a schedule proposal: the maker must be allowed to run the report, and the parameters must be valid. */
    public Map<String, String> checkSchedulable(String code, Map<String, Object> requested) {
        Def def = definition(code);
        requirePermission(def.api(), CurrentUser.get());
        if (def.api().allBranchesOnly()) scope.requireAll();
        return parameters(def.api().parameters(), requested);
    }

    /** The names of the report's parameters. */
    public java.util.Set<String> parameterNames(String code) {
        Map<String, Object> schema = definition(code).api().parameters();
        return schema != null && schema.get("properties") instanceof Map<?, ?> p
                ? p.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet()) : java.util.Set.of();
    }

    private static void requirePermission(Definition d, CurrentUser user) {
        if (!user.has(d.permission())) throw ApiException.forbidden("report " + d.code() + " needs the permission " + d.permission());
        if ("UCRF".equals(d.outputFormat()) && !user.has("bureau:export")) {
            throw ApiException.forbidden("the bureau file needs the permission bureau:export");
        }
    }

    /** @param login the staff user whose branch scope applies and who owns the run */
    private Run execute(Def def, Map<String, Object> requested, String login, String schedule) {
        Definition d = def.api();
        String code = d.code();
        boolean bureau = "UCRF".equals(d.outputFormat());
        if (!def.sqlFunction().matches("reporting\\.[a-z][a-z0-9_]*")) throw new IllegalStateException("bad report function for " + code);
        Map<String, String> params = parameters(d.parameters(), requested);
        UcrfConsumerFile.Header header = bureau ? bureauHeader(params) : null;

        String tenant = CurrentUser.requireTenant();
        LocalDate businessDate = days.current().businessDate();
        UUID id = UUID.randomUUID();
        String paramJson = json.write(params);
        jdbc.update("""
                INSERT INTO reporting.report_run (id, report_code, requested_by, business_date, parameters, status, schedule)
                VALUES (?, ?, ?, ?, ?::jsonb, 'RUNNING', ?)
                """, id, code, login, businessDate, paramJson, schedule);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", id.toString());
        detail.put("parameters", params);
        if (schedule != null) detail.put("scheduledFor", login);
        audit.record(schedule == null ? login : "scheduler", bureau ? "BUREAU_EXPORT" : "REPORT_RUN", "REPORT", code, detail);

        try {
            if (def.sqlFunction().startsWith("reporting.rpt_gst_")) {
                jdbc.queryForObject("SELECT lending.issue_fee_invoices(NULL)", Integer.class);   // invoices not yet issued
            }
            Built built = bureau ? bureauFile(def, login, paramJson, header, tenant) : csv(def, login, paramJson);
            String ext = bureau ? ".txt" : ".csv";
            String fileName = DocumentKey.safeFileName(code.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + businessDate + "-" + id.toString().substring(0, 8) + ext);
            String key = DocumentKey.forTenant(tenant, "reports", businessDate.toString(), code + "-" + id + ext);
            String contentType = bureau ? TEXT_TYPE : CSV_TYPE;
            store.put(key, built.content(), contentType);
            if (built.rejections() != null) store.put(key + ".rejected.csv", built.rejections(), CSV_TYPE);
            jdbc.update("""
                    UPDATE reporting.report_run
                       SET status = 'COMPLETED', row_count = ?, rejected_count = ?, artifact_key = ?, artifact_bytes = ?, artifact_sha256 = ?,
                           file_name = ?, content_type = ?, finished_at = now()
                     WHERE id = ?
                    """, built.rows(), built.rejected(), key, (long) built.content().length, sha256(built.content()), fileName, contentType, id);
        } catch (RuntimeException e) {
            jdbc.update("UPDATE reporting.report_run SET status = 'FAILED', error = ?, finished_at = now() WHERE id = ? AND status = 'RUNNING'",
                    failure(e), id);
        }
        return runs("r.id = ?", id).get(0);
    }

    /** Checks the request against the report's parameter schema and returns the accepted values as text. */
    static Map<String, String> parameters(Map<String, Object> schema, Map<String, Object> requested) {
        Map<?, ?> properties = schema != null && schema.get("properties") instanceof Map<?, ?> p ? p : Map.of();
        List<?> required = schema != null && schema.get("required") instanceof List<?> r ? r : List.of();
        Map<String, Object> given = requested == null ? Map.of() : requested;
        for (String name : given.keySet()) {
            if (!properties.containsKey(name)) throw ApiException.invalid("this report has no parameter '" + name + "'");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : properties.entrySet()) {
            String name = String.valueOf(e.getKey());
            Object raw = given.get(name);
            String value = raw == null ? "" : String.valueOf(raw).trim();
            if (value.isEmpty()) {
                if (required.contains(name)) throw ApiException.invalid("parameter '" + name + "' is required");
                continue;
            }
            if (!(raw instanceof String) && !(raw instanceof Number)) throw ApiException.invalid("parameter '" + name + "' must be a single value");
            Map<?, ?> spec = e.getValue() instanceof Map<?, ?> m ? m : Map.of();
            if ("date".equals(spec.get("format"))) {
                try {
                    value = LocalDate.parse(value).toString();
                } catch (DateTimeParseException x) {
                    throw ApiException.invalid("parameter '" + name + "' must be a date as YYYY-MM-DD");
                }
            } else if (value.length() > 100) {
                throw ApiException.invalid("parameter '" + name + "' is too long");
            }
            out.put(name, value);
        }
        if (out.containsKey("from") && out.containsKey("to") && out.get("to").compareTo(out.get("from")) < 0) {
            throw ApiException.invalid("'to' cannot be before 'from'");
        }
        return out;
    }

    private Built csv(Def def, String user, String paramJson) {
        ResultSetExtractor<Built> toCsv = rs -> {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            StringBuilder sb = new StringBuilder();
            List<String> cells = new ArrayList<>(cols);
            for (int c = 1; c <= cols; c++) cells.add(md.getColumnLabel(c));
            sb.append(Csv.line(cells));
            int rows = 0;
            while (rs.next()) {
                if (++rows > MAX_ROWS) throw ApiException.conflict("the report has more than " + MAX_ROWS + " rows; narrow the period");
                cells.clear();
                for (int c = 1; c <= cols; c++) cells.add(rs.getString(c));
                sb.append(Csv.line(cells));
            }
            return new Built(sb.toString().getBytes(StandardCharsets.UTF_8), rows, null, 0);
        };
        return jdbc.query("SELECT * FROM " + def.sqlFunction() + "(?, ?::jsonb)", toCsv, user, paramJson);
    }

    // ------------------------------------------------------------------------------------------------ bureau
    private UcrfConsumerFile.Header bureauHeader(Map<String, String> params) {
        List<Map<String, Object>> props = jdbc.queryForList(
                "SELECT key, value FROM platform.system_property WHERE key IN ('bureau.member-code', 'bureau.member-name')");
        String memberCode = null;
        String memberName = null;
        for (Map<String, Object> p : props) {
            if ("bureau.member-code".equals(p.get("key"))) memberCode = String.valueOf(p.get("value"));
            else memberName = String.valueOf(p.get("value"));
        }
        if (memberCode == null || memberCode.isBlank()) {
            throw ApiException.conflict("the bureau member code is not configured (system property bureau.member-code)");
        }
        LocalDate reported = params.containsKey("asOf") ? LocalDate.parse(params.get("asOf")) : days.current().businessDate();
        if (reported.isAfter(days.current().businessDate())) throw ApiException.invalid("'asOf' cannot be after the business date");
        return new UcrfConsumerFile.Header(memberCode, memberName, reported);
    }

    /**
     * The UCRF-style consumer file. PAN, mobile and address are decrypted here, in memory, for the file only; accounts
     * that fail a check are left out and listed (account number and reason, no personal data) in a second file.
     * The layout is NOT certified by any bureau: see {@link UcrfConsumerFile}.
     */
    private Built bureauFile(Def def, String user, String paramJson, UcrfConsumerFile.Header header, String tenant) {
        PiiCipher cipher = keys.forTenant(tenant);
        List<UcrfConsumerFile.Account> accounts = jdbc.query("SELECT * FROM " + def.sqlFunction() + "(?, ?::jsonb)", (rs, i) -> {
            String line = cipher.decrypt(rs.getBytes("address_cipher"), "customer.address");
            String city = rs.getString("city");
            String address = line == null || line.isBlank() ? city : city == null ? line : line + ", " + city;
            return new UcrfConsumerFile.Account(rs.getString("loan_no"), rs.getString("account_type"), "1",
                    rs.getObject("date_opened", LocalDate.class), rs.getObject("date_last_payment", LocalDate.class),
                    rs.getObject("date_closed", LocalDate.class), rs.getBigDecimal("sanctioned_amount"), rs.getBigDecimal("current_balance"),
                    rs.getBigDecimal("amount_overdue"), rs.getInt("dpd"), rs.getString("asset_class"), rs.getString("status"),
                    rs.getBoolean("restructured"), rs.getBigDecimal("emi"), rs.getInt("tenure_months"), rs.getBigDecimal("rate"),
                    rs.getString("customer_name"), rs.getObject("date_of_birth", LocalDate.class), rs.getString("gender"),
                    cipher.decrypt(rs.getBytes("pan_cipher"), "customer.pan"), cipher.decrypt(rs.getBytes("mobile_cipher"), "customer.mobile"),
                    address, rs.getString("state_code"), rs.getString("pincode"));
        }, user, paramJson);
        UcrfConsumerFile.Result r = UcrfConsumerFile.format(header, accounts);
        byte[] rejections = null;
        if (!r.rejected().isEmpty()) {
            StringBuilder sb = new StringBuilder(Csv.line(List.of("account_number", "reason")));
            for (UcrfConsumerFile.Rejection x : r.rejected()) sb.append(Csv.line(List.of(String.valueOf(x.accountNo()), x.reason())));
            rejections = sb.toString().getBytes(StandardCharsets.UTF_8);
        }
        return new Built(r.text().getBytes(StandardCharsets.UTF_8), r.accepted(), rejections, r.rejected().size());
    }

    // ------------------------------------------------------------------------------------------------ runs
    /** The caller's runs, newest first; every user's runs with {@code report:admin}. */
    public List<Run> runs(int page, int size) {
        CurrentUser user = CurrentUser.get();
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        return runs("(? OR r.requested_by = ?) ORDER BY r.requested_at DESC, r.id LIMIT ? OFFSET ?", user.has("report:admin"), user.login(),
                limit, offset);
    }

    private List<Run> runs(String where, Object... args) {
        return jdbc.query("SELECT r.id, r.report_code, r.requested_by, to_char(r.requested_at AT TIME ZONE 'UTC', " + UTC + "), r.business_date,"
                + " r.parameters::text, r.status, r.row_count, r.rejected_count, r.file_name, r.content_type, r.artifact_bytes, r.error,"
                + " to_char(r.finished_at AT TIME ZONE 'UTC', " + UTC + ") FROM reporting.report_run r WHERE " + where,
                (rs, i) -> new Run(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate.class),
                        json.readMap(rs.getString(6)), rs.getString(7), (Integer) rs.getObject(8), rs.getInt(9), rs.getString(10),
                        rs.getString(11), (Long) rs.getObject(12), rs.getString(13), rs.getString(14)),
                args);
    }

    /**
     * The file of a completed run. Only the user who ran it, or a user with {@code report:admin}, and only while
     * they still hold the report's permission (so {@code report:admin} alone never opens a bureau file).
     * {@code part} "rejections" returns the list of accounts left out of a bureau file.
     */
    public Download download(UUID runId, String part) {
        CurrentUser user = CurrentUser.get();
        List<Stored> found = jdbc.query("""
                SELECT artifact_key, artifact_sha256, content_type, report_code,
                       CASE WHEN artifact_purged_at IS NOT NULL THEN 'REMOVED' ELSE status END, requested_by, file_name, rejected_count
                  FROM reporting.report_run WHERE id = ?
                """, (rs, i) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getInt(8)), runId);
        if (found.isEmpty()) throw ApiException.notFound("report run " + runId);
        Stored s = found.get(0);
        if (!s.requestedBy().equalsIgnoreCase(user.login()) && !user.has("report:admin")) throw ApiException.notFound("report run " + runId);
        Definition d = definition(s.reportCode()).api();
        if (!user.has(d.permission())) throw ApiException.forbidden("report " + d.code() + " needs the permission " + d.permission());
        if ("REMOVED".equals(s.status())) throw ApiException.conflict("the file of report run " + runId + " was removed under the retention policy; run the report again");
        if (!"COMPLETED".equals(s.status())) throw ApiException.conflict("report run " + runId + " is " + s.status() + " and has no file");
        boolean rejections = "rejections".equals(part);
        if (part != null && !part.isBlank() && !rejections) throw ApiException.invalid("part must be 'rejections' or left out");
        if (rejections && s.rejected() == 0) throw ApiException.notFound("rejections of report run " + runId);

        String key = DocumentKey.requireTenant(s.key(), CurrentUser.requireTenant());
        byte[] content = store.get(rejections ? key + ".rejected.csv" : key);
        if (!rejections && s.sha256() != null && !s.sha256().equals(sha256(content))) {
            throw ApiException.conflict("the stored file of report run " + runId + " no longer matches its record; run the report again");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", runId.toString());
        detail.put("part", rejections ? "rejections" : "file");
        audit.record(user.login(), d.containsPersonalData() ? "BUREAU_DOWNLOAD" : "REPORT_DOWNLOAD", "REPORT", d.code(), detail);
        return rejections
                ? new Download(DocumentKey.safeFileName(s.fileName() + ".rejected.csv"), CSV_TYPE, content)
                : new Download(s.fileName(), s.contentType(), content);
    }

    /**
     * For e-mail delivery of a scheduled run (ReportJobs): the stored file, checked against its SHA-256 as on download,
     * and whether the report may be e-mailed at all — a credit-bureau file never is (it goes to the bureau through
     * its own channel). Audited like a download, under the run's owner. Null when there is no file to send.
     */
    Download fileForDelivery(UUID runId) {
        List<Stored> found = jdbc.query("""
                SELECT artifact_key, artifact_sha256, content_type, report_code, status, requested_by, file_name, rejected_count
                  FROM reporting.report_run WHERE id = ? AND artifact_purged_at IS NULL
                """, (rs, i) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getInt(8)), runId);
        if (found.isEmpty() || !"COMPLETED".equals(found.get(0).status())) return null;
        Stored s = found.get(0);
        Definition d = definition(s.reportCode()).api();
        if ("UCRF".equals(d.outputFormat())) return null;
        byte[] content = store.get(DocumentKey.requireTenant(s.key(), CurrentUser.requireTenant()));
        if (s.sha256() != null && !s.sha256().equals(sha256(content))) throw new IllegalStateException("the stored report file no longer matches its record");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", runId.toString());
        detail.put("part", "e-mail");
        audit.record(s.requestedBy(), "REPORT_EMAILED", "REPORT", d.code(), detail);
        return new Download(s.fileName(), s.contentType(), content);
    }

    // ------------------------------------------------------------------------------------------------ retention
    /**
     * Removes the stored files of completed runs older than the retention period (US-113: generated files are
     * retained per policy). The run stays as the record of who exported what; only its file goes.
     *
     * @return files removed, and runs whose file could not be removed
     */
    public int[] purge(int retentionDays) {
        String tenant = CurrentUser.requireTenant();
        record Old(UUID id, String key, int rejected) {}
        List<Old> old = jdbc.query("SELECT id, artifact_key, rejected_count FROM reporting.purgeable_runs(?)",
                (rs, i) -> new Old(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)), retentionDays);
        int removed = 0;
        int failed = 0;
        for (Old o : old) {
            try {
                String key = DocumentKey.requireTenant(o.key(), tenant);
                store.delete(key);
                if (o.rejected() > 0) store.delete(key + ".rejected.csv");
                jdbc.update("UPDATE reporting.report_run SET artifact_purged_at = now() WHERE id = ?", o.id());
                removed++;
            } catch (RuntimeException e) {
                failed++;
            }
        }
        return new int[] {removed, failed};
    }

    // ------------------------------------------------------------------------------------------------ helpers
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What went wrong, for the run record: the first line of the innermost message, cut to 500 characters. */
    private static String failure(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage() == null ? e.getClass().getSimpleName() : t.getMessage();
        int nl = m.indexOf('\n');
        if (nl > 0) m = m.substring(0, nl);
        m = m.replaceFirst("^ERROR:\\s*", "");
        return m.length() > 500 ? m.substring(0, 500) : m.isBlank() ? "the report failed" : m;
    }
}
