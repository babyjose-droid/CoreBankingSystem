package com.corebanking.lending.documents;

import com.corebanking.kernel.Inr;
import com.corebanking.kernel.SimplePdf;
import com.corebanking.kernel.SimplePdf.Column;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Borrower documents as PDF (P2-4): Key Facts Statement, statement of account, repayment schedule, no-objection
 * (closure) letter and GST tax invoice for fees. Pure: each builder takes plain records, so the same code can be
 * tested without a database and gives the same bytes for the same input.
 *
 * <p>The Key Facts Statement follows the layout of RBI's circular "Key Facts Statement (KFS) for Loans &amp;
 * Advances" of 15-Apr-2024: Part 1 (interest rate and fees/charges), Part 2 (other qualitative information), the
 * illustration of the APR computation (Annex B of the circular) and the repayment schedule (Annex C). The wording
 * of the rows is the lender's responsibility: have compliance review a generated KFS against the circular before
 * the first use.
 */
public final class LoanDocuments {

    private LoanDocuments() {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    /** Printed where a tenant setting has not been filled in, so a gap is obvious on the document. */
    static final String NOT_CONFIGURED = "[not configured]";

    // ------------------------------------------------------------------------------------------------ shared data
    /** How the file is written. Tests switch compression off to read the text. */
    public record Render(boolean compress, Instant created) {
        public static final Render DEFAULT = new Render(true, null);
        public static final Render PLAIN = new Render(false, null);
    }

    /** The lender (tenant legal entity). {@code stateCode} is the GST state code of the supplying branch. */
    public record Lender(String name, String address, String cin, String rbiRegistration, String gstin, String stateCode,
                         String stateName) {
        public Lender {
            Objects.requireNonNull(name, "lender name");
        }
    }

    /** The borrower. PAN and mobile arrive already masked; name and address are printed in full. */
    public record Borrower(String name, String customerNo, String address, String stateCode, String stateName, String panMasked,
                           String mobileMasked) {
        public Borrower {
            Objects.requireNonNull(name, "borrower name");
        }
    }

    public record LoanSummary(String loanNo, String productName, String branchName, BigDecimal sanctionedAmount, BigDecimal ratePercent,
                              int tenorMonths, BigDecimal emi, LocalDate disbursedOn, String status, LocalDate nextDueDate) {
        public LoanSummary {
            Objects.requireNonNull(loanNo, "loan number");
        }
    }

    /** One instalment of a schedule; {@code openingPrincipal} is the principal outstanding before it. */
    public record ScheduleRow(int number, LocalDate dueDate, BigDecimal openingPrincipal, BigDecimal principal, BigDecimal interest,
                              BigDecimal instalment) {}

    // ------------------------------------------------------------------------------------------------ KFS
    /** A fee charged at or before disbursement, payable to the lender or to a third party through the lender. */
    public record KfsFee(String name, boolean oneTime, boolean thirdParty, BigDecimal amount, BigDecimal gst, BigDecimal total) {}

    public enum ContingentKind { PENAL_DELAYED_PAYMENT, OTHER_PENAL, FORECLOSURE, SWITCHING, OTHER }

    /** A charge that applies only if something happens (delay, foreclosure …), with its terms in words. */
    public record ContingentCharge(ContingentKind kind, String terms) {}

    /** Row 7 of Part 1; null for a fixed-rate loan. */
    public record FloatingRate(String benchmark, BigDecimal benchmarkRatePercent, BigDecimal spreadPercent, BigDecimal finalRatePercent,
                               String resetPeriodicity, String impactOfChange) {}

    /** Part 2 of the KFS. Values come from tenant settings; a missing one prints as "[not configured]". */
    public record Qualitative(String recoveryAgentClause, String grievanceClause, String nodalOfficerName, String nodalOfficerPhone,
                              String nodalOfficerEmail, boolean transferable, String collaborativeLending, String lspRecoveryAgent) {}

    /**
     * @param aprPercent      the APR as computed by the product at booking (not recomputed here)
     * @param aprBasis        how the product computes it, printed under the illustration
     * @param acceptance      e.g. "Accepted on 01-Jul-2026 by OTP (ref …)", or null when not yet accepted
     */
    public record Kfs(Lender lender, Borrower borrower, String loanNo, String productName, LocalDate kfsDate, int validityDays,
                      BigDecimal sanctionedAmount, String disbursalSchedule, int tenorMonths, String instalmentType, int instalments,
                      BigDecimal emi, LocalDate firstDueDate, String rateType, BigDecimal ratePercent, FloatingRate floating,
                      List<KfsFee> fees, BigDecimal netDisbursed, BigDecimal totalInterest, BigDecimal totalRepayable,
                      BigDecimal aprPercent, String aprBasis, List<ContingentCharge> contingent, Qualitative qualitative,
                      int coolingOffDays, List<ScheduleRow> schedule, String acceptance) {
        public Kfs {
            Objects.requireNonNull(lender);
            Objects.requireNonNull(borrower);
            Objects.requireNonNull(sanctionedAmount, "sanctioned amount");
            Objects.requireNonNull(aprPercent, "APR");
            Objects.requireNonNull(qualitative, "qualitative information");
            fees = fees == null ? List.of() : List.copyOf(fees);
            contingent = contingent == null ? List.of() : List.copyOf(contingent);
            schedule = schedule == null ? List.of() : List.copyOf(schedule);
        }
    }

    public static byte[] kfs(Kfs k, Render render) {
        SimplePdf pdf = start("Key Facts Statement", "Key Facts Statement - " + k.loanNo(), k.lender(), render);
        pdf.centredHeading("Key Facts Statement");
        pdf.small("As per RBI circular \"Key Facts Statement (KFS) for Loans & Advances\" dated 15-Apr-2024. Amounts are in Indian rupees (Rs.).");
        Map<String, String> who = new LinkedHashMap<>();
        who.put("Lender (regulated entity)", k.lender().name());
        who.put("Borrower", k.borrower().name() + (k.borrower().customerNo() == null ? "" : " (customer " + k.borrower().customerNo() + ")"));
        who.put("Date of this statement", date(k.kfsDate()));
        who.put("Valid for", k.validityDays() + " working days from the date of this statement");
        pdf.keyValues(who);

        pdf.subheading("Part 1 (Interest rate and fees/charges)");
        List<Column> three = List.of(Column.left("Sr. No.", 1), Column.left("Parameter", 6), Column.left("Details", 6));
        List<List<String>> p1 = new ArrayList<>();
        p1.add(List.of("1", "Loan proposal / account No.", text(k.loanNo())));
        p1.add(List.of("", "Type of loan", text(k.productName())));
        p1.add(List.of("2", "Sanctioned loan amount", Inr.rs(k.sanctionedAmount())));
        p1.add(List.of("3", "Disbursal schedule (in stages or 100% upfront)", text(k.disbursalSchedule())));
        p1.add(List.of("4", "Loan term", k.tenorMonths() + " months"));
        p1.add(List.of("5", "Instalment details: type of instalments", text(k.instalmentType())));
        p1.add(List.of("", "Number of equated periodic instalments (EPIs)", String.valueOf(k.instalments())));
        p1.add(List.of("", "EPI", k.emi() == null ? "Not equated: see the repayment schedule" : Inr.rs(k.emi())));
        p1.add(List.of("", "Commencement of repayment, post sanction", date(k.firstDueDate())));
        p1.add(List.of("6", "Interest rate (%) and type (fixed, floating or hybrid)",
                Inr.percent(k.ratePercent()) + " per annum, " + capitalised(k.rateType()).toLowerCase(Locale.ROOT)));
        FloatingRate f = k.floating();
        if (f == null) {
            p1.add(List.of("7", "Additional information in case of floating rate of interest", "Not applicable"));
        } else {
            p1.add(List.of("7", "Floating rate: reference benchmark", text(f.benchmark())));
            p1.add(List.of("", "Benchmark rate (B)", Inr.percent(f.benchmarkRatePercent())));
            p1.add(List.of("", "Spread (S)", Inr.percent(f.spreadPercent())));
            p1.add(List.of("", "Final rate (R) = (B) + (S)", Inr.percent(f.finalRatePercent())));
            p1.add(List.of("", "Reset periodicity", text(f.resetPeriodicity())));
            p1.add(List.of("", "Impact of a change in the reference benchmark (for 25 bps: EPI, number of EPIs)", text(f.impactOfChange())));
        }
        pdf.table(three, p1);

        pdf.bold("8. Fees / charges");
        List<Column> feeCols = List.of(Column.left("Fee", 5), Column.left("One-time / recurring", 3), Column.left("Payable to", 3),
                Column.right("Amount (Rs.)", 3), Column.right("GST (Rs.)", 2.5), Column.right("Total (Rs.)", 3));
        List<List<String>> feeRows = new ArrayList<>();
        BigDecimal feeTotal = BigDecimal.ZERO;
        for (KfsFee fee : k.fees()) {
            feeRows.add(List.of(text(fee.name()), fee.oneTime() ? "One-time" : "Recurring",
                    fee.thirdParty() ? "Third party through the lender (B)" : "The lender (A)",
                    Inr.amount(fee.amount()), Inr.amount(fee.gst()), Inr.amount(fee.total())));
            feeTotal = feeTotal.add(fee.total());
        }
        if (feeRows.isEmpty()) feeRows.add(List.of("No fees or charges are payable at disbursement", "", "", "", "", ""));
        pdf.table(feeCols, feeRows, List.of("Total", "", "", "", "", Inr.amount(feeTotal)));

        List<List<String>> p1b = new ArrayList<>();
        p1b.add(List.of("9", "Annual Percentage Rate (APR) (%)", Inr.percent(k.aprPercent())));
        p1b.add(List.of("10", "Contingent charges: (i) penal charges, if any, in case of delayed payment", contingent(k, ContingentKind.PENAL_DELAYED_PAYMENT)));
        p1b.add(List.of("", "(ii) other penal charges, if any", contingent(k, ContingentKind.OTHER_PENAL)));
        p1b.add(List.of("", "(iii) foreclosure charges, if applicable", contingent(k, ContingentKind.FORECLOSURE)));
        p1b.add(List.of("", "(iv) charges for switching of loans from floating to fixed rate and vice versa", contingent(k, ContingentKind.SWITCHING)));
        p1b.add(List.of("", "(v) any other charges", contingent(k, ContingentKind.OTHER)));
        pdf.table(three, p1b);

        Qualitative q = k.qualitative();
        pdf.subheading("Part 2 (Other qualitative information)");
        List<List<String>> p2 = new ArrayList<>();
        p2.add(List.of("1", "Clause of loan agreement relating to engagement of recovery agents", setting(q.recoveryAgentClause())));
        p2.add(List.of("2", "Clause of loan agreement which details the grievance redressal mechanism", setting(q.grievanceClause())));
        p2.add(List.of("3", "Phone number and email id of the nodal grievance redressal officer",
                setting(q.nodalOfficerName()) + "; phone " + setting(q.nodalOfficerPhone()) + "; email " + setting(q.nodalOfficerEmail())));
        p2.add(List.of("4", "Whether the loan is, or in future may be, subject to transfer to other regulated entities or securitisation",
                q.transferable() ? "Yes" : "No"));
        p2.add(List.of("5", "In case of lending under collaborative lending arrangements (e.g. co-lending / outsourcing): "
                + "originating and partner entities, funding proportion and blended rate of interest",
                q.collaborativeLending() == null || q.collaborativeLending().isBlank() ? "Not applicable" : q.collaborativeLending()));
        p2.add(List.of("6", "In case of digital loans: (i) cooling-off / look-up period during which the borrower shall not be "
                + "charged any penalty on prepayment of the loan", k.coolingOffDays() + " days from disbursement"));
        p2.add(List.of("", "(ii) details of the lending service provider acting as recovery agent and authorised to approach the borrower",
                q.lspRecoveryAgent() == null || q.lspRecoveryAgent().isBlank() ? "Not applicable" : q.lspRecoveryAgent()));
        pdf.table(three, p2);

        pdf.subheading("Illustration for computation of APR (Annex B)");
        BigDecimal lenderFees = BigDecimal.ZERO;
        BigDecimal thirdPartyFees = BigDecimal.ZERO;
        for (KfsFee fee : k.fees()) {
            if (fee.thirdParty()) thirdPartyFees = thirdPartyFees.add(fee.total());
            else lenderFees = lenderFees.add(fee.total());
        }
        List<List<String>> b = new ArrayList<>();
        b.add(List.of("1", "Sanctioned loan amount", Inr.rs(k.sanctionedAmount())));
        b.add(List.of("2", "Loan term", k.tenorMonths() + " months"));
        b.add(List.of("", "(a) Number of instalments for payment of principal, in case of non-equated periodic loans",
                k.emi() == null ? String.valueOf(k.schedule().stream().filter(r -> r.principal().signum() > 0).count()) : "Not applicable"));
        b.add(List.of("", "(b) Type of EPI; amount of each EPI; number of EPIs",
                k.emi() == null ? "Not applicable" : text(k.instalmentType()) + "; " + Inr.rs(k.emi()) + "; " + k.instalments()));
        b.add(List.of("", "(c) Number of instalments for payment of capitalised interest, if any", "Nil"));
        b.add(List.of("", "(d) Commencement of repayments, post sanction", date(k.firstDueDate())));
        b.add(List.of("3", "Interest rate type (fixed, floating or hybrid)", capitalised(k.rateType())));
        b.add(List.of("4", "Rate of interest", Inr.percent(k.ratePercent()) + " per annum"));
        b.add(List.of("5", "Total interest amount to be charged during the entire tenor of the loan as per the rate prevailing on sanction date",
                Inr.rs(k.totalInterest())));
        b.add(List.of("6", "Fees / charges payable (including GST): (A) payable to the lender", Inr.rs(lenderFees)));
        b.add(List.of("", "(B) payable to a third party routed through the lender", Inr.rs(thirdPartyFees)));
        b.add(List.of("7", "Net disbursed amount", Inr.rs(k.netDisbursed())));
        b.add(List.of("8", "Total amount to be paid by the borrower (sum of 1 and 5)", Inr.rs(k.totalRepayable())));
        b.add(List.of("9", "Annual Percentage Rate: effective annualised interest rate (%)", Inr.percent(k.aprPercent())));
        b.add(List.of("10", "Schedule of disbursement as per terms and conditions", text(k.disbursalSchedule())));
        b.add(List.of("11", "Due date of payment of instalment and interest", "As per the repayment schedule below"));
        pdf.table(three, b);
        if (k.aprBasis() != null) pdf.small("Basis of the APR: " + k.aprBasis());

        pdf.subheading("Repayment schedule (Annex C)");
        scheduleTable(pdf, k.schedule());

        pdf.subheading("Acknowledgement");
        pdf.paragraph(k.acceptance() == null
                ? "Not yet accepted by the borrower. The loan is disbursed only after the borrower has accepted this statement."
                : k.acceptance());
        return pdf.build();
    }

    private static String contingent(Kfs k, ContingentKind kind) {
        List<String> terms = k.contingent().stream().filter(c -> c.kind() == kind).map(ContingentCharge::terms).toList();
        return terms.isEmpty() ? "Nil" : String.join("; ", terms);
    }

    // ------------------------------------------------------------------------------------------------ statement
    /** Balance by component. Charges are fees plus penal charges, including GST. */
    public record Balance(BigDecimal principal, BigDecimal interest, BigDecimal charges) {
        public Balance {
            Objects.requireNonNull(principal);
            Objects.requireNonNull(interest);
            Objects.requireNonNull(charges);
        }

        public BigDecimal total() {
            return principal.add(interest).add(charges);
        }
    }

    /**
     * One line of the statement. {@code principal}, {@code interest} and {@code charges} are the movement in what the
     * borrower owes: positive when charged (disbursement, interest, a fee), negative when paid, waived or reversed.
     * {@code received} is money received from the borrower in this transaction (null when none).
     */
    public record StatementLine(LocalDate date, String particulars, BigDecimal received, BigDecimal principal, BigDecimal interest,
                                BigDecimal charges) {
        public StatementLine {
            Objects.requireNonNull(date);
            principal = principal == null ? BigDecimal.ZERO : principal;
            interest = interest == null ? BigDecimal.ZERO : interest;
            charges = charges == null ? BigDecimal.ZERO : charges;
        }
    }

    public record Overdue(BigDecimal principal, BigDecimal interest, BigDecimal charges, int daysPastDue, LocalDate oldestDueDate) {
        public BigDecimal total() {
            return principal.add(interest).add(charges);
        }
    }

    /**
     * @param advanceHeld money received ahead of a due date and not yet applied (not part of the outstanding)
     */
    public record Statement(Lender lender, Borrower borrower, LoanSummary loan, LocalDate from, LocalDate to, LocalDate generatedOn,
                            Balance opening, Balance closing, List<StatementLine> lines, Overdue overdue, BigDecimal advanceHeld) {
        public Statement {
            Objects.requireNonNull(lender);
            Objects.requireNonNull(borrower);
            Objects.requireNonNull(loan);
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            Objects.requireNonNull(opening);
            Objects.requireNonNull(closing);
            if (to.isBefore(from)) throw new IllegalArgumentException("the statement period ends before it starts");
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /**
     * Statement of account for a period. The running outstanding starts at the opening balance and must arrive at
     * the closing balance: if the lines do not add up the statement is refused rather than printed wrong.
     */
    public static byte[] statement(Statement s, Render render) {
        BigDecimal running = s.opening().total();
        BigDecimal principal = s.opening().principal();
        BigDecimal interest = s.opening().interest();
        BigDecimal charges = s.opening().charges();
        BigDecimal received = BigDecimal.ZERO;
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(date(s.from()), "Opening balance", "", Inr.amount(s.opening().principal()), Inr.amount(s.opening().interest()),
                Inr.amount(s.opening().charges()), Inr.amount(running)));
        for (StatementLine l : s.lines()) {
            if (l.date().isBefore(s.from()) || l.date().isAfter(s.to())) {
                throw new IllegalArgumentException("a transaction dated " + l.date() + " is outside the statement period");
            }
            principal = principal.add(l.principal());
            interest = interest.add(l.interest());
            charges = charges.add(l.charges());
            running = running.add(l.principal()).add(l.interest()).add(l.charges());
            if (l.received() != null) received = received.add(l.received());
            rows.add(List.of(date(l.date()), text(l.particulars()), l.received() == null ? "" : Inr.amount(l.received()),
                    movement(l.principal()), movement(l.interest()), movement(l.charges()), Inr.amount(running)));
        }
        if (principal.compareTo(s.closing().principal()) != 0 || interest.compareTo(s.closing().interest()) != 0
                || charges.compareTo(s.closing().charges()) != 0) {
            throw new IllegalArgumentException("the statement does not balance: transactions lead to "
                    + principal.toPlainString() + " / " + interest.toPlainString() + " / " + charges.toPlainString()
                    + " (principal / interest / charges) but the closing balance is " + s.closing().principal().toPlainString() + " / "
                    + s.closing().interest().toPlainString() + " / " + s.closing().charges().toPlainString());
        }

        SimplePdf pdf = start("Statement of account", "Statement of account - " + s.loan().loanNo(), s.lender(), render);
        pdf.centredHeading("Statement of account");
        pdf.keyValues(parties(s.borrower(), s.loan()));
        Map<String, String> period = new LinkedHashMap<>();
        period.put("Statement period", date(s.from()) + " to " + date(s.to()));
        period.put("Generated on (business date)", date(s.generatedOn()));
        period.put("Opening outstanding", Inr.rs(s.opening().total()));
        period.put("Closing outstanding", Inr.rs(s.closing().total()) + " (principal " + Inr.amount(s.closing().principal())
                + ", interest " + Inr.amount(s.closing().interest()) + ", charges " + Inr.amount(s.closing().charges()) + ")");
        period.put("Received in the period", Inr.rs(received));
        if (s.advanceHeld() != null && s.advanceHeld().signum() > 0) period.put("Advance held (not yet applied)", Inr.rs(s.advanceHeld()));
        pdf.keyValues(period);

        pdf.subheading("Transactions");
        List<Column> cols = List.of(Column.left("Date", 2.6), Column.left("Particulars", 6.4), Column.right("Received", 2.8),
                Column.right("Principal", 2.9), Column.right("Interest", 2.7), Column.right("Charges", 2.5), Column.right("Outstanding", 3.1));
        pdf.table(cols, rows, List.of(date(s.to()), "Closing balance", Inr.amount(received), Inr.amount(s.closing().principal()),
                Inr.amount(s.closing().interest()), Inr.amount(s.closing().charges()), Inr.amount(s.closing().total())));
        pdf.small("Principal, Interest and Charges show the change in the amount owed: a plain figure was charged to the account, "
                + "a figure with a minus sign was paid, waived or reversed. Interest includes interest accrued up to the date shown, "
                + "whether or not it has fallen due. Charges are fees and penal charges including GST. Outstanding is the total owed after each line.");

        pdf.subheading("Overdue summary as on " + date(s.generatedOn()));
        Overdue o = s.overdue();
        Map<String, String> od = new LinkedHashMap<>();
        if (o == null || o.total().signum() == 0) {
            od.put("Amount overdue", "Nil");
            od.put("Days past due", "0");
        } else {
            od.put("Amount overdue", Inr.rs(o.total()));
            od.put("Principal overdue", Inr.rs(o.principal()));
            od.put("Interest overdue", Inr.rs(o.interest()));
            od.put("Charges unpaid", Inr.rs(o.charges()));
            od.put("Oldest unpaid due date", date(o.oldestDueDate()));
            od.put("Days past due", String.valueOf(o.daysPastDue()));
        }
        if (s.loan().nextDueDate() != null) od.put("Next due date", date(s.loan().nextDueDate()));
        pdf.keyValues(od);
        return pdf.build();
    }

    private static String movement(BigDecimal v) {
        return v.signum() == 0 ? "" : Inr.amount(v);
    }

    // ------------------------------------------------------------------------------------------------ schedule
    /** An instalment already demanded, with what has been paid against it. */
    public record RaisedInstalment(int number, LocalDate dueDate, BigDecimal principalDue, BigDecimal interestDue, BigDecimal paid,
                                   BigDecimal unpaid) {}

    public record Schedule(Lender lender, Borrower borrower, LoanSummary loan, LocalDate asOn, List<RaisedInstalment> raised,
                           List<ScheduleRow> future) {
        public Schedule {
            Objects.requireNonNull(lender);
            Objects.requireNonNull(borrower);
            Objects.requireNonNull(loan);
            raised = raised == null ? List.of() : List.copyOf(raised);
            future = future == null ? List.of() : List.copyOf(future);
        }
    }

    public static byte[] schedule(Schedule s, Render render) {
        SimplePdf pdf = start("Repayment schedule", "Repayment schedule - " + s.loan().loanNo(), s.lender(), render);
        pdf.centredHeading("Repayment schedule");
        Map<String, String> kv = parties(s.borrower(), s.loan());
        kv.put("As on (business date)", date(s.asOn()));
        pdf.keyValues(kv);
        if (!s.raised().isEmpty()) {
            pdf.subheading("Instalments fallen due");
            List<Column> cols = List.of(Column.right("No.", 1), Column.left("Due date", 3), Column.right("Principal (Rs.)", 3),
                    Column.right("Interest (Rs.)", 3), Column.right("Instalment (Rs.)", 3), Column.right("Paid (Rs.)", 3),
                    Column.right("Unpaid (Rs.)", 3));
            List<List<String>> rows = new ArrayList<>();
            BigDecimal due = BigDecimal.ZERO;
            BigDecimal paid = BigDecimal.ZERO;
            BigDecimal unpaid = BigDecimal.ZERO;
            for (RaisedInstalment r : s.raised()) {
                BigDecimal instalment = r.principalDue().add(r.interestDue());
                rows.add(List.of(String.valueOf(r.number()), date(r.dueDate()), Inr.amount(r.principalDue()), Inr.amount(r.interestDue()),
                        Inr.amount(instalment), Inr.amount(r.paid()), Inr.amount(r.unpaid())));
                due = due.add(instalment);
                paid = paid.add(r.paid());
                unpaid = unpaid.add(r.unpaid());
            }
            pdf.table(cols, rows, List.of("", "Total", "", "", Inr.amount(due), Inr.amount(paid), Inr.amount(unpaid)));
        }
        pdf.subheading(s.raised().isEmpty() ? "Schedule" : "Instalments to come");
        if (s.future().isEmpty()) pdf.paragraph("No further instalments are scheduled.");
        else scheduleTable(pdf, s.future());
        pdf.small("The schedule changes if the loan is prepaid, amended or restructured, or if the interest rate is reset.");
        return pdf.build();
    }

    private static void scheduleTable(SimplePdf pdf, List<ScheduleRow> schedule) {
        List<Column> cols = List.of(Column.right("Instalment No.", 2), Column.left("Due date", 3),
                Column.right("Outstanding principal (Rs.)", 4), Column.right("Principal (Rs.)", 3.5), Column.right("Interest (Rs.)", 3.5),
                Column.right("Instalment (Rs.)", 3.5));
        List<List<String>> rows = new ArrayList<>();
        BigDecimal principal = BigDecimal.ZERO;
        BigDecimal interest = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (ScheduleRow r : schedule) {
            rows.add(List.of(String.valueOf(r.number()), date(r.dueDate()), Inr.amount(r.openingPrincipal()), Inr.amount(r.principal()),
                    Inr.amount(r.interest()), Inr.amount(r.instalment())));
            principal = principal.add(r.principal());
            interest = interest.add(r.interest());
            total = total.add(r.instalment());
        }
        pdf.table(cols, rows, List.of("", "Total", "", Inr.amount(principal), Inr.amount(interest), Inr.amount(total)));
    }

    // ------------------------------------------------------------------------------------------------ NOC
    public record Noc(Lender lender, Borrower borrower, LoanSummary loan, LocalDate closedOn, LocalDate issuedOn, String reference) {
        public Noc {
            Objects.requireNonNull(lender);
            Objects.requireNonNull(borrower);
            Objects.requireNonNull(loan);
        }
    }

    /** No-objection / closure letter. Issued only for a loan that is CLOSED (fully repaid); anything else is refused. */
    public static byte[] noc(Noc n, Render render) {
        if (!"CLOSED".equals(n.loan().status())) {
            throw new IllegalStateException("a no-objection letter is issued only for a closed loan; this loan is " + n.loan().status());
        }
        if (n.closedOn() == null) throw new IllegalStateException("the loan has no closure date");
        SimplePdf pdf = start("No-objection letter", "No-objection letter - " + n.loan().loanNo(), n.lender(), render);
        pdf.paragraph("Date: " + date(n.issuedOn()) + (n.reference() == null ? "" : "\nReference: " + n.reference()));
        pdf.paragraph("To\n" + n.borrower().name() + (n.borrower().address() == null ? "" : "\n" + n.borrower().address()));
        pdf.spacer(6);
        pdf.centredHeading("No-objection certificate and loan closure letter");
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("Loan account", n.loan().loanNo());
        kv.put("Type of loan", text(n.loan().productName()));
        kv.put("Amount sanctioned", Inr.rs(n.loan().sanctionedAmount()));
        kv.put("Disbursed on", date(n.loan().disbursedOn()));
        kv.put("Closed on", date(n.closedOn()));
        pdf.keyValues(kv);
        pdf.paragraph("This is to certify that the loan account above, in the name of " + n.borrower().name()
                + ", has been repaid in full and was closed on " + date(n.closedOn()) + ". No amount is outstanding on this account "
                + "towards principal, interest or charges as on the date of closure.");
        pdf.paragraph(n.lender().name() + " has no objection to the release of any security, lien or mandate held for this loan, "
                + "and has no further claim against the borrower in respect of this loan account. The closure will be reported to the "
                + "credit information companies in the next reporting cycle.");
        pdf.paragraph("This letter is issued at the request of the borrower.");
        pdf.spacer(18);
        pdf.paragraph("For " + n.lender().name());
        pdf.spacer(24);
        pdf.paragraph("Authorised signatory");
        pdf.small("This is a system-generated letter and is valid without a signature when verified with the lender.");
        return pdf.build();
    }

    // ------------------------------------------------------------------------------------------------ GST invoice
    /** SAC for financial and related services (fees charged on loans). */
    public static final String SAC_FINANCIAL_SERVICES = "9971";

    public record InvoiceLine(String description, String sac, BigDecimal taxable, BigDecimal gstRatePercent, BigDecimal cgst,
                              BigDecimal sgst, BigDecimal igst) {
        public InvoiceLine {
            Objects.requireNonNull(taxable);
            sac = sac == null ? SAC_FINANCIAL_SERVICES : sac;
            cgst = cgst == null ? BigDecimal.ZERO : cgst;
            sgst = sgst == null ? BigDecimal.ZERO : sgst;
            igst = igst == null ? BigDecimal.ZERO : igst;
        }

        public BigDecimal total() {
            return taxable.add(cgst).add(sgst).add(igst);
        }
    }

    /**
     * @param supplier         the lender; {@code stateCode} is the GST state of the branch that supplies the service
     * @param placeOfSupply    GST state code of the recipient's address (the place of supply)
     * @param status           ISSUED or CANCELLED (the fee was reversed)
     */
    public record TaxInvoice(Lender supplier, Borrower recipient, String recipientGstin, String invoiceNo, LocalDate invoiceDate,
                             String loanNo, String placeOfSupply, String placeOfSupplyName, List<InvoiceLine> lines, String status) {
        public TaxInvoice {
            Objects.requireNonNull(supplier);
            Objects.requireNonNull(recipient);
            Objects.requireNonNull(invoiceNo, "invoice number");
            Objects.requireNonNull(invoiceDate, "invoice date");
            lines = List.copyOf(lines);
            if (lines.isEmpty()) throw new IllegalArgumentException("an invoice needs at least one line");
        }

        public boolean interState() {
            return placeOfSupply != null && supplier.stateCode() != null && !placeOfSupply.equals(supplier.stateCode());
        }
    }

    /**
     * GST tax invoice for fees (CGST Rules, rule 46 particulars that apply to a service): supplier and recipient,
     * consecutive invoice number and date, SAC, taxable value, rate and amount of each tax, place of supply.
     * An intra-state supply must carry CGST and SGST and no IGST; an inter-state supply IGST only.
     */
    public static byte[] taxInvoice(TaxInvoice t, Render render) {
        if (t.invoiceNo().length() > 16) throw new IllegalArgumentException("a GST invoice number is at most 16 characters");
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        List<List<String>> rows = new ArrayList<>();
        int n = 0;
        for (InvoiceLine l : t.lines()) {
            if (t.interState() && (l.cgst().signum() != 0 || l.sgst().signum() != 0)) {
                throw new IllegalArgumentException("an inter-state supply carries IGST, not CGST/SGST");
            }
            if (!t.interState() && l.igst().signum() != 0) {
                throw new IllegalArgumentException("an intra-state supply carries CGST and SGST, not IGST");
            }
            taxable = taxable.add(l.taxable());
            cgst = cgst.add(l.cgst());
            sgst = sgst.add(l.sgst());
            igst = igst.add(l.igst());
            rows.add(List.of(String.valueOf(++n), text(l.description()), l.sac(), Inr.amount(l.taxable()), Inr.percent(l.gstRatePercent()),
                    Inr.amount(l.cgst()), Inr.amount(l.sgst()), Inr.amount(l.igst()), Inr.amount(l.total())));
        }
        BigDecimal total = taxable.add(cgst).add(sgst).add(igst);

        boolean cancelled = "CANCELLED".equals(t.status());
        SimplePdf pdf = start("Tax invoice", "Tax invoice " + t.invoiceNo(), t.supplier(), render);
        pdf.centredHeading(cancelled ? "Tax invoice (CANCELLED)" : "Tax invoice");
        if (cancelled) pdf.bold("This invoice was cancelled because the fee was reversed. It is not a demand for payment.");
        Map<String, String> head = new LinkedHashMap<>();
        head.put("Invoice No.", t.invoiceNo());
        head.put("Invoice date", date(t.invoiceDate()));
        head.put("Loan account", text(t.loanNo()));
        head.put("Supplier", t.supplier().name());
        head.put("Supplier address", setting(t.supplier().address()));
        head.put("Supplier GSTIN", setting(t.supplier().gstin()));
        head.put("Supplier state", state(t.supplier().stateName(), t.supplier().stateCode()));
        head.put("Recipient", t.recipient().name());
        head.put("Recipient address", text(t.recipient().address()));
        head.put("Recipient GSTIN", t.recipientGstin() == null || t.recipientGstin().isBlank() ? "Unregistered" : t.recipientGstin());
        head.put("Place of supply", state(t.placeOfSupplyName(), t.placeOfSupply()));
        head.put("Nature of supply", t.interState() ? "Inter-state (IGST)" : "Intra-state (CGST + SGST)");
        head.put("Reverse charge", "No");
        pdf.keyValues(head);

        List<Column> cols = List.of(Column.right("No.", 1), Column.left("Description of service", 5.5), Column.left("SAC", 1.6),
                Column.right("Taxable value", 3), Column.right("Rate", 1.6), Column.right("CGST", 2.3), Column.right("SGST", 2.3),
                Column.right("IGST", 2.3), Column.right("Total", 3));
        pdf.table(cols, rows, List.of("", "Total (Rs.)", "", Inr.amount(taxable), "", Inr.amount(cgst), Inr.amount(sgst), Inr.amount(igst),
                Inr.amount(total)));
        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("Total taxable value", Inr.rs(taxable));
        totals.put("Total tax", Inr.rs(cgst.add(sgst).add(igst)));
        totals.put("Invoice total", Inr.rs(total));
        totals.put("Invoice total in words", Inr.words(total));
        pdf.keyValues(totals);
        pdf.small("SAC " + SAC_FINANCIAL_SERVICES + ": financial and related services. This is a computer-generated invoice.");
        return pdf.build();
    }

    // ------------------------------------------------------------------------------------------------ helpers
    private static SimplePdf start(String name, String title, Lender lender, Render render) {
        Render r = render == null ? Render.DEFAULT : render;
        StringBuilder foot = new StringBuilder(lender.name());
        if (lender.cin() != null && !lender.cin().isBlank()) foot.append(" | CIN ").append(lender.cin());
        if (lender.rbiRegistration() != null && !lender.rbiRegistration().isBlank()) foot.append(" | RBI registration ").append(lender.rbiRegistration());
        return SimplePdf.a4().compress(r.compress()).created(r.created()).title(title).header(lender.name(), name).footer(foot.toString());
    }

    private static Map<String, String> parties(Borrower b, LoanSummary l) {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("Borrower", b.name() + (b.customerNo() == null ? "" : " (customer " + b.customerNo() + ")"));
        if (b.address() != null && !b.address().isBlank()) kv.put("Address", b.address());
        if (b.panMasked() != null) kv.put("PAN", b.panMasked());
        if (b.mobileMasked() != null) kv.put("Mobile", b.mobileMasked());
        kv.put("Loan account", l.loanNo());
        kv.put("Type of loan", text(l.productName()));
        if (l.branchName() != null) kv.put("Branch", l.branchName());
        kv.put("Amount sanctioned", Inr.rs(l.sanctionedAmount()));
        kv.put("Interest rate", Inr.percent(l.ratePercent()) + " per annum");
        kv.put("Term", l.tenorMonths() + " months");
        if (l.emi() != null) kv.put("Instalment (EMI)", Inr.rs(l.emi()));
        kv.put("Disbursed on", date(l.disbursedOn()));
        kv.put("Status", capitalised(l.status()));
        return kv;
    }

    private static String state(String name, String code) {
        if (name == null && code == null) return NOT_CONFIGURED;
        return name == null ? "State code " + code : code == null ? name : name + " (state code " + code + ")";
    }

    private static String date(LocalDate d) {
        return formatDate(d);
    }

    /** Dates as printed on every document: 05-Sep-2026; "-" when there is none. */
    public static String formatDate(LocalDate d) {
        return d == null ? "-" : DATE.format(d);
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /** FIXED → "Fixed". */
    private static String capitalised(String s) {
        if (s == null || s.isBlank()) return "-";
        String v = s.trim().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    private static String setting(String s) {
        return s == null || s.isBlank() ? NOT_CONFIGURED : s;
    }
}
