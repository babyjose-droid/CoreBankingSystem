package com.corebanking.deposits;

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
 * Depositor documents as PDF (P4-7): the statement of an account for a period, the receipt of a term or recurring
 * deposit, and the certificate of interest paid and tax deducted for a tax year. Pure: each builder takes plain
 * records, so the same input gives the same bytes and the layout is tested without a database.
 *
 * <p>The receipt carries what RBI asks a deposit-taking NBFC's receipt to show (the depositor's name, the amount in
 * words and figures, the rate, the date of maturity and the amount repayable, the company's name, an authorised
 * signatory) and the line on insurance the institution's rule set calls for. The interest certificate is the
 * institution's own statement; the certificate of tax deducted (Form 16A) is downloaded from TRACES, not made here.
 */
public final class DepositDocuments {

    private DepositDocuments() {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    /** Printed where a tenant setting has not been filled in, so a gap is obvious on the document. */
    public static final String NOT_CONFIGURED = "[not configured]";

    /** How the file is written. Tests switch compression off to read the text. */
    public record Render(boolean compress, Instant created) {
        public static final Render DEFAULT = new Render(true, null);
        public static final Render PLAIN = new Render(false, null);
    }

    /**
     * The bank or NBFC. {@code disclosure} is the line on deposit insurance the rule set calls for: the DICGC cover at a
     * bank, "not insured" at a deposit-taking NBFC.
     */
    public record Institution(String name, String address, String cin, String rbiRegistration, String disclosure) {
        public Institution {
            Objects.requireNonNull(name, "institution name");
        }
    }

    /** A holder: name in full, the address of the first holder only. */
    public record Holder(String name, String customerNo, String address) {
        public Holder {
            Objects.requireNonNull(name, "holder name");
        }
    }

    /**
     * @param kind       "Savings account", "Fixed deposit (cumulative)" …
     * @param nomination e.g. "Registered: 1 nominee" or "No nominee"
     */
    public record Account(String accountNo, String kind, String productName, String branchName, String operatingMode, LocalDate openedOn,
                          String status, String nomination) {
        public Account {
            Objects.requireNonNull(accountNo, "account number");
        }
    }

    // ------------------------------------------------------------------------------------------------ statement
    /** One movement: a debit or a credit (the other zero), with the balance after it as the books show it. */
    public record StatementLine(LocalDate date, LocalDate valueDate, String particulars, String reference, BigDecimal debit, BigDecimal credit,
                                BigDecimal balance) {
        public StatementLine {
            debit = debit == null ? BigDecimal.ZERO : debit;
            credit = credit == null ? BigDecimal.ZERO : credit;
            if (debit.signum() < 0 || credit.signum() < 0 || (debit.signum() > 0 && credit.signum() > 0)) {
                throw new IllegalArgumentException("a statement line is a debit or a credit, not both and not negative");
            }
        }
    }

    /**
     * @param held      amount held under lien at the end of the period (null or zero: none)
     * @param interestRate e.g. "2.75% a year on daily balances", or null
     */
    public record Statement(Institution institution, List<Holder> holders, Account account, LocalDate from, LocalDate to, LocalDate generatedOn,
                            BigDecimal opening, BigDecimal closing, List<StatementLine> lines, BigDecimal held, String interestRate) {
        public Statement {
            Objects.requireNonNull(institution);
            Objects.requireNonNull(account);
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(opening, "opening balance");
            Objects.requireNonNull(closing, "closing balance");
            holders = holders == null ? List.of() : List.copyOf(holders);
            lines = lines == null ? List.of() : List.copyOf(lines);
            if (holders.isEmpty()) throw new IllegalArgumentException("an account has at least one holder");
            if (to.isBefore(from)) throw new IllegalArgumentException("the statement period ends before it starts");
        }
    }

    /**
     * The statement: opening balance, each movement with the running balance, closing balance. Refused when the
     * movements do not lead from the opening to the closing balance, or a running balance differs from the books.
     */
    public static byte[] statement(Statement s, Render render) {
        BigDecimal running = s.opening();
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(date(s.from()), "Opening balance", "", "", "", "", Inr.amount(s.opening())));
        for (StatementLine l : s.lines()) {
            if (l.date().isBefore(s.from()) || l.date().isAfter(s.to())) {
                throw new IllegalArgumentException("a movement dated " + l.date() + " is outside the statement period");
            }
            running = running.add(l.credit()).subtract(l.debit());
            debits = debits.add(l.debit());
            credits = credits.add(l.credit());
            if (l.balance() != null && l.balance().compareTo(running) != 0) {
                throw new IllegalArgumentException("the statement does not balance: after the movement of " + l.date() + " the books show "
                        + l.balance().toPlainString() + " but the movements lead to " + running.toPlainString());
            }
            rows.add(List.of(date(l.date()), text(l.particulars()), l.reference() == null ? "" : l.reference(), l.valueDate() == null || l.valueDate().equals(l.date()) ? "" : date(l.valueDate()),
                    amount(l.debit()), amount(l.credit()), Inr.amount(running)));
        }
        if (running.compareTo(s.closing()) != 0) {
            throw new IllegalArgumentException("the statement does not balance: the movements lead to " + running.toPlainString()
                    + " but the closing balance is " + s.closing().toPlainString());
        }
        SimplePdf pdf = start("Statement of account", "Statement of account - " + s.account().accountNo(), s.institution(), render);
        pdf.centredHeading("Statement of account");
        Map<String, String> who = parties(s.holders(), s.account());
        if (s.interestRate() != null) who.put("Interest", s.interestRate());
        pdf.keyValues(who);
        Map<String, String> period = new LinkedHashMap<>();
        period.put("Statement period", date(s.from()) + " to " + date(s.to()));
        period.put("Generated on (business date)", date(s.generatedOn()));
        period.put("Opening balance", Inr.rs(s.opening()));
        period.put("Total debits", Inr.rs(debits) + " (" + count(s.lines(), true) + ")");
        period.put("Total credits", Inr.rs(credits) + " (" + count(s.lines(), false) + ")");
        period.put("Closing balance", Inr.rs(s.closing()));
        if (s.held() != null && s.held().signum() > 0) {
            period.put("Held under lien", Inr.rs(s.held()));
            period.put("Available", Inr.rs(s.closing().subtract(s.held()).max(BigDecimal.ZERO)));
        }
        pdf.keyValues(period);
        pdf.subheading("Transactions");
        List<Column> cols = List.of(Column.left("Date", 2.6), Column.left("Particulars", 6.6), Column.left("Reference", 3), Column.left("Value date", 2.6),
                Column.right("Debit", 2.8), Column.right("Credit", 2.8), Column.right("Balance", 3.2));
        pdf.table(cols, rows, List.of(date(s.to()), "Closing balance", "", "", Inr.amount(debits), Inr.amount(credits), Inr.amount(s.closing())));
        if (s.lines().isEmpty()) pdf.small("No transaction in the period.");
        pdf.small("Value date is shown only where it differs from the date of the entry. Please tell the branch within 30 days of any "
                + "entry you do not recognise.");
        disclosure(pdf, s.institution());
        return pdf.build();
    }

    private static String count(List<StatementLine> lines, boolean debit) {
        long n = lines.stream().filter(l -> (debit ? l.debit() : l.credit()).signum() > 0).count();
        return n + (n == 1 ? " entry" : " entries");
    }

    // ------------------------------------------------------------------------------------------------ receipt
    /**
     * @param receiptNo       the receipt's own number (the account number and the funding date)
     * @param instalment      a recurring deposit's monthly instalment; null for a fixed deposit
     * @param interest        how interest is paid, in words: "Compounded quarterly and paid at maturity" …
     * @param maturityValue   what is repayable on the maturity date if nothing changes (a recurring deposit: if every
     *                        instalment is paid on time)
     * @param atMaturity      the maturity instruction in words
     * @param lien            e.g. "Pledged for loan 10010000000017", or null
     */
    public record Receipt(Institution institution, List<Holder> holders, Account account, String receiptNo, LocalDate issuedOn,
                          BigDecimal principal, BigDecimal instalment, BigDecimal ratePercent, String tenure, LocalDate startDate,
                          LocalDate maturityDate, BigDecimal maturityValue, String interest, String atMaturity, String fundedBy,
                          String lien) {
        public Receipt {
            Objects.requireNonNull(institution);
            Objects.requireNonNull(account);
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(ratePercent, "rate");
            Objects.requireNonNull(startDate, "start date (a receipt is for a funded deposit)");
            Objects.requireNonNull(maturityDate, "maturity date");
            Objects.requireNonNull(maturityValue, "maturity value");
            holders = holders == null ? List.of() : List.copyOf(holders);
            if (holders.isEmpty()) throw new IllegalArgumentException("a deposit has at least one holder");
            if (maturityDate.isBefore(startDate)) throw new IllegalArgumentException("the deposit matures before it starts");
        }
    }

    public static byte[] receipt(Receipt r, Render render) {
        boolean recurring = r.instalment() != null;
        String title = recurring ? "Recurring deposit receipt" : "Term deposit receipt";
        SimplePdf pdf = start(title, title + " - " + r.account().accountNo(), r.institution(), render);
        pdf.centredHeading(title);
        Map<String, String> head = new LinkedHashMap<>();
        head.put("Receipt number", r.receiptNo());
        head.put("Issued on", date(r.issuedOn()));
        pdf.keyValues(head);
        pdf.keyValues(parties(r.holders(), r.account()));
        Map<String, String> terms = new LinkedHashMap<>();
        if (recurring) {
            terms.put("Monthly instalment", Inr.rs(r.instalment()));
            terms.put("Monthly instalment in words", Inr.words(r.instalment()));
        } else {
            terms.put("Amount of deposit", Inr.rs(r.principal()));
            terms.put("Amount in words", Inr.words(r.principal()));
        }
        terms.put("Rate of interest", Inr.percent(r.ratePercent()) + " per annum");
        terms.put("Interest", text(r.interest()));
        terms.put("Period", text(r.tenure()));
        terms.put("Date of deposit", date(r.startDate()));
        terms.put("Date of maturity", date(r.maturityDate()));
        terms.put("Amount repayable on maturity", Inr.rs(r.maturityValue()) + (recurring ? " (if every instalment is paid on time)" : ""));
        terms.put("Amount repayable in words", Inr.words(r.maturityValue()));
        terms.put("On maturity", text(r.atMaturity()));
        if (r.fundedBy() != null) terms.put("Received by", r.fundedBy());
        if (r.lien() != null) terms.put("Lien", r.lien());
        pdf.keyValues(terms);
        pdf.small("Interest is subject to tax deducted at source as the Income-tax Act requires. Premature withdrawal, where allowed, "
                + "is on the terms of the product at the time of withdrawal. This receipt is not transferable.");
        disclosure(pdf, r.institution());
        pdf.spacer(24);
        pdf.paragraph("For " + r.institution().name());
        pdf.spacer(28);
        pdf.paragraph("Authorised signatory");
        return pdf.build();
    }

    // ------------------------------------------------------------------------------------------------ certificate
    /** One account's interest for the tax year: credited (less any recovered on premature closure) and tax deducted. */
    public record CertificateRow(String accountNo, String kind, BigDecimal interest, BigDecimal tax) {
        public CertificateRow {
            interest = interest == null ? BigDecimal.ZERO : interest;
            tax = tax == null ? BigDecimal.ZERO : tax;
        }
    }

    /** Interest and tax deducted by quarter of the tax year (1: April to June …). */
    public record Quarter(int quarter, BigDecimal interest, BigDecimal tax) {}

    /**
     * @param taxYear     "2026-27"
     * @param panMasked   e.g. "XXXXX1234X", or null when no PAN is on file
     * @param declaration e.g. "Form 121 received on 05-Apr-2026 (ref …)", or null
     */
    public record InterestCertificate(Institution institution, Holder depositor, String panMasked, String taxYear, LocalDate from, LocalDate to,
                                      LocalDate generatedOn, List<CertificateRow> accounts, List<Quarter> quarters, String declaration) {
        public InterestCertificate {
            Objects.requireNonNull(institution);
            Objects.requireNonNull(depositor);
            Objects.requireNonNull(taxYear, "tax year");
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            quarters = quarters == null ? List.of() : List.copyOf(quarters);
        }
    }

    /** Refused when the quarters do not add up to the accounts. */
    public static byte[] interestCertificate(InterestCertificate c, Render render) {
        BigDecimal interest = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        List<List<String>> rows = new ArrayList<>();
        for (CertificateRow a : c.accounts()) {
            interest = interest.add(a.interest());
            tax = tax.add(a.tax());
            rows.add(List.of(a.accountNo(), text(a.kind()), Inr.amount(a.interest()), Inr.amount(a.tax()), Inr.amount(a.interest().subtract(a.tax()))));
        }
        if (!c.quarters().isEmpty()) {
            BigDecimal qi = c.quarters().stream().map(Quarter::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal qt = c.quarters().stream().map(Quarter::tax).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (qi.compareTo(interest) != 0 || qt.compareTo(tax) != 0) {
                throw new IllegalArgumentException("the certificate does not add up: the quarters show " + qi.toPlainString() + " / " + qt.toPlainString()
                        + " (interest / tax) and the accounts " + interest.toPlainString() + " / " + tax.toPlainString());
            }
        }
        SimplePdf pdf = start("Interest certificate", "Interest certificate " + c.taxYear() + " - " + c.depositor().customerNo(), c.institution(), render);
        pdf.centredHeading("Certificate of interest on deposits");
        Map<String, String> who = new LinkedHashMap<>();
        who.put("Depositor", c.depositor().name() + (c.depositor().customerNo() == null ? "" : " (customer " + c.depositor().customerNo() + ")"));
        if (c.depositor().address() != null && !c.depositor().address().isBlank()) who.put("Address", c.depositor().address());
        who.put("PAN", c.panMasked() == null ? "Not on file" : c.panMasked());
        who.put("Tax year", c.taxYear() + " (" + date(c.from()) + " to " + date(c.to()) + ")");
        who.put("Generated on (business date)", date(c.generatedOn()));
        if (c.declaration() != null) who.put("Declaration for no deduction", c.declaration());
        pdf.keyValues(who);
        pdf.paragraph("This is to certify that the interest below was paid or credited to the depositor on deposits held with "
                + c.institution().name() + " in the tax year " + c.taxYear() + ", with the tax deducted at source from it.");
        pdf.subheading("By account");
        List<Column> cols = List.of(Column.left("Account", 3.6), Column.left("Deposit", 4.6), Column.right("Interest (Rs.)", 3.2),
                Column.right("Tax deducted (Rs.)", 3.4), Column.right("Net (Rs.)", 3));
        if (rows.isEmpty()) rows.add(List.of("No interest was paid or credited in the year", "", "", "", ""));
        pdf.table(cols, rows, List.of("Total", "", Inr.amount(interest), Inr.amount(tax), Inr.amount(interest.subtract(tax))));
        if (!c.quarters().isEmpty()) {
            pdf.subheading("By quarter");
            List<Column> q = List.of(Column.left("Quarter", 6), Column.right("Interest (Rs.)", 3.4), Column.right("Tax deducted (Rs.)", 3.4));
            List<List<String>> qr = new ArrayList<>();
            for (Quarter x : c.quarters()) qr.add(List.of(QUARTER[Math.max(1, Math.min(4, x.quarter())) - 1], Inr.amount(x.interest()), Inr.amount(x.tax())));
            pdf.table(q, qr);
        }
        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("Total interest", Inr.rs(interest));
        totals.put("Total interest in words", Inr.words(interest));
        totals.put("Total tax deducted", Inr.rs(tax));
        pdf.keyValues(totals);
        pdf.small("Interest is shown on the date it was paid or credited, less any recovered when a deposit was closed early. "
                + "The certificate of tax deducted at source (Form 16A) is issued from the Income Tax Department's TRACES portal; "
                + "tax deducted appears in the depositor's annual tax statement once the quarter's return is filed.");
        pdf.spacer(24);
        pdf.paragraph("For " + c.institution().name());
        pdf.spacer(28);
        pdf.paragraph("Authorised signatory");
        return pdf.build();
    }

    private static final String[] QUARTER = {"April to June", "July to September", "October to December", "January to March"};

    // ------------------------------------------------------------------------------------------------ shared
    private static SimplePdf start(String name, String title, Institution in, Render render) {
        Render r = render == null ? Render.DEFAULT : render;
        StringBuilder foot = new StringBuilder(in.name());
        if (in.cin() != null && !in.cin().isBlank()) foot.append(" | CIN ").append(in.cin());
        if (in.rbiRegistration() != null && !in.rbiRegistration().isBlank()) foot.append(" | RBI registration ").append(in.rbiRegistration());
        SimplePdf pdf = SimplePdf.a4().compress(r.compress()).created(r.created()).title(title).header(in.name(), name).footer(foot.toString());
        if (in.address() != null && !in.address().isBlank()) pdf.small(in.address());
        return pdf;
    }

    private static void disclosure(SimplePdf pdf, Institution in) {
        if (in.disclosure() != null && !in.disclosure().isBlank()) pdf.bold(in.disclosure());
    }

    private static Map<String, String> parties(List<Holder> holders, Account a) {
        Map<String, String> kv = new LinkedHashMap<>();
        Holder first = holders.get(0);
        kv.put(holders.size() == 1 ? "Holder" : "First holder", first.name() + (first.customerNo() == null ? "" : " (customer " + first.customerNo() + ")"));
        if (first.address() != null && !first.address().isBlank()) kv.put("Address", first.address());
        for (int i = 1; i < holders.size(); i++) {
            Holder h = holders.get(i);
            kv.put("Holder " + (i + 1), h.name() + (h.customerNo() == null ? "" : " (customer " + h.customerNo() + ")"));
        }
        kv.put("Account", a.accountNo());
        kv.put("Type", text(a.kind()) + (a.productName() == null ? "" : " - " + a.productName()));
        if (a.branchName() != null) kv.put("Branch", a.branchName());
        if (a.operatingMode() != null && holders.size() > 1) kv.put("Operated", a.operatingMode());
        if (a.openedOn() != null) kv.put("Opened on", date(a.openedOn()));
        if (a.status() != null) kv.put("Status", a.status());
        if (a.nomination() != null) kv.put("Nomination", a.nomination());
        return kv;
    }

    public static String date(LocalDate d) {
        return d == null ? "-" : DATE.format(d);
    }

    private static String amount(BigDecimal v) {
        return v == null || v.signum() == 0 ? "" : Inr.amount(v);
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? NOT_CONFIGURED : s;
    }
}
