package com.corebanking.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

/**
 * Structure checks on the generated files. Real-reader checks (qpdf --check, pdftotext) are run outside Java on the
 * files this test writes when the system property {@code pdf.out} names a directory.
 */
class SimplePdfTest {

    private static final List<SimplePdf.Column> COLUMNS = List.of(
            SimplePdf.Column.left("Date", 2), SimplePdf.Column.left("Particulars", 5), SimplePdf.Column.right("Amount", 3));

    private static String latin1(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }

    private static void save(String name, byte[] pdf) throws Exception {
        String dir = System.getProperty("pdf.out");
        if (dir == null) return;
        Files.createDirectories(Path.of(dir));
        Files.write(Path.of(dir, name), pdf);
    }

    private static SimplePdf sample(boolean compress, int rows) {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("Loan account", "CLAUDE-TEST-0001");
        kv.put("Borrower", "CLAUDE-TEST Borrower (sample)");
        List<List<String>> body = new ArrayList<>();
        for (int i = 1; i <= rows; i++) {
            body.add(List.of("0" + (i % 9 + 1) + "-Jul-2026", "CLAUDE-TEST row " + i, Inr.amount(new BigDecimal(i).multiply(new BigDecimal("12345.67")))));
        }
        return SimplePdf.a4().compress(compress).title("CLAUDE-TEST statement").header("CLAUDE-TEST Finance Ltd", "Statement of account")
                .footer("System generated; no signature needed.").created(Instant.parse("2026-07-01T10:15:30Z"))
                .heading("Statement of account").keyValues(kv)
                .paragraph("This paragraph is long enough to wrap on to a second line because it keeps going with more and more words "
                        + "until the right margin is reached and passed, which is the point of the test.")
                .subheading("Transactions").table(COLUMNS, body, List.of("", "Total", Inr.amount(new BigDecimal("999999.5"))));
    }

    @Test
    void file_structure_is_valid() throws Exception {
        byte[] pdf = sample(false, 5).build();
        save("simple-uncompressed.pdf", pdf);
        String s = latin1(pdf);
        assertTrue(s.startsWith("%PDF-1.4\n"));
        assertTrue(s.endsWith("%%EOF\n"));

        Matcher sx = Pattern.compile("startxref\n(\\d+)\n%%EOF\n$").matcher(s);
        assertTrue(sx.find(), "startxref present");
        int xref = Integer.parseInt(sx.group(1));
        assertTrue(s.startsWith("xref\n0 ", xref), "startxref points at the xref table");

        Matcher size = Pattern.compile("xref\n0 (\\d+)\n").matcher(s.substring(xref));
        assertTrue(size.find());
        int n = Integer.parseInt(size.group(1));
        String[] lines = s.substring(xref).split("\n");
        assertEquals("0000000000 65535 f ", lines[2]);
        for (int i = 1; i < n; i++) {
            String entry = lines[2 + i];
            assertEquals(19, entry.length(), "xref entries are 20 bytes with the line end");
            assertTrue(entry.endsWith(" 00000 n "));
            int offset = Integer.parseInt(entry.substring(0, 10));
            assertTrue(s.startsWith(i + " 0 obj\n", offset), "object " + i + " is at its xref offset");
        }
        assertTrue(s.contains("/Size " + n + " /Root 1 0 R /Info 5 0 R"));
        assertEquals(n - 1, count(s, " 0 obj\n"));
        assertEquals(count(s, " 0 obj\n"), count(s, "\nendobj\n"));
    }

    @Test
    void stream_length_matches_the_bytes() {
        String s = latin1(sample(false, 3).build());
        Matcher m = Pattern.compile("<< /Length (\\d+) >>\nstream\n").matcher(s);
        assertTrue(m.find());
        int length = Integer.parseInt(m.group(1));
        assertTrue(s.startsWith("\nendstream\nendobj\n", m.end() + length));
    }

    @Test
    void uncompressed_text_is_searchable() {
        String s = latin1(sample(false, 5).build());
        assertTrue(s.contains("(Statement of account) Tj"));
        assertTrue(s.contains("(CLAUDE-TEST-0001) Tj"));
        assertTrue(s.contains("(CLAUDE-TEST Borrower \\(sample\\)) Tj"), "parentheses are escaped");
        assertTrue(s.contains("(CLAUDE-TEST row 5) Tj"));
        assertTrue(s.contains("(61,728.35) Tj"));
        assertTrue(s.contains("(9,99,999.50) Tj"));
        assertTrue(s.contains("(Page 1 of 1) Tj"));
        assertTrue(s.contains("/Title (CLAUDE-TEST statement)"));
        assertTrue(s.contains("/CreationDate (D:20260701101530Z)"));
        assertFalse(s.contains("FlateDecode"));
    }

    @Test
    void paragraph_wraps_within_the_page_width() {
        String text = "word ".repeat(200).trim();
        List<String> lines = SimplePdf.wrap(text, false, 9.5, 515);
        assertTrue(lines.size() > 1);
        for (String l : lines) assertTrue(SimplePdf.width(l, false, 9.5) <= 515, l);
        assertEquals(text, String.join(" ", lines));
        // a single word wider than the line is broken rather than overflowing
        List<String> broken = SimplePdf.wrap("X".repeat(300), false, 9.5, 100);
        assertTrue(broken.size() > 2);
        for (String l : broken) assertTrue(SimplePdf.width(l, false, 9.5) <= 100);
        assertEquals(300, String.join("", broken).length());
        assertEquals(List.of("a", "", "b"), SimplePdf.wrap("a\n\nb", false, 9.5, 100));
    }

    @Test
    void numbers_are_right_aligned() {
        String s = latin1(SimplePdf.a4().compress(false).table(COLUMNS,
                List.of(List.of("d", "p", "1.00"), List.of("d", "p", "1,23,45,678.90"))).build());
        double right = 595 - 40 - 3;                             // right edge of the last column less the cell padding
        for (String amount : List.of("1.00", "1,23,45,678.90")) {
            Matcher m = Pattern.compile("1 0 0 1 ([0-9.]+) [0-9.]+ Tm \\(" + Pattern.quote(amount) + "\\) Tj").matcher(s);
            assertTrue(m.find(), amount);
            double x = Double.parseDouble(m.group(1));
            assertTrue(Math.abs(x + SimplePdf.width(amount, false, 8) - right) < 0.02, amount + " ends at the column edge");
        }
    }

    @Test
    void long_table_breaks_pages_and_repeats_the_header() throws Exception {
        SimplePdf doc = sample(false, 150);
        byte[] pdf = doc.build();
        save("simple-multipage.pdf", pdf);
        String s = latin1(pdf);
        int pages = doc.pageCount();
        assertTrue(pages >= 3, "150 rows need several pages, got " + pages);
        assertTrue(s.contains("/Count " + pages + " "));
        assertEquals(pages, count(s, "/Type /Page /Parent"));
        assertEquals(pages, count(s, "(Particulars) Tj"), "table header on every page");
        for (int i = 1; i <= pages; i++) assertTrue(s.contains("(Page " + i + " of " + pages + ") Tj"));
        assertEquals(pages, count(s, "(CLAUDE-TEST Finance Ltd) Tj"), "page header on every page");
        assertEquals(1, count(s, "(CLAUDE-TEST row 150) Tj"));
        assertEquals(1, count(s, "(Total) Tj"));
        // nothing is printed below the bottom margin except the footer line
        Matcher m = Pattern.compile("1 0 0 1 [0-9.]+ ([0-9.]+) Tm").matcher(s);
        while (m.find()) {
            double y = Double.parseDouble(m.group(1));
            assertTrue(y >= 52 || y == 30, "text at y=" + y);
        }
    }

    @Test
    void compressed_output_inflates_to_the_same_content() throws Exception {
        byte[] plain = sample(false, 40).build();
        byte[] packed = sample(true, 40).build();
        save("simple-compressed.pdf", packed);
        assertTrue(packed.length < plain.length, "compression makes the file smaller");
        String s = latin1(packed);
        Matcher m = Pattern.compile("<< /Length (\\d+) /Filter /FlateDecode >>\nstream\n").matcher(s);
        StringBuilder text = new StringBuilder();
        int streams = 0;
        while (m.find()) {
            int length = Integer.parseInt(m.group(1));
            byte[] data = new byte[length];
            System.arraycopy(packed, m.end(), data, 0, length);
            Inflater inf = new Inflater();
            inf.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!inf.finished()) {
                int k = inf.inflate(buf);
                if (k == 0 && inf.needsInput()) break;
                out.write(buf, 0, k);
            }
            inf.end();
            text.append(out.toString(StandardCharsets.US_ASCII));
            streams++;
            assertTrue(s.startsWith("\nendstream", m.end() + length));
        }
        assertTrue(streams >= 1);
        assertTrue(text.toString().contains("(CLAUDE-TEST row 40) Tj"));
        // xref offsets still valid with binary streams in the file
        Matcher sx = Pattern.compile("startxref\n(\\d+)\n%%EOF\n$").matcher(s);
        assertTrue(sx.find());
        assertTrue(s.startsWith("xref\n", Integer.parseInt(sx.group(1))));
    }

    @Test
    void same_input_gives_the_same_bytes() {
        assertEquals(latin1(sample(true, 10).build()), latin1(sample(true, 10).build()));
    }

    @Test
    void text_outside_winansi_is_replaced() throws Exception {
        assertEquals("Rs.500", SimplePdf.winAnsi("₹500"));
        assertEquals("Zoë a",SimplePdf.winAnsi("Zoë ā"));                 // e-diaeresis is WinAnsi; a-macron loses its accent
        assertEquals("?? ok", SimplePdf.winAnsi("कख ok"));               // Devanagari cannot be printed
        assertEquals("a b c", SimplePdf.winAnsi("a\tb\nc"));
        assertEquals("\u0093q\u0094 \u0096", SimplePdf.winAnsi("“q” –")); // curly quotes and en dash have WinAnsi codes
        assertEquals("a\\\\b \\(c\\) \\353", SimplePdf.escape("a\\b (c) ë"));
        byte[] pdf = SimplePdf.a4().compress(false).paragraph("Amount ₹1,000 for Zoë (क) \\ end").build();
        save("simple-encoding.pdf", pdf);
        String s = latin1(pdf);
        assertTrue(s.contains("(Amount Rs.1,000 for Zo\\353 \\(?\\) \\\\ end) Tj"));
        for (int i = 15; i < pdf.length; i++) assertTrue(pdf[i] >= 0, "uncompressed output is ASCII after the binary marker line");
    }

    @Test
    void indian_grouping() {
        assertEquals("1,23,45,678.90", Inr.amount(new BigDecimal("12345678.9")));
        assertEquals("999.00", Inr.amount(new BigDecimal("999")));
        assertEquals("1,000.00", Inr.amount(new BigDecimal("1000")));
        assertEquals("10,000.50", Inr.amount(new BigDecimal("10000.5")));
        assertEquals("1,00,000.00", Inr.amount(new BigDecimal("100000.0000")));
        assertEquals("-1,00,000.01", Inr.amount(new BigDecimal("-100000.005")));
        assertEquals("0.00", Inr.amount(BigDecimal.ZERO));
        assertEquals("-", Inr.amount(null));
        assertEquals("Rs. 12,34,567.00", Inr.rs(new BigDecimal("1234567")));
        assertEquals("12,34,567", Inr.count(1234567));
        assertEquals("18.5%", Inr.percent(new BigDecimal("18.5000")));
        assertEquals("20%", Inr.percent(new BigDecimal("2E+1")));
    }

    @Test
    void amount_in_words_uses_lakh_and_crore() {
        assertEquals("Rupees Zero Only", Inr.words(BigDecimal.ZERO));
        assertEquals("Rupees One Lakh Twenty Three Thousand Four Hundred Fifty Six and Paise Fifty Only", Inr.words(new BigDecimal("123456.50")));
        assertEquals("Rupees Eight Hundred Eighty Five Only", Inr.words(new BigDecimal("885.00")));
        assertEquals("Rupees Nineteen and Paise Five Only", Inr.words(new BigDecimal("19.05")));
        assertEquals("Rupees Twelve Crore Thirty Four Lakh Fifty Six Thousand Seven Hundred Eighty Nine Only", Inr.words(new BigDecimal("123456789")));
        assertEquals("Rupees One Crore Only", Inr.words(new BigDecimal("10000000")));
        assertEquals("Rupees One Thousand Crore Ten Only", Inr.words(new BigDecimal("10000000010")));
        assertEquals("Rupees One Hundred Only", Inr.words(new BigDecimal("99.999")));
        assertEquals("Minus Rupees Forty Only", Inr.words(new BigDecimal("-40")));
    }

    @Test
    void bad_tables_are_refused() {
        assertThrows(IllegalArgumentException.class, () -> SimplePdf.a4().table(COLUMNS, List.of(List.of("only one"))));
        assertThrows(IllegalArgumentException.class, () -> SimplePdf.a4().table(List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SimplePdf.Column("x", 0, SimplePdf.Align.LEFT));
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}
