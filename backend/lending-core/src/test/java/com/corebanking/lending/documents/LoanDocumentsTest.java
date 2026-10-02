package com.corebanking.lending.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.FeeCalculator;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.documents.LoanDocuments.Balance;
import com.corebanking.lending.documents.LoanDocuments.Borrower;
import com.corebanking.lending.documents.LoanDocuments.ContingentCharge;
import com.corebanking.lending.documents.LoanDocuments.ContingentKind;
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
import com.corebanking.lending.engine.LoanTerms;
import com.corebanking.lending.engine.ScheduleBuilder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden text checks on the borrower documents, built from the engine's own schedule for the golden loan
 * (Rs. 1,00,000 at 18% for 12 months from 30-Jun-2026). All names are test data.
 */
class LoanDocumentsTest {

    static BigDecimal bd(String s) { return new BigDecimal(s); }

    static final LocalDate OPEN = LocalDate.of(2026, 6, 30);
    static final Lender LENDER = new Lender("CLAUDE-TEST Finance Limited", "1 Test Road, Kochi 682001", "U65999KL2020PLC000000",
            "N-00.00000", "32AAAAA0000A1Z5", "32", "Kerala");
    static final Borrower BORROWER = new Borrower("CLAUDE-TEST Borrower One", "90010000000013", "12 Sample Street, Kochi, Kerala 682001",
            "32", "Kerala", "XXXXX1234X", "XXXXXX3210");
    static final List<Instalment> SCHEDULE = ScheduleBuilder.build(LoanTerms.equated(bd("100000"), bd("18"), 12, OPEN));

    static LoanSummary loan(String status) {
        return new LoanSummary("10010000000017", "CLAUDE-TEST Personal loan", "Head Office Kochi", bd("100000"), bd("18"), 12,
                ScheduleBuilder.emi(LoanTerms.equated(bd("100000"), bd("18"), 12, OPEN)), OPEN, status, LocalDate.of(2026, 9, 30));
    }

    static List<ScheduleRow> rows(List<Instalment> schedule) {
        return schedule.stream().map(i -> new ScheduleRow(i.number(), i.dueDate(), i.openingBalance(), i.principal(), i.interest(), i.instalment())).toList();
    }

    static String text(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }

    static void has(String pdf, String text) {
        assertTrue(pdf.contains("(" + text + ") Tj"), "document should print: " + text);
    }

    /** All printed text in order, lines joined by a space: for prose that wraps across lines. */
    static String flow(String pdf) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((.*?)\\) Tj").matcher(pdf);
        while (m.find()) sb.append(m.group(1).replace("\\(", "(").replace("\\)", ")")).append(' ');
        return sb.toString();
    }

    static void prints(String pdf, String prose) {
        assertTrue(flow(pdf).contains(prose), "document should say: " + prose);
    }

    static void save(String name, byte[] pdf) throws Exception {
        String dir = System.getProperty("pdf.out");
        if (dir == null) return;
        Files.createDirectories(Path.of(dir));
        Files.write(Path.of(dir, name), pdf);
    }

    // ------------------------------------------------------------------------------------------------ KFS
    static Kfs kfs() {
        BigDecimal interest = SCHEDULE.stream().map(Instalment::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Kfs(LENDER, BORROWER, "10010000000017", "CLAUDE-TEST Personal loan", OPEN, 3, bd("100000"), "100% upfront", 12,
                "Monthly", SCHEDULE.size(), bd("9168"), SCHEDULE.get(0).dueDate(), "FIXED", bd("18.0000"), null,
                List.of(new KfsFee("Processing fee", true, false, bd("750"), bd("135"), bd("885"))),
                bd("99115"), interest, bd("100000").add(interest), bd("18.58"),
                "internal rate of return of the net cash flows, fees taken excluding GST, monthly instalments, stated as a nominal annual rate",
                List.of(new ContingentCharge(ContingentKind.PENAL_DELAYED_PAYMENT, "24% per annum on the overdue amount, for the days it is overdue; not compounded"),
                        new ContingentCharge(ContingentKind.FORECLOSURE, "Foreclosure fee: 2% of the principal prepaid plus GST")),
                new Qualitative("Clause 14", "Clause 21", "CLAUDE-TEST Officer", "0000000000", "grievance@claude-test.invalid", false, null, null),
                3, rows(SCHEDULE), null);
    }

    @Test
    void kfs_has_both_parts_the_apr_illustration_and_the_schedule() throws Exception {
        save("kfs.pdf", LoanDocuments.kfs(kfs(), Render.DEFAULT));
        String s = text(LoanDocuments.kfs(kfs(), Render.PLAIN));
        has(s, "Key Facts Statement");
        has(s, "Part 1 \\(Interest rate and fees/charges\\)");
        has(s, "Part 2 \\(Other qualitative information\\)");
        has(s, "Illustration for computation of APR \\(Annex B\\)");
        has(s, "Repayment schedule \\(Annex C\\)");
        has(s, "Sanctioned loan amount");
        has(s, "Rs. 1,00,000.00");
        has(s, "18% per annum, fixed");
        has(s, "Rs. 9,168.00");
        has(s, "Annual Percentage Rate \\(APR\\) \\(%\\)");
        has(s, "18.58%");
        has(s, "Processing fee");
        has(s, "750.00");
        has(s, "135.00");
        has(s, "885.00");
        has(s, "Rs. 99,115.00");                                         // net disbursed
        has(s, "Rs. 885.00");                                            // fees payable to the lender, with GST
        has(s, "Rs. 0.00");                                              // nothing routed to a third party
        has(s, "Not applicable");                                        // floating-rate block on a fixed loan
        has(s, "3 days from disbursement");                              // cooling-off
        prints(s, "CLAUDE-TEST Officer; phone 0000000000; email grievance@claude-test.invalid");
        has(s, "Clause 14");
        has(s, "Nil");                                                   // contingent charges with nothing configured
        prints(s, "Foreclosure fee: 2% of the principal prepaid plus GST");
        has(s, "31-Jul-2026");                                           // first instalment (month-end loan)
        has(s, "30-Jun-2027");                                           // last instalment
        has(s, "3 working days from the date of this statement");
        prints(s, "Not yet accepted by the borrower");
        // schedule totals: principal adds up to the amount sanctioned
        has(s, "1,00,000.00");
        // the borrower's PAN and mobile are not part of a KFS
        assertFalse(s.contains("XXXXX1234X"));
    }

    @Test
    void kfs_total_repayable_is_principal_plus_interest_of_the_schedule() {
        Kfs k = kfs();
        BigDecimal instalments = SCHEDULE.stream().map(Instalment::instalment).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, instalments.compareTo(k.totalRepayable()));
        String s = text(LoanDocuments.kfs(k, Render.PLAIN));
        has(s, "Rs. " + com.corebanking.kernel.Inr.amount(k.totalRepayable()));
        has(s, com.corebanking.kernel.Inr.amount(instalments));       // total of the Instalment column
    }

    @Test
    void kfs_shows_unconfigured_settings_and_floating_rate_details() {
        Kfs b = kfs();
        Kfs k = new Kfs(b.lender(), b.borrower(), b.loanNo(), b.productName(), b.kfsDate(), b.validityDays(), b.sanctionedAmount(),
                b.disbursalSchedule(), b.tenorMonths(), b.instalmentType(), b.instalments(), b.emi(), b.firstDueDate(), "FLOATING",
                b.ratePercent(), new LoanDocuments.FloatingRate("CLAUDE-TEST benchmark", bd("8.5"), bd("9.5"), bd("18"), "Quarterly",
                "For a 25 bps rise: EMI Rs. 9,180 for 12 instalments"),
                List.of(), b.sanctionedAmount(), b.totalInterest(), b.totalRepayable(), b.aprPercent(), null, List.of(),
                new Qualitative(null, null, null, null, null, true, "CLAUDE-TEST Partner Bank 80% : lender 20%, blended rate 16%", "CLAUDE-TEST LSP"),
                b.coolingOffDays(), b.schedule(), "Accepted on 30-Jun-2026 by OTP (reference CLAUDE-TEST-REF)");
        String s = text(LoanDocuments.kfs(k, Render.PLAIN));
        has(s, "[not configured]");
        prints(s, "[not configured]; phone [not configured]; email [not configured]");
        has(s, "CLAUDE-TEST benchmark");
        has(s, "8.5%");
        has(s, "Quarterly");
        has(s, "Yes");
        has(s, "CLAUDE-TEST LSP");
        prints(s, "No fees or charges are payable at disbursement");
        prints(s, "Accepted on 30-Jun-2026 by OTP");
    }

    // ------------------------------------------------------------------------------------------------ statement
    static Statement statement(Balance closing) {
        List<StatementLine> lines = List.of(
                new StatementLine(OPEN, "Loan disbursed", null, bd("100000"), null, null),
                new StatementLine(LocalDate.of(2026, 7, 30), "Interest 30-Jun-2026 to 30-Jul-2026", null, null, bd("1479"), null),
                new StatementLine(LocalDate.of(2026, 7, 30), "Receipt UPI CLAUDE-TEST-1", bd("9168"), bd("-7689"), bd("-1479"), null),
                new StatementLine(LocalDate.of(2026, 8, 30), "Interest 31-Jul-2026 to 30-Aug-2026", null, null, bd("1411.25"), null),
                new StatementLine(LocalDate.of(2026, 9, 5), "Penal charges", null, null, null, bd("36.17")),
                new StatementLine(LocalDate.of(2026, 9, 6), "Bounce charge", null, null, null, bd("590")));
        return new Statement(LENDER, BORROWER, loan("ACTIVE"), OPEN, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10),
                new Balance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), closing, lines,
                new Overdue(bd("7757"), bd("1411"), bd("626.17"), 11, LocalDate.of(2026, 8, 30)), bd("250"));
    }

    @Test
    void statement_lists_every_transaction_with_its_split_and_running_outstanding() throws Exception {
        Statement st = statement(new Balance(bd("92311"), bd("1411.25"), bd("626.17")));
        save("statement.pdf", LoanDocuments.statement(st, Render.DEFAULT));
        String s = text(LoanDocuments.statement(st, Render.PLAIN));
        has(s, "Statement of account");
        has(s, "30-Jun-2026 to 10-Sep-2026");
        has(s, "CLAUDE-TEST Borrower One \\(customer 90010000000013\\)");
        has(s, "XXXXX1234X");
        has(s, "XXXXXX3210");
        has(s, "Opening balance");
        has(s, "Loan disbursed");
        has(s, "Receipt UPI CLAUDE-TEST-1");
        has(s, "9,168.00");                    // received
        has(s, "-7,689.00");                   // principal part of the receipt
        has(s, "-1,479.00");                   // interest part of the receipt
        has(s, "1,01,479.00");                 // outstanding after the first interest line
        has(s, "92,311.00");                   // outstanding after the receipt = principal left
        has(s, "93,722.25");                   // after August interest
        has(s, "93,758.42");                   // after penal charges
        has(s, "94,348.42");                   // closing: 92,311 + 1,411.25 + 626.17
        has(s, "Closing balance");
        has(s, "Rs. 94,348.42 \\(principal 92,311.00, interest 1,411.25, charges 626.17\\)");
        has(s, "Rs. 9,168.00");                // received in the period
        has(s, "Rs. 250.00");                  // advance held
        has(s, "Overdue summary as on 10-Sep-2026");
        has(s, "Rs. 9,794.17");                // 7,757 + 1,411 + 626.17
        has(s, "30-Aug-2026");
        has(s, "11");                          // days past due
    }

    @Test
    void statement_that_does_not_balance_is_refused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoanDocuments.statement(statement(new Balance(bd("92311"), bd("1411.25"), bd("626.18"))), Render.PLAIN));
        assertTrue(e.getMessage().contains("does not balance"));
        // a line outside the period is refused too
        Statement ok = statement(new Balance(bd("92311"), bd("1411.25"), bd("626.17")));
        List<StatementLine> lines = new ArrayList<>(ok.lines());
        lines.add(new StatementLine(LocalDate.of(2026, 9, 11), "late", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> LoanDocuments.statement(new Statement(ok.lender(), ok.borrower(), ok.loan(),
                ok.from(), ok.to(), ok.generatedOn(), ok.opening(), ok.closing(), lines, ok.overdue(), null), Render.PLAIN));
        assertThrows(IllegalArgumentException.class, () -> new Statement(ok.lender(), ok.borrower(), ok.loan(), ok.to(), ok.from(),
                ok.generatedOn(), ok.opening(), ok.closing(), List.of(), null, null));
    }

    @Test
    void statement_without_arrears_says_nil() {
        Statement st = new Statement(LENDER, BORROWER, loan("ACTIVE"), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 8, 1), new Balance(bd("100000"), bd("49.32"), BigDecimal.ZERO),
                new Balance(bd("100000"), bd("49.32"), BigDecimal.ZERO), List.of(), null, null);
        String s = text(LoanDocuments.statement(st, Render.PLAIN));
        has(s, "Nil");
        has(s, "Rs. 1,00,049.32");
        assertFalse(s.contains("Advance held"));
    }

    @Test
    void long_statement_runs_over_pages_with_the_header_repeated() throws Exception {
        List<StatementLine> lines = new ArrayList<>();
        BigDecimal interest = BigDecimal.ZERO;
        for (int i = 0; i < 200; i++) {
            lines.add(new StatementLine(LocalDate.of(2026, 7, 1).plusDays(i), "Interest accrued CLAUDE-TEST " + i, null, null, bd("49.32"), null));
            interest = interest.add(bd("49.32"));
        }
        Statement st = new Statement(LENDER, BORROWER, loan("ACTIVE"), LocalDate.of(2026, 7, 1), LocalDate.of(2027, 1, 31),
                LocalDate.of(2027, 2, 1), new Balance(bd("100000"), BigDecimal.ZERO, BigDecimal.ZERO),
                new Balance(bd("100000"), interest, BigDecimal.ZERO), lines, null, null);
        byte[] pdf = LoanDocuments.statement(st, Render.PLAIN);
        save("statement-long.pdf", LoanDocuments.statement(st, Render.DEFAULT));
        String s = text(pdf);
        int pages = s.split("/Type /Page /Parent", -1).length - 1;
        assertTrue(pages >= 4, "pages: " + pages);
        assertEquals(pages, s.split("\\(Particulars\\) Tj", -1).length - 1);
        has(s, "Page " + pages + " of " + pages);
        has(s, "Interest accrued CLAUDE-TEST 199");
    }

    // ------------------------------------------------------------------------------------------------ schedule
    @Test
    void schedule_shows_instalments_fallen_due_and_those_to_come() throws Exception {
        List<RaisedInstalment> raised = List.of(
                new RaisedInstalment(1, LocalDate.of(2026, 7, 30), bd("7689"), bd("1479"), bd("9168"), BigDecimal.ZERO),
                new RaisedInstalment(2, LocalDate.of(2026, 8, 30), bd("7757"), bd("1411"), bd("1000"), bd("8168")));
        Schedule sc = new Schedule(LENDER, BORROWER, loan("ACTIVE"), LocalDate.of(2026, 9, 10), raised, rows(SCHEDULE.subList(2, SCHEDULE.size())));
        save("schedule.pdf", LoanDocuments.schedule(sc, Render.DEFAULT));
        String s = text(LoanDocuments.schedule(sc, Render.PLAIN));
        has(s, "Repayment schedule");
        has(s, "Instalments fallen due");
        has(s, "Instalments to come");
        has(s, "8,168.00");
        has(s, "10,168.00");                    // paid total
        has(s, "18,336.00");                    // two instalments due
        has(s, "30-Sep-2026");
        has(s, "30-Jun-2027");
        has(s, "10-Sep-2026");
        String fresh = text(LoanDocuments.schedule(new Schedule(LENDER, BORROWER, loan("ACTIVE"), OPEN, List.of(), rows(SCHEDULE)), Render.PLAIN));
        has(fresh, "Schedule");
        assertFalse(fresh.contains("Instalments fallen due"));
        String done = text(LoanDocuments.schedule(new Schedule(LENDER, BORROWER, loan("CLOSED"), OPEN, raised, List.of()), Render.PLAIN));
        has(done, "No further instalments are scheduled.");
    }

    // ------------------------------------------------------------------------------------------------ NOC
    @Test
    void noc_is_issued_only_for_a_closed_loan() throws Exception {
        Noc n = new Noc(LENDER, BORROWER, loan("CLOSED"), LocalDate.of(2027, 6, 30), LocalDate.of(2027, 7, 2), "NOC/10010000000017");
        save("noc.pdf", LoanDocuments.noc(n, Render.DEFAULT));
        String s = text(LoanDocuments.noc(n, Render.PLAIN));
        has(s, "No-objection certificate and loan closure letter");
        has(s, "10010000000017");
        has(s, "30-Jun-2027");
        has(s, "CLAUDE-TEST Borrower One");
        has(s, "12 Sample Street, Kochi, Kerala 682001");
        has(s, "Reference: NOC/10010000000017");
        prints(s, "has been repaid in full and was closed on 30-Jun-2027");
        has(s, "For CLAUDE-TEST Finance Limited");
        for (String status : List.of("ACTIVE", "FROZEN", "CANCELLED", "WRITTEN_OFF", "SANCTIONED")) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> LoanDocuments.noc(
                    new Noc(LENDER, BORROWER, loan(status), LocalDate.of(2027, 6, 30), LocalDate.of(2027, 7, 2), null), Render.PLAIN));
            assertTrue(e.getMessage().contains("only for a closed loan"));
        }
        assertThrows(IllegalStateException.class, () -> LoanDocuments.noc(new Noc(LENDER, BORROWER, loan("CLOSED"), null, OPEN, null), Render.PLAIN));
    }

    // ------------------------------------------------------------------------------------------------ GST invoice
    static TaxInvoice invoice(String placeOfSupply, String placeName, InvoiceLine line, String status) {
        return new TaxInvoice(LENDER, BORROWER, null, "70010000000018", OPEN, "10010000000017", placeOfSupply, placeName, List.of(line), status);
    }

    @Test
    void intra_state_invoice_splits_gst_into_cgst_and_sgst() throws Exception {
        FeeRule pf = new FeeRule("PF", "Processing fee", FeeRule.Event.DISBURSEMENT, FeeRule.CalcType.FIXED, bd("750"), null, null, null, null,
                bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, true);
        FeeRule.Charge c = pf.compute(bd("100000"), "32", "32", com.corebanking.calc.Rounding.PAISE_HALF_UP);
        TaxInvoice t = invoice("32", "Kerala", new InvoiceLine(c.name(), null, c.fee(), bd("18"), c.gst().cgst(), c.gst().sgst(), c.gst().igst()), "ISSUED");
        save("invoice-intra-state.pdf", LoanDocuments.taxInvoice(t, Render.DEFAULT));
        String s = text(LoanDocuments.taxInvoice(t, Render.PLAIN));
        has(s, "Tax invoice");
        has(s, "70010000000018");
        has(s, "32AAAAA0000A1Z5");
        has(s, "Kerala \\(state code 32\\)");
        has(s, "Intra-state \\(CGST + SGST\\)");
        has(s, "9971");
        has(s, "750.00");
        has(s, "67.50");
        has(s, "885.00");
        has(s, "18%");
        has(s, "Unregistered");
        has(s, "Rs. 885.00");
        has(s, "Rupees Eight Hundred Eighty Five Only");
        assertFalse(s.contains("CANCELLED"));
    }

    @Test
    void inter_state_invoice_carries_igst_only() throws Exception {
        TaxInvoice t = invoice("27", "Maharashtra", new InvoiceLine("Bounce charge", null, bd("500"), bd("18"), null, null, bd("90")), "CANCELLED");
        save("invoice-inter-state.pdf", LoanDocuments.taxInvoice(t, Render.DEFAULT));
        String s = text(LoanDocuments.taxInvoice(t, Render.PLAIN));
        has(s, "Inter-state \\(IGST\\)");
        has(s, "Maharashtra \\(state code 27\\)");
        has(s, "90.00");
        has(s, "590.00");
        has(s, "Tax invoice \\(CANCELLED\\)");
        // the wrong tax for the place of supply is refused
        assertThrows(IllegalArgumentException.class, () -> LoanDocuments.taxInvoice(
                invoice("27", "Maharashtra", new InvoiceLine("x", null, bd("500"), bd("18"), bd("45"), bd("45"), null), "ISSUED"), Render.PLAIN));
        assertThrows(IllegalArgumentException.class, () -> LoanDocuments.taxInvoice(
                invoice("32", "Kerala", new InvoiceLine("x", null, bd("500"), bd("18"), null, null, bd("90")), "ISSUED"), Render.PLAIN));
        assertThrows(IllegalArgumentException.class, () -> LoanDocuments.taxInvoice(new TaxInvoice(LENDER, BORROWER, null,
                "12345678901234567", OPEN, "x", "32", "Kerala", List.of(new InvoiceLine("x", null, bd("1"), bd("18"), null, null, null)), "ISSUED"), Render.PLAIN));
        assertThrows(IllegalArgumentException.class, () -> new TaxInvoice(LENDER, BORROWER, null, "1", OPEN, "x", "32", "Kerala", List.of(), "ISSUED"));
    }

    @Test
    void same_input_gives_identical_files() {
        assertEquals(text(LoanDocuments.kfs(kfs(), Render.DEFAULT)), text(LoanDocuments.kfs(kfs(), Render.DEFAULT)));
    }

    // ------------------------------------------------------------------------------------------------ engine link
    /**
     * Fee invoices are issued from the ledger (V16 lending.issue_fee_invoices): the k-th FEE_CHARGE lot of a
     * transaction belongs to charge "C" + (charge sequence before the transaction + k). This holds that link.
     */
    @Test
    void fee_charge_lots_follow_the_charge_sequence() {
        FeeRule doc = new FeeRule("DOC", "Documentation charge", FeeRule.Event.DISBURSEMENT, FeeRule.CalcType.FIXED, bd("200"), null, null,
                null, null, bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, false);
        FeeRule stamp = new FeeRule("STAMP", "Stamp charge", FeeRule.Event.DISBURSEMENT, FeeRule.CalcType.FIXED, bd("100"), null, null,
                null, null, bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, false);
        FeeRule pf = new FeeRule("PF", "Processing fee", FeeRule.Event.DISBURSEMENT, FeeRule.CalcType.FIXED, bd("750"), null, null,
                null, null, bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, true);
        FeeRule bounce = new FeeRule("BNC", "Bounce charge", FeeRule.Event.BOUNCE, FeeRule.CalcType.FIXED, bd("500"), null, null,
                null, null, bd("18"), FeeCalculator.TaxTreatment.EXCLUSIVE, false);
        var params = new LoanAccount.Params("10010000000017", "HO", "32", "32", bd("18"), bd("24"), null, null, null, null, 3,
                BigDecimal.ZERO, null, List.of(doc, pf, stamp, bounce));
        var book = LoanAccount.disburse(params, LoanTerms.equated(bd("100000"), bd("18"), 12, OPEN), OPEN);
        LoanAccount a = book.account();
        List<String> feeLots = book.result().lots().stream().filter(l -> l.type().equals("FEE_CHARGE")).map(l -> l.lines().get(0).narration()).toList();
        assertEquals(List.of("Documentation charge", "Stamp charge"), feeLots);
        assertEquals(List.of("C1", "C2"), a.charges().stream().map(LoanAccount.ChargeRow::id).toList());
        assertEquals(List.of("Documentation charge", "Stamp charge"), a.charges().stream().map(LoanAccount.ChargeRow::name).toList());
        assertEquals(2, a.snapshot().chargeSeq());

        // run past a missed instalment so a penal charge takes the next sequence number, then charge a fee
        for (LocalDate d = OPEN; !d.isAfter(LocalDate.of(2026, 8, 5)); d = d.plusDays(1)) a.endOfDay(d, null);
        int before = a.snapshot().chargeSeq();
        assertTrue(before > 2, "a penal charge row was added");
        var r = a.chargeFee("BNC", bd("0"), LocalDate.of(2026, 8, 6));
        assertEquals(1, r.lots().stream().filter(l -> l.type().equals("FEE_CHARGE")).count());
        LoanAccount.ChargeRow last = a.charges().get(a.charges().size() - 1);
        assertEquals("C" + (before + 1), last.id());
        assertEquals("Bounce charge", last.name());
        assertEquals(0, bd("590").compareTo(last.amount()));
    }
}
