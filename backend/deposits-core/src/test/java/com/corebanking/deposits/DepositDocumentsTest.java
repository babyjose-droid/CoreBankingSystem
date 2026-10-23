package com.corebanking.deposits;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.deposits.DepositDocuments.Account;
import com.corebanking.deposits.DepositDocuments.CertificateRow;
import com.corebanking.deposits.DepositDocuments.Holder;
import com.corebanking.deposits.DepositDocuments.Institution;
import com.corebanking.deposits.DepositDocuments.InterestCertificate;
import com.corebanking.deposits.DepositDocuments.Quarter;
import com.corebanking.deposits.DepositDocuments.Receipt;
import com.corebanking.deposits.DepositDocuments.Render;
import com.corebanking.deposits.DepositDocuments.Statement;
import com.corebanking.deposits.DepositDocuments.StatementLine;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Text checks on the depositor documents (P4-7). All names are test data. */
class DepositDocumentsTest {

    static BigDecimal bd(String s) { return new BigDecimal(s); }

    static final Institution BANK = new Institution("CLAUDE-TEST Bank Limited", "1 Test Road, Kochi 682001", "U65999KL2020PLC000000", null,
            "Deposits are insured by the Deposit Insurance and Credit Guarantee Corporation (DICGC) up to Rs. 5,00,000 per depositor.");
    static final Institution NBFC = new Institution("CLAUDE-TEST Finance Limited", null, null, "N-00.00000",
            "This deposit is not insured. The Reserve Bank of India does not guarantee the repayment of deposits accepted by NBFCs.");
    static final Holder ASHA = new Holder("CLAUDE-TEST Asha Depositor", "90010000000013", "12 Sample Street, Kochi, Kerala 682001");
    static final Holder BIJU = new Holder("CLAUDE-TEST Biju Second", "90010000000021", null);
    static final LocalDate D = LocalDate.of(2026, 6, 30);

    static String text(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }

    static String flow(String pdf) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((.*?)\\) Tj").matcher(pdf);
        while (m.find()) sb.append(m.group(1).replace("\\(", "(").replace("\\)", ")")).append(' ');
        return sb.toString().replaceAll("\\s+", " ");
    }

    static void has(String flow, String s) {
        assertTrue(flow.contains(s), "document should say: " + s + "\n---\n" + flow);
    }

    static Statement statement(BigDecimal closing, BigDecimal lastBalance) {
        Account a = new Account("20010000000013", "Savings account", "SB01 Demo savings", "Head Office Kochi", "Either or survivor", D.minusMonths(3),
                "Active", "Registered: 1 nominee");
        return new Statement(BANK, List.of(ASHA, BIJU), a, LocalDate.of(2026, 6, 1), D, D, bd("10000"), closing, List.of(
                new StatementLine(LocalDate.of(2026, 6, 3), null, "Cash deposit", "SLIP 7", null, bd("5000"), bd("15000")),
                new StatementLine(LocalDate.of(2026, 6, 10), null, "Cheque 100002 to CLAUDE-TEST Payee", "Cheque 100002", bd("2500.50"), null, bd("12499.50")),
                new StatementLine(D, D, "Interest for April to June", null, null, bd("85"), lastBalance)), bd("1000"), "2.75% a year on the daily balance");
    }

    @Test
    void statementRunsFromOpeningToClosing() {
        String s = flow(text(DepositDocuments.statement(statement(bd("12584.50"), bd("12584.50")), Render.PLAIN)));
        has(s, "Statement of account");
        has(s, "First holder CLAUDE-TEST Asha Depositor (customer 90010000000013)");
        has(s, "Holder 2 CLAUDE-TEST Biju Second");
        has(s, "Statement period 01-Jun-2026 to 30-Jun-2026");
        has(s, "Opening balance Rs. 10,000.00");
        has(s, "Total debits Rs. 2,500.50 (1 entry)");
        has(s, "Total credits Rs. 5,085.00 (2 entries)");
        has(s, "Closing balance Rs. 12,584.50");
        has(s, "Held under lien Rs. 1,000.00");
        has(s, "Available Rs. 11,584.50");
        has(s, "12,499.50");
        has(s, "DICGC");
        assertFalse(s.contains(DepositDocuments.NOT_CONFIGURED + " -"), "every row has its particulars");
        IllegalArgumentException off = assertThrows(IllegalArgumentException.class,
                () -> DepositDocuments.statement(statement(bd("12584.51"), bd("12584.50")), Render.PLAIN));
        assertTrue(off.getMessage().contains("does not balance"));
        IllegalArgumentException books = assertThrows(IllegalArgumentException.class,
                () -> DepositDocuments.statement(statement(bd("12584.50"), bd("12584.00")), Render.PLAIN));
        assertTrue(books.getMessage().contains("the books show 12584.00"));
        assertThrows(IllegalArgumentException.class, () -> new StatementLine(D, null, "x", null, bd("1"), bd("1"), null));
    }

    @Test
    void receiptCarriesWhatTheDepositorIsOwed() {
        Account a = new Account("30010000000011", "Fixed deposit (cumulative)", "FD01 Demo public deposit", "Head Office Kochi", null, D, "Active",
                "No nominee");
        Receipt r = new Receipt(NBFC, List.of(ASHA), a, "30010000000011/2026-06-30", D, bd("125000"), null, bd("8"), "12 months", D,
                LocalDate.of(2027, 6, 30), bd("135303"), "Compounded yearly and paid at maturity", "Paid out to the bank account recorded at funding",
                "Cash, slip 4", null);
        String s = flow(text(DepositDocuments.receipt(r, Render.PLAIN)));
        has(s, "Term deposit receipt");
        has(s, "Holder CLAUDE-TEST Asha Depositor (customer 90010000000013)");
        has(s, "Amount of deposit Rs. 1,25,000.00");
        has(s, "Amount in words Rupees One Lakh Twenty Five Thousand Only");
        has(s, "Rate of interest 8% per annum");
        has(s, "Date of maturity 30-Jun-2027");
        has(s, "Amount repayable on maturity Rs. 1,35,303.00");
        has(s, "not insured");
        has(s, "For CLAUDE-TEST Finance Limited");
        has(s, "Authorised signatory");
        has(s, "RBI registration N-00.00000");

        Receipt rd = new Receipt(BANK, List.of(ASHA), a, "30010000000045/2026-06-30", D, bd("1000"), bd("1000"), bd("7"), "12 months", D,
                LocalDate.of(2027, 6, 30), bd("12460"), "Compounded quarterly and paid at maturity", "Paid out", null, "Pledged for loan 10010000000017");
        String t = flow(text(DepositDocuments.receipt(rd, Render.PLAIN)));
        has(t, "Recurring deposit receipt");
        has(t, "Monthly instalment Rs. 1,000.00");
        has(t, "(if every instalment is paid on time)");
        has(t, "Lien Pledged for loan 10010000000017");
        assertThrows(NullPointerException.class, () -> new Receipt(BANK, List.of(ASHA), a, "x", D, bd("1"), null, bd("1"), "1", null, D, bd("1"),
                null, null, null, null));
    }

    @Test
    void certificateAddsUpByAccountAndQuarter() {
        List<CertificateRow> rows = List.of(new CertificateRow("20010000000013", "Savings account", bd("340.25"), BigDecimal.ZERO),
                new CertificateRow("30010000000011", "Fixed deposit (cumulative)", bd("62000"), bd("6200")));
        List<Quarter> q = List.of(new Quarter(1, bd("15085.25"), bd("1500")), new Quarter(2, bd("15700"), bd("1570")), new Quarter(3, bd("15755"), bd("1565")),
                new Quarter(4, bd("15800"), bd("1565")));
        InterestCertificate c = new InterestCertificate(BANK, ASHA, "XXXXX1234X", "2026-27", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31),
                LocalDate.of(2027, 4, 2), rows, q, null);
        String s = flow(text(DepositDocuments.interestCertificate(c, Render.PLAIN)));
        has(s, "Certificate of interest on deposits");
        has(s, "PAN XXXXX1234X");
        has(s, "Tax year 2026-27 (01-Apr-2026 to 31-Mar-2027)");
        has(s, "Total interest Rs. 62,340.25");
        has(s, "Total tax deducted Rs. 6,200.00");
        has(s, "January to March");
        has(s, "Form 16A");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DepositDocuments.interestCertificate(
                new InterestCertificate(BANK, ASHA, null, "2026-27", null, null, null, rows, List.of(new Quarter(1, bd("1"), bd("0"))), null), Render.PLAIN));
        assertTrue(e.getMessage().contains("does not add up"));
        String none = flow(text(DepositDocuments.interestCertificate(
                new InterestCertificate(NBFC, ASHA, null, "2026-27", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), D, List.of(), List.of(), null),
                Render.PLAIN)));
        has(none, "PAN Not on file");
        has(none, "No interest was paid or credited in the year");
    }
}
