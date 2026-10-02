package com.corebanking.ledger.web;

import com.corebanking.kernel.Csv;
import com.corebanking.ledger.LedgerSettings;
import com.corebanking.ledger.NumberSeries;
import com.corebanking.ledger.NumberSeriesService;
import com.corebanking.ledger.PostingLine;
import com.corebanking.ledger.PostingService;
import com.corebanking.ledger.TransactionLot;
import com.corebanking.platform.AmountLimitService;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manual vouchers with maker-checker (US-103). Lines become a balanced posting lot when approved. */
@Service
class VoucherService {

    record Line(String branch, String glCode, String account, String side, String amount, String narration) {}
    record Input(String voucherType, LocalDate valueDate, String reference, String description, List<Line> lines) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final BusinessDays days;
    private final Json json;
    private final BranchScope scope;
    private final AmountLimitService limits;

    VoucherService(JdbcTemplate jdbc, ApprovalService approvals, BusinessDays days, Json json, BranchScope scope,
                   AmountLimitService limits) {
        this.limits = limits;
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.days = days;
        this.json = json;
        this.scope = scope;
    }

    @Transactional
    public ApprovalRequest propose(Input in, String idempotencyKey) {
        LocalDate bd = days.requireOpen();
        if (in.voucherType() == null || !in.voucherType().matches("CONTRA|RECEIPT|PAYMENT|JOURNAL")) {
            throw ApiException.invalid("voucherType must be CONTRA, RECEIPT, PAYMENT or JOURNAL");
        }
        if (in.description() == null || in.description().isBlank()) throw ApiException.invalid("description is required");
        LocalDate valueDate = in.valueDate() == null ? bd : in.valueDate();
        if (valueDate.isAfter(bd)) throw ApiException.invalid("value date cannot be after the business date " + bd);
        if (in.lines() == null || in.lines().size() < 2) throw ApiException.invalid("a voucher needs at least two lines");
        for (Line l : in.lines()) {
            validateLine(l);
            scope.require(l.branch());
        }
        TransactionLot preview = build(in, bd, valueDate, "IBR-CHECK");   // throws if unbalanced
        BigDecimal amount = preview.lines().stream()
                .filter(l -> l.side() == PostingLine.Side.DR && !l.glCode().equals("IBR-CHECK"))
                .map(PostingLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        limits.require("VOUCHER", AmountLimitService.MAKE, amount, in.reference());       // maker's role amount limit (US-021)
        Map<String, Object> payload = json.toMap(new Input(in.voucherType(), valueDate, in.reference(), in.description(), in.lines()));
        return approvals.propose("VOUCHER", "CREATE", null, payload, null, amount, in.lines().get(0).branch(), idempotencyKey);
    }

    /** Columns of the voucher upload file (US-103); rows with the same voucherRef form one voucher. */
    static final List<String> UPLOAD_COLUMNS = List.of("voucherRef", "voucherType", "valueDate", "description",
            "branch", "glCode", "side", "amount");

    /**
     * Voucher upload (US-103): every voucher in the file is validated like a single voucher and proposed for
     * approval. All or nothing: one bad voucher rejects the file and no approval is created.
     * Optional columns: account, narration, reference.
     */
    @Transactional
    public List<ApprovalRequest> proposeUpload(String csv, String idempotencyKey) {
        if (csv != null && csv.length() > 5_000_000) throw ApiException.invalid("the file is larger than 5 MB");
        Csv.Table table;
        try {
            table = Csv.parse(csv, UPLOAD_COLUMNS, 2_000);
        } catch (Csv.CsvException e) {
            throw ApiException.invalid(e.getMessage());
        }
        Map<String, List<Csv.Row>> groups = new LinkedHashMap<>();
        for (Csv.Row r : table.rows()) {
            String ref = r.get("voucherRef");
            if (ref == null) throw ApiException.invalid("line " + r.line() + ": voucherRef is required");
            groups.computeIfAbsent(ref, k -> new ArrayList<>()).add(r);
        }
        if (groups.size() > 200) throw ApiException.invalid("at most 200 vouchers per file");
        List<ApprovalRequest> out = new ArrayList<>();
        for (Map.Entry<String, List<Csv.Row>> g : groups.entrySet()) {
            Csv.Row first = g.getValue().get(0);
            String where = "voucher " + g.getKey() + " (line " + first.line() + ")";
            LocalDate valueDate;
            try {
                valueDate = first.get("valueDate") == null ? null : LocalDate.parse(first.get("valueDate"));
            } catch (java.time.format.DateTimeParseException e) {
                throw ApiException.invalid(where + ": valueDate must be YYYY-MM-DD");
            }
            List<Line> lines = new ArrayList<>();
            for (Csv.Row r : g.getValue()) {
                for (String header : List.of("voucherType", "valueDate", "description", "reference")) {
                    String v = r.get(header);
                    if (v != null && !v.equals(first.get(header))) {
                        throw ApiException.invalid("line " + r.line() + ": " + header + " differs from the first line of " + g.getKey());
                    }
                }
                String side = r.get("side") == null ? null : r.get("side").toUpperCase();
                lines.add(new Line(r.get("branch"), r.get("glCode"), r.get("account"), side, r.get("amount"), r.get("narration")));
            }
            String reference = first.get("reference") == null ? g.getKey() : first.get("reference");
            Input in = new Input(first.get("voucherType") == null ? null : first.get("voucherType").toUpperCase(), valueDate,
                    reference, first.get("description"), lines);
            try {
                out.add(propose(in, idempotencyKey == null ? null : idempotencyKey + ":" + g.getKey()));
            } catch (ApiException e) {
                if (e.status() != org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT) throw e;
                throw ApiException.invalid(where + ": " + e.getMessage());
            } catch (IllegalArgumentException e) {
                throw ApiException.invalid(where + ": " + e.getMessage());
            }
        }
        return out;
    }

    @Transactional
    public ApprovalRequest proposeReversal(UUID voucherId, String reason) {
        days.requireOpen();
        if (reason == null || reason.isBlank()) throw ApiException.invalid("a reason is required");
        List<Map<String, Object>> v = jdbc.queryForList(
                "SELECT voucher_no, status, amount, description FROM ledger.voucher WHERE id = ?", voucherId);
        if (v.isEmpty()) throw ApiException.notFound("voucher " + voucherId);
        List<String> branches = jdbc.queryForList("""
                SELECT DISTINCT e.branch_code FROM ledger.account_entry e JOIN ledger.voucher v ON v.lot_id = e.lot_id
                 WHERE v.id = ? ORDER BY 1
                """, String.class, voucherId);
        for (String b : branches) scope.requireRecord(b, "voucher " + voucherId);
        if (!"POSTED".equals(v.get(0).get("status"))) throw ApiException.conflict("voucher is already reversed");
        limits.require("VOUCHER", AmountLimitService.MAKE, (BigDecimal) v.get(0).get("amount"), String.valueOf(v.get(0).get("voucher_no")));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("voucherId", voucherId.toString());
        payload.put("voucherNo", v.get(0).get("voucher_no"));
        payload.put("description", v.get(0).get("description"));
        payload.put("reason", reason);
        return approvals.propose("VOUCHER", "REVERSE", voucherId.toString(), payload, null,
                (BigDecimal) v.get(0).get("amount"), branches.isEmpty() ? null : branches.get(0), null);
    }

    private void validateLine(Line l) {
        if (l.branch() == null || l.glCode() == null || l.side() == null || l.amount() == null) {
            throw ApiException.invalid("each line needs branch, glCode, side and amount");
        }
        if (!l.side().equals("DR") && !l.side().equals("CR")) throw ApiException.invalid("side must be DR or CR");
        List<Map<String, Object>> gl = jdbc.queryForList("SELECT is_posting, status FROM ledger.gl_head WHERE code = ?", l.glCode());
        if (gl.isEmpty()) throw ApiException.invalid("GL head " + l.glCode() + " does not exist");
        if (!Boolean.TRUE.equals(gl.get(0).get("is_posting"))) throw ApiException.invalid("GL head " + l.glCode() + " is not a posting head");
        if (!"ACTIVE".equals(gl.get(0).get("status"))) throw ApiException.invalid("GL head " + l.glCode() + " is " + gl.get(0).get("status"));
        if (jdbc.queryForList("SELECT 1 FROM platform.branch WHERE code = ? AND status = 'ACTIVE'", l.branch()).isEmpty()) {
            throw ApiException.invalid("branch " + l.branch() + " is not active");
        }
    }

    static TransactionLot build(Input in, LocalDate businessDate, LocalDate valueDate, String interBranchGl) {
        TransactionLot.Builder b = TransactionLot.builder("VOUCHER", businessDate).valueDate(valueDate)
                .reference(in.reference()).interBranchGl(interBranchGl);
        for (Line l : in.lines()) {
            BigDecimal amt;
            try {
                amt = new BigDecimal(l.amount());
            } catch (NumberFormatException e) {
                throw ApiException.invalid("amount '" + l.amount() + "' is not a decimal");
            }
            b.line(new PostingLine(l.branch(), l.glCode(), l.account() == null || l.account().isBlank() ? l.glCode() : l.account(),
                    PostingLine.Side.valueOf(l.side()), amt, "INR", l.narration() == null ? in.description() : l.narration()));
        }
        return b.build();
    }

    /** Applies approved voucher creates and reversals. */
    @Service
    static class Applier implements ApprovalApplier {
        private final JdbcTemplate jdbc;
        private final PostingService posting;
        private final NumberSeriesService numbers;
        private final LedgerSettings settings;
        private final BusinessDays days;
        private final Json json;

        Applier(JdbcTemplate jdbc, PostingService posting, NumberSeriesService numbers, LedgerSettings settings,
                BusinessDays days, Json json) {
            this.jdbc = jdbc;
            this.posting = posting;
            this.numbers = numbers;
            this.settings = settings;
            this.days = days;
            this.json = json;
        }

        @Override public String entityType() { return "VOUCHER"; }

        @Override
        public String apply(ApprovalRequest r) {
            return "REVERSE".equals(r.action()) ? reverse(r) : create(r);
        }

        private String create(ApprovalRequest r) {
            LocalDate bd = days.requireOpen();
            Input in = json.convert(r.payload(), Input.class);
            LocalDate valueDate = in.valueDate().isAfter(bd) ? bd : in.valueDate();
            TransactionLot lot = build(in, bd, valueDate, settings.interBranchGl());
            posting.post(lot, r.maker());
            String no = numbers.next(NumberSeries.Family.VOUCHER);
            BigDecimal amount = new ArrayList<>(in.lines()).stream().filter(l -> l.side().equals("DR"))
                    .map(l -> new BigDecimal(l.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
            jdbc.update("""
                    INSERT INTO ledger.voucher (id, voucher_no, voucher_type, value_date, business_date, reference,
                                                description, amount, lot_id, approval_id, maker, checker)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), no, in.voucherType(), valueDate, bd, in.reference(), in.description(),
                    amount, lot.id(), r.id(), r.maker(), com.corebanking.platform.CurrentUser.username());
            return no;
        }

        private String reverse(ApprovalRequest r) {
            LocalDate bd = days.requireOpen();
            UUID voucherId = UUID.fromString(String.valueOf(r.payload().get("voucherId")));
            Map<String, Object> v = jdbc.queryForMap("SELECT voucher_no, status, lot_id FROM ledger.voucher WHERE id = ? FOR UPDATE", voucherId);
            if (!"POSTED".equals(v.get("status"))) throw ApiException.conflict("voucher is already reversed");
            TransactionLot original = posting.load((UUID) v.get("lot_id"));
            TransactionLot reversal = TransactionLot.reversal(original, bd, String.valueOf(r.payload().get("reason")));
            posting.post(reversal, r.maker());
            jdbc.update("UPDATE ledger.voucher SET status = 'REVERSED', reversal_lot_id = ? WHERE id = ?", reversal.id(), voucherId);
            return (String) v.get("voucher_no");
        }
    }
}
