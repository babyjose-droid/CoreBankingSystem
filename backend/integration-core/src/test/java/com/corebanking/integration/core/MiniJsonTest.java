package com.corebanking.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniJsonTest {

    @Test
    void writes_objects_in_insertion_order_so_the_signed_text_is_stable() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("z", 1);
        m.put("a", "x");
        m.put("n", null);
        m.put("amount", new BigDecimal("100000.00"));
        m.put("list", List.of(true, false));
        assertEquals("{\"z\":1,\"a\":\"x\",\"n\":null,\"amount\":100000.00,\"list\":[true,false]}", MiniJson.write(m));
    }

    @Test
    void escapes_quotes_control_characters_and_line_separators() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\\u2028\"", MiniJson.write("a\"b\\c\n\u0001\u2028"));
    }

    @Test
    void round_trips_nested_values() {
        String text = "{\"id\":\"e1\",\"data\":{\"amount\":\"5200.00\",\"n\":12.50,\"ok\":true,\"tags\":[\"a\",\"b\"],\"none\":null}}";
        Map<String, Object> m = MiniJson.parseObject(text);
        assertEquals(text, MiniJson.write(m));
        Map<String, Object> data = MiniJson.object(m.get("data"));
        assertEquals("5200.00", MiniJson.string(data, "amount"));
        assertEquals(new BigDecimal("12.50"), MiniJson.decimal(data, "n"));
        assertEquals(new BigDecimal("5200.00"), MiniJson.decimal(data, "amount"));
        assertNull(MiniJson.string(data, "none"));
        assertEquals(List.of("a", "b"), MiniJson.array(data.get("tags")));
    }

    @Test
    void reads_escapes_and_whitespace() {
        Map<String, Object> m = MiniJson.parseObject(" { \"a\" : \"x\\u0041\\n\\/\" , \"b\" : [ ] , \"c\" : { } , \"d\" : -1.5e2 } ");
        assertEquals("xA\n/", m.get("a"));
        assertTrue(MiniJson.array(m.get("b")).isEmpty());
        assertTrue(MiniJson.object(m.get("c")).isEmpty());
        assertEquals(0, new BigDecimal("-150").compareTo((BigDecimal) m.get("d")));
    }

    @Test
    void refuses_malformed_text() {
        for (String bad : List.of("", "{", "{\"a\":}", "{\"a\":1,}", "[1,]", "{\"a\":1} x", "{'a':1}", "{\"a\":01}", "{\"a\":1.}",
                "{\"a\":\"\u0001\"}", "{\"a\":\"\\x\"}", "{\"a\":tru}", "nul", "{\"a\":1 \"b\":2}", "{\"a\":\"unterminated}")) {
            assertThrows(MiniJson.JsonException.class, () -> MiniJson.parse(bad), bad);
        }
    }

    @Test
    void refuses_duplicate_members() {
        // two parsers that disagree on which duplicate wins is a classic way around a signature check
        assertThrows(MiniJson.JsonException.class, () -> MiniJson.parseObject("{\"amount\":\"1\",\"amount\":\"2\"}"));
    }

    @Test
    void bounds_depth_and_length() {
        String deep = "[".repeat(MiniJson.MAX_DEPTH + 2) + "]".repeat(MiniJson.MAX_DEPTH + 2);
        assertThrows(MiniJson.JsonException.class, () -> MiniJson.parse(deep));
        String ok = "[".repeat(MiniJson.MAX_DEPTH) + "]".repeat(MiniJson.MAX_DEPTH);
        MiniJson.parse(ok);
        assertThrows(MiniJson.JsonException.class, () -> MiniJson.parse("\"" + "a".repeat(MiniJson.MAX_LENGTH) + "\""));
    }

    @Test
    void parse_object_and_array_insist_on_their_kind() {
        assertThrows(MiniJson.JsonException.class, () -> MiniJson.parseObject("[1]"));
        assertThrows(MiniJson.JsonException.class, () -> MiniJson.parseArray("{}"));
        assertEquals(2, MiniJson.parseArray("[1,2]").size());
    }

    @Test
    void decimal_reads_numbers_and_numeric_strings_only() {
        Map<String, Object> m = MiniJson.parseObject("{\"a\":\"12.30\",\"b\":7,\"c\":\"x\",\"d\":\" \"}");
        assertEquals(new BigDecimal("12.30"), MiniJson.decimal(m, "a"));
        assertEquals(new BigDecimal("7"), MiniJson.decimal(m, "b"));
        assertThrows(MiniJson.JsonException.class, () -> MiniJson.decimal(m, "c"));
        assertNull(MiniJson.decimal(m, "d"));
        assertNull(MiniJson.decimal(m, "missing"));
    }
}
