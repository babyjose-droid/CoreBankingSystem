package com.corebanking.kernel;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * A small, dependency-free PDF 1.4 writer for statements and letters (US-048, US-113): A4 portrait pages, the
 * standard fonts Helvetica and Helvetica-Bold in WinAnsi encoding (no font is embedded), headings, wrapped
 * paragraphs, key-value blocks and tables whose header row repeats after a page break, a header and footer line on
 * every page and "Page x of y".
 *
 * <p>Limits, by design:
 * <ul>
 *   <li>Text is limited to WinAnsi (Windows-1252). Accented letters outside it lose their accent, the rupee sign
 *       becomes "Rs." and anything else (for example Devanagari) prints as "?". Documents in Indian scripts need an
 *       embedded font, which this writer does not do.</li>
 *   <li>No images, links, bookmarks, tagging (PDF/UA) or digital signatures.</li>
 * </ul>
 * The content streams contain only ASCII (other bytes are written as octal escapes), so an uncompressed document
 * can be searched as text in tests.
 */
public final class SimplePdf {

    public enum Align { LEFT, RIGHT, CENTER }

    /** A table column: its title, its share of the table width and how its cells are aligned. */
    public record Column(String title, double weight, Align align) {
        public Column {
            if (weight <= 0) throw new IllegalArgumentException("column weight must be positive");
            title = title == null ? "" : title;
            align = align == null ? Align.LEFT : align;
        }

        public static Column left(String title, double weight) {
            return new Column(title, weight, Align.LEFT);
        }

        public static Column right(String title, double weight) {
            return new Column(title, weight, Align.RIGHT);
        }
    }

    public static final double PAGE_WIDTH = 595;
    public static final double PAGE_HEIGHT = 842;
    private static final double MARGIN_X = 40;
    private static final double TOP = PAGE_HEIGHT - 58;
    private static final double BOTTOM = 52;
    private static final double CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN_X;
    private static final double BODY = 9.5;
    private static final double TABLE = 8;
    private static final double PAD = 3;

    private final List<StringBuilder> pages = new ArrayList<>();
    private StringBuilder page;
    private double y;
    private String title = "";
    private String headerLeft = "";
    private String headerRight = "";
    private String footer = "";
    private boolean compress = true;
    private Instant created;

    private SimplePdf() {
        newPage();
    }

    public static SimplePdf a4() {
        return new SimplePdf();
    }

    // ------------------------------------------------------------------------------------------------ settings
    /** Document title (shown by PDF viewers in the window title and document properties). */
    public SimplePdf title(String value) {
        this.title = value == null ? "" : value;
        return this;
    }

    /** Text at the top of every page: left (for example the lender's name) and right (the document name). */
    public SimplePdf header(String left, String right) {
        this.headerLeft = left == null ? "" : left;
        this.headerRight = right == null ? "" : right;
        return this;
    }

    /** Text at the bottom left of every page; the page number is printed at the bottom right. */
    public SimplePdf footer(String text) {
        this.footer = text == null ? "" : text;
        return this;
    }

    /** Flate compression of page content (on by default). */
    public SimplePdf compress(boolean value) {
        this.compress = value;
        return this;
    }

    /** Creation time for the document properties; leave unset for byte-identical output from identical input. */
    public SimplePdf created(Instant value) {
        this.created = value;
        return this;
    }

    // ------------------------------------------------------------------------------------------------ content
    public SimplePdf heading(String text) {
        return block(text, true, 14, 18, 6, Align.LEFT);
    }

    public SimplePdf centredHeading(String text) {
        return block(text, true, 14, 18, 6, Align.CENTER);
    }

    public SimplePdf subheading(String text) {
        ensure(11 * 1.3 + 30);                       // keep a subheading with the first lines that follow it
        y -= 4;
        return block(text, true, 11, 14, 4, Align.LEFT);
    }

    public SimplePdf paragraph(String text) {
        return block(text, false, BODY, 12.5, 5, Align.LEFT);
    }

    public SimplePdf bold(String text) {
        return block(text, true, BODY, 12.5, 5, Align.LEFT);
    }

    public SimplePdf small(String text) {
        return block(text, false, 7.5, 9.5, 4, Align.LEFT);
    }

    public SimplePdf spacer(double points) {
        y -= points;
        return this;
    }

    public SimplePdf pageBreak() {
        newPage();
        return this;
    }

    /** Label and value pairs, one per line: the label in bold on the left, the value wrapped on the right. */
    public SimplePdf keyValues(Map<String, String> pairs) {
        double labelWidth = CONTENT_WIDTH * 0.36;
        double valueWidth = CONTENT_WIDTH - labelWidth;
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            List<String> label = wrap(e.getKey(), true, BODY, labelWidth - 2 * PAD);
            List<String> value = wrap(e.getValue() == null ? "-" : e.getValue(), false, BODY, valueWidth - 2 * PAD);
            int lines = Math.max(label.size(), value.size());
            double height = lines * 12 + 2;
            ensure(height);
            for (int i = 0; i < label.size(); i++) text(label.get(i), true, BODY, MARGIN_X + PAD, y - 10 - i * 12);
            for (int i = 0; i < value.size(); i++) text(value.get(i), false, BODY, MARGIN_X + labelWidth + PAD, y - 10 - i * 12);
            y -= height;
        }
        y -= 5;
        return this;
    }

    public SimplePdf table(List<Column> columns, List<List<String>> rows) {
        return table(columns, rows, null);
    }

    /**
     * A table across the page width. Cell text wraps inside its column; a row never splits across pages; the header
     * row is repeated at the top of every page the table continues on. {@code totals}, when given, is printed in
     * bold as the last row.
     */
    public SimplePdf table(List<Column> columns, List<List<String>> rows, List<String> totals) {
        if (columns.isEmpty()) throw new IllegalArgumentException("a table needs at least one column");
        double sum = columns.stream().mapToDouble(Column::weight).sum();
        double[] width = new double[columns.size()];
        double[] left = new double[columns.size()];
        double x = MARGIN_X;
        for (int c = 0; c < width.length; c++) {
            width[c] = CONTENT_WIDTH * columns.get(c).weight() / sum;
            left[c] = x;
            x += width[c];
        }
        List<String> titles = columns.stream().map(Column::title).toList();
        ensure(rowHeight(titles, true, width) + 2 * (TABLE + 2 * PAD + 2));      // header plus at least one row
        row(columns, titles, true, true, left, width);
        for (List<String> r : rows) {
            if (r.size() != columns.size()) {
                throw new IllegalArgumentException("row has " + r.size() + " cells, table has " + columns.size() + " columns");
            }
            if (y - rowHeight(r, false, width) < BOTTOM) {
                newPage();
                row(columns, titles, true, true, left, width);
            }
            row(columns, r, false, false, left, width);
        }
        if (totals != null) {
            if (totals.size() != columns.size()) throw new IllegalArgumentException("totals row does not match the columns");
            if (y - rowHeight(totals, true, width) < BOTTOM) {
                newPage();
                row(columns, titles, true, true, left, width);
            }
            row(columns, totals, true, false, left, width);
        }
        y -= 8;
        return this;
    }

    public int pageCount() {
        return pages.size();
    }

    // ------------------------------------------------------------------------------------------------ layout
    private void newPage() {
        page = new StringBuilder();
        pages.add(page);
        y = TOP;
    }

    private void ensure(double height) {
        if (y - height < BOTTOM && y < TOP) newPage();
    }

    private SimplePdf block(String text, boolean bold, double size, double leading, double after, Align align) {
        for (String line : wrap(text, bold, size, CONTENT_WIDTH)) {
            ensure(leading);
            double x = switch (align) {
                case LEFT -> MARGIN_X;
                case CENTER -> MARGIN_X + (CONTENT_WIDTH - width(line, bold, size)) / 2;
                case RIGHT -> MARGIN_X + CONTENT_WIDTH - width(line, bold, size);
            };
            text(line, bold, size, x, y - size);
            y -= leading;
        }
        y -= after;
        return this;
    }

    private static double rowHeight(List<String> cells, boolean bold, double[] width) {
        int lines = 1;
        for (int c = 0; c < cells.size(); c++) lines = Math.max(lines, wrap(cells.get(c), bold, TABLE, width[c] - 2 * PAD).size());
        return lines * (TABLE + 2) + 2 * PAD;
    }

    private void row(List<Column> columns, List<String> cells, boolean bold, boolean shaded, double[] left, double[] width) {
        double height = rowHeight(cells, bold, width);
        if (shaded) {
            page.append("0.9 g ").append(num(MARGIN_X)).append(' ').append(num(y - height)).append(' ')
                    .append(num(CONTENT_WIDTH)).append(' ').append(num(height)).append(" re f 0 g\n");
        }
        for (int c = 0; c < cells.size(); c++) {
            List<String> lines = wrap(cells.get(c), bold, TABLE, width[c] - 2 * PAD);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                double w = width(line, bold, TABLE);
                double x = switch (columns.get(c).align()) {
                    case LEFT -> left[c] + PAD;
                    case CENTER -> left[c] + (width[c] - w) / 2;
                    case RIGHT -> left[c] + width[c] - PAD - w;
                };
                text(line, bold, TABLE, x, y - PAD - TABLE - i * (TABLE + 2) + 1);
            }
        }
        y -= height;
        page.append("0.7 G 0.4 w ").append(num(MARGIN_X)).append(' ').append(num(y)).append(" m ")
                .append(num(MARGIN_X + CONTENT_WIDTH)).append(' ').append(num(y)).append(" l S 0 G\n");
    }

    private void text(String line, boolean bold, double size, double x, double baseline) {
        text(page, line, bold, size, x, baseline);
    }

    private static void text(StringBuilder out, String line, boolean bold, double size, double x, double baseline) {
        if (line.isEmpty()) return;
        out.append("BT /").append(bold ? "F2" : "F1").append(' ').append(num(size)).append(" Tf 1 0 0 1 ")
                .append(num(x)).append(' ').append(num(baseline)).append(" Tm (").append(escape(line)).append(") Tj ET\n");
    }

    // ------------------------------------------------------------------------------------------------ text
    /**
     * Makes text printable in WinAnsi: line breaks and tabs become spaces (callers split on line breaks first), the
     * rupee sign becomes "Rs.", accented letters outside WinAnsi lose their accent, and anything else becomes "?".
     * The result holds one char per output byte (0x20–0xFF).
     */
    static String winAnsi(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x20B9) {
                sb.append("Rs.");
            } else if (cp == '\t' || cp == '\n' || cp == '\r' || cp == 0xA0) {
                sb.append(' ');
            } else {
                int code = code(cp);
                if (code < 0) {
                    String base = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKD)
                            .replaceAll("\\p{M}+", "");
                    boolean ok = !base.isEmpty();
                    for (int k = 0; k < base.length() && ok; k++) ok = code(base.charAt(k)) >= 0;
                    if (ok) {
                        for (int k = 0; k < base.length(); k++) sb.append((char) code(base.charAt(k)));
                    } else {
                        sb.append('?');
                    }
                } else {
                    sb.append((char) code);
                }
            }
        }
        return sb.toString();
    }

    /** WinAnsi code of a character, or -1 when the encoding (or this writer's width table) has no such character. */
    private static int code(int cp) {
        if (cp >= 0x20 && cp <= 0x7E) return cp;
        if (cp >= 0xA1 && cp <= 0xFF) return HELVETICA[cp - 32] > 0 ? cp : -1;
        if (cp == 0) return -1;
        int i = CP1252_HIGH.indexOf(cp);
        return i >= 0 && HELVETICA[0x80 + i - 32] > 0 ? 0x80 + i : -1;
    }

    /** Width in points of text already in WinAnsi form. */
    static double width(String winAnsi, boolean bold, double size) {
        int[] w = bold ? HELVETICA_BOLD : HELVETICA;
        long units = 0;
        for (int i = 0; i < winAnsi.length(); i++) {
            char c = winAnsi.charAt(i);
            units += c >= 32 && c <= 255 ? w[c - 32] : 0;
        }
        return units * size / 1000.0;
    }

    /** Splits text into lines no wider than {@code max}; a word longer than a line is broken. Returns WinAnsi text. */
    static List<String> wrap(String text, boolean bold, double size, double max) {
        List<String> out = new ArrayList<>();
        String source = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        for (String para : source.split("\n", -1)) {
            String clean = winAnsi(para).trim();
            StringBuilder line = new StringBuilder();
            for (String word : clean.split(" +")) {
                if (word.isEmpty()) continue;
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (width(candidate, bold, size) <= max) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                String rest = word;
                while (width(rest, bold, size) > max && rest.length() > 1) {
                    int n = rest.length() - 1;
                    while (n > 1 && width(rest.substring(0, n), bold, size) > max) n--;
                    out.add(rest.substring(0, n));
                    rest = rest.substring(n);
                }
                line.append(rest);
            }
            out.add(line.toString());
        }
        return out;
    }

    /** PDF literal string body: backslash and parentheses escaped, bytes outside printable ASCII as octal. */
    static String escape(String winAnsi) {
        StringBuilder sb = new StringBuilder(winAnsi.length() + 8);
        for (int i = 0; i < winAnsi.length(); i++) {
            char c = winAnsi.charAt(i);
            if (c == '\\' || c == '(' || c == ')') sb.append('\\').append(c);
            else if (c < 32 || c > 126) sb.append('\\').append(String.format(Locale.ROOT, "%03o", c & 0xFF));
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String num(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        if (s.endsWith(".00")) return s.substring(0, s.length() - 3);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    // ------------------------------------------------------------------------------------------------ file
    /** The finished document. Objects: 1 catalog, 2 page tree, 3–4 fonts, 5 properties, then a page and its content per page. */
    public byte[] build() {
        int n = pages.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096 + n * 2048);
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n");
        out.writeBytes(new byte[] {'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});   // marks the file as binary

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(6 + 2 * i).append(" 0 R ");
        object(out, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        object(out, offsets, 2, "<< /Type /Pages /Kids [ " + kids + "] /Count " + n + " >>");
        object(out, offsets, 3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        object(out, offsets, 4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        StringBuilder info = new StringBuilder("<< /Title (").append(escape(winAnsi(title))).append(") /Producer (CoreBanking SimplePdf)");
        if (created != null) {
            info.append(" /CreationDate (D:")
                    .append(DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT).withZone(ZoneOffset.UTC).format(created)).append("Z)");
        }
        object(out, offsets, 5, info.append(" >>").toString());

        for (int i = 0; i < n; i++) {
            StringBuilder content = new StringBuilder(pages.get(i));
            furniture(content, i + 1, n);
            byte[] raw = content.toString().getBytes(StandardCharsets.US_ASCII);
            byte[] data = compress ? deflate(raw) : raw;
            object(out, offsets, 6 + 2 * i, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + num(PAGE_WIDTH) + " " + num(PAGE_HEIGHT)
                    + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (7 + 2 * i) + " 0 R >>");
            offsets.add(out.size());
            write(out, (7 + 2 * i) + " 0 obj\n<< /Length " + data.length + (compress ? " /Filter /FlateDecode" : "") + " >>\nstream\n");
            out.writeBytes(data);
            write(out, "\nendstream\nendobj\n");
        }

        int xref = out.size();
        int size = offsets.size() + 1;
        StringBuilder table = new StringBuilder("xref\n0 ").append(size).append("\n0000000000 65535 f \n");
        for (int offset : offsets) table.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        table.append("trailer\n<< /Size ").append(size).append(" /Root 1 0 R /Info 5 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(out, table.toString());
        return out.toByteArray();
    }

    /** Header line, footer line and "Page x of y" for one page. */
    private void furniture(StringBuilder content, int number, int of) {
        double top = PAGE_HEIGHT - 36;
        text(content, clip(winAnsi(headerLeft), true, 9, CONTENT_WIDTH * 0.6), true, 9, MARGIN_X, top);
        String right = clip(winAnsi(headerRight), false, 9, CONTENT_WIDTH * 0.38);
        text(content, right, false, 9, MARGIN_X + CONTENT_WIDTH - width(right, false, 9), top);
        content.append("0.5 G 0.5 w ").append(num(MARGIN_X)).append(' ').append(num(top - 6)).append(" m ")
                .append(num(MARGIN_X + CONTENT_WIDTH)).append(' ').append(num(top - 6)).append(" l S 0 G\n");
        String pageOf = "Page " + number + " of " + of;
        text(content, clip(winAnsi(footer), false, 7.5, CONTENT_WIDTH - 70), false, 7.5, MARGIN_X, 30);
        text(content, pageOf, false, 7.5, MARGIN_X + CONTENT_WIDTH - width(pageOf, false, 7.5), 30);
    }

    private static String clip(String winAnsi, boolean bold, double size, double max) {
        String s = winAnsi;
        while (s.length() > 1 && width(s, bold, size) > max) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static void object(ByteArrayOutputStream out, List<Integer> offsets, int number, String body) {
        if (offsets.size() != number - 1) throw new IllegalStateException("objects must be written in order");
        offsets.add(out.size());
        write(out, number + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void write(ByteArrayOutputStream out, String ascii) {
        out.writeBytes(ascii.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 64);
            byte[] buf = new byte[4096];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    // ------------------------------------------------------------------------------------------------ metrics
    /** Windows-1252 characters at codes 0x80–0x9F (NUL where the code is unused). */
    private static final String CP1252_HIGH =
            "€\0‚ƒ„…†‡ˆ‰Š‹Œ\0Ž\0"
                    + "\0‘’“”•–—˜™š›œ\0žŸ";

    /**
     * Advance widths (1/1000 of the font size) for WinAnsi codes 32–255, from Adobe's AFM files for the standard
     * fonts. 0 marks a code this writer does not print (it has no reliable width for it).
     */
    private static final int[] HELVETICA = {
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556,
        556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556,
        556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584, 0,
        0, 0, 0, 0, 0, 1000, 556, 556, 0, 1000, 667, 333, 1000, 0, 611, 0, 0, 222, 222, 333, 333, 350, 556, 1000,
        0, 1000, 500, 333, 944, 0, 500, 667, 278, 333, 556, 556, 0, 556, 260, 556, 333, 737, 370, 556, 584, 333, 737, 333,
        400, 584, 0, 0, 333, 556, 537, 278, 333, 0, 365, 556, 0, 834, 0, 611, 667, 667, 667, 667, 667, 667, 1000, 722,
        667, 667, 667, 667, 278, 278, 278, 278, 722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
        556, 556, 556, 556, 556, 556, 889, 500, 556, 556, 556, 556, 278, 278, 278, 278, 556, 556, 556, 556, 556, 556, 556, 584,
        611, 556, 556, 556, 556, 500, 556, 500};

    private static final int[] HELVETICA_BOLD = {
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556,
        556, 556, 333, 333, 584, 584, 584, 611, 975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556, 333, 556, 611, 556, 611, 556, 333, 611,
        611, 278, 278, 556, 278, 889, 611, 611, 611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584, 0,
        0, 0, 0, 0, 0, 1000, 556, 556, 0, 1000, 667, 333, 1000, 0, 611, 0, 0, 278, 278, 500, 500, 350, 556, 1000,
        0, 1000, 556, 333, 944, 0, 500, 667, 278, 333, 556, 556, 0, 556, 280, 556, 333, 737, 370, 556, 584, 333, 737, 333,
        400, 584, 0, 0, 333, 611, 556, 278, 333, 0, 365, 556, 0, 834, 0, 611, 722, 722, 722, 722, 722, 722, 1000, 722,
        667, 667, 667, 667, 278, 278, 278, 278, 722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
        556, 556, 556, 556, 556, 556, 889, 556, 556, 556, 556, 556, 278, 278, 278, 278, 611, 611, 611, 611, 611, 611, 611, 584,
        611, 611, 611, 611, 611, 556, 611, 556};
}
