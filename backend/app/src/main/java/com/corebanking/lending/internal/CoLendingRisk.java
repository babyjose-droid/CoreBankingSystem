package com.corebanking.lending.internal;

import com.corebanking.kernel.Csv;
import com.corebanking.ledger.PostingService;
import com.corebanking.ledger.TransactionLot;
import com.corebanking.lending.engine.CoLending;
import com.corebanking.lending.engine.CoLending.DlgForm;
import com.corebanking.lending.engine.CoLending.DlgHeads;
import com.corebanking.lending.engine.CoLending.Terms;
import com.corebanking.lending.engine.Delinquency.AssetClass;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Co-lending once the partner holds its share (P5-3).
 *
 * <ul>
 *   <li><b>Classification at borrower level</b> (Directions para 33): this lender's classification of each loan goes to
 *       the partner in a file; the partner's classification of the borrower comes back (its file, through a checker). An
 *       NPA at the partner holds the loan at that class here by an asset-class override for a year (so the day-end does
 *       not classify it back from its own days past due), and the borrower's other loans follow at day-end; it is
 *       un-marked as any override. An SMA or standard report is recorded, the loan's own days past due being the same
 *       loan's.</li>
 *   <li><b>Default loss guarantee</b> (para 32; Digital Lending Directions, 2025): covers lodged with the partner (cash,
 *       a fixed deposit under lien or a bank guarantee) up to the arrangement's percentage of the partner's share funded
 *       (5% at most), lasting at least as long as the longest loan it covers; the partner invokes it for a loan in
 *       default (within 120 days overdue) and the loss is the originator's, not set off against the loan; recoveries
 *       are shared back. Every step through a checker.</li>
 * </ul>
 * Provisioning on the own share is in the daily split ({@link CoLendingSplit}); the bureau file reports the own share
 * (tenant V41).
 */
@Service
public class CoLendingRisk {

    static final String CLASSIFICATION = "CO_LENDING_CLASSIFICATION";
    static final String DLG = "CO_LENDING_DLG";
    private static final List<String> CLASSES = List.of("STANDARD", "SMA0", "SMA1", "SMA2", "SUBSTANDARD", "DOUBTFUL1", "DOUBTFUL2", "DOUBTFUL3", "LOSS");

    /** One row of the partner's classification file: openapi.yaml#/components/schemas/CoLendingPartnerClass. */
    public record PartnerClassInput(String loanNo, String assetClass, LocalDate npaSince, Integer partnerDpd) {}

    /** openapi.yaml#/components/schemas/CoLendingDlgCoverInput. */
    public record CoverInput(String form, BigDecimal amount, String instrumentRef, LocalDate issuedOn, LocalDate validUntil) {}

    /** openapi.yaml#/components/schemas/CoLendingDlgInvocationInput. */
    public record InvocationInput(String loanNo, String coverId, BigDecimal amount, String reason) {}

    /** openapi.yaml#/components/schemas/CoLendingDlgRecoveryInput. */
    public record RecoveryInput(BigDecimal amount, String reference) {}

    public record ReasonInput(String reason) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final PostingService posting;
    private final BusinessDays days;
    private final BranchScope scope;
    private final LoanStore store;
    private final LoanEventPublisher events;

    CoLendingRisk(JdbcTemplate jdbc, ApprovalService approvals, PostingService posting, BusinessDays days, BranchScope scope, LoanStore store,
                  LoanEventPublisher events) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.posting = posting;
        this.days = days;
        this.scope = scope;
        this.store = store;
        this.events = events;
    }

    private Map<String, Object> arrangement(String code) {
        List<Map<String, Object>> a = jdbc.queryForList("SELECT * FROM lending.co_lending_arrangement WHERE code = ?", code);
        if (a.isEmpty()) throw ApiException.notFound("co-lending arrangement " + code);
        return a.get(0);
    }

    // ================================================================================================ classification

    /** This lender's classification of the loans whose share the partner holds, for the partner (no personal data). */
    public byte[] classificationFile(String code) {
        arrangement(code);
        LocalDate today = days.current().businessDate();
        StringBuilder sb = new StringBuilder(Csv.line(List.of("as_of", "loan_no", "partner_loan_ref", "customer_no", "dpd", "asset_class", "npa_since",
                "principal_outstanding", "partner_principal")));
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT l.loan_no, c.partner_loan_ref, cu.customer_no, l.dpd, l.asset_class, l.npa_since, l.principal_outstanding::numeric AS po, l.branch_code,
                       c.originator_share, c.originator_rate, c.partner_rate
                  FROM lending.co_lent_loan c JOIN lending.loan_account l ON l.id = c.loan_id JOIN customer.customer cu ON cu.id = l.customer_id
                 WHERE c.arrangement_code = ? AND c.status = 'ACCEPTED' AND l.status IN ('ACTIVE','FROZEN') ORDER BY l.loan_no
                """, code)) {
            if (!scope.canSee((String) r.get("branch_code"))) continue;
            Terms t = new Terms((BigDecimal) r.get("originator_share"), (BigDecimal) r.get("originator_rate"), (BigDecimal) r.get("partner_rate"), null, null,
                    null, null);
            sb.append(Csv.line(List.of(today.toString(), s(r.get("loan_no")), s(r.get("partner_loan_ref")), s(r.get("customer_no")), s(r.get("dpd")),
                    s(r.get("asset_class")), s(r.get("npa_since")), CoLendingService.plain((BigDecimal) r.get("po")),
                    CoLendingService.plain(CoLending.partnerOutstanding(t, (BigDecimal) r.get("po"))))));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The partner's classification of borrowers whose loans it shares, recorded through a checker. */
    @Transactional
    public ApprovalRequest proposePartnerClasses(String code, List<PartnerClassInput> rows, String idempotencyKey) {
        arrangement(code);
        LocalDate today = days.requireOpen();
        if (rows == null || rows.isEmpty()) throw ApiException.invalid("no classification given");
        if (rows.size() > 5000) throw ApiException.invalid("at most 5,000 rows at a time");
        List<String> problems = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int npa = 0;
        for (PartnerClassInput r : rows) {
            String no = r == null || r.loanNo() == null ? null : r.loanNo().trim();
            if (no == null || no.isEmpty()) { problems.add("a row without a loan number"); continue; }
            if (!seen.add(no)) { problems.add("loan " + no + " is given twice"); continue; }
            String cls = r.assetClass() == null ? null : r.assetClass().trim().toUpperCase(Locale.ROOT);
            if (!CLASSES.contains(cls)) { problems.add("loan " + no + ": the class is one of " + String.join(", ", CLASSES)); continue; }
            boolean isNpa = AssetClass.valueOf(cls).isNpa();
            if (isNpa && (r.npaSince() == null || r.npaSince().isAfter(today))) { problems.add("loan " + no + ": an NPA has its date, not after today"); continue; }
            if (!isNpa && r.npaSince() != null) { problems.add("loan " + no + ": only an NPA has an NPA date"); continue; }
            if (r.partnerDpd() != null && r.partnerDpd() < 0) { problems.add("loan " + no + ": days past due are 0 or more"); continue; }
            List<Map<String, Object>> f = jdbc.queryForList("""
                    SELECT c.status, c.arrangement_code, l.branch_code FROM lending.co_lent_loan c JOIN lending.loan_account l ON l.id = c.loan_id WHERE l.loan_no = ?
                    """, no);
            if (f.isEmpty() || !code.equals(f.get(0).get("arrangement_code")) || !scope.canSee((String) f.get(0).get("branch_code"))) {
                problems.add("loan " + no + " is not co-lent under " + code);
                continue;
            }
            if (!"ACCEPTED".equals(f.get(0).get("status"))) { problems.add("loan " + no + ": the partner holds no share of it"); continue; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("loanNo", no);
            m.put("assetClass", cls);
            m.put("npaSince", r.npaSince() == null ? null : r.npaSince().toString());
            m.put("partnerDpd", r.partnerDpd());
            out.add(m);
            if (isNpa) npa++;
        }
        CoLendingService.refuseIfAny("the partner's classification cannot be recorded", problems);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("arrangementCode", code);
        payload.put("rows", out);
        payload.put("npa", npa);
        return approvals.propose(CLASSIFICATION, "RECORD", code, payload, null, null, null, idempotencyKey);
    }

    String applyPartnerClasses(ApprovalRequest r) {
        LocalDate today = days.requireOpen();
        int made = 0;
        int recorded = 0;
        for (Object o : (List<?>) r.payload().get("rows")) {
            Map<?, ?> row = (Map<?, ?>) o;
            UUID loanId = jdbc.queryForObject("SELECT id FROM lending.loan_account WHERE loan_no = ?", UUID.class, row.get("loanNo"));
            AssetClass cls = AssetClass.valueOf((String) row.get("assetClass"));
            LocalDate since = row.get("npaSince") == null ? null : LocalDate.parse((String) row.get("npaSince"));
            String outcome;
            LoanStore.Loaded l = store.lock(jdbc, loanId);
            LoanAccount a = l.account();
            boolean live = a.status() == LoanAccount.Status.ACTIVE || a.status() == LoanAccount.Status.FROZEN;
            if (cls.isNpa() && live && a.assetClass().ordinal() < cls.ordinal()) {
                // held at the partner's class by an override, so the day-end does not classify it back from its own days
                // past due; the borrower's other loans follow at day-end (borrower-level NPA)
                LoanAccount.Snapshot before = a.snapshot();
                LoanAccount.Status statusBefore = a.status();
                AssetClass was = a.assetClass();
                boolean npaBefore = was.isNpa();
                LocalDate until = today.plusYears(1);
                try {
                    LoanAccount.Result res = a.overrideAssetClass(cls, until, today);
                    for (TransactionLot lot : res.lots()) posting.post(lot, r.maker());
                    store.save(jdbc, loanId, a, today);
                    store.recordTxn(jdbc, loanId, "EOD", today, today, null, res.lots(), before,
                            "NPA by the co-lending partner's classification (" + cls + " since " + since + "), held until " + until, null, r.maker());
                    store.recordDpd(jdbc, loanId, today, a);
                    events.transitions(jdbc, loanId, statusBefore, npaBefore, a, today);
                    outcome = "made " + cls + " here (was " + was + "), held until " + until;
                    made++;
                } catch (IllegalStateException | IllegalArgumentException e) {
                    outcome = "not applied: " + e.getMessage();
                    recorded++;
                }
            } else if (cls.isNpa() && live) {
                outcome = "already " + a.assetClass() + " here";
                recorded++;
            } else if (cls.isNpa()) {
                outcome = "the loan is " + a.status() + ": nothing to classify";
                recorded++;
            } else {
                outcome = "recorded (" + a.assetClass() + " here, " + a.dpd() + " days past due)";
                recorded++;
            }
            jdbc.update("""
                    INSERT INTO lending.co_lending_partner_class (id, loan_id, reported_on, asset_class, npa_since, partner_dpd, outcome, approval_id, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), loanId, today, cls.name(), since, row.get("partnerDpd"), outcome, r.id(), r.maker());
        }
        return r.payload().get("arrangementCode") + ": " + made + " made NPA, " + recorded + " recorded";
    }

    /** The partner's latest classification of a loan, for its page; null when none was reported. */
    public Map<String, Object> latestPartnerClass(UUID loanId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT reported_on, asset_class, npa_since, partner_dpd, outcome FROM lending.co_lending_partner_class
                 WHERE loan_id = ? ORDER BY reported_on DESC, created_at DESC LIMIT 1
                """, loanId);
        if (rows.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportedOn", str(rows.get(0).get("reported_on")));
        m.put("assetClass", rows.get(0).get("asset_class"));
        m.put("npaSince", str(rows.get(0).get("npa_since")));
        m.put("partnerDpd", rows.get(0).get("partner_dpd"));
        m.put("outcome", rows.get(0).get("outcome"));
        return m;
    }

    // ================================================================================================ guarantee

    private DlgHeads heads() {
        DlgHeads d = DlgHeads.starter();
        return new DlgHeads(setting("lending.co-lending.dlg-deposit-gl", d.deposit()), setting("lending.co-lending.dlg-loss-gl", d.loss()),
                setting("lending.co-lending.dlg-recovery-gl", d.recovery()), setting("lending.co-lending.dlg-bank-gl", d.bank()));
    }

    private String setting(String key, String fallback) {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = ?", String.class, key);
        return v.isEmpty() || v.get(0).isBlank() ? fallback : v.get(0).trim();
    }

    private String headOffice() {
        return jdbc.queryForObject("SELECT code FROM platform.branch WHERE is_head_office ORDER BY code LIMIT 1", String.class);
    }

    private static Terms terms(Map<String, Object> a) {
        return new Terms((BigDecimal) a.get("originator_share"), (BigDecimal) a.get("originator_rate"), (BigDecimal) a.get("partner_rate"), null, null, null,
                (BigDecimal) a.get("dlg_percent"));
    }

    /** The partner's share funded under the arrangement: the base of the cap. */
    private BigDecimal partnerFunded(String code) {
        return jdbc.queryForObject("SELECT coalesce(sum(partner_principal), 0)::numeric FROM lending.co_lent_loan WHERE arrangement_code = ? AND status = 'ACCEPTED'",
                BigDecimal.class, code);
    }

    private BigDecimal covered(String code) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0)::numeric FROM lending.co_lending_dlg_cover WHERE arrangement_code = ? AND status = 'ACTIVE'",
                BigDecimal.class, code);
    }

    private BigDecimal invoked(UUID coverId) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0)::numeric FROM lending.co_lending_dlg_invocation WHERE cover_id = ?", BigDecimal.class, coverId);
    }

    /** The last due date of the loans the partner shares: a cover lasts at least that long (para 10 of the DLG rules). */
    private LocalDate longestTenor(String code) {
        java.sql.Date d = jdbc.queryForObject("""
                SELECT max((s->>'dueDate')::date) FROM lending.co_lent_loan c JOIN lending.loan_account l ON l.id = c.loan_id,
                       jsonb_array_elements(coalesce(l.state->'futureSchedule', '[]'::jsonb)) s
                 WHERE c.arrangement_code = ? AND c.status = 'ACCEPTED' AND l.status IN ('ACTIVE','FROZEN')
                """, java.sql.Date.class, code);
        return d == null ? null : d.toLocalDate();
    }

    /** The guarantee of an arrangement: the cap, the covers, what was invoked and recovered, and the loans in default. */
    public Map<String, Object> dlg(String code) {
        Map<String, Object> a = arrangement(code);
        Terms t = terms(a);
        BigDecimal funded = partnerFunded(code);
        BigDecimal cap = CoLending.dlgCap(t, funded);
        BigDecimal covered = covered(code);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("arrangementCode", code);
        m.put("dlgPercent", CoLendingService.plain(t.dlgPercent()));
        m.put("partnerFunded", CoLendingService.plain(funded));
        m.put("cap", CoLendingService.plain(cap));
        m.put("covered", CoLendingService.plain(covered));
        m.put("headroom", CoLendingService.plain(cap.subtract(covered).max(BigDecimal.ZERO)));
        LocalDate longest = longestTenor(code);
        m.put("longestLoanDue", longest == null ? null : longest.toString());
        BigDecimal invokedTotal = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        List<Map<String, Object>> covers = new ArrayList<>();
        for (Map<String, Object> c : jdbc.queryForList("SELECT * FROM lending.co_lending_dlg_cover WHERE arrangement_code = ? ORDER BY created_at", code)) {
            BigDecimal inv = invoked((UUID) c.get("id"));
            invokedTotal = invokedTotal.add(inv);
            BigDecimal left = "ACTIVE".equals(c.get("status")) ? ((BigDecimal) c.get("amount")).subtract(inv) : BigDecimal.ZERO;
            available = available.add(left);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", c.get("id").toString());
            x.put("coverNo", c.get("cover_no"));
            x.put("form", c.get("form"));
            x.put("amount", CoLendingService.plain((BigDecimal) c.get("amount")));
            x.put("invoked", CoLendingService.plain(inv));
            x.put("available", CoLendingService.plain(left));
            x.put("instrumentRef", c.get("instrument_ref"));
            x.put("issuedOn", str(c.get("issued_on")));
            x.put("validUntil", str(c.get("valid_until")));
            x.put("status", c.get("status"));
            x.put("releasedOn", str(c.get("released_on")));
            x.put("lastsLongEnough", longest == null || !((java.sql.Date) c.get("valid_until")).toLocalDate().isBefore(longest));
            covers.add(x);
        }
        m.put("invoked", CoLendingService.plain(invokedTotal));
        m.put("available", CoLendingService.plain(available));
        m.put("covers", covers);
        m.put("invocations", jdbc.queryForList("""
                SELECT i.id, v.cover_no, l.loan_no, i.business_date, i.amount, i.dpd, i.reason,
                       (SELECT coalesce(sum(r.amount), 0) FROM lending.co_lending_dlg_recovery r WHERE r.invocation_id = i.id) AS recovered
                  FROM lending.co_lending_dlg_invocation i JOIN lending.co_lending_dlg_cover v ON v.id = i.cover_id JOIN lending.loan_account l ON l.id = i.loan_id
                 WHERE v.arrangement_code = ? ORDER BY i.created_at
                """, code).stream().map(x -> {
            Map<String, Object> y = new LinkedHashMap<>();
            y.put("id", x.get("id").toString());
            y.put("coverNo", x.get("cover_no"));
            y.put("loanNo", x.get("loan_no"));
            y.put("businessDate", str(x.get("business_date")));
            y.put("amount", CoLendingService.plain((BigDecimal) x.get("amount")));
            y.put("dpd", x.get("dpd"));
            y.put("reason", x.get("reason"));
            y.put("recovered", CoLendingService.plain((BigDecimal) x.get("recovered")));
            return y;
        }).toList());
        LocalDate today = days.current().businessDate();
        m.put("inDefault", jdbc.queryForList("""
                SELECT l.loan_no, l.dpd, l.asset_class, l.principal_outstanding::numeric AS po, l.branch_code, c.originator_share, c.originator_rate, c.partner_rate
                  FROM lending.co_lent_loan c JOIN lending.loan_account l ON l.id = c.loan_id
                 WHERE c.arrangement_code = ? AND c.status = 'ACCEPTED' AND l.status IN ('ACTIVE','FROZEN') AND l.dpd > 0 ORDER BY l.dpd DESC, l.loan_no
                """, code).stream().filter(x -> scope.canSee((String) x.get("branch_code"))).map(x -> {
            Terms lt = new Terms((BigDecimal) x.get("originator_share"), (BigDecimal) x.get("originator_rate"), (BigDecimal) x.get("partner_rate"), null, null,
                    null, null);
            int dpd = ((Number) x.get("dpd")).intValue();
            Map<String, Object> y = new LinkedHashMap<>();
            y.put("loanNo", x.get("loan_no"));
            y.put("dpd", dpd);
            y.put("assetClass", x.get("asset_class"));
            y.put("partnerPrincipal", CoLendingService.plain(CoLending.partnerOutstanding(lt, (BigDecimal) x.get("po"))));
            y.put("invokeBy", today.plusDays(Math.max(0, CoLending.DLG_INVOKE_WITHIN_DAYS - dpd)).toString());
            return y;
        }).toList());
        return m;
    }

    @Transactional
    public ApprovalRequest proposeCover(String code, CoverInput in, String idempotencyKey) {
        Map<String, Object> a = arrangement(code);
        LocalDate today = days.requireOpen();
        List<String> problems = new ArrayList<>();
        if (!"ACTIVE".equals(a.get("status"))) problems.add("arrangement " + code + " is " + String.valueOf(a.get("status")).toLowerCase(Locale.ROOT));
        if (in == null) throw ApiException.invalid("the cover is required");
        DlgForm form = null;
        try {
            form = DlgForm.valueOf(String.valueOf(in.form()));
        } catch (IllegalArgumentException e) {
            problems.add("the form is CASH, FD_LIEN (a fixed deposit with a scheduled commercial bank under lien) or BANK_GUARANTEE");
        }
        if (in.amount() == null || in.amount().signum() <= 0 || in.amount().scale() > 2) problems.add("the amount is above zero, to the paisa");
        String ref = in.instrumentRef() == null ? null : in.instrumentRef().trim();
        if (form != null && form != DlgForm.CASH && (ref == null || ref.isEmpty())) problems.add("a fixed deposit or a guarantee is lodged with its number");
        if (ref != null && ref.length() > 60) problems.add("the instrument number is at most 60 characters");
        LocalDate issued = in.issuedOn() == null ? today : in.issuedOn();
        if (issued.isAfter(today)) problems.add("the cover is issued by today");
        if (in.validUntil() == null || !in.validUntil().isAfter(today)) problems.add("the cover is valid after today");
        LocalDate longest = longestTenor(code);
        if (in.validUntil() != null && longest != null && in.validUntil().isBefore(longest)) {
            problems.add("the cover lasts at least as long as the longest loan it covers (to " + longest + ")");
        }
        BigDecimal cap = CoLending.dlgCap(terms(a), partnerFunded(code));
        BigDecimal after = covered(code).add(in.amount() == null ? BigDecimal.ZERO : in.amount());
        if (after.compareTo(cap) > 0) {
            problems.add("the covers would be " + CoLendingService.plain(after) + ", above the cap of " + CoLendingService.plain(cap) + " ("
                    + CoLendingService.plain((BigDecimal) a.get("dlg_percent")) + "% of the partner's share funded)");
        }
        CoLendingService.refuseIfAny("the cover cannot be lodged", problems);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "COVER");
        payload.put("arrangementCode", code);
        payload.put("form", form.name());
        payload.put("amount", CoLendingService.plain(in.amount()));
        payload.put("instrumentRef", ref);
        payload.put("issuedOn", issued.toString());
        payload.put("validUntil", in.validUntil().toString());
        return approvals.propose(DLG, "COVER", code, payload, null, in.amount(), null, idempotencyKey);
    }

    @Transactional
    public ApprovalRequest proposeRelease(UUID coverId, ReasonInput in, String idempotencyKey) {
        days.requireOpen();
        Map<String, Object> c = cover(coverId);
        if (!"ACTIVE".equals(c.get("status"))) throw ApiException.conflict("cover " + c.get("cover_no") + " is released");
        if (in == null || in.reason() == null || in.reason().isBlank()) throw ApiException.invalid("a reason is required");
        BigDecimal left = ((BigDecimal) c.get("amount")).subtract(invoked(coverId));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RELEASE");
        payload.put("coverId", coverId.toString());
        payload.put("coverNo", c.get("cover_no"));
        payload.put("arrangementCode", c.get("arrangement_code"));
        payload.put("remaining", CoLendingService.plain(left));
        payload.put("reason", in.reason().trim());
        return approvals.propose(DLG, "RELEASE", (String) c.get("arrangement_code"), payload, null, left, null, idempotencyKey);
    }

    private Map<String, Object> cover(UUID id) {
        List<Map<String, Object>> c = jdbc.queryForList("SELECT * FROM lending.co_lending_dlg_cover WHERE id = ?", id);
        if (c.isEmpty()) throw ApiException.notFound("default loss guarantee cover " + id);
        return c.get(0);
    }

    @Transactional
    public ApprovalRequest proposeInvocation(String code, InvocationInput in, String idempotencyKey) {
        arrangement(code);
        days.requireOpen();
        if (in == null) throw ApiException.invalid("the invocation is required");
        List<String> problems = new ArrayList<>();
        String no = in.loanNo() == null ? "" : in.loanNo().trim();
        List<Map<String, Object>> f = jdbc.queryForList("""
                SELECT c.loan_id, c.status, c.arrangement_code, l.dpd, l.status AS loan_status, l.principal_outstanding::numeric AS po, l.branch_code,
                       c.originator_share, c.originator_rate, c.partner_rate
                  FROM lending.co_lent_loan c JOIN lending.loan_account l ON l.id = c.loan_id WHERE l.loan_no = ?
                """, no);
        Map<String, Object> loan = f.isEmpty() ? null : f.get(0);
        if (loan == null || !code.equals(loan.get("arrangement_code")) || !scope.canSee((String) loan.get("branch_code"))) {
            problems.add("loan " + no + " is not co-lent under " + code);
        } else if (!"ACCEPTED".equals(loan.get("status"))) {
            problems.add("loan " + no + ": the partner holds no share of it");
        } else if (((Number) loan.get("dpd")).intValue() <= 0) {
            problems.add("loan " + no + " is not overdue: the guarantee covers a loan in default");
        }
        Map<String, Object> c = null;
        try {
            c = cover(UUID.fromString(String.valueOf(in.coverId())));
        } catch (IllegalArgumentException | ApiException e) {
            problems.add("name a cover of " + code);
        }
        if (c != null && !code.equals(c.get("arrangement_code"))) problems.add("cover " + c.get("cover_no") + " is not under " + code);
        if (c != null && !"ACTIVE".equals(c.get("status"))) problems.add("cover " + c.get("cover_no") + " is released");
        if (in.amount() == null || in.amount().signum() <= 0 || in.amount().scale() > 2) problems.add("the amount is above zero, to the paisa");
        if (c != null && in.amount() != null && in.amount().compareTo(((BigDecimal) c.get("amount")).subtract(invoked((UUID) c.get("id")))) > 0) {
            problems.add("cover " + c.get("cover_no") + " has " + CoLendingService.plain(((BigDecimal) c.get("amount")).subtract(invoked((UUID) c.get("id"))))
                    + " left");
        }
        BigDecimal partner = BigDecimal.ZERO;
        if (loan != null && loan.get("po") != null) {
            Terms t = new Terms((BigDecimal) loan.get("originator_share"), (BigDecimal) loan.get("originator_rate"), (BigDecimal) loan.get("partner_rate"),
                    null, null, null, null);
            partner = CoLending.partnerOutstanding(t, (BigDecimal) loan.get("po"));
            if (in.amount() != null && in.amount().compareTo(partner) > 0) {
                problems.add("at most the partner's principal outstanding on the loan, " + CoLendingService.plain(partner));
            }
        }
        if (in.reason() == null || in.reason().isBlank()) problems.add("the partner's reason is required");
        CoLendingService.refuseIfAny("the guarantee cannot be invoked", problems);
        int dpd = ((Number) loan.get("dpd")).intValue();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "INVOKE");
        payload.put("arrangementCode", code);
        payload.put("coverId", c.get("id").toString());
        payload.put("coverNo", c.get("cover_no"));
        payload.put("loanNo", no);
        payload.put("amount", CoLendingService.plain(in.amount()));
        payload.put("dpd", dpd);
        payload.put("withinRule", dpd <= CoLending.DLG_INVOKE_WITHIN_DAYS);
        payload.put("reason", in.reason().trim());
        return approvals.propose(DLG, "INVOKE", code, payload, null, in.amount(), null, idempotencyKey);
    }

    @Transactional
    public ApprovalRequest proposeRecovery(UUID invocationId, RecoveryInput in, String idempotencyKey) {
        days.requireOpen();
        List<Map<String, Object>> f = jdbc.queryForList("""
                SELECT i.*, v.arrangement_code, l.loan_no,
                       (SELECT coalesce(sum(r.amount), 0) FROM lending.co_lending_dlg_recovery r WHERE r.invocation_id = i.id) AS recovered
                  FROM lending.co_lending_dlg_invocation i JOIN lending.co_lending_dlg_cover v ON v.id = i.cover_id JOIN lending.loan_account l ON l.id = i.loan_id
                 WHERE i.id = ?
                """, invocationId);
        if (f.isEmpty()) throw ApiException.notFound("invocation " + invocationId);
        Map<String, Object> i = f.get(0);
        BigDecimal left = ((BigDecimal) i.get("amount")).subtract((BigDecimal) i.get("recovered"));
        if (in == null || in.amount() == null || in.amount().signum() <= 0 || in.amount().scale() > 2) throw ApiException.invalid("the amount is above zero, to the paisa");
        if (in.amount().compareTo(left) > 0) throw ApiException.invalid("at most what was invoked and not yet recovered, " + CoLendingService.plain(left));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RECOVER");
        payload.put("arrangementCode", i.get("arrangement_code"));
        payload.put("invocationId", invocationId.toString());
        payload.put("loanNo", i.get("loan_no"));
        payload.put("amount", CoLendingService.plain(in.amount()));
        payload.put("reference", in.reference() == null ? null : in.reference().trim());
        return approvals.propose(DLG, "RECOVER", (String) i.get("arrangement_code"), payload, null, in.amount(), null, idempotencyKey);
    }

    String applyDlg(ApprovalRequest r) {
        LocalDate today = days.requireOpen();
        Map<String, Object> p = r.payload();
        DlgHeads h = heads();
        String ho = headOffice();
        switch (String.valueOf(p.get("kind"))) {
            case "COVER" -> {
                String code = (String) p.get("arrangementCode");
                BigDecimal amount = new BigDecimal((String) p.get("amount"));
                Map<String, Object> a = arrangement(code);
                BigDecimal cap = CoLending.dlgCap(terms(a), partnerFunded(code));
                if (covered(code).add(amount).compareTo(cap) > 0) throw ApiException.conflict("the covers would pass the cap of " + CoLendingService.plain(cap));
                jdbc.queryForObject("SELECT ledger.load_co_lending_dlg_heads()", Integer.class);
                DlgForm form = DlgForm.valueOf((String) p.get("form"));
                String no = "DLG" + String.format("%06d", jdbc.queryForObject("SELECT nextval('lending.co_lending_dlg_seq')", Long.class));
                TransactionLot lot = CoLending.dlgLodged(form, ho, h, no, amount, today);
                if (lot != null) posting.post(lot, r.maker());
                jdbc.update("""
                        INSERT INTO lending.co_lending_dlg_cover (id, cover_no, arrangement_code, form, amount, instrument_ref, issued_on, valid_until, lot_id,
                                                                  approval_id, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?::date, ?::date, ?, ?, ?)
                        """, UUID.randomUUID(), no, code, form.name(), amount, p.get("instrumentRef"), p.get("issuedOn"), p.get("validUntil"),
                        lot == null ? null : lot.id(), r.id(), r.maker());
                return no + ": " + form + " cover of " + CoLendingService.plain(amount) + " for " + code;
            }
            case "RELEASE" -> {
                UUID id = UUID.fromString((String) p.get("coverId"));
                Map<String, Object> c = cover(id);
                if (!"ACTIVE".equals(c.get("status"))) throw ApiException.conflict("cover " + c.get("cover_no") + " is released");
                BigDecimal left = ((BigDecimal) c.get("amount")).subtract(invoked(id));
                TransactionLot lot = CoLending.dlgReleased(DlgForm.valueOf((String) c.get("form")), ho, h, (String) c.get("cover_no"), left, today);
                if (lot != null) posting.post(lot, r.maker());
                jdbc.update("UPDATE lending.co_lending_dlg_cover SET status = 'RELEASED', released_on = ?, release_lot_id = ? WHERE id = ?", today,
                        lot == null ? null : lot.id(), id);
                return c.get("cover_no") + " released (" + CoLendingService.plain(left) + " not invoked)";
            }
            case "INVOKE" -> {
                UUID coverId = UUID.fromString((String) p.get("coverId"));
                Map<String, Object> c = cover(coverId);
                BigDecimal amount = new BigDecimal((String) p.get("amount"));
                String loanNo = (String) p.get("loanNo");
                UUID loanId = jdbc.queryForObject("SELECT id FROM lending.loan_account WHERE loan_no = ?", UUID.class, loanNo);
                TransactionLot lot = CoLending.dlgInvoked(DlgForm.valueOf((String) c.get("form")), ho, h, (String) c.get("cover_no"), loanNo, amount, today);
                posting.post(lot, r.maker());
                jdbc.update("""
                        INSERT INTO lending.co_lending_dlg_invocation (id, cover_id, loan_id, business_date, amount, dpd, reason, lot_id, approval_id, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), coverId, loanId, today, amount, ((Number) p.get("dpd")).intValue(), p.get("reason"), lot.id(), r.id(), r.maker());
                return CoLendingService.plain(amount) + " paid to the partner under " + c.get("cover_no") + " for loan " + loanNo
                        + (Boolean.FALSE.equals(p.get("withinRule")) ? " (invoked after 120 days overdue)" : "");
            }
            case "RECOVER" -> {
                UUID invocationId = UUID.fromString((String) p.get("invocationId"));
                BigDecimal amount = new BigDecimal((String) p.get("amount"));
                TransactionLot lot = CoLending.dlgRecovered(ho, h, (String) p.get("loanNo"), amount, today);
                posting.post(lot, r.maker());
                jdbc.update("""
                        INSERT INTO lending.co_lending_dlg_recovery (id, invocation_id, business_date, amount, reference, lot_id, approval_id, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), invocationId, today, amount, p.get("reference"), lot.id(), r.id(), r.maker());
                return CoLendingService.plain(amount) + " recovered under the guarantee for loan " + p.get("loanNo");
            }
            default -> throw new IllegalStateException("unknown guarantee request " + p.get("kind"));
        }
    }

    private static String s(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @Service
    static class ClassificationApplier implements ApprovalApplier {
        private final CoLendingRisk risk;

        ClassificationApplier(@Lazy CoLendingRisk risk) {
            this.risk = risk;
        }

        @Override public String entityType() { return CLASSIFICATION; }

        @Override public String apply(ApprovalRequest r) { return risk.applyPartnerClasses(r); }
    }

    @Service
    static class DlgApplier implements ApprovalApplier {
        private final CoLendingRisk risk;

        DlgApplier(@Lazy CoLendingRisk risk) {
            this.risk = risk;
        }

        @Override public String entityType() { return DLG; }

        @Override public String apply(ApprovalRequest r) { return risk.applyDlg(r); }
    }
}
