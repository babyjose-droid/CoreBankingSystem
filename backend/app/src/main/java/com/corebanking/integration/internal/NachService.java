package com.corebanking.integration.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.eod.EodStepProvider;
import com.corebanking.integration.core.Hashing;
import com.corebanking.integration.core.Lifecycle;
import com.corebanking.integration.core.ValueDateRule;
import com.corebanking.integration.core.WebhookEvents;
import com.corebanking.integration.core.nach.NachFile;
import com.corebanking.integration.core.nach.NachFileFormat;
import com.corebanking.integration.core.nach.NachRules;
import com.corebanking.kernel.DocumentKey;
import com.corebanking.kernel.EodEngine;
import com.corebanking.lending.LoanOperations;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.DocumentStore;
import com.corebanking.platform.Json;
import com.corebanking.platform.Outbox;
import com.corebanking.platform.TenantDataSources;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NACH debit presentations and responses (US-071, US-072).
 * <ul>
 *   <li><b>Presentation</b>: at end of day, for the instalments falling due {@code nach.presentation.lead-days}
 *       working days ahead (holiday calendar), and for bounced demands whose re-presentation date has come. One
 *       file per sponsor bank and utility code, in the layout named by {@code nach.format} / {@code nach.encoding}
 *       — only GENERIC exists; a sponsor bank's layout is another {@link NachFileFormat}. A debit above the
 *       mandate's maximum or outside its validity is never presented.</li>
 *   <li><b>Response</b>: the file is stored and refused as a whole when it is not in the layout or its control
 *       totals disagree. The same bytes are never taken twice (hash); the same response re-sent is recognised by
 *       its control totals; and each row's outcome is recorded once (row-level idempotency).</li>
 *   <li><b>Success</b> posts a repayment. <b>Bounce</b> records the return reason, levies the product's bounce
 *       fee through the fee engine, posts no receipt (so the demand stays unpaid and DPD runs on), and schedules
 *       a re-presentation when the reason and the tenant's limits allow.</li>
 * </ul>
 * Files contain account numbers: they are kept in the document store, never in the database, and every download
 * is audited.
 */
@Service
class NachService implements EodStepProvider {

    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbc;
    private final Secrets secrets;
    private final LoanOperations loans;
    private final DocumentStore store;
    private final TenantProps props;
    private final BusinessDays days;
    private final BranchScope scope;
    private final Outbox outbox;
    private final AuditLog audit;
    private final Json json;
    private final TransactionTemplate tx;

    NachService(JdbcTemplate jdbc, Secrets secrets, LoanOperations loans, DocumentStore store, TenantProps props, BusinessDays days,
                BranchScope scope, Outbox outbox, AuditLog audit, Json json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.loans = loans;
        this.store = store;
        this.props = props;
        this.days = days;
        this.scope = scope;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    private NachFileFormat format() {
        try {
            return NachFileFormat.of(props.text("nach.format", "GENERIC"), props.text("nach.encoding", "FIXED"));
        } catch (IllegalArgumentException e) {
            throw ApiException.conflict(e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ end of day
    /** After the loan day-end (100) and before the ledger snapshot. */
    @Override
    public int order() {
        return 300;
    }

    @Override
    public List<EodEngine.Step> steps(JdbcTemplate tenantJdbc, String tenant) {
        // A per-item step with one item: if the files cannot be written (layout not configured, key missing) it is
        // an end-of-day exception for operations, never a reason for the day not to close.
        return List.of(new EodEngine.ItemStep() {
            @Override public String name() { return "NACH presentation files"; }

            @Override public List<String> items(EodEngine.Context ctx) { return List.of("presentation-files"); }

            @Override
            public void process(EodEngine.Context ctx, String item) {
                // this module's services use the routed data source: bind the tenant for the step
                TenantDataSources.runAs(tenant, () -> generate(ctx.businessDate(), "eod"));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ presentation
    private record Candidate(LoanOperations.Due due, int attempt) {}

    /** Generates the presentation files for the business date. Running it again adds only what is not yet presented. */
    List<Map<String, Object>> generate(LocalDate businessDate, String user) {
        NachFileFormat format = format();
        int lead = props.number("nach.presentation.lead-days", 2, 0, 10);
        int max = props.number("nach.max-presentations-per-demand", 3, 1, 10);
        LocalDate settlement = jdbc.queryForObject("SELECT integration.working_days_after(?::date, ?)", LocalDate.class, businessDate, lead);
        List<Candidate> candidates = new ArrayList<>();
        for (LoanOperations.Due d : loans.duesOn(jdbc, settlement)) candidates.add(new Candidate(d, 1));
        // bounced demands whose re-presentation date has come
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT s.loan_id, s.due_date, s.presentations FROM integration.nach_demand_status s
                 WHERE s.last_status = 'BOUNCED' AND NOT s.collected AND NOT s.open AND s.represent_on <= ? AND s.presentations < ?
                """, settlement, max)) {
            LoanOperations.Due d = loans.dueFor(jdbc, (UUID) r.get("loan_id"), ((java.sql.Date) r.get("due_date")).toLocalDate());
            if (d != null) candidates.add(new Candidate(d, ((Number) r.get("presentations")).intValue() + 1));
        }
        Map<String, List<Object[]>> groups = new LinkedHashMap<>();     // sponsor bank | utility code → debit + bookkeeping
        List<String> skipped = new ArrayList<>();
        for (Candidate c : candidates) {
            LoanOperations.Due d = c.due();
            List<Map<String, Object>> ms = jdbc.queryForList("SELECT * FROM integration.mandate WHERE loan_id = ? AND status = 'ACTIVE'", d.loanId());
            if (ms.isEmpty()) continue;                                 // no mandate in force: collected another way
            Map<String, Object> m = ms.get(0);
            if (!jdbc.queryForList("SELECT 1 FROM integration.nach_presentation WHERE loan_id = ? AND due_date = ? AND (status IN ('GENERATED','SUCCESS') OR attempt_no >= ?)",
                    d.loanId(), d.dueDate(), c.attempt()).isEmpty()) {
                continue;                                               // already presented for this attempt, or collected
            }
            BigDecimal amount = d.amount().setScale(2, RoundingMode.HALF_UP);
            java.sql.Date end = (java.sql.Date) m.get("end_date");
            String gap = NachRules.coverage(amount, settlement, (BigDecimal) m.get("max_amount"), ((java.sql.Date) m.get("start_date")).toLocalDate(),
                    end == null ? null : end.toLocalDate());
            if (gap != null) {
                skipped.add(d.loanNo() + ": " + gap);
                continue;
            }
            groups.computeIfAbsent(m.get("sponsor_bank_code") + "|" + m.get("utility_code"), k -> new ArrayList<>())
                    .add(new Object[] {d, m, amount, c.attempt()});
        }
        List<Map<String, Object>> files = new ArrayList<>();
        for (Map.Entry<String, List<Object[]>> g : groups.entrySet()) {
            String[] codes = g.getKey().split("\\|");
            files.add(tx.execute(s -> writeFile(format, codes[0], codes[1], settlement, g.getValue(), user, skipped)));
        }
        return files;
    }

    private Map<String, Object> writeFile(NachFileFormat format, String sponsor, String utility, LocalDate settlement, List<Object[]> rows,
                                          String user, List<String> skipped) {
        Integer n = jdbc.queryForObject("SELECT count(*) + 1 FROM integration.nach_file WHERE direction = 'PRESENTATION' AND settlement_date = ?",
                Integer.class, settlement);
        String fileRef = "NP" + COMPACT.format(settlement) + "-" + String.format("%02d", n);
        UUID fileId = UUID.randomUUID();
        List<NachFile.Debit> debits = new ArrayList<>();
        List<Object[]> inserts = new ArrayList<>();
        int seq = 0;
        for (Object[] row : rows) {
            LoanOperations.Due d = (LoanOperations.Due) row[0];
            Map<?, ?> m = (Map<?, ?>) row[1];
            BigDecimal amount = (BigDecimal) row[2];
            seq++;
            String itemRef = fileRef + "-" + String.format("%06d", seq);
            debits.add(new NachFile.Debit(seq, itemRef, (String) m.get("umrn"), secrets.open((byte[]) m.get("account_cipher"), MandateService.ACCOUNT_AAD),
                    (String) m.get("ifsc"), (String) m.get("account_type"), secrets.open((byte[]) m.get("holder_name_cipher"), MandateService.NAME_AAD),
                    amount, d.loanNo()));
            inserts.add(new Object[] {UUID.randomUUID(), fileId, seq, itemRef, m.get("id"), d.loanId(), d.loanNo(), java.sql.Date.valueOf(d.dueDate()),
                    java.sql.Date.valueOf(settlement), amount, row[3]});
        }
        NachFile.Presentation p = new NachFile.Presentation(new NachFile.Header(fileRef, utility, sponsor, settlement), debits);
        String text = format.render(p);
        String key = DocumentKey.forTenant(CurrentUser.requireTenant(), "nach", "out", fileRef + "." + format.fileExtension());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("skipped", skipped);
        jdbc.update("""
                INSERT INTO integration.nach_file (id, direction, file_ref, format, encoding, settlement_date, record_count, total_amount,
                    content_sha256, document_key, status, summary, created_by)
                VALUES (?, 'PRESENTATION', ?, ?, ?, ?, ?, ?, ?, ?, 'GENERATED', ?::jsonb, ?)
                """, fileId, fileRef, format.code(), format.encoding(), settlement, debits.size(), p.total(), Hashing.sha256Hex(text), key,
                json.write(summary), user);
        jdbc.batchUpdate("""
                INSERT INTO integration.nach_presentation (id, file_id, seq, item_ref, mandate_id, loan_id, loan_no, due_date, settlement_date, amount, attempt_no)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, inserts);
        store.put(key, text.getBytes(StandardCharsets.US_ASCII), "text/plain");
        audit.record(user, "NACH_PRESENTATION_FILE", "NACH_FILE", fileRef, Map.of("records", debits.size(), "total", p.total().toPlainString()));
        return file(fileId);
    }

    /** Operations: generate now for the open business date (the end-of-day step does the same). */
    List<Map<String, Object>> generateNow() {
        return generate(days.requireOpen(), CurrentUser.username());
    }

    // ------------------------------------------------------------------------------------------------ response
    /** Stores a response file for processing. The same bytes twice is refused here (file-level idempotency). */
    Map<String, Object> receive(String text) {
        if (text == null || text.isBlank()) throw ApiException.invalid("the file is empty");
        String sha = Hashing.sha256Hex(text);
        List<UUID> known = jdbc.queryForList("SELECT id FROM integration.nach_file WHERE direction = 'RESPONSE' AND content_sha256 = ?", UUID.class, sha);
        if (!known.isEmpty()) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "this response file was already received",
                Map.of("fileId", known.get(0).toString()));
        NachFileFormat format = format();
        UUID id = UUID.randomUUID();
        String key = DocumentKey.forTenant(CurrentUser.requireTenant(), "nach", "in", sha + "." + format.fileExtension());
        store.put(key, text.getBytes(StandardCharsets.UTF_8), "text/plain");
        jdbc.update("""
                INSERT INTO integration.nach_file (id, direction, file_ref, format, encoding, content_sha256, document_key, status, created_by)
                VALUES (?, 'RESPONSE', ?, ?, ?, ?, ?, 'RECEIVED', ?)
                """, id, "PENDING-" + sha.substring(0, 16), format.code(), format.encoding(), sha, key, CurrentUser.username());
        audit.record(CurrentUser.username(), "NACH_RESPONSE_RECEIVED", "NACH_FILE", id.toString(), Map.of("sha256", sha));
        process(id);
        return file(id);
    }

    /** The job: response files received and not yet processed (also picks up after a crash). */
    void processReceived() {
        for (UUID id : jdbc.queryForList("SELECT id FROM integration.nach_file WHERE direction = 'RESPONSE' AND status = 'RECEIVED' ORDER BY created_at LIMIT 5", UUID.class)) {
            process(id);
        }
    }

    private void reject(UUID fileId, String status, String error) {
        jdbc.update("UPDATE integration.nach_file SET status = ?, error = ?, processed_at = now() WHERE id = ?", status, error, fileId);
    }

    void process(UUID fileId) {
        Map<String, Object> f = jdbc.queryForMap("SELECT document_key, format, encoding, status FROM integration.nach_file WHERE id = ?", fileId);
        if (!"RECEIVED".equals(f.get("status"))) return;
        NachFile.Response r;
        try {
            String text = new String(store.get((String) f.get("document_key")), StandardCharsets.UTF_8);
            r = NachFileFormat.of((String) f.get("format"), (String) f.get("encoding")).parseResponse(text);
        } catch (NachFile.FormatException e) {
            reject(fileId, "REJECTED", e.getMessage());
            return;
        }
        List<UUID> presentation = jdbc.queryForList("SELECT id FROM integration.nach_file WHERE direction = 'PRESENTATION' AND file_ref = ?",
                UUID.class, r.header().fileRef());
        if (presentation.isEmpty()) {
            reject(fileId, "REJECTED", "the response names presentation file " + r.header().fileRef() + ", which does not exist");
            return;
        }
        String control = r.header().fileRef() + "|" + r.items().size() + "|" + r.total().toPlainString() + "|" + r.successCount() + "|"
                + r.successTotal().toPlainString();
        if (!jdbc.queryForList("SELECT 1 FROM integration.nach_file WHERE direction = 'RESPONSE' AND status = 'PROCESSED' AND control_key = ?", control).isEmpty()) {
            reject(fileId, "DUPLICATE", "a response with the same control totals was already processed for " + r.header().fileRef());
            return;
        }
        LocalDate today = days.current().businessDate();
        int gap = props.number("nach.representation.gap-days", 3, 1, 30);
        NachRules.Policy policy = new NachRules.Policy(props.number("nach.max-presentations-per-demand", 3, 1, 10), gap);
        int success = 0;
        int bounced = 0;
        int already = 0;
        List<String> errors = new ArrayList<>();
        for (NachFile.Outcome o : r.items()) {
            try {
                String result = tx.execute(s -> outcome(fileId, presentation.get(0), o, today, policy));
                if ("SUCCESS".equals(result)) success++; else if ("BOUNCED".equals(result)) bounced++; else already++;
            } catch (ApiException e) {
                errors.add(o.itemRef() + ": " + e.getMessage());
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("success", success);
        summary.put("bounced", bounced);
        summary.put("alreadyRecorded", already);
        summary.put("errors", errors);
        jdbc.update("""
                UPDATE integration.nach_file SET status = 'PROCESSED', control_key = ?, presentation_file_id = ?, settlement_date = ?, record_count = ?,
                       total_amount = ?, success_count = ?, success_amount = ?, summary = ?::jsonb, processed_at = now() WHERE id = ?
                """, control, presentation.get(0), r.header().settlementDate(), r.items().size(), r.total(), (int) r.successCount(), r.successTotal(),
                json.write(summary), fileId);
        followUp();
    }

    /** Records one row's outcome, once. */
    private String outcome(UUID responseFile, UUID presentationFile, NachFile.Outcome o, LocalDate today, NachRules.Policy policy) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id, p.status, p.amount, p.loan_id, p.loan_no, p.due_date, p.attempt_no, m.status AS mandate_status, m.mandate_ref, m.customer_id
                  FROM integration.nach_presentation p JOIN integration.mandate m ON m.id = p.mandate_id
                 WHERE p.item_ref = ? AND p.file_id = ? FOR UPDATE OF p
                """, o.itemRef(), presentationFile);
        if (rows.isEmpty()) throw ApiException.notFound("item in the presentation file");
        Map<String, Object> p = rows.get(0);
        if (!"GENERATED".equals(p.get("status"))) return "ALREADY";                  // row-level idempotency
        BigDecimal amount = (BigDecimal) p.get("amount");
        if (amount.compareTo(o.amount()) != 0) throw ApiException.conflict("amount " + o.amount() + " differs from the amount presented");
        if (Lifecycle.decide(Lifecycle.Presentation.GENERATED, o.success() ? Lifecycle.Presentation.SUCCESS : Lifecycle.Presentation.BOUNCED)
                != Lifecycle.Decision.APPLY) {
            return "ALREADY";
        }
        if (o.success()) {
            jdbc.update("""
                    UPDATE integration.nach_presentation SET status = 'SUCCESS', response_file_id = ?, bank_ref = ?, posting = 'PENDING', processed_at = now()
                     WHERE id = ?
                    """, responseFile, o.bankRef(), p.get("id"));
            return "SUCCESS";
        }
        List<Map<String, Object>> reason = jdbc.queryForList("SELECT description, representable FROM integration.nach_return_reason WHERE code = ?", o.returnCode());
        String description = reason.isEmpty() ? "Unknown return reason" : (String) reason.get(0).get("description");
        boolean representable = !reason.isEmpty() && Boolean.TRUE.equals(reason.get(0).get("representable"));
        int made = ((Number) p.get("attempt_no")).intValue();
        NachRules.Representation again = NachRules.afterBounce(made, representable, Lifecycle.Mandate.valueOf((String) p.get("mandate_status")), policy);
        LocalDate representOn = again.represent()
                ? jdbc.queryForObject("SELECT integration.working_days_after(?::date, ?)", LocalDate.class, today, policy.gapWorkingDays()) : null;
        jdbc.update("""
                UPDATE integration.nach_presentation SET status = 'BOUNCED', response_file_id = ?, return_code = ?, return_reason = ?, bank_ref = ?,
                       bounce_charge = 'PENDING', represent_on = ?, represent_note = ?, processed_at = now() WHERE id = ?
                """, responseFile, o.returnCode(), description, o.bankRef(), representOn, again.reason(), p.get("id"));
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("loanId", String.valueOf(p.get("loan_id")));
        e.put("loanNo", p.get("loan_no"));
        e.put("customerId", String.valueOf(p.get("customer_id")));
        e.put("amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        e.put("dueDate", String.valueOf(p.get("due_date")));
        e.put("businessDate", today.toString());
        e.put("returnCode", o.returnCode());
        e.put("returnReason", description);
        e.put("attempt", made);
        e.put("mandateRef", p.get("mandate_ref"));
        e.put("representOn", representOn == null ? null : representOn.toString());
        outbox.publish(jdbc, WebhookEvents.PAYMENT_BOUNCED, (String) p.get("loan_no"), e);
        return "BOUNCED";
    }

    /**
     * What an outcome leads to in the loan: the repayment for a success, the bounce fee for a bounce. Separate
     * from recording the outcome because it needs the books to be open; whatever is PENDING is tried again.
     */
    void followUp() {
        BusinessDays.BusinessDay day = days.current();
        if (day == null || !"OPEN".equals(day.status())) return;
        int back = props.number("collections.max-back-value-days", 3, 0, 31);
        for (Map<String, Object> p : jdbc.queryForList("""
                SELECT id, loan_id, amount, settlement_date, item_ref, bank_ref, status FROM integration.nach_presentation
                 WHERE posting = 'PENDING' OR bounce_charge = 'PENDING' ORDER BY processed_at LIMIT 100
                """)) {
            UUID id = (UUID) p.get("id");
            boolean success = "SUCCESS".equals(p.get("status"));
            try {
                tx.executeWithoutResult(s -> {
                    if (success) {
                        ValueDateRule.Decision v = ValueDateRule.decide(((java.sql.Date) p.get("settlement_date")).toLocalDate(), day.businessDate(), back);
                        LoanOperations.Posting posted = loans.postRepayment((UUID) p.get("loan_id"), (BigDecimal) p.get("amount"), v.valueDate(), "NACH",
                                p.get("bank_ref") == null ? (String) p.get("item_ref") : (String) p.get("bank_ref"));
                        jdbc.update("UPDATE integration.nach_presentation SET posting = 'POSTED', loan_txn_id = ?, posting_error = ? WHERE id = ?",
                                posted.txnId(), v.note(), id);
                    } else {
                        LoanOperations.BounceCharge c = loans.chargeBounceFee((UUID) p.get("loan_id"), (BigDecimal) p.get("amount"));
                        jdbc.update("UPDATE integration.nach_presentation SET bounce_charge = ?, bounce_charge_note = ? WHERE id = ?",
                                c.charged() ? "CHARGED" : "NO_FEE_RULE", c.note(), id);
                    }
                });
            } catch (ApiException e) {
                // the loan cannot take it (closed, frozen): for operations
                if (success) {
                    jdbc.update("UPDATE integration.nach_presentation SET posting = 'FAILED', posting_error = ? WHERE id = ?", e.getMessage(), id);
                } else {
                    jdbc.update("UPDATE integration.nach_presentation SET bounce_charge = 'FAILED', bounce_charge_note = ? WHERE id = ?", e.getMessage(), id);
                }
            }
        }
    }

    /** Test and demo: the response a bank would send for a presentation file — amounts ending in .04 bounce with reason 04. */
    Map<String, Object> simulateResponse(UUID presentationFileId) {
        Map<String, Object> f = jdbc.queryForMap("SELECT document_key, format, encoding FROM integration.nach_file WHERE id = ? AND direction = 'PRESENTATION'",
                presentationFileId);
        NachFileFormat format = NachFileFormat.of((String) f.get("format"), (String) f.get("encoding"));
        NachFile.Presentation p = format.parsePresentation(new String(store.get((String) f.get("document_key")), StandardCharsets.US_ASCII));
        List<NachFile.Outcome> outcomes = new ArrayList<>();
        for (NachFile.Debit d : p.items()) {
            boolean bounce = d.amount().movePointRight(2).remainder(BigDecimal.valueOf(100)).intValue() == 4;
            outcomes.add(new NachFile.Outcome(d.seq(), d.itemRef(), d.umrn(), d.amount(), !bounce, bounce ? "04" : null, bounce ? null : "SIM" + d.seq()));
        }
        return receive(format.renderResponse(new NachFile.Response(p.header(), outcomes)));
    }

    // ------------------------------------------------------------------------------------------------ views
    private void requireAll() {
        if (!scope.seesAll()) throw ApiException.forbidden("NACH files cover every branch: all-branch access is needed");
    }

    Map<String, Object> file(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, direction, file_ref AS "fileRef", format, encoding, settlement_date::text AS "settlementDate", record_count AS "recordCount",
                       total_amount::text AS "totalAmount", success_count AS "successCount", success_amount::text AS "successAmount",
                       content_sha256 AS "sha256", status, summary::text AS summary, error, created_by AS "createdBy", created_at AS "createdAt",
                       processed_at AS "processedAt"
                  FROM integration.nach_file WHERE id = ?
                """, id);
        if (rows.isEmpty()) throw ApiException.notFound("NACH file " + id);
        Map<String, Object> m = new LinkedHashMap<>(rows.get(0));
        m.put("summary", json.readMap((String) m.get("summary")));
        return m;
    }

    List<Map<String, Object>> files(String direction) {
        requireAll();
        return jdbc.queryForList("SELECT id FROM integration.nach_file WHERE (?::text IS NULL OR direction = ?) ORDER BY created_at DESC LIMIT 200",
                UUID.class, direction, direction).stream().map(this::file).toList();
    }

    /** The file itself (it holds account numbers): all-branch access, and audited. */
    String content(UUID id) {
        requireAll();
        Map<String, Object> f = file(id);
        String key = jdbc.queryForObject("SELECT document_key FROM integration.nach_file WHERE id = ?", String.class, id);
        audit.record(CurrentUser.username(), "NACH_FILE_DOWNLOAD", "NACH_FILE", String.valueOf(f.get("fileRef")), Map.of("direction", String.valueOf(f.get("direction"))));
        return new String(store.get(DocumentKey.requireTenant(key, CurrentUser.requireTenant())), StandardCharsets.UTF_8);
    }

    /** Presentations of a loan, or everything that needs operations when no loan is given. */
    List<Map<String, Object>> presentations(UUID loanId) {
        return jdbc.queryForList("""
                SELECT p.id, p.item_ref AS "itemRef", p.loan_id AS "loanId", p.loan_no AS "loanNo", p.due_date::text AS "dueDate",
                       p.settlement_date::text AS "settlementDate", p.amount::text AS amount, p.attempt_no AS "attemptNo", p.status,
                       p.return_code AS "returnCode", p.return_reason AS "returnReason", p.posting, p.posting_error AS "postingNote",
                       p.loan_txn_id AS "loanTxnId", p.bounce_charge AS "bounceCharge", p.bounce_charge_note AS "bounceChargeNote",
                       p.represent_on::text AS "representOn", p.represent_note AS "representNote"
                  FROM integration.nach_presentation p JOIN integration.mandate m ON m.id = p.mandate_id
                 WHERE m.branch_code IN (SELECT branch_code FROM platform.visible_branches(?))
                   AND (CASE WHEN ?::uuid IS NULL THEN p.posting IN ('PENDING','FAILED') OR p.bounce_charge IN ('PENDING','FAILED') ELSE p.loan_id = ? END)
                 ORDER BY p.settlement_date DESC, p.attempt_no DESC LIMIT 500
                """, scope.user(), loanId, loanId);
    }

    List<Map<String, Object>> returnReasons() {
        return jdbc.queryForList("SELECT code, description, category, representable, verified FROM integration.nach_return_reason ORDER BY code");
    }

    @Component
    static class Task implements IntegrationTask {
        private final NachService nach;

        Task(@Lazy NachService nach) {
            this.nach = nach;
        }

        @Override public String name() { return "nach-responses"; }

        @Override
        public void run() {
            nach.processReceived();
            nach.followUp();
        }
    }
}
