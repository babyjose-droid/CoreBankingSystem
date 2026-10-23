package com.corebanking.lending.internal;

import com.corebanking.kernel.Csv;
import com.corebanking.ledger.PostingService;
import com.corebanking.ledger.TransactionLot;
import com.corebanking.lending.engine.CoLending;
import com.corebanking.lending.engine.CoLending.Sharing;
import com.corebanking.lending.engine.CoLending.Terms;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Co-lending as the originating lender (P5-1), under the RBI (Co-Lending Arrangements) Directions, 2025.
 *
 * <ul>
 *   <li>An arrangement with a partner (a bank, an AIFI or another NBFC) is agreed through a checker: each lender's
 *       share (at least 10% each) and rate, the servicing fee, how fees and penal charges are shared, the default loss
 *       guarantee (at most 5%), the products it covers and the borrower's single point of contact. A change binds
 *       loans sanctioned after it; each co-lent loan keeps the terms of its sanction.</li>
 *   <li>A loan sanctioned under an arrangement is priced at the blended rate and its KFS names both lenders. At
 *       disbursement the partner is offered its share; it is booked when the partner accepts within 15 calendar days
 *       (Dr co-lending escrow / Cr partner's share of co-lent loans, the partner's part of the principal outstanding).
 *       An offer declined or not accepted in time leaves the loan wholly with the originator (paras 22, 24).</li>
 *   <li>The partner's decisions arrive by file (or from the simulator) and are recorded through a checker.</li>
 *   <li>P5-2: each day-end divides what the borrower paid between the lenders ({@link CoLendingSplit}); a settlement
 *       pays the partner what is due through the escrow account, through a checker, with a statement.</li>
 * </ul>
 */
@Service
public class CoLendingService {

    static final String ARRANGEMENT = "CO_LENDING_ARRANGEMENT";
    static final String DECISION = "CO_LENDING_DECISION";
    static final String SETTLEMENT = "CO_LENDING_SETTLEMENT";

    /** openapi.yaml#/components/schemas/CoLendingArrangementInput. */
    public record ArrangementInput(String code, String name, String partnerName, String partnerKind, String partnerReference,
                                   String agreementReference, BigDecimal originatorShare, BigDecimal originatorRate, BigDecimal partnerRate,
                                   BigDecimal servicingFee, String feeSharing, String penalSharing, BigDecimal dlgPercent,
                                   List<String> productCodes, String contact) {}

    /** openapi.yaml#/components/schemas/CoLendingStatusInput. */
    public record StatusInput(String status, String reason) {}

    /** One partner decision: openapi.yaml#/components/schemas/CoLendingDecision. */
    public record DecisionInput(String loanNo, String decision, String partnerLoanRef, String reason) {}

    /** What a loan sanctioned under an arrangement takes from it. */
    public record Quote(String code, int version, Terms terms, String partnerName, String contact) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final PostingService posting;
    private final BusinessDays days;
    private final BranchScope scope;
    private final Json json;
    private final LoanStore store;

    CoLendingService(JdbcTemplate jdbc, ApprovalService approvals, PostingService posting, BusinessDays days, BranchScope scope, Json json,
                     LoanStore store) {
        this.store = store;
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.posting = posting;
        this.days = days;
        this.scope = scope;
        this.json = json;
    }

    // ================================================================================================ arrangements

    @Transactional
    public ApprovalRequest proposeArrangement(ArrangementInput in, String idempotencyKey) {
        List<String> problems = new ArrayList<>();
        if (in == null) throw ApiException.invalid("the arrangement is required");
        String code = in.code() == null ? null : in.code().trim().toUpperCase(java.util.Locale.ROOT);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,19}")) problems.add("the code is 2 to 20 capital letters, digits or _, starting with a letter");
        List<Map<String, Object>> existing = code == null ? List.of() : jdbc.queryForList(
                "SELECT status, partner_name, partner_kind FROM lending.co_lending_arrangement WHERE code = ?", code);
        boolean change = !existing.isEmpty();
        if (change && "CLOSED".equals(existing.get(0).get("status"))) problems.add("arrangement " + code + " is closed");
        if (change && (!String.valueOf(existing.get(0).get("partner_name")).equals(trim(in.partnerName()))
                || !String.valueOf(existing.get(0).get("partner_kind")).equals(in.partnerKind()))) {
            problems.add("the partner of an arrangement never changes; close it and agree a new one");
        }
        require(in.name(), 120, "the name", problems);
        require(in.partnerName(), 200, "the partner's name", problems);
        if (in.partnerKind() == null || !List.of("BANK", "AIFI", "NBFC").contains(in.partnerKind())) problems.add("the partner is a BANK, an AIFI or an NBFC");
        if (in.partnerReference() != null && in.partnerReference().length() > 60) problems.add("the partner's reference is at most 60 characters");
        require(in.agreementReference(), 100, "the reference of the co-lending agreement", problems);
        require(in.contact(), 200, "the borrower's single point of contact", problems);
        Terms t = null;
        if (in.originatorShare() == null || in.originatorRate() == null || in.partnerRate() == null) {
            problems.add("the originator's share and both lenders' rates are required");
        } else {
            t = new Terms(in.originatorShare(), in.originatorRate(), in.partnerRate(), in.servicingFee(), sharing(in.feeSharing(), Sharing.ORIGINATOR, problems),
                    sharing(in.penalSharing(), Sharing.BY_SHARE, problems), in.dlgPercent());
            problems.addAll(CoLending.problems(t));
        }
        List<String> products = in.productCodes() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(in.productCodes()));
        if (products.isEmpty()) problems.add("name the loan products the arrangement covers");
        for (String p : products) {
            List<Map<String, Object>> pr = jdbc.queryForList(
                    "SELECT multiple_disbursements, benchmark_code IS NOT NULL AS floating FROM lending.loan_product WHERE code = ?", p);
            if (pr.isEmpty()) problems.add("no loan product " + p);
            else if (Boolean.TRUE.equals(pr.get(0).get("multiple_disbursements"))) problems.add("product " + p + " is disbursed in tranches: it is not co-lent (open item P5-01)");
            else if (Boolean.TRUE.equals(pr.get(0).get("floating"))) problems.add("product " + p + " has a floating rate: a co-lent loan carries the fixed blended rate");
        }
        refuseIfAny(change ? "the arrangement cannot be changed" : "the arrangement cannot be agreed", problems);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("name", in.name().trim());
        payload.put("partnerName", in.partnerName().trim());
        payload.put("partnerKind", in.partnerKind());
        payload.put("partnerReference", trim(in.partnerReference()));
        payload.put("agreementReference", in.agreementReference().trim());
        payload.put("originatorShare", plain(t.originatorShare()));
        payload.put("partnerShare", plain(t.partnerShare()));
        payload.put("originatorRate", plain(t.originatorRate()));
        payload.put("partnerRate", plain(t.partnerRate()));
        payload.put("blendedRate", plain(t.blendedRate()));
        payload.put("servicingFee", plain(t.servicingFee()));
        payload.put("feeSharing", t.fees().name());
        payload.put("penalSharing", t.penal().name());
        payload.put("dlgPercent", plain(t.dlgPercent()));
        payload.put("productCodes", products);
        payload.put("contact", in.contact().trim());
        return approvals.propose(ARRANGEMENT, change ? "CHANGE" : "CREATE", code, payload, change ? arrangement(code) : null, null, null, idempotencyKey);
    }

    @Transactional
    public ApprovalRequest proposeStatus(String code, StatusInput in, String idempotencyKey) {
        Map<String, Object> a = arrangement(code);
        String to = in == null ? null : in.status();
        List<String> problems = new ArrayList<>();
        String from = (String) a.get("status");
        if (to == null || !List.of("ACTIVE", "SUSPENDED", "CLOSED").contains(to)) problems.add("the status is ACTIVE, SUSPENDED or CLOSED");
        else if (to.equals(from)) problems.add("arrangement " + code + " is already " + from);
        else if ("CLOSED".equals(from)) problems.add("arrangement " + code + " is closed");
        if ("CLOSED".equals(to)) {
            Integer open = jdbc.queryForObject("SELECT count(*)::int FROM lending.co_lent_loan WHERE arrangement_code = ? AND status IN ('SANCTIONED','OFFERED')",
                    Integer.class, code);
            if (open != null && open > 0) problems.add(open + " loan(s) under it are sanctioned or offered to the partner: settle them first");
        }
        String reason = in == null ? null : trim(in.reason());
        if (reason == null || reason.isEmpty()) problems.add("a reason is required");
        refuseIfAny("the status cannot change", problems);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("status", to);
        payload.put("reason", reason);
        return approvals.propose(ARRANGEMENT, to, code, payload, Map.of("status", from), null, null, idempotencyKey);
    }

    String applyArrangement(ApprovalRequest r) {
        Map<String, Object> p = r.payload();
        String code = (String) p.get("code");
        jdbc.queryForObject("SELECT ledger.load_co_lending_heads()", Integer.class);
        jdbc.queryForObject("SELECT ledger.load_co_lending_dlg_heads()", Integer.class);
        LocalDate today = days.requireOpen();
        switch (r.action()) {
            case "CREATE" -> jdbc.update("""
                    INSERT INTO lending.co_lending_arrangement (code, name, partner_name, partner_kind, partner_reference, agreement_reference,
                        originator_share, originator_rate, partner_rate, servicing_fee, fee_sharing, penal_sharing, dlg_percent, product_codes,
                        contact, effective_from, created_by, approval_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?)
                    """, code, p.get("name"), p.get("partnerName"), p.get("partnerKind"), p.get("partnerReference"), p.get("agreementReference"),
                    dec(p.get("originatorShare")), dec(p.get("originatorRate")), dec(p.get("partnerRate")), dec(p.get("servicingFee")),
                    p.get("feeSharing"), p.get("penalSharing"), dec(p.get("dlgPercent")), textArray(p.get("productCodes")), p.get("contact"), today,
                    r.maker(), r.id());
            case "CHANGE" -> jdbc.update("""
                    UPDATE lending.co_lending_arrangement SET name = ?, partner_reference = ?, agreement_reference = ?, originator_share = ?,
                           originator_rate = ?, partner_rate = ?, servicing_fee = ?, fee_sharing = ?, penal_sharing = ?, dlg_percent = ?,
                           product_codes = ?::text[], contact = ?, effective_from = ?, approval_id = ?, updated_by = ?
                     WHERE code = ?
                    """, p.get("name"), p.get("partnerReference"), p.get("agreementReference"), dec(p.get("originatorShare")), dec(p.get("originatorRate")),
                    dec(p.get("partnerRate")), dec(p.get("servicingFee")), p.get("feeSharing"), p.get("penalSharing"), dec(p.get("dlgPercent")),
                    textArray(p.get("productCodes")), p.get("contact"), today, r.id(), r.maker(), code);
            default -> {
                int n = jdbc.update("UPDATE lending.co_lending_arrangement SET status = ?, updated_by = ?, approval_id = ? WHERE code = ? AND status <> 'CLOSED'",
                        p.get("status"), r.maker(), r.id(), code);
                if (n == 0) throw ApiException.conflict("arrangement " + code + " is closed");
            }
        }
        return code;
    }

    /** A text[] literal: each element quoted (product codes are checked to exist before they get here). */
    private static String textArray(Object v) {
        List<?> l = v instanceof List<?> x ? x : List.of();
        return "{" + String.join(",", l.stream().map(o -> "\"" + String.valueOf(o).replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList()) + "}";
    }

    /** Every arrangement with what is under it. */
    public List<Map<String, Object>> arrangements() {
        return jdbc.queryForList("SELECT code FROM lending.co_lending_arrangement ORDER BY status, code", String.class).stream().map(this::arrangement).toList();
    }

    public Map<String, Object> arrangement(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT code, name, role, partner_name, partner_kind, partner_reference, agreement_reference, originator_share::numeric,
                       originator_rate::numeric, partner_rate::numeric, servicing_fee::numeric, fee_sharing, penal_sharing, dlg_percent::numeric,
                       array_to_string(product_codes, ',') AS products, contact, status, effective_from, version, created_by, updated_by
                  FROM lending.co_lending_arrangement WHERE code = ?
                """, code);
        if (rows.isEmpty()) throw ApiException.notFound("co-lending arrangement " + code);
        Map<String, Object> a = rows.get(0);
        Terms t = terms(a);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", a.get("code"));
        m.put("name", a.get("name"));
        m.put("role", a.get("role"));
        m.put("partnerName", a.get("partner_name"));
        m.put("partnerKind", a.get("partner_kind"));
        m.put("partnerReference", a.get("partner_reference"));
        m.put("agreementReference", a.get("agreement_reference"));
        m.put("originatorShare", plain(t.originatorShare()));
        m.put("partnerShare", plain(t.partnerShare()));
        m.put("originatorRate", plain(t.originatorRate()));
        m.put("partnerRate", plain(t.partnerRate()));
        m.put("blendedRate", plain(t.blendedRate()));
        m.put("servicingFee", plain(t.servicingFee()));
        m.put("feeSharing", t.fees().name());
        m.put("penalSharing", t.penal().name());
        m.put("dlgPercent", plain(t.dlgPercent()));
        m.put("productCodes", List.of(String.valueOf(a.get("products")).split(",")));
        m.put("contact", a.get("contact"));
        m.put("status", a.get("status"));
        m.put("effectiveFrom", String.valueOf(a.get("effective_from")));
        m.put("version", a.get("version"));
        m.put("createdBy", a.get("created_by"));
        m.put("updatedBy", a.get("updated_by"));
        Map<String, Object> book = new LinkedHashMap<>();
        for (Map<String, Object> s : jdbc.queryForList("""
                SELECT c.status, count(*)::int AS n, coalesce(sum(c.partner_principal), 0)::numeric AS partner
                  FROM lending.co_lent_loan c WHERE c.arrangement_code = ? GROUP BY c.status
                """, code)) {
            book.put(((String) s.get("status")).toLowerCase(java.util.Locale.ROOT), ((Number) s.get("n")).intValue());
            if ("ACCEPTED".equals(s.get("status"))) m.put("partnerPrincipalBooked", plain((BigDecimal) s.get("partner")));
        }
        m.put("loans", book);
        m.putIfAbsent("partnerPrincipalBooked", "0");
        return m;
    }

    private static Terms terms(Map<String, Object> a) {
        return new Terms((BigDecimal) a.get("originator_share"), (BigDecimal) a.get("originator_rate"), (BigDecimal) a.get("partner_rate"),
                (BigDecimal) a.get("servicing_fee"), Sharing.valueOf((String) a.get("fee_sharing")), Sharing.valueOf((String) a.get("penal_sharing")),
                (BigDecimal) a.get("dlg_percent"));
    }

    // ================================================================================================ loans

    /** The terms a loan of {@code productCode} takes from the arrangement: refused when it is not active or does not cover it. */
    Quote quote(String code, String productCode) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT code, version, status, partner_name, contact, originator_share::numeric, originator_rate::numeric, partner_rate::numeric,
                       servicing_fee::numeric, fee_sharing, penal_sharing, dlg_percent::numeric, ? = ANY (product_codes) AS covers
                  FROM lending.co_lending_arrangement WHERE code = ?
                """, productCode, code);
        if (rows.isEmpty()) throw ApiException.invalid("no co-lending arrangement " + code);
        Map<String, Object> a = rows.get(0);
        List<String> problems = new ArrayList<>();
        if (!"ACTIVE".equals(a.get("status"))) problems.add("co-lending arrangement " + code + " is " + a.get("status") + ": it takes no new loan");
        if (!Boolean.TRUE.equals(a.get("covers"))) problems.add("product " + productCode + " is not under co-lending arrangement " + code);
        refuseIfAny("the loan cannot be co-lent", problems);
        return new Quote(code, ((Number) a.get("version")).intValue(), terms(a), (String) a.get("partner_name"), (String) a.get("contact"));
    }

    /** What the KFS says of the two lenders (RBI KFS circular of 15-Apr-2024; Directions para 19). */
    static Map<String, Object> kfsSection(Quote q, String originatorName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("arrangementCode", q.code());
        m.put("lenders", List.of(lender(originatorName, "Originating lender", q.terms().originatorShare(), q.terms().originatorRate()),
                lender(q.partnerName(), "Partner lender", q.terms().partnerShare(), q.terms().partnerRate())));
        m.put("blendedRate", plain(q.terms().blendedRate()));
        m.put("singlePointOfContact", q.contact());
        m.put("note", "This loan is lent jointly by the lenders above under a co-lending arrangement; the interest rate is the blended rate. "
                + "The originating lender services the loan and is the single point of contact.");
        return m;
    }

    private static Map<String, Object> lender(String name, String role, BigDecimal share, BigDecimal rate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("role", role);
        m.put("sharePercent", plain(share));
        m.put("ratePercent", plain(rate));
        return m;
    }

    /** Records a just-sanctioned loan under the arrangement, its terms frozen. */
    void attach(UUID loanId, Quote q) {
        Terms t = q.terms();
        jdbc.update("""
                INSERT INTO lending.co_lent_loan (loan_id, arrangement_code, arrangement_version, originator_share, originator_rate, partner_rate,
                    blended_rate, servicing_fee, fee_sharing, penal_sharing, dlg_percent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, loanId, q.code(), q.version(), t.originatorShare(), t.originatorRate(), t.partnerRate(), t.blendedRate(), t.servicingFee(),
                t.fees().name(), t.penal().name(), t.dlgPercent());
    }

    /** At the first disbursement: the partner is offered its share, open for 15 calendar days. */
    void offer(UUID loanId) {
        jdbc.update("UPDATE lending.co_lent_loan SET status = 'OFFERED' WHERE loan_id = ? AND status = 'SANCTIONED'", loanId);
    }

    /** The disbursement is to be reversed: refused once the partner holds its share; an open offer is taken back. */
    void beforeDisbursementReversal(UUID loanId) {
        List<String> s = jdbc.queryForList("SELECT status FROM lending.co_lent_loan WHERE loan_id = ?", String.class, loanId);
        if (s.isEmpty()) return;
        if ("ACCEPTED".equals(s.get(0))) {
            throw ApiException.conflict("the co-lending partner holds its share of this loan: the disbursement is not reversed as a whole");
        }
        if ("OFFERED".equals(s.get(0))) jdbc.update("UPDATE lending.co_lent_loan SET status = 'SANCTIONED' WHERE loan_id = ?", loanId);
    }

    /** A loan's co-lending, for its page; null when it is not co-lent. */
    public Map<String, Object> loanView(UUID loanId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.*, a.partner_name, l.principal_outstanding::numeric AS principal
                  FROM lending.co_lent_loan c JOIN lending.co_lending_arrangement a ON a.code = c.arrangement_code
                  JOIN lending.loan_account l ON l.id = c.loan_id WHERE c.loan_id = ?
                """, loanId);
        if (rows.isEmpty()) return null;
        Map<String, Object> c = rows.get(0);
        Terms t = new Terms((BigDecimal) c.get("originator_share"), (BigDecimal) c.get("originator_rate"), (BigDecimal) c.get("partner_rate"),
                (BigDecimal) c.get("servicing_fee"), Sharing.valueOf((String) c.get("fee_sharing")), Sharing.valueOf((String) c.get("penal_sharing")),
                (BigDecimal) c.get("dlg_percent"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("arrangementCode", c.get("arrangement_code"));
        m.put("arrangementVersion", c.get("arrangement_version"));
        m.put("partnerName", c.get("partner_name"));
        m.put("originatorShare", plain(t.originatorShare()));
        m.put("partnerShare", plain(t.partnerShare()));
        m.put("originatorRate", plain(t.originatorRate()));
        m.put("partnerRate", plain(t.partnerRate()));
        m.put("blendedRate", plain(t.blendedRate()));
        m.put("servicingFee", plain(t.servicingFee()));
        m.put("feeSharing", t.fees().name());
        m.put("penalSharing", t.penal().name());
        m.put("dlgPercent", plain(t.dlgPercent()));
        m.put("status", c.get("status"));
        m.put("offeredOn", str(c.get("offered_on")));
        m.put("deadline", str(c.get("deadline")));
        m.put("decidedOn", str(c.get("decided_on")));
        m.put("decisionReason", c.get("decision_reason"));
        m.put("partnerLoanRef", c.get("partner_loan_ref"));
        m.put("partnerPrincipalAtTransfer", c.get("partner_principal") == null ? null : plain((BigDecimal) c.get("partner_principal")));
        BigDecimal principal = (BigDecimal) c.get("principal");
        if ("ACCEPTED".equals(c.get("status"))) {
            CoLending.Split now = CoLending.byShare(t, principal);
            m.put("originatorPrincipal", plain(now.originator()));
            m.put("partnerPrincipal", plain(now.partner()));
            List<Map<String, Object>> pos = jdbc.queryForList("""
                    SELECT p.as_of, p.principal, p.interest, (SELECT coalesce(sum(value::numeric), 0) FROM jsonb_each_text(p.fees)) AS fees, p.penal,
                           p.servicing, (SELECT coalesce(sum(x.net), 0) FROM lending.co_lending_split x WHERE x.loan_id = p.loan_id AND x.settlement_id IS NULL)
                             AS unsettled
                      FROM lending.co_lent_position p WHERE p.loan_id = ?
                    """, loanId);
            if (!pos.isEmpty()) {
                Map<String, Object> p = pos.get(0);
                m.put("splitAsOf", str(p.get("as_of")));
                m.put("partnerPrincipalRepaid", plain((BigDecimal) p.get("principal")));
                m.put("partnerInterest", plain((BigDecimal) p.get("interest")));
                m.put("partnerFees", plain((BigDecimal) p.get("fees")));
                m.put("partnerPenal", plain((BigDecimal) p.get("penal")));
                m.put("servicingFeeTaken", plain((BigDecimal) p.get("servicing")));
                m.put("unsettled", plain((BigDecimal) p.get("unsettled")));
            }
            List<Map<String, Object>> pc = jdbc.queryForList("""
                    SELECT reported_on, asset_class, npa_since, outcome FROM lending.co_lending_partner_class
                     WHERE loan_id = ? ORDER BY reported_on DESC, created_at DESC LIMIT 1
                    """, loanId);
            if (!pc.isEmpty()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("reportedOn", str(pc.get(0).get("reported_on")));
                x.put("assetClass", pc.get(0).get("asset_class"));
                x.put("npaSince", str(pc.get(0).get("npa_since")));
                x.put("outcome", pc.get(0).get("outcome"));
                m.put("partnerClass", x);
            }
        }
        return m;
    }

    /** The rate of a co-lent loan is the arrangement's blended rate: it is not amended (each lender's rate is agreed). */
    public void refuseRateChange(UUID loanId) {
        List<String> s = jdbc.queryForList("SELECT arrangement_code FROM lending.co_lent_loan WHERE loan_id = ? AND status IN ('SANCTIONED','OFFERED','ACCEPTED')",
                String.class, loanId);
        if (!s.isEmpty()) {
            throw ApiException.conflict("the loan is co-lent under " + s.get(0) + ": its rate is the arrangement's blended rate and is not amended");
        }
    }

    /** Restructuring a loan whose share the partner holds needs both lenders; it comes with the stress handling of P5-3. */
    public void refuseRestructure(UUID loanId) {
        List<String> s = jdbc.queryForList("SELECT arrangement_code FROM lending.co_lent_loan WHERE loan_id = ? AND status = 'ACCEPTED'", String.class, loanId);
        if (!s.isEmpty()) {
            throw ApiException.conflict("the partner of " + s.get(0) + " holds a share of this loan: restructuring a co-lent loan is not built yet (open item P5-03)");
        }
    }

    // ================================================================================================ settlement (P5-2)

    /** What is due to the partner and not yet settled, by loan, from the splits of the day-ends so far. */
    public Map<String, Object> unsettled(String code) {
        arrangement(code);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.id AS loan_id, l.loan_no, l.branch_code, max(x.business_date) AS last_split, sum(x.principal) AS principal, sum(x.interest) AS interest,
                       sum(x.fees) AS fees, sum(x.penal) AS penal, sum(x.servicing) AS servicing, sum(x.net) AS net
                  FROM lending.co_lending_split x JOIN lending.co_lent_loan c ON c.loan_id = x.loan_id JOIN lending.loan_account l ON l.id = x.loan_id
                 WHERE c.arrangement_code = ? AND x.settlement_id IS NULL
                 GROUP BY l.id, l.loan_no, l.branch_code ORDER BY l.loan_no
                """, code);
        List<Map<String, Object>> loans = new ArrayList<>();
        BigDecimal[] tot = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        String[] keys = {"principal", "interest", "fees", "penal", "servicing", "net"};
        for (Map<String, Object> r : rows) {
            if (!scope.canSee((String) r.get("branch_code"))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("loanId", r.get("loan_id").toString());
            m.put("loanNo", r.get("loan_no"));
            m.put("branchCode", r.get("branch_code"));
            m.put("lastSplit", str(r.get("last_split")));
            for (int i = 0; i < keys.length; i++) {
                BigDecimal v = (BigDecimal) r.get(keys[i]);
                m.put(keys[i], plain(v));
                tot[i] = tot[i].add(v);
            }
            loans.add(m);
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) totals.put(keys[i], plain(tot[i]));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arrangementCode", code);
        out.put("asOf", str(jdbc.queryForObject("""
                SELECT max(p.as_of) FROM lending.co_lent_position p JOIN lending.co_lent_loan c ON c.loan_id = p.loan_id WHERE c.arrangement_code = ?
                """, java.sql.Date.class, code)));
        out.put("loans", loans);
        out.put("totals", totals);
        return out;
    }

    /** A settlement with the partner: pays what is due through escrow, once a checker approves it. */
    @Transactional
    public ApprovalRequest proposeSettlement(String code, String idempotencyKey) {
        Map<String, Object> a = arrangement(code);
        days.requireOpen();
        Integer pending = jdbc.queryForObject("SELECT count(*)::int FROM platform.approval_request WHERE entity_type = ? AND entity_id = ? AND status = 'PENDING'",
                Integer.class, SETTLEMENT, code);
        if (pending != null && pending > 0) throw ApiException.conflict("a settlement of " + code + " is already waiting for a checker");
        List<String> ids = jdbc.queryForList("""
                SELECT x.id::text FROM lending.co_lending_split x JOIN lending.co_lent_loan c ON c.loan_id = x.loan_id
                 WHERE c.arrangement_code = ? AND x.settlement_id IS NULL ORDER BY x.business_date, x.id
                """, String.class, code);
        Map<String, Object> due = unsettled(code);
        @SuppressWarnings("unchecked") Map<String, Object> totals = (Map<String, Object>) due.get("totals");
        BigDecimal net = new BigDecimal((String) totals.get("net"));
        if (ids.isEmpty() || net.signum() <= 0) throw ApiException.conflict("nothing is due to the partner of " + code);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("arrangementCode", code);
        payload.put("partnerName", a.get("partnerName"));
        payload.put("asOf", due.get("asOf"));
        payload.put("loans", ((List<?>) due.get("loans")).size());
        payload.putAll(totals);
        payload.put("splitIds", ids);
        return approvals.propose(SETTLEMENT, "SETTLE", code, payload, null, net, null, idempotencyKey);
    }

    String applySettlement(ApprovalRequest r) {
        LocalDate today = days.requireOpen();
        String code = (String) r.payload().get("arrangementCode");
        List<UUID> ids = ((List<?>) r.payload().get("splitIds")).stream().map(o -> UUID.fromString(String.valueOf(o))).toList();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT x.id, x.settlement_id, x.principal, x.interest, x.fees, x.penal, x.servicing, x.net, c.arrangement_code, l.loan_no, l.branch_code
                  FROM lending.co_lending_split x JOIN lending.co_lent_loan c ON c.loan_id = x.loan_id JOIN lending.loan_account l ON l.id = x.loan_id
                 WHERE x.id = ANY (?::uuid[]) FOR UPDATE OF x
                """, "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}");
        if (rows.size() != ids.size() || rows.stream().anyMatch(x -> x.get("settlement_id") != null || !code.equals(x.get("arrangement_code")))) {
            throw ApiException.conflict("some of these splits were settled meanwhile: propose the settlement again");
        }
        Map<String, BigDecimal> byLoan = new LinkedHashMap<>();
        Map<String, String> branch = new LinkedHashMap<>();
        BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Map<String, Object> x : rows) {
            byLoan.merge((String) x.get("loan_no"), (BigDecimal) x.get("net"), BigDecimal::add);
            branch.put((String) x.get("loan_no"), (String) x.get("branch_code"));
            t[0] = t[0].add((BigDecimal) x.get("principal"));
            t[1] = t[1].add((BigDecimal) x.get("interest"));
            t[2] = t[2].add((BigDecimal) x.get("fees"));
            t[3] = t[3].add((BigDecimal) x.get("penal"));
            t[4] = t[4].add((BigDecimal) x.get("servicing"));
        }
        BigDecimal net = t[0].add(t[1]).add(t[2]).add(t[3]).subtract(t[4]);
        if (net.signum() <= 0) throw ApiException.conflict("nothing is due to the partner of " + code);
        String no = "CLS" + String.format("%08d", jdbc.queryForObject("SELECT nextval('lending.co_lending_settlement_seq')", Long.class));
        List<CoLending.SettlementLine> lines = byLoan.entrySet().stream().map(e -> new CoLending.SettlementLine(branch.get(e.getKey()), e.getKey(), e.getValue())).toList();
        TransactionLot lot = CoLending.settlementLot(CoLendingSplit.heads(jdbc), lines, no, today);
        posting.post(lot, r.maker());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO lending.co_lending_settlement (id, settlement_no, arrangement_code, business_date, loans, principal, interest, fees, penal, servicing,
                                                           net, lot_id, approval_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, no, code, today, byLoan.size(), t[0], t[1], t[2], t[3], t[4], net, lot.id(), r.id(), r.maker());
        jdbc.update("UPDATE lending.co_lending_split SET settlement_id = ? WHERE id = ANY (?::uuid[])", id,
                "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}");
        return no + ": " + plain(net) + " paid to the partner of " + code + " through escrow (" + byLoan.size() + " loan" + (byLoan.size() == 1 ? "" : "s") + ")";
    }

    public List<Map<String, Object>> settlements(String code) {
        arrangement(code);
        return jdbc.queryForList("SELECT * FROM lending.co_lending_settlement WHERE arrangement_code = ? ORDER BY created_at DESC", code).stream()
                .map(CoLendingService::settlementRow).toList();
    }

    private static Map<String, Object> settlementRow(Map<String, Object> x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.get("id").toString());
        m.put("settlementNo", x.get("settlement_no"));
        m.put("arrangementCode", x.get("arrangement_code"));
        m.put("businessDate", str(x.get("business_date")));
        m.put("loans", x.get("loans"));
        for (String k : List.of("principal", "interest", "fees", "penal", "servicing", "net")) m.put(k, plain((BigDecimal) x.get(k)));
        m.put("createdBy", x.get("created_by"));
        return m;
    }

    /** A settlement with what it paid for each loan. */
    public Map<String, Object> settlement(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM lending.co_lending_settlement WHERE id = ?", id);
        if (rows.isEmpty()) throw ApiException.notFound("co-lending settlement " + id);
        Map<String, Object> m = settlementRow(rows.get(0));
        m.put("lines", jdbc.queryForList("""
                SELECT l.loan_no, l.branch_code, sum(x.principal) AS principal, sum(x.interest) AS interest, sum(x.fees) AS fees, sum(x.penal) AS penal,
                       sum(x.servicing) AS servicing, sum(x.net) AS net
                  FROM lending.co_lending_split x JOIN lending.loan_account l ON l.id = x.loan_id WHERE x.settlement_id = ?
                 GROUP BY l.loan_no, l.branch_code ORDER BY l.loan_no
                """, id).stream().map(x -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("loanNo", x.get("loan_no"));
            line.put("branchCode", x.get("branch_code"));
            for (String k : List.of("principal", "interest", "fees", "penal", "servicing", "net")) line.put(k, plain((BigDecimal) x.get(k)));
            return line;
        }).toList());
        return m;
    }

    /** The settlement statement for the partner: one line per loan and the total. */
    public byte[] statementFile(UUID id) {
        Map<String, Object> st = settlement(id);
        StringBuilder sb = new StringBuilder(Csv.line(List.of("settlement_no", "arrangement", "business_date", "loan_no", "principal", "interest", "fees",
                "penal_charges", "servicing_fee", "net_paid")));
        for (Object o : (List<?>) st.get("lines")) {
            Map<?, ?> l = (Map<?, ?>) o;
            sb.append(Csv.line(List.of(s(st.get("settlementNo")), s(st.get("arrangementCode")), s(st.get("businessDate")), s(l.get("loanNo")), s(l.get("principal")),
                    s(l.get("interest")), s(l.get("fees")), s(l.get("penal")), s(l.get("servicing")), s(l.get("net")))));
        }
        sb.append(Csv.line(List.of(s(st.get("settlementNo")), s(st.get("arrangementCode")), s(st.get("businessDate")), "TOTAL", s(st.get("principal")),
                s(st.get("interest")), s(st.get("fees")), s(st.get("penal")), s(st.get("servicing")), s(st.get("net")))));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ================================================================================================ offers and decisions

    /** The loans offered to the partner of an arrangement and waiting for its decision, oldest deadline first. */
    public List<Map<String, Object>> offers(String code) {
        arrangement(code);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> x : jdbc.queryForList("""
                SELECT l.id, l.loan_no, l.branch_code, c.customer_no, l.disbursed_on, c2.deadline, l.sanctioned_amount::numeric AS amount,
                       l.principal_outstanding::numeric AS principal, l.tenor_months, l.emi::numeric AS emi, l.asset_class, l.dpd,
                       c2.originator_share::numeric, c2.originator_rate::numeric, c2.partner_rate::numeric, c2.servicing_fee::numeric,
                       c2.fee_sharing, c2.penal_sharing, c2.dlg_percent::numeric, c2.blended_rate::numeric
                  FROM lending.co_lent_loan c2 JOIN lending.loan_account l ON l.id = c2.loan_id JOIN customer.customer c ON c.id = l.customer_id
                 WHERE c2.arrangement_code = ? AND c2.status = 'OFFERED' ORDER BY c2.deadline, l.loan_no
                """, code)) {
            if (!scope.canSee((String) x.get("branch_code"))) continue;
            Terms t = new Terms((BigDecimal) x.get("originator_share"), (BigDecimal) x.get("originator_rate"), (BigDecimal) x.get("partner_rate"),
                    (BigDecimal) x.get("servicing_fee"), Sharing.valueOf((String) x.get("fee_sharing")), Sharing.valueOf((String) x.get("penal_sharing")),
                    (BigDecimal) x.get("dlg_percent"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("loanId", x.get("id").toString());
            m.put("loanNo", x.get("loan_no"));
            m.put("branchCode", x.get("branch_code"));
            m.put("customerNo", x.get("customer_no"));
            m.put("disbursedOn", str(x.get("disbursed_on")));
            m.put("deadline", str(x.get("deadline")));
            m.put("amount", plain((BigDecimal) x.get("amount")));
            m.put("principalOutstanding", plain((BigDecimal) x.get("principal")));
            m.put("partnerShare", plain(t.partnerShare()));
            m.put("partnerPrincipal", plain(CoLending.byShare(t, (BigDecimal) x.get("principal")).partner()));
            m.put("partnerRate", plain(t.partnerRate()));
            m.put("blendedRate", plain((BigDecimal) x.get("blended_rate")));
            m.put("tenorMonths", x.get("tenor_months"));
            m.put("emi", x.get("emi") == null ? null : plain((BigDecimal) x.get("emi")));
            m.put("assetClass", x.get("asset_class"));
            m.put("dpd", x.get("dpd"));
            out.add(m);
        }
        return out;
    }

    /** The offers as the CSV file sent to the partner. */
    public byte[] offersFile(String code) {
        List<Map<String, Object>> rows = offers(code);
        List<String> head = List.of("loan_no", "customer_no", "disbursed_on", "deadline", "sanctioned_amount", "principal_outstanding", "partner_share_percent",
                "partner_principal", "partner_rate", "blended_rate", "tenor_months", "emi", "asset_class", "dpd");
        StringBuilder sb = new StringBuilder(Csv.line(head));
        for (Map<String, Object> r : rows) {
            sb.append(Csv.line(List.of(s(r.get("loanNo")), s(r.get("customerNo")), s(r.get("disbursedOn")), s(r.get("deadline")), s(r.get("amount")),
                    s(r.get("principalOutstanding")), s(r.get("partnerShare")), s(r.get("partnerPrincipal")), s(r.get("partnerRate")), s(r.get("blendedRate")),
                    s(r.get("tenorMonths")), s(r.get("emi")), s(r.get("assetClass")), s(r.get("dpd")))));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The partner's decisions on offered loans (from its file, or the simulator), recorded through a checker. */
    @Transactional
    public ApprovalRequest proposeDecisions(String code, List<DecisionInput> decisions, String idempotencyKey) {
        arrangement(code);
        LocalDate today = days.requireOpen();
        if (decisions == null || decisions.isEmpty()) throw ApiException.invalid("no decision given");
        if (decisions.size() > 1000) throw ApiException.invalid("at most 1,000 decisions at a time");
        List<String> problems = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DecisionInput d : decisions) {
            String no = d == null ? null : trim(d.loanNo());
            if (no == null || no.isEmpty()) { problems.add("a decision without a loan number"); continue; }
            if (!seen.add(no)) { problems.add("loan " + no + " is decided twice"); continue; }
            String decision = d.decision() == null ? null : d.decision().trim().toUpperCase(java.util.Locale.ROOT);
            if (!"ACCEPT".equals(decision) && !"DECLINE".equals(decision)) { problems.add("loan " + no + ": the decision is ACCEPT or DECLINE"); continue; }
            List<Map<String, Object>> found = jdbc.queryForList("""
                    SELECT c.status, c.deadline, c.arrangement_code, l.branch_code, l.principal_outstanding::numeric AS principal, c.originator_share::numeric
                      FROM lending.co_lent_loan c JOIN lending.loan_account l ON l.id = c.loan_id WHERE l.loan_no = ?
                    """, no);
            if (found.isEmpty() || !code.equals(found.get(0).get("arrangement_code"))) { problems.add("loan " + no + " is not co-lent under " + code); continue; }
            Map<String, Object> f = found.get(0);
            if (!scope.canSee((String) f.get("branch_code"))) { problems.add("loan " + no + " is not co-lent under " + code); continue; }
            if (!"OFFERED".equals(f.get("status"))) { problems.add("loan " + no + " is " + String.valueOf(f.get("status")).toLowerCase(java.util.Locale.ROOT) + ", not offered"); continue; }
            LocalDate deadline = ((java.sql.Date) f.get("deadline")).toLocalDate();
            if ("ACCEPT".equals(decision) && today.isAfter(deadline)) { problems.add("loan " + no + ": the offer lapsed on " + deadline); continue; }
            String ref = trim(d.partnerLoanRef());
            if (ref != null && ref.length() > 60) problems.add("loan " + no + ": the partner's reference is at most 60 characters");
            String reason = trim(d.reason());
            if ("DECLINE".equals(decision) && (reason == null || reason.isEmpty())) problems.add("loan " + no + ": the partner's reason for declining is required");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("loanNo", no);
            row.put("decision", decision);
            row.put("partnerLoanRef", ref);
            row.put("reason", reason);
            rows.add(row);
            if ("ACCEPT".equals(decision)) {
                BigDecimal share = new BigDecimal("100").subtract((BigDecimal) f.get("originator_share"));
                total = total.add(((BigDecimal) f.get("principal")).multiply(share).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP));
            }
        }
        refuseIfAny("the partner's decisions cannot be recorded", problems);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("arrangementCode", code);
        payload.put("decisions", rows);
        long accepts = rows.stream().filter(x -> "ACCEPT".equals(x.get("decision"))).count();
        payload.put("accepted", accepts);
        payload.put("declined", rows.size() - accepts);
        return approvals.propose(DECISION, "DECIDE", code, payload, null, total, null, idempotencyKey);
    }

    /** The simulated partner accepts every loan offered to it whose offer is open. */
    @Transactional
    public ApprovalRequest simulatePartner(String code, String idempotencyKey) {
        LocalDate today = days.requireOpen();
        List<DecisionInput> all = offers(code).stream().filter(o -> !today.isAfter(LocalDate.parse((String) o.get("deadline"))))
                .map(o -> new DecisionInput((String) o.get("loanNo"), "ACCEPT", "SIM-" + o.get("loanNo"), null)).toList();
        if (all.isEmpty()) throw ApiException.conflict("no loan is offered to the partner of " + code + " within its 15 days");
        return proposeDecisions(code, all, idempotencyKey);
    }

    String applyDecisions(ApprovalRequest r) {
        LocalDate today = days.requireOpen();
        String code = (String) r.payload().get("arrangementCode");
        String gl = setting("lending.co-lending.partner-share-gl", "1105");
        String escrow = setting("lending.co-lending.escrow-gl", "1208");
        int accepted = 0;
        int declined = 0;
        int lapsed = 0;
        int gone = 0;
        for (Object o : (List<?>) r.payload().get("decisions")) {
            Map<?, ?> d = (Map<?, ?>) o;
            Map<String, Object> f = jdbc.queryForMap("""
                    SELECT c.loan_id, c.status, c.deadline, l.loan_no, l.branch_code, l.principal_outstanding::numeric AS principal,
                           c.originator_share::numeric, c.originator_rate::numeric, c.partner_rate::numeric
                      FROM lending.co_lent_loan c JOIN lending.loan_account l ON l.id = c.loan_id WHERE l.loan_no = ? FOR UPDATE OF c, l
                    """, d.get("loanNo"));
            if (!"OFFERED".equals(f.get("status"))) {
                gone++;                                       // lapsed or withdrawn while the decision waited for the checker
                continue;
            }
            UUID loanId = (UUID) f.get("loan_id");
            if ("DECLINE".equals(d.get("decision"))) {
                jdbc.update("UPDATE lending.co_lent_loan SET status = 'DECLINED', decision_reason = ?, decision_approval_id = ? WHERE loan_id = ?",
                        d.get("reason"), r.id(), loanId);
                declined++;
                continue;
            }
            if (today.isAfter(((java.sql.Date) f.get("deadline")).toLocalDate())) {
                lapsed++;                                     // the window closed while the decision waited for the checker
                continue;
            }
            Terms t = new Terms((BigDecimal) f.get("originator_share"), (BigDecimal) f.get("originator_rate"), (BigDecimal) f.get("partner_rate"),
                    null, null, null, null);
            BigDecimal partner = CoLending.byShare(t, (BigDecimal) f.get("principal")).partner();
            TransactionLot lot = CoLending.transfer((String) f.get("branch_code"), escrow, gl, (String) f.get("loan_no"), partner, today);
            posting.post(lot, r.maker());
            jdbc.update("""
                    UPDATE lending.co_lent_loan SET status = 'ACCEPTED', partner_loan_ref = ?, partner_principal = ?, transfer_lot_id = ?,
                           decision_approval_id = ? WHERE loan_id = ?
                    """, d.get("partnerLoanRef"), partner, lot.id(), r.id(), loanId);
            CoLendingSplit.open(jdbc, loanId, store.read(jdbc, loanId).account(), today);
            accepted++;
        }
        return code + ": " + accepted + " accepted, " + declined + " declined"
                + (lapsed > 0 ? ", " + lapsed + " past their 15 days (left with the originator)" : "")
                + (gone > 0 ? ", " + gone + " no longer offered" : "");
    }

    /** The day-end of a deadline: offers not accepted by then lapse, and the loans stay with the originator. */
    List<String> toLapse(LocalDate today) {
        return jdbc.queryForList("SELECT loan_id::text FROM lending.co_lent_loan WHERE status = 'OFFERED' AND deadline <= ? ORDER BY deadline", String.class, today);
    }

    static void lapse(JdbcTemplate jdbc, UUID loanId) {
        jdbc.update("UPDATE lending.co_lent_loan SET status = 'LAPSED', decision_reason = 'not accepted within 15 calendar days of disbursement' "
                + "WHERE loan_id = ? AND status = 'OFFERED'", loanId);
    }

    // ================================================================================================ helpers

    private String setting(String key, String fallback) {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = ?", String.class, key);
        return v.isEmpty() || v.get(0).isBlank() ? fallback : v.get(0).trim();
    }

    private static Sharing sharing(String v, Sharing fallback, List<String> problems) {
        if (v == null || v.isBlank()) return fallback;
        try {
            return Sharing.valueOf(v);
        } catch (IllegalArgumentException e) {
            problems.add("fees and penal charges are shared ORIGINATOR (all to the originator) or BY_SHARE");
            return fallback;
        }
    }

    private static void require(String v, int max, String what, List<String> problems) {
        if (v == null || v.isBlank()) problems.add(what + " is required");
        else if (v.trim().length() > max) problems.add(what + " is at most " + max + " characters");
    }

    static void refuseIfAny(String what, List<String> problems) {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(problems));
        if (!distinct.isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, what + ": " + String.join("; ", distinct), Map.of("problems", distinct));
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String s(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal dec(Object o) {
        return o == null ? null : new BigDecimal(String.valueOf(o));
    }

    /** Applies arrangements once a checker approves them. */
    @Service
    static class ArrangementApplier implements ApprovalApplier {
        private final CoLendingService colending;

        ArrangementApplier(@Lazy CoLendingService colending) {
            this.colending = colending;
        }

        @Override public String entityType() { return ARRANGEMENT; }

        @Override public String apply(ApprovalRequest r) { return colending.applyArrangement(r); }
    }

    /** Pays the partner once a checker approves a settlement. */
    @Service
    static class SettlementApplier implements ApprovalApplier {
        private final CoLendingService colending;

        SettlementApplier(@Lazy CoLendingService colending) {
            this.colending = colending;
        }

        @Override public String entityType() { return SETTLEMENT; }

        @Override public String apply(ApprovalRequest r) { return colending.applySettlement(r); }
    }

    /** Applies the partner's decisions once a checker approves them. */
    @Service
    static class DecisionApplier implements ApprovalApplier {
        private final CoLendingService colending;

        DecisionApplier(@Lazy CoLendingService colending) {
            this.colending = colending;
        }

        @Override public String entityType() { return DECISION; }

        @Override public String apply(ApprovalRequest r) { return colending.applyDecisions(r); }
    }
}
