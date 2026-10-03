package com.corebanking.integration.core.nach;

import com.corebanking.kernel.Csv;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The product's own GENERIC layout for NACH debit files. It is <b>not</b> any bank's or NPCI's layout: it exists
 * so that presentation and response processing work end to end and can be tested, and as the template for a
 * sponsor bank's layout. Documented in docs/integration/nach-generic-format.md.
 * <p>
 * Both encodings carry the same records: one header (H), one detail per debit (D), one trailer (T) with the
 * control totals — the number of details and the sum of their amounts; a response trailer also carries the count
 * and sum of the successful debits. A file whose trailer does not agree with its details is refused as a whole.
 *
 * <pre>
 * FIXED: ASCII, every line {@value #WIDTH} characters, CRLF. Text is left-aligned and space-padded, numbers are
 * right-aligned and zero-padded, amounts are in paise, dates are YYYYMMDD.
 *
 *   H  1       'H'
 *      2-10    layout id "CBSNACH01"
 *      11      'P' presentation, 'R' response
 *      12-29   utility code (18)
 *      30-40   sponsor bank code (11)
 *      41-70   file reference (30) — in a response: the reference of the presentation file it answers
 *      71-78   settlement date
 *   D  1       'D'
 *      2-7     sequence number (6)
 *      8-37    item reference (30)
 *      38-57   UMRN (20)
 *      58-92   account number (35)
 *      93-103  IFSC (11)
 *      104-105 account type (SB, CA, CC, OT)
 *      106-145 account holder name (40)
 *      146-158 amount in paise (13)
 *      159-176 user reference, i.e. the loan number (18)
 *      177     response only: '1' debited, '0' returned
 *      178-180 response only: return reason code (3), blank when debited
 *      181-200 response only: bank reference (20)
 *   T  1       'T'
 *      2-7     number of detail records (6)
 *      8-22    sum of amounts in paise (15)
 *      23-28   response only: number of debited records (6)
 *      29-43   response only: sum of debited amounts in paise (15)
 *
 * CSV: RFC 4180 with a header row and the same three record types in the first column:
 *   record_type,file_ref,utility_code,sponsor_bank_code,settlement_date,direction,seq,item_ref,umrn,account_number,
 *   ifsc,account_type,holder_name,amount,user_ref,status,return_code,bank_ref,count,total_amount,success_count,
 *   success_amount
 * Amounts are rupees with two decimals, dates are YYYY-MM-DD. Columns that do not apply to a record are empty.
 * </pre>
 */
public final class GenericNachFormat implements NachFileFormat {

    public static final String CODE = "GENERIC";
    public static final String LAYOUT_ID = "CBSNACH01";
    public static final int WIDTH = 200;
    public static final int MAX_ITEMS = 50_000;

    public enum Encoding { FIXED, CSV }

    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> COLUMNS = List.of("record_type", "file_ref", "utility_code", "sponsor_bank_code",
            "settlement_date", "direction", "seq", "item_ref", "umrn", "account_number", "ifsc", "account_type",
            "holder_name", "amount", "user_ref", "status", "return_code", "bank_ref", "count", "total_amount",
            "success_count", "success_amount");
    private static final Set<String> ACCOUNT_TYPES = Set.of("SB", "CA", "CC", "OT");

    private final Encoding encoding;

    public GenericNachFormat(Encoding encoding) {
        this.encoding = encoding;
    }

    @Override public String code() { return CODE; }
    @Override public String encoding() { return encoding.name(); }
    @Override public String fileExtension() { return encoding == Encoding.CSV ? "csv" : "txt"; }

    // ------------------------------------------------------------------------------------------------ render
    @Override
    public String render(NachFile.Presentation p) {
        check(p.header());
        if (p.items().isEmpty()) throw new NachFile.FormatException(0, "a presentation file needs at least one debit");
        if (p.items().size() > MAX_ITEMS) throw new NachFile.FormatException(0, "at most " + MAX_ITEMS + " debits per file");
        Set<String> refs = new HashSet<>();
        int expected = 1;
        for (NachFile.Debit d : p.items()) {
            if (d.seq() != expected++) throw new NachFile.FormatException(0, "sequence numbers must run from 1 without gaps");
            if (!refs.add(d.itemRef())) throw new NachFile.FormatException(0, "item reference " + d.itemRef() + " appears twice");
            check(d);
        }
        StringBuilder sb = new StringBuilder();
        if (encoding == Encoding.CSV) {
            sb.append(Csv.line(COLUMNS));
            sb.append(csvHeader(p.header(), "P"));
            for (NachFile.Debit d : p.items()) {
                sb.append(Csv.line(List.of("D", "", "", "", "", "", String.valueOf(d.seq()), d.itemRef(), d.umrn(), d.accountNumber(),
                        d.ifsc(), d.accountType(), clean(d.holderName(), 40), rupees(d.amount()), d.userRef(), "", "", "", "", "", "", "")));
            }
            sb.append(Csv.line(List.of("T", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                    String.valueOf(p.items().size()), rupees(p.total()), "", "")));
        } else {
            sb.append(fixedHeader(p.header(), 'P'));
            for (NachFile.Debit d : p.items()) sb.append(pad(detail(d), WIDTH)).append("\r\n");
            sb.append(pad("T" + num(p.items().size(), 6) + num(paise(p.total()), 15), WIDTH)).append("\r\n");
        }
        return sb.toString();
    }

    @Override
    public String renderResponse(NachFile.Response r) {
        check(r.header());
        StringBuilder sb = new StringBuilder();
        if (encoding == Encoding.CSV) {
            sb.append(Csv.line(COLUMNS));
            sb.append(csvHeader(r.header(), "R"));
            for (NachFile.Outcome o : r.items()) {
                sb.append(Csv.line(List.of("D", "", "", "", "", "", String.valueOf(o.seq()), o.itemRef(), text(o.umrn()), "", "", "", "",
                        rupees(o.amount()), "", o.success() ? "1" : "0", text(o.returnCode()), text(o.bankRef()), "", "", "", "")));
            }
            sb.append(Csv.line(List.of("T", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                    String.valueOf(r.items().size()), rupees(r.total()), String.valueOf(r.successCount()), rupees(r.successTotal()))));
        } else {
            sb.append(fixedHeader(r.header(), 'R'));
            for (NachFile.Outcome o : r.items()) {
                String line = "D" + num(o.seq(), 6) + left(o.itemRef(), 30) + left(text(o.umrn()), 20) + left("", 35) + left("", 11)
                        + left("", 2) + left("", 40) + num(paise(o.amount()), 13) + left("", 18)
                        + (o.success() ? "1" : "0") + left(text(o.returnCode()), 3) + left(text(o.bankRef()), 20);
                sb.append(pad(line, WIDTH)).append("\r\n");
            }
            sb.append(pad("T" + num(r.items().size(), 6) + num(paise(r.total()), 15) + num(r.successCount(), 6)
                    + num(paise(r.successTotal()), 15), WIDTH)).append("\r\n");
        }
        return sb.toString();
    }

    private static String csvHeader(NachFile.Header h, String direction) {
        return Csv.line(List.of("H", h.fileRef(), h.utilityCode(), h.sponsorBankCode(), h.settlementDate().toString(), direction,
                "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ""));
    }

    private static String fixedHeader(NachFile.Header h, char direction) {
        return pad("H" + LAYOUT_ID + direction + left(h.utilityCode(), 18) + left(h.sponsorBankCode(), 11) + left(h.fileRef(), 30)
                + COMPACT.format(h.settlementDate()), WIDTH) + "\r\n";
    }

    private static String detail(NachFile.Debit d) {
        return "D" + num(d.seq(), 6) + left(d.itemRef(), 30) + left(d.umrn(), 20) + left(d.accountNumber(), 35) + left(d.ifsc(), 11)
                + left(d.accountType(), 2) + left(clean(d.holderName(), 40), 40) + num(paise(d.amount()), 13) + left(d.userRef(), 18);
    }

    // ------------------------------------------------------------------------------------------------ parse
    @Override
    public NachFile.Presentation parsePresentation(String text) {
        Parsed p = encoding == Encoding.CSV ? parseCsv(text, "P") : parseFixed(text, 'P');
        List<NachFile.Debit> items = new ArrayList<>();
        for (Row r : p.rows) {
            NachFile.Debit d = new NachFile.Debit(r.seq, r.itemRef, r.umrn, r.account, r.ifsc, r.accountType, r.holder, r.amount, r.userRef);
            try {
                check(d);
            } catch (NachFile.FormatException e) {
                throw new NachFile.FormatException(r.line, e.getMessage());
            }
            items.add(d);
        }
        controls(p, items.size(), items.stream().map(NachFile.Debit::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        return new NachFile.Presentation(p.header, items);
    }

    @Override
    public NachFile.Response parseResponse(String text) {
        Parsed p = encoding == Encoding.CSV ? parseCsv(text, "R") : parseFixed(text, 'R');
        List<NachFile.Outcome> items = new ArrayList<>();
        for (Row r : p.rows) {
            if (!"0".equals(r.status) && !"1".equals(r.status)) throw new NachFile.FormatException(r.line, "status must be 1 (debited) or 0 (returned)");
            boolean ok = "1".equals(r.status);
            String code = r.returnCode == null || r.returnCode.isBlank() ? null : r.returnCode.trim();
            if (!ok && code == null) throw new NachFile.FormatException(r.line, "a returned debit needs a return reason code");
            if (ok && code != null) throw new NachFile.FormatException(r.line, "a debited record must not carry a return reason code");
            if (code != null && !code.matches("[A-Za-z0-9]{1,3}")) throw new NachFile.FormatException(r.line, "invalid return reason code");
            items.add(new NachFile.Outcome(r.seq, r.itemRef, blankToNull(r.umrn), r.amount, ok, code, blankToNull(r.bankRef)));
        }
        NachFile.Response resp = new NachFile.Response(p.header, items);
        controls(p, items.size(), resp.total());
        if (p.successCount == null || p.successTotal == null) throw new NachFile.FormatException(p.trailerLine, "the trailer has no success totals");
        if (p.successCount != resp.successCount() || p.successTotal.compareTo(resp.successTotal()) != 0) {
            throw new NachFile.FormatException(p.trailerLine, "the trailer's success totals do not match the detail records");
        }
        return resp;
    }

    private static void controls(Parsed p, int count, BigDecimal total) {
        if (p.count != count) {
            throw new NachFile.FormatException(p.trailerLine, "the trailer says " + p.count + " records, the file has " + count);
        }
        if (p.total.compareTo(total) != 0) {
            throw new NachFile.FormatException(p.trailerLine, "the trailer's total amount does not match the detail records");
        }
    }

    private static final class Row {
        int line;
        int seq;
        String itemRef;
        String umrn;
        String account;
        String ifsc;
        String accountType;
        String holder;
        BigDecimal amount;
        String userRef;
        String status;
        String returnCode;
        String bankRef;
    }

    private static final class Parsed {
        NachFile.Header header;
        final List<Row> rows = new ArrayList<>();
        int count;
        BigDecimal total;
        Long successCount;
        BigDecimal successTotal;
        int trailerLine;
    }

    private static Parsed parseFixed(String text, char direction) {
        if (text == null || text.isBlank()) throw new NachFile.FormatException(0, "the file is empty");
        String[] lines = text.split("\r\n|\n|\r", -1);
        Parsed p = new Parsed();
        boolean trailer = false;
        Set<Integer> seqs = new HashSet<>();
        Set<String> refs = new HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            int n = i + 1;
            if (l.isEmpty()) {
                if (i == lines.length - 1) continue;                      // the final line break
                throw new NachFile.FormatException(n, "empty line");
            }
            if (trailer) throw new NachFile.FormatException(n, "there are records after the trailer");
            if (l.length() != WIDTH) throw new NachFile.FormatException(n, "expected " + WIDTH + " characters, found " + l.length());
            for (int c = 0; c < l.length(); c++) {
                if (l.charAt(c) < 0x20 || l.charAt(c) > 0x7E) throw new NachFile.FormatException(n, "character " + (c + 1) + " is not printable ASCII");
            }
            char type = l.charAt(0);
            if (i == 0) {
                if (type != 'H') throw new NachFile.FormatException(n, "the first record must be the header");
                if (!l.startsWith(LAYOUT_ID, 1)) throw new NachFile.FormatException(n, "this is not a " + LAYOUT_ID + " file");
                if (l.charAt(10) != direction) {
                    throw new NachFile.FormatException(n, direction == 'R' ? "this is not a response file" : "this is not a presentation file");
                }
                p.header = new NachFile.Header(l.substring(40, 70).trim(), l.substring(11, 29).trim(), l.substring(29, 40).trim(),
                        date(l.substring(70, 78), COMPACT, n));
                check(p.header, n);
            } else if (type == 'D') {
                if (p.rows.size() >= MAX_ITEMS) throw new NachFile.FormatException(n, "at most " + MAX_ITEMS + " debits per file");
                Row r = new Row();
                r.line = n;
                r.seq = (int) number(l.substring(1, 7), n, "sequence number");
                r.itemRef = l.substring(7, 37).trim();
                r.umrn = l.substring(37, 57).trim();
                r.account = l.substring(57, 92).trim();
                r.ifsc = l.substring(92, 103).trim();
                r.accountType = l.substring(103, 105).trim();
                r.holder = l.substring(105, 145).trim();
                r.amount = BigDecimal.valueOf(number(l.substring(145, 158), n, "amount"), 2);
                r.userRef = l.substring(158, 176).trim();
                r.status = l.substring(176, 177).trim();
                r.returnCode = l.substring(177, 180).trim();
                r.bankRef = l.substring(180, 200).trim();
                unique(r, seqs, refs);
                p.rows.add(r);
            } else if (type == 'T') {
                trailer = true;
                p.trailerLine = n;
                p.count = (int) number(l.substring(1, 7), n, "record count");
                p.total = BigDecimal.valueOf(number(l.substring(7, 22), n, "total amount"), 2);
                if (!l.substring(22, 43).isBlank()) {
                    p.successCount = number(l.substring(22, 28), n, "success count");
                    p.successTotal = BigDecimal.valueOf(number(l.substring(28, 43), n, "success amount"), 2);
                }
            } else {
                throw new NachFile.FormatException(n, "unknown record type '" + type + "'");
            }
        }
        if (p.header == null) throw new NachFile.FormatException(0, "the file has no header");
        if (!trailer) throw new NachFile.FormatException(0, "the file has no trailer (it may be incomplete)");
        return p;
    }

    private static Parsed parseCsv(String text, String direction) {
        Csv.Table t;
        try {
            t = Csv.parse(text, COLUMNS, MAX_ITEMS + 2);
        } catch (Csv.CsvException e) {
            throw new NachFile.FormatException(e.line(), e.line() > 0 ? e.getMessage().replaceFirst("^line \\d+: ", "") : e.getMessage());
        }
        Parsed p = new Parsed();
        boolean trailer = false;
        Set<Integer> seqs = new HashSet<>();
        Set<String> refs = new HashSet<>();
        for (int i = 0; i < t.rows().size(); i++) {
            Csv.Row c = t.rows().get(i);
            int n = c.line();
            String type = c.get("record_type");
            if (trailer) throw new NachFile.FormatException(n, "there are records after the trailer");
            if (i == 0) {
                if (!"H".equals(type)) throw new NachFile.FormatException(n, "the first record must be the header");
                if (!direction.equals(c.get("direction"))) {
                    throw new NachFile.FormatException(n, "R".equals(direction) ? "this is not a response file" : "this is not a presentation file");
                }
                String d = c.get("settlement_date");
                p.header = new NachFile.Header(text(c.get("file_ref")), text(c.get("utility_code")), text(c.get("sponsor_bank_code")),
                        date(d == null ? "" : d, DateTimeFormatter.ISO_LOCAL_DATE, n));
                check(p.header, n);
            } else if ("D".equals(type)) {
                Row r = new Row();
                r.line = n;
                r.seq = (int) number(text(c.get("seq")), n, "sequence number");
                r.itemRef = text(c.get("item_ref"));
                r.umrn = text(c.get("umrn"));
                r.account = text(c.get("account_number"));
                r.ifsc = text(c.get("ifsc"));
                r.accountType = text(c.get("account_type"));
                r.holder = text(c.get("holder_name"));
                r.amount = rupees(c.get("amount"), n, "amount");
                r.userRef = text(c.get("user_ref"));
                r.status = text(c.get("status"));
                r.returnCode = text(c.get("return_code"));
                r.bankRef = text(c.get("bank_ref"));
                unique(r, seqs, refs);
                p.rows.add(r);
            } else if ("T".equals(type)) {
                trailer = true;
                p.trailerLine = n;
                p.count = (int) number(text(c.get("count")), n, "record count");
                p.total = rupees(c.get("total_amount"), n, "total amount");
                if (c.get("success_count") != null) {
                    p.successCount = number(c.get("success_count"), n, "success count");
                    p.successTotal = rupees(c.get("success_amount"), n, "success amount");
                }
            } else {
                throw new NachFile.FormatException(n, "unknown record type '" + type + "'");
            }
        }
        if (!trailer) throw new NachFile.FormatException(0, "the file has no trailer (it may be incomplete)");
        return p;
    }

    private static void unique(Row r, Set<Integer> seqs, Set<String> refs) {
        if (r.seq < 1) throw new NachFile.FormatException(r.line, "sequence numbers start at 1");
        if (!seqs.add(r.seq)) throw new NachFile.FormatException(r.line, "sequence number " + r.seq + " appears twice");
        if (r.itemRef.isEmpty()) throw new NachFile.FormatException(r.line, "item reference is missing");
        if (!refs.add(r.itemRef)) throw new NachFile.FormatException(r.line, "item reference " + r.itemRef + " appears twice");
        if (r.amount.signum() <= 0) throw new NachFile.FormatException(r.line, "amount must be positive");
    }

    // ------------------------------------------------------------------------------------------------ fields
    private static void check(NachFile.Header h) {
        check(h, 0);
    }

    private static void check(NachFile.Header h, int line) {
        if (h.fileRef() == null || !h.fileRef().matches("[A-Za-z0-9-]{1,30}")) throw new NachFile.FormatException(line, "invalid file reference");
        if (h.utilityCode() == null || !h.utilityCode().matches("[A-Za-z0-9]{1,18}")) throw new NachFile.FormatException(line, "invalid utility code");
        if (h.sponsorBankCode() == null || !h.sponsorBankCode().matches("[A-Za-z0-9]{1,11}")) {
            throw new NachFile.FormatException(line, "invalid sponsor bank code");
        }
        if (h.settlementDate() == null) throw new NachFile.FormatException(line, "settlement date is missing");
    }

    private static void check(NachFile.Debit d) {
        if (d.itemRef() == null || !d.itemRef().matches("[A-Za-z0-9-]{1,30}")) throw new NachFile.FormatException(0, "invalid item reference");
        if (d.umrn() == null || !d.umrn().matches("[A-Za-z0-9]{20}")) throw new NachFile.FormatException(0, "item " + d.itemRef() + ": the UMRN must be 20 letters or digits");
        if (d.accountNumber() == null || !d.accountNumber().matches("[A-Za-z0-9]{4,35}")) {
            throw new NachFile.FormatException(0, "item " + d.itemRef() + ": invalid account number");
        }
        if (d.ifsc() == null || !d.ifsc().matches("[A-Z]{4}0[A-Z0-9]{6}")) throw new NachFile.FormatException(0, "item " + d.itemRef() + ": invalid IFSC");
        if (d.accountType() == null || !ACCOUNT_TYPES.contains(d.accountType())) {
            throw new NachFile.FormatException(0, "item " + d.itemRef() + ": account type must be SB, CA, CC or OT");
        }
        if (d.amount() == null || d.amount().signum() <= 0 || d.amount().scale() > 2 && d.amount().stripTrailingZeros().scale() > 2) {
            throw new NachFile.FormatException(0, "item " + d.itemRef() + ": the amount must be positive with at most two decimals");
        }
        if (d.amount().compareTo(new BigDecimal("99999999999.99")) > 0) throw new NachFile.FormatException(0, "item " + d.itemRef() + ": amount too large");
        if (d.userRef() == null || !d.userRef().matches("[A-Za-z0-9-]{1,18}")) throw new NachFile.FormatException(0, "item " + d.itemRef() + ": invalid user reference");
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Printable ASCII only, upper case, no commas or quotes: a name must not be able to break a record. */
    static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = Character.toUpperCase(s.charAt(i));
            sb.append((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ' || c == '.' || c == '&' || c == '\'' ? c : ' ');
        }
        return sb.toString().trim().replaceAll(" {2,}", " ");
    }

    private static String left(String s, int width) {
        String v = text(s);
        if (v.length() > width) throw new NachFile.FormatException(0, "a value is longer than its " + width + "-character field");
        return v + " ".repeat(width - v.length());
    }

    private static String pad(String s, int width) {
        return left(s, width);
    }

    private static String num(long value, int width) {
        String v = Long.toString(value);
        if (value < 0 || v.length() > width) throw new NachFile.FormatException(0, "a number does not fit its " + width + "-digit field");
        return "0".repeat(width - v.length()) + v;
    }

    private static long paise(BigDecimal amount) {
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new NachFile.FormatException(0, "an amount has more than two decimals");
        }
    }

    private static String rupees(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException e) {
            throw new NachFile.FormatException(0, "an amount has more than two decimals");
        }
    }

    private static BigDecimal rupees(String s, int line, String what) {
        if (s == null || !s.matches("[0-9]{1,13}(\\.[0-9]{1,2})?")) throw new NachFile.FormatException(line, what + " is not a valid amount");
        return new BigDecimal(s).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static long number(String s, int line, String what) {
        if (s == null || !s.matches("[0-9]{1,15}")) throw new NachFile.FormatException(line, what + " is not a number");
        return Long.parseLong(s);
    }

    private static LocalDate date(String s, DateTimeFormatter f, int line) {
        try {
            return LocalDate.parse(s.trim(), f);
        } catch (DateTimeParseException e) {
            throw new NachFile.FormatException(line, "settlement date is not valid");
        }
    }
}
