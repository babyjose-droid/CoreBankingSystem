package com.corebanking.kernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small RFC 4180 CSV reader and writer for master and voucher uploads (US-011, US-012, US-103).
 * <ul>
 *   <li>The first row is the header; names are matched case-insensitively and must include every required column.</li>
 *   <li>Quoted fields may contain commas, quotes ({@code ""}) and line breaks; a UTF-8 BOM is ignored.</li>
 *   <li>Blank lines are skipped. Each row keeps its 1-based line number so errors can point at it.</li>
 *   <li>{@link #cell} neutralises spreadsheet formulas (CSV injection, ASVS 5.3.10) when writing.</li>
 * </ul>
 */
public final class Csv {

    /** One data row: its line number in the file and its values keyed by lower-case header name. */
    public record Row(int line, Map<String, String> values) {
        public String get(String column) {
            String v = values.get(column.toLowerCase());
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    public record Table(List<String> header, List<Row> rows) {}

    /** A problem in the file, with the line it is on (0 when it concerns the whole file). */
    public static final class CsvException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final int line;

        public CsvException(int line, String message) {
            super(line > 0 ? "line " + line + ": " + message : message);
            this.line = line;
        }

        public int line() {
            return line;
        }
    }

    private Csv() {}

    public static Table parse(String text, List<String> required, int maxRows) {
        if (text == null || text.isBlank()) throw new CsvException(0, "the file is empty");
        if (text.charAt(0) == '﻿') text = text.substring(1);
        List<List<String>> records = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldWasQuoted = false;
        int line = 1;
        int recordLine = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') line++;
                    field.append(c);
                }
            } else if (c == '"') {
                if (field.length() > 0 && !field.toString().isBlank()) {
                    throw new CsvException(line, "a quote may only start a field");
                }
                field.setLength(0);
                quoted = true;
                fieldWasQuoted = true;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
                fieldWasQuoted = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                current.add(field.toString());
                field.setLength(0);
                if (!(current.size() == 1 && current.get(0).isBlank() && !fieldWasQuoted)) {
                    records.add(current);
                    lines.add(recordLine);
                }
                fieldWasQuoted = false;
                current = new ArrayList<>();
                line++;
                recordLine = line;
            } else {
                field.append(c);
            }
        }
        if (quoted) throw new CsvException(recordLine, "a quoted field is not closed");
        if (field.length() > 0 || !current.isEmpty() || fieldWasQuoted) {
            current.add(field.toString());
            if (!(current.size() == 1 && current.get(0).isBlank() && !fieldWasQuoted)) {
                records.add(current);
                lines.add(recordLine);
            }
        }
        if (records.isEmpty()) throw new CsvException(0, "the file is empty");

        List<String> header = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String h : records.get(0)) {
            String name = h.trim().toLowerCase();
            if (name.isEmpty()) throw new CsvException(lines.get(0), "a header column is blank");
            if (!seen.add(name)) throw new CsvException(lines.get(0), "column '" + h.trim() + "' appears twice");
            header.add(name);
        }
        for (String r : required) {
            if (!seen.contains(r.toLowerCase())) throw new CsvException(lines.get(0), "missing column '" + r + "'");
        }
        if (records.size() - 1 > maxRows) throw new CsvException(0, "at most " + maxRows + " rows per file");
        if (records.size() == 1) throw new CsvException(0, "the file has a header but no rows");

        List<Row> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            if (rec.size() != header.size()) {
                throw new CsvException(lines.get(r), "expected " + header.size() + " fields, found " + rec.size());
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) values.put(header.get(c), rec.get(c));
            rows.add(new Row(lines.get(r), Collections.unmodifiableMap(values)));
        }
        return new Table(List.copyOf(header), List.copyOf(rows));
    }

    /**
     * Escapes one value for a CSV file that will be opened in a spreadsheet: quotes when needed and prefixes
     * a leading =, +, -, @, tab or carriage return with an apostrophe so it is not run as a formula.
     * Plain negative numbers (e.g. -125.50) are left alone.
     */
    public static String cell(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?[0-9]+(\\.[0-9]+)?")) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r") || !v.equals(value)) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    public static String line(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(values.get(i)));
        }
        return sb.append("\r\n").toString();
    }
}
