package com.corebanking.lending.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.customer.PiiKeys;
import com.corebanking.kernel.DocumentKey;
import com.corebanking.kernel.Inr;
import com.corebanking.lending.documents.LoanDocuments;
import com.corebanking.lending.documents.LoanDocuments.Balance;
import com.corebanking.lending.documents.LoanDocuments.Borrower;
import com.corebanking.lending.documents.LoanDocuments.ContingentCharge;
import com.corebanking.lending.documents.LoanDocuments.ContingentKind;
import com.corebanking.lending.documents.LoanDocuments.FloatingRate;
import com.corebanking.lending.documents.LoanDocuments.InvoiceLine;
import com.corebanking.lending.documents.LoanDocuments.Kfs;
import com.corebanking.lending.documents.LoanDocuments.KfsFee;
import com.corebanking.lending.documents.LoanDocuments.Lender;
import com.corebanking.lending.documents.LoanDocuments.LoanSummary;
import com.corebanking.lending.documents.LoanDocuments.Noc;
import com.corebanking.lending.documents.LoanDocuments.Overdue;
import com.corebanking.lending.documents.LoanDocuments.Qualitative;
import com.corebanking.lending.documents.LoanDocuments.RaisedInstalment;
import com.corebanking.lending.documents.LoanDocuments.Render;
import com.corebanking.lending.documents.LoanDocuments.Schedule;
import com.corebanking.lending.documents.LoanDocuments.ScheduleRow;
import com.corebanking.lending.documents.LoanDocuments.Statement;
import com.corebanking.lending.documents.LoanDocuments.StatementLine;
import com.corebanking.lending.documents.LoanDocuments.TaxInvoice;
import com.corebanking.lending.engine.FeeRule;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
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

/**
 * Borrower documents as PDF (P2-4): Key Facts Statement, statement of account, repayment schedule, no-objection
 * letter and GST tax invoice. This class gathers the data; {@link LoanDocuments} lays it out.
 *
 * <ul>
 *   <li>A document carries personal data (name and address in full; PAN and mobile masked), so every generation is
 *       written to the audit log: who, which loan, which document.</li>
 *   <li>Figures come from the same sources as the screens: the KFS stored at booking, the engine state, and the
 *       ledger entries on the loan account (statement).</li>
 *   <li>Lender details come from the legal entity and from system properties {@code lender.*}; one that is not set
 *       prints as "[not configured]".</li>
 * </ul>
 */
@Service
public class LoanDocumentService {

    /** A generated file and the name to download it under. */
    public record Document(String fileName, byte[] content) {}

    private static final String APR_BASIS = "internal rate of return of the loan's cash flows (amount disbursed less fees"
            + " excluding GST, then each instalment), stated as a nominal annual rate";

    private final JdbcTemplate jdbc;
    private final Json json;
    private final AuditLog audit;
    private final BusinessDays days;
    private final PiiKeys keys;
    private final LoanEventPublisher events;

    public LoanDocumentService(JdbcTemplate jdbc, Json json, AuditLog audit, BusinessDays days, PiiKeys keys,
                               LoanEventPublisher events) {
        this.events = events;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.days = days;
        this.keys = keys;
    }

    /** The loan's branch, for the branch-scope check (US-020). */
    public String branchOf(UUID id) {
        List<String> b = jdbc.queryForList("SELECT branch_code FROM lending.loan_account WHERE id = ?", String.class, id);
        if (b.isEmpty()) throw ApiException.notFound("loan " + id);
        return b.get(0);
    }

    // ------------------------------------------------------------------------------------------------ documents
    @Transactional
    public Document kfs(UUID loanId) {
        Loan l = loan(loanId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT kfs::text AS kfs, generated_at::date AS generated_on, accepted_at::date AS accepted_on, accepted_channel, evidence_ref
                  FROM lending.loan_kfs WHERE loan_id = ?
                """, loanId);
        if (rows.isEmpty()) throw ApiException.notFound("key facts statement for loan " + l.loanNo());
        Map<String, Object> row = rows.get(0);
        Map<String, Object> k = json.readMap((String) row.get("kfs"));
        Map<String, String> props = lenderProperties();

        List<KfsFee> fees = new ArrayList<>();
        if (k.get("fees") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> f)) continue;
                BigDecimal gst = dec(f.get("cgst")).add(dec(f.get("sgst"))).add(dec(f.get("igst")));
                fees.add(new KfsFee(str(f.get("name")), true, false, dec(f.get("fee")), gst, dec(f.get("total"))));
            }
        }
        List<ScheduleRow> schedule = kfsSchedule(k);
        List<ContingentCharge> contingent = new ArrayList<>();
        BigDecimal penal = k.get("penalChargeRate") == null ? null : dec(k.get("penalChargeRate"));
        if (penal != null && penal.signum() > 0) {
            contingent.add(new ContingentCharge(ContingentKind.PENAL_DELAYED_PAYMENT, Inr.percent(penal)
                    + " per annum on the overdue amount for the days it is overdue; not added to the interest rate and not compounded"));
        }
        for (FeeRule f : l.params() == null ? List.<FeeRule>of() : l.params().fees()) {
            if (f.event() == FeeRule.Event.DISBURSEMENT) continue;
            ContingentKind kind = switch (f.event()) {
                case PRECLOSURE, PART_PREPAYMENT -> ContingentKind.FORECLOSURE;
                case LATE_PAYMENT -> ContingentKind.OTHER_PENAL;
                default -> ContingentKind.OTHER;
            };
            contingent.add(new ContingentCharge(kind, describe(f)));
        }
        String rateType = str(k.get("rateType"));
        FloatingRate floating = "FLOATING".equals(rateType)
                ? new FloatingRate(props.get("lender.kfs.benchmark"), null, null, dec(k.get("interestRate")),
                        props.get("lender.kfs.reset-periodicity"), props.get("lender.kfs.reset-impact"))
                : null;
        String acceptance = row.get("accepted_on") == null ? null
                : "Accepted by the borrower on " + LoanDocuments.formatDate(date(row.get("accepted_on")))
                        + (row.get("accepted_channel") == null ? "" : " through " + row.get("accepted_channel"))
                        + (row.get("evidence_ref") == null ? "" : " (reference " + row.get("evidence_ref") + ")") + ".";
        Kfs data = new Kfs(lender(props, null), borrower(l.customerId(), false), l.loanNo(), str(k.get("productName")),
                date(row.get("generated_on")), integer(props.get("lender.kfs.validity-days"), 3), dec(k.get("amount")), "100% upfront",
                integer(k.get("tenorMonths"), l.tenorMonths()), "Monthly", integer(k.get("instalments"), schedule.size()),
                k.get("emi") == null ? null : dec(k.get("emi")), schedule.isEmpty() ? l.firstDueDate() : schedule.get(0).dueDate(),
                rateType, dec(k.get("interestRate")), floating, fees, dec(k.get("netDisbursal")), dec(k.get("totalInterest")),
                dec(k.get("totalRepayable")), dec(k.get("apr")), APR_BASIS, contingent,
                new Qualitative(props.get("lender.kfs.recovery-agent-clause"), props.get("lender.kfs.grievance-clause"),
                        props.get("lender.grievance.officer-name"), props.get("lender.grievance.officer-phone"),
                        props.get("lender.grievance.officer-email"), "true".equalsIgnoreCase(props.get("lender.kfs.transferable")),
                        props.get("lender.kfs.collaborative-lending"), props.get("lender.kfs.lsp-recovery-agent")),
                integer(k.get("coolingOffDays"), 3), schedule, acceptance);
        byte[] pdf = LoanDocuments.kfs(data, Render.DEFAULT);
        audited(l, "KFS", Map.of());
        return new Document(DocumentKey.safeFileName("kfs-" + l.loanNo() + ".pdf"), pdf);
    }

    @Transactional
    public Document statement(UUID loanId, LocalDate from, LocalDate to) {
        Loan l = loan(loanId);
        if (l.account() == null) throw ApiException.conflict("loan " + l.loanNo() + " is " + l.status() + ": there is nothing to state yet");
        LocalDate today = days.current().businessDate();
        LocalDate start = from == null ? l.disbursedOn() : from;
        LocalDate end = to == null ? today : to;
        if (start == null) throw ApiException.invalid("from is required");
        if (end.isAfter(today)) throw ApiException.invalid("to cannot be after the business date " + today);
        if (end.isBefore(start)) throw ApiException.invalid("to cannot be before from");

        Balance opening = balance(loanId, start.minusDays(1)).balance();
        LedgerBalance close = balance(loanId, end);
        List<StatementLine> lines = jdbc.query("""
                SELECT line_date, particulars, received, principal, interest, charges
                  FROM lending.loan_statement(?, ?, ?) ORDER BY line_no
                """, (rs, i) -> new StatementLine(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)), loanId, start, end);

        LoanAccount a = l.account();
        BigDecimal principal = BigDecimal.ZERO;
        BigDecimal interest = BigDecimal.ZERO;
        LocalDate oldest = null;
        for (LoanAccount.DemandRow d : a.demands()) {
            if (d.principalUnpaid().signum() <= 0 && d.interestUnpaid().signum() <= 0) continue;
            principal = principal.add(d.principalUnpaid().max(BigDecimal.ZERO));
            interest = interest.add(d.interestUnpaid().max(BigDecimal.ZERO));
            if (oldest == null || d.dueDate().isBefore(oldest)) oldest = d.dueDate();
        }
        BigDecimal charges = a.charges().stream().map(LoanAccount.ChargeRow::unpaid).filter(c -> c.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Overdue overdue = new Overdue(principal, interest, charges, a.dpd(), oldest);

        Statement data = new Statement(lender(lenderProperties(), null), borrower(l.customerId(), true), summary(l), start, end, today,
                opening, close.balance(), lines, overdue, close.advance());
        byte[] pdf = LoanDocuments.statement(data, Render.DEFAULT);
        audited(l, "STATEMENT", Map.of("from", start.toString(), "to", end.toString()));
        return new Document(DocumentKey.safeFileName("statement-" + l.loanNo() + "-" + start + "-" + end + ".pdf"), pdf);
    }

    @Transactional
    public Document schedule(UUID loanId) {
        Loan l = loan(loanId);
        List<RaisedInstalment> raised = new ArrayList<>();
        List<ScheduleRow> future;
        if (l.account() == null) {
            String k = jdbc.queryForObject("SELECT kfs::text FROM lending.loan_kfs WHERE loan_id = ?", String.class, loanId);
            future = kfsSchedule(json.readMap(k));
        } else {
            for (LoanAccount.DemandRow d : l.account().demands()) {
                BigDecimal unpaid = d.principalUnpaid().max(BigDecimal.ZERO).add(d.interestUnpaid().max(BigDecimal.ZERO));
                raised.add(new RaisedInstalment(d.instalmentNo(), d.dueDate(), d.principalDue(), d.interestDue(),
                        d.principalPaid().add(d.interestPaid()), unpaid));
            }
            future = new ArrayList<>();
            for (Instalment i : l.account().futureSchedule()) {
                future.add(new ScheduleRow(i.number(), i.dueDate(), i.openingBalance(), i.principal(), i.interest(), i.instalment()));
            }
        }
        Schedule data = new Schedule(lender(lenderProperties(), null), borrower(l.customerId(), true), summary(l),
                days.current().businessDate(), raised, future);
        byte[] pdf = LoanDocuments.schedule(data, Render.DEFAULT);
        audited(l, "SCHEDULE", Map.of());
        return new Document(DocumentKey.safeFileName("schedule-" + l.loanNo() + ".pdf"), pdf);
    }

    /** No-objection letter: only for a CLOSED loan (409 otherwise). */
    @Transactional
    public Document noc(UUID loanId) {
        Loan l = loan(loanId);
        if (!"CLOSED".equals(l.status())) {
            throw ApiException.conflict("a no-objection letter is issued only for a closed loan; loan " + l.loanNo() + " is " + l.status());
        }
        if (l.closedOn() == null) throw ApiException.conflict("loan " + l.loanNo() + " has no closure date");
        Noc data = new Noc(lender(lenderProperties(), null), borrower(l.customerId(), true), summary(l), l.closedOn(),
                days.current().businessDate(), "NOC/" + l.loanNo());
        byte[] pdf = LoanDocuments.noc(data, Render.DEFAULT);
        audited(l, "NOC", Map.of());
        // the borrower is told that the letter is ready (P2-2 messaging; sent once per loan however often it is printed)
        events.nocIssued(jdbc, loanId, days.current().businessDate());
        return new Document(DocumentKey.safeFileName("noc-" + l.loanNo() + ".pdf"), pdf);
    }

    /**
     * GST tax invoice for a fee. {@code chargeId} is the charge id shown on the loan (C1, C2 …) or D1, D2 … for the
     * fees deducted from the disbursement. Invoices are issued from the ledger (V16 lending.issue_fee_invoices);
     * any not yet issued for this loan are issued first.
     */
    @Transactional
    public Document invoice(UUID loanId, String chargeId) {
        Loan l = loan(loanId);
        if (chargeId == null || !chargeId.matches("[CDP][0-9]{1,6}")) throw ApiException.notFound("charge " + chargeId);
        if (chargeId.startsWith("P")) throw ApiException.conflict("penal charges carry no GST, so there is no tax invoice for " + chargeId);
        jdbc.queryForObject("SELECT lending.issue_fee_invoices(?)", Integer.class, loanId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT invoice_no, invoice_date, description, sac, supplier_state, place_of_supply, taxable_value, gst_rate, cgst, sgst, igst,
                       status, platform.state_name(supplier_state) AS supplier_state_name, platform.state_name(place_of_supply) AS place_name
                  FROM lending.fee_invoice WHERE loan_id = ? AND charge_ref = ?
                 ORDER BY (status = 'ISSUED') DESC, invoice_no DESC LIMIT 1
                """, loanId, chargeId);
        if (rows.isEmpty()) throw ApiException.notFound("tax invoice for charge " + chargeId);
        Map<String, Object> r = rows.get(0);
        Lender supplier = lender(lenderProperties(), new String[] {str(r.get("supplier_state")), str(r.get("supplier_state_name"))});
        TaxInvoice data = new TaxInvoice(supplier, borrower(l.customerId(), false), null, str(r.get("invoice_no")), date(r.get("invoice_date")),
                l.loanNo(), str(r.get("place_of_supply")), str(r.get("place_name")),
                List.of(new InvoiceLine(str(r.get("description")), str(r.get("sac")), dec(r.get("taxable_value")), dec(r.get("gst_rate")),
                        dec(r.get("cgst")), dec(r.get("sgst")), dec(r.get("igst")))),
                str(r.get("status")));
        byte[] pdf = LoanDocuments.taxInvoice(data, Render.DEFAULT);
        audited(l, "TAX_INVOICE", Map.of("invoiceNo", data.invoiceNo(), "chargeId", chargeId));
        return new Document(DocumentKey.safeFileName("invoice-" + data.invoiceNo() + ".pdf"), pdf);
    }

    // ------------------------------------------------------------------------------------------------ data
    private record Loan(UUID id, String loanNo, UUID customerId, String status, BigDecimal sanctioned, BigDecimal rate, int tenorMonths,
                        BigDecimal emi, LocalDate disbursedOn, LocalDate firstDueDate, LocalDate nextDueDate, LocalDate closedOn,
                        String productName, String branchName, LoanAccount.Params params, LoanAccount account) {}

    private record LedgerBalance(Balance balance, BigDecimal advance) {}

    private Loan loan(UUID loanId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.loan_no, l.customer_id, l.status, l.sanctioned_amount, coalesce(l.current_rate, l.rate) AS rate, l.tenor_months, l.emi,
                       l.disbursed_on, l.first_due_date, l.next_due_date, l.closed_on, l.product_snapshot::text AS params,
                       l.state::text AS state, p.name AS product_name, b.name AS branch_name
                  FROM lending.loan_account l
                  JOIN lending.loan_product p ON p.code = l.product_code
                  JOIN platform.branch b ON b.code = l.branch_code
                 WHERE l.id = ?
                """, loanId);
        if (rows.isEmpty()) throw ApiException.notFound("loan " + loanId);
        Map<String, Object> r = rows.get(0);
        String p = (String) r.get("params");
        String s = (String) r.get("state");
        LoanAccount.Params params = p == null ? null : json.read(p, LoanAccount.Params.class);
        LoanAccount account = params == null || s == null || s.equals("{}") ? null
                : LoanAccount.restore(params, json.read(s, LoanAccount.Snapshot.class));
        return new Loan(loanId, str(r.get("loan_no")), (UUID) r.get("customer_id"), str(r.get("status")), dec(r.get("sanctioned_amount")),
                dec(r.get("rate")), ((Number) r.get("tenor_months")).intValue(), r.get("emi") == null ? null : dec(r.get("emi")),
                date(r.get("disbursed_on")), date(r.get("first_due_date")), date(r.get("next_due_date")), date(r.get("closed_on")),
                str(r.get("product_name")), str(r.get("branch_name")), params, account);
    }

    private static LoanSummary summary(Loan l) {
        return new LoanSummary(l.loanNo(), l.productName(), l.branchName(), l.sanctioned(), l.rate(), l.tenorMonths(), l.emi(),
                l.disbursedOn(), l.status(), l.nextDueDate());
    }

    private LedgerBalance balance(UUID loanId, LocalDate upto) {
        return jdbc.queryForObject("SELECT principal, interest, charges, advance FROM lending.loan_balance(?, ?)",
                (rs, i) -> new LedgerBalance(new Balance(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), rs.getBigDecimal(4)),
                loanId, upto);
    }

    /** System properties {@code lender.*}: address, grievance officer and the KFS clauses. */
    private Map<String, String> lenderProperties() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT key, value FROM platform.system_property WHERE key LIKE 'lender.%'")) {
            m.put(str(r.get("key")), str(r.get("value")));
        }
        return m;
    }

    /** @param supplierState GST state code and name of the supplying branch (tax invoice); null = the registered state */
    private Lender lender(Map<String, String> props, String[] supplierState) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT legal_name, cin, rbi_registration, gstin, registered_state, platform.state_name(registered_state) AS state_name
                  FROM platform.legal_entity WHERE id = 1
                """);
        Map<String, Object> e = rows.isEmpty() ? Map.of() : rows.get(0);
        String name = e.get("legal_name") == null ? props.getOrDefault("lender.name", "[lender name not configured]") : str(e.get("legal_name"));
        return new Lender(name, props.get("lender.registered-address"), str(e.get("cin")), str(e.get("rbi_registration")), str(e.get("gstin")),
                supplierState == null ? str(e.get("registered_state")) : supplierState[0],
                supplierState == null ? str(e.get("state_name")) : supplierState[1]);
    }

    /** Name and address in full (the document is for the borrower); PAN and mobile only masked, and only when asked for. */
    private Borrower borrower(UUID customerId, boolean withMaskedIds) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.customer_no, c.display_name, c.pan_last4, c.mobile_last4, a.line_cipher, a.city, a.state_code, a.pincode,
                       platform.state_name(a.state_code) AS state_name, platform.gst_state_code(a.state_code) AS gst_state
                  FROM customer.customer c
                  LEFT JOIN customer.address a ON a.customer_id = c.id AND a.address_type = 'COMMUNICATION'
                 WHERE c.id = ?
                """, customerId);
        if (rows.isEmpty()) throw ApiException.notFound("customer of the loan");
        Map<String, Object> r = rows.get(0);
        List<String> parts = new ArrayList<>();
        if (r.get("line_cipher") instanceof byte[] cipher) {
            String line = keys.forTenant(CurrentUser.requireTenant()).decrypt(cipher, "customer.address");
            if (line != null && !line.isBlank()) parts.add(line.trim());
        }
        for (String col : List.of("city", "state_name", "pincode")) {
            if (r.get(col) != null && !str(r.get(col)).isBlank()) parts.add(str(r.get(col)));
        }
        String pan = r.get("pan_last4") == null || !withMaskedIds ? null : "XXXXX" + r.get("pan_last4") + "X";
        String mobile = r.get("mobile_last4") == null || !withMaskedIds ? null : "XXXXXX" + r.get("mobile_last4");
        return new Borrower(str(r.get("display_name")), str(r.get("customer_no")), parts.isEmpty() ? null : String.join(", ", parts),
                str(r.get("gst_state")), str(r.get("state_name")), pan, mobile);
    }

    private static List<ScheduleRow> kfsSchedule(Map<String, Object> k) {
        List<ScheduleRow> out = new ArrayList<>();
        if (k.get("schedule") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> s)) continue;
                out.add(new ScheduleRow(integer(s.get("instalmentNo"), out.size() + 1), date(s.get("dueDate")), dec(s.get("openingBalance")),
                        dec(s.get("principal")), dec(s.get("interest")), dec(s.get("instalment"))));
            }
        }
        return out;
    }

    /** A fee rule in words, for the contingent-charges rows of the KFS. */
    static String describe(FeeRule f) {
        String amount = switch (f.calcType()) {
            case FIXED -> Inr.rs(f.amount());
            case PERCENT -> Inr.percent(f.percent()) + " of the amount it applies to"
                    + (f.min() == null ? "" : ", minimum " + Inr.rs(f.min())) + (f.max() == null ? "" : ", maximum " + Inr.rs(f.max()));
            case SLAB -> "as per the lender's schedule of charges";
        };
        String gst = f.gstRatePercent().signum() == 0 ? ""
                : f.taxTreatment() == com.corebanking.calc.FeeCalculator.TaxTreatment.INCLUSIVE ? " (including GST at " + Inr.percent(f.gstRatePercent()) + ")"
                : " plus GST at " + Inr.percent(f.gstRatePercent());
        return (f.name() == null ? f.code() : f.name()) + ": " + amount + gst;
    }

    private void audited(Loan l, String document, Map<String, String> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("document", document);
        detail.put("loanId", l.id().toString());
        detail.putAll(extra);
        audit.record(CurrentUser.username(), "DOCUMENT_GENERATED", "LOAN", l.loanNo(), detail);
    }

    // ------------------------------------------------------------------------------------------------ conversions
    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return o instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(o));
    }

    private static int integer(Object o, int fallback) {
        if (o == null) return fallback;
        try {
            return o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static LocalDate date(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
    }
}
