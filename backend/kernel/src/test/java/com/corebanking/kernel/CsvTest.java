package com.corebanking.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CsvTest {

    @Test void parses_quotes_commas_line_breaks_bom_and_crlf() {
        String text = "﻿BranchCode,Day,Reason\r\n"
                + "HO,2026-10-02,\"Gandhi Jayanti, national\"\r\n"
                + "\r\n"
                + ",2026-12-25,\"Christmas \"\"Day\"\"\nobserved\"\r\n";
        Csv.Table t = Csv.parse(text, List.of("branchCode", "day", "reason"), 10);
        assertEquals(List.of("branchcode", "day", "reason"), t.header());
        assertEquals(2, t.rows().size());
        assertEquals("Gandhi Jayanti, national", t.rows().get(0).get("reason"));
        assertEquals(2, t.rows().get(0).line());
        assertNull(t.rows().get(1).get("BRANCHCODE"));
        assertEquals("Christmas \"Day\"\nobserved", t.rows().get(1).get("reason"));
        assertEquals(4, t.rows().get(1).line());
    }

    @Test void rejects_bad_files_with_line_numbers() {
        var missing = assertThrows(Csv.CsvException.class, () -> Csv.parse("day,reason\n2026-01-26,R\n", List.of("branchCode"), 10));
        assertTrue(missing.getMessage().contains("missing column 'branchCode'"));
        var width = assertThrows(Csv.CsvException.class, () -> Csv.parse("a,b\n1,2\n3\n", List.of(), 10));
        assertEquals(3, width.line());
        assertThrows(Csv.CsvException.class, () -> Csv.parse("a,a\n1,2\n", List.of(), 10));
        assertThrows(Csv.CsvException.class, () -> Csv.parse("a\n\"open\n", List.of(), 10));
        assertThrows(Csv.CsvException.class, () -> Csv.parse("a\n1\n2\n3\n", List.of(), 2));
        assertThrows(Csv.CsvException.class, () -> Csv.parse("a,b\n", List.of(), 2));
        assertThrows(Csv.CsvException.class, () -> Csv.parse("  ", List.of(), 2));
    }

    @Test void last_line_without_newline_is_read() {
        Csv.Table t = Csv.parse("a,b\n1,\"x\"", List.of(), 5);
        assertEquals("x", t.rows().get(0).get("b"));
    }

    @Test void cells_neutralise_formulas_but_keep_negative_numbers() {
        assertEquals("'=HYPERLINK(1)", Csv.cell("=HYPERLINK(1)").replace("\"", ""));
        assertTrue(Csv.cell("@SUM(A1)").startsWith("\"'@"));
        assertEquals("-125.50", Csv.cell("-125.50"));
        assertEquals("\"a,b\"", Csv.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", Csv.cell("say \"hi\""));
        assertEquals("", Csv.cell(null));
        assertEquals("HO,\"x,y\"\r\n", Csv.line(List.of("HO", "x,y")));
    }
}
