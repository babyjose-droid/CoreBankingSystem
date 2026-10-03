package com.corebanking.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.kernel.CustomFields.DataType;
import com.corebanking.kernel.CustomFields.Definition;
import com.corebanking.kernel.CustomFields.InvalidException;
import com.corebanking.kernel.CustomFields.Problem;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CustomFieldsTest {

    private static Definition def(String key, DataType type) {
        return new Definition("CUSTOMER", key, "CLAUDE-TEST " + key, type, null, null, false, null, null, null, false, true);
    }

    private static final List<Definition> DEFS = List.of(
            new Definition("CUSTOMER", "employeeCode", "Employee code", DataType.TEXT, null, null, false, "[A-Z]{2}[0-9]{4}", null, null, false, true),
            new Definition("CUSTOMER", "nickName", "Nick name", DataType.TEXT, null, null, false, null, BigDecimal.valueOf(2), BigDecimal.TEN, false, true),
            new Definition("CUSTOMER", "familySize", "Family size", DataType.NUMBER, null, null, false, null, BigDecimal.ONE, BigDecimal.valueOf(20), false, true),
            def("anniversary", DataType.DATE),
            def("staffRelative", DataType.BOOLEAN),
            new Definition("CUSTOMER", "sourcingChannel", "Sourcing channel", DataType.ENUM, "sourcing-channel", Set.of("DSA", "BRANCH"), false, null, null, null, false, true),
            new Definition("CUSTOMER", "passportNo", "Passport number", DataType.TEXT, null, null, false, "[A-Z][0-9]{7}", null, null, true, true),
            new Definition("CUSTOMER", "oldField", "Old field", DataType.TEXT, null, null, true, null, null, null, false, false),
            new Definition("LOAN_ACCOUNT", "purpose", "Purpose of loan", DataType.TEXT, null, null, true, null, null, null, false, true));

    private static List<Problem> problems(String entity, Map<String, ?> values) {
        return assertThrows(InvalidException.class, () -> CustomFields.validate(entity, DEFS, values)).problems();
    }

    private static Map<String, Object> one(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    @Test
    void valid_values_come_back_in_canonical_form() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("employeeCode", "AB1234");
        in.put("nickName", "Appu");
        in.put("familySize", 4);
        in.put("anniversary", "2020-02-29");
        in.put("staffRelative", Boolean.FALSE);
        in.put("sourcingChannel", "DSA");
        Map<String, Object> out = CustomFields.validate("CUSTOMER", DEFS, in);
        assertEquals(new BigDecimal("4"), out.get("familySize"));
        assertEquals("2020-02-29", out.get("anniversary"));
        assertEquals(6, out.size());
        assertEquals(new BigDecimal("2.5"), CustomFields.validate("CUSTOMER", DEFS, one("familySize", 2.5d)).get("familySize"));
        assertTrue(CustomFields.validate("CUSTOMER", DEFS, null).isEmpty());
    }

    @Test
    void unknown_key_is_an_error() {
        List<Problem> p = problems("CUSTOMER", one("shoeSize", 9));
        assertEquals(1, p.size());
        assertEquals("shoeSize", p.get(0).field());
        assertTrue(p.get(0).message().contains("not defined for CUSTOMER"));
        // a field of another entity is unknown here
        assertTrue(problems("CUSTOMER", one("purpose", "x")).get(0).message().contains("not defined"));
    }

    @Test
    void wrong_types_are_errors() {
        assertTrue(problems("CUSTOMER", one("familySize", "four")).get(0).message().contains("must be a number"));
        assertTrue(problems("CUSTOMER", one("nickName", 7)).get(0).message().contains("must be text"));
        assertTrue(problems("CUSTOMER", one("staffRelative", "yes")).get(0).message().contains("true or false"));
        assertTrue(problems("CUSTOMER", one("anniversary", 20200229)).get(0).message().contains("YYYY-MM-DD"));
        assertTrue(problems("CUSTOMER", one("sourcingChannel", 1)).get(0).message().contains("active values of sourcing-channel"));
        assertTrue(problems("CUSTOMER", one("familySize", Double.NaN)).get(0).message().contains("must be a number"));
        assertTrue(problems("CUSTOMER", one("nickName", List.of("a"))).get(0).message().contains("must be text"));
    }

    @Test
    void missing_required_field_is_an_error_and_inactive_required_is_not() {
        List<Problem> p = problems("LOAN_ACCOUNT", Map.of());
        assertEquals("purpose", p.get(0).field());
        assertTrue(p.get(0).message().contains("is required"));
        assertTrue(problems("LOAN_ACCOUNT", null).get(0).message().contains("is required"));
        assertEquals("Two-wheeler", CustomFields.validate("LOAN_ACCOUNT", DEFS, one("purpose", "Two-wheeler")).get("purpose"));
        // oldField is required but inactive: a customer without it is fine, a value for it is refused
        assertTrue(CustomFields.validate("CUSTOMER", DEFS, Map.of()).isEmpty());
        assertTrue(problems("CUSTOMER", one("oldField", "x")).get(0).message().contains("no longer in use"));
    }

    @Test
    void pattern_length_range_date_and_enum_rules() {
        assertTrue(problems("CUSTOMER", one("employeeCode", "ab1234")).get(0).message().contains("expected format"));
        assertTrue(problems("CUSTOMER", one("employeeCode", "AB1234-and-more")).get(0).message().contains("expected format"));
        assertTrue(problems("CUSTOMER", one("nickName", "A")).get(0).message().contains("at least 2"));
        assertTrue(problems("CUSTOMER", one("nickName", "Abcdefghijk")).get(0).message().contains("at most 10"));
        assertTrue(problems("CUSTOMER", one("familySize", 0)).get(0).message().contains("at least 1"));
        assertTrue(problems("CUSTOMER", one("familySize", 21)).get(0).message().contains("at most 20"));
        assertTrue(problems("CUSTOMER", one("anniversary", "2026-02-30")).get(0).message().contains("YYYY-MM-DD"));
        assertTrue(problems("CUSTOMER", one("anniversary", "30-02-2026")).get(0).message().contains("YYYY-MM-DD"));
        assertTrue(problems("CUSTOMER", one("sourcingChannel", "ONLINE")).get(0).message().contains("active values"));
        assertTrue(problems("CUSTOMER", one("nickName", null)).get(0).message().contains("instead of sending null"));
    }

    @Test
    void every_problem_is_reported_at_once() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("familySize", 99);
        in.put("shoeSize", 9);
        in.put("employeeCode", "x");
        List<Problem> p = problems("CUSTOMER", in);
        assertEquals(3, p.size());
        assertEquals(List.of("familySize", "shoeSize", "employeeCode"), p.stream().map(Problem::field).toList());
    }

    @Test
    void personal_data_is_checked_in_clear_then_sealed_and_returned_masked() {
        PiiCipher cipher = new PiiCipher(1, new byte[32], new byte[32]);
        assertTrue(problems("CUSTOMER", one("passportNo", "bad")).get(0).message().contains("expected format"));
        Map<String, Object> validated = CustomFields.validate("CUSTOMER", DEFS, Map.of("passportNo", "K1234567", "nickName", "Appu"));
        Map<String, Object> sealed = CustomFields.seal("CUSTOMER", DEFS, validated, cipher);
        assertEquals("Appu", sealed.get("nickName"));
        Map<?, ?> stored = (Map<?, ?>) sealed.get("passportNo");
        assertEquals(Set.of("enc", "mask"), stored.keySet());
        assertEquals("****4567", stored.get("mask"));
        String enc = (String) stored.get("enc");
        assertFalse(enc.contains("K1234567"));
        assertTrue(enc.matches("[A-Za-z0-9+/]{43,}={0,2}"), "the database accepts this shape");
        assertFalse(sealed.toString().contains("K1234567"));

        Map<String, Object> view = CustomFields.view(sealed);
        assertEquals("****4567", view.get("passportNo"));
        assertEquals("Appu", view.get("nickName"));
        assertEquals("K1234567", CustomFields.open("CUSTOMER", "passportNo", stored, cipher));
        // the ciphertext is bound to its field and entity
        assertThrows(IllegalArgumentException.class, () -> CustomFields.open("CUSTOMER", "otherField", stored, cipher));
        assertThrows(IllegalArgumentException.class, () -> CustomFields.open("LOAN_ACCOUNT", "passportNo", stored, cipher));
        assertThrows(IllegalArgumentException.class, () -> CustomFields.open("CUSTOMER", "nickName", "Appu", cipher));
    }

    @Test
    void masks_never_show_short_values() {
        assertEquals("****", CustomFields.mask("1234567"));
        assertEquals("****5678", CustomFields.mask("12345678"));
        assertEquals("****", CustomFields.mask(""));
        assertEquals(null, CustomFields.mask(null));
        // a sealed value without a mask is still not shown
        assertEquals("****", CustomFields.view(Map.of("x", Map.of("enc", "abc"))).get("x"));
        assertTrue(CustomFields.view(null).isEmpty());
    }

    @Test
    void definitions_are_checked() {
        assertTrue(CustomFields.checkDefinition(DEFS.get(0), null).isEmpty());
        assertEquals("entity", CustomFields.checkDefinition(
                new Definition("BRANCH", "x1", "X", DataType.TEXT, null, null, false, null, null, null, false, true), null).get(0).field());
        assertEquals("key", CustomFields.checkDefinition(
                new Definition("CUSTOMER", "Bad Key", "X", DataType.TEXT, null, null, false, null, null, null, false, true), null).get(0).field());
        assertEquals("label", CustomFields.checkDefinition(
                new Definition("CUSTOMER", "x1", " ", DataType.TEXT, null, null, false, null, null, null, false, true), null).get(0).field());
        assertEquals("dataType", CustomFields.checkDefinition(
                new Definition("CUSTOMER", "x1", "X", null, null, null, false, null, null, null, false, true), null).get(0).field());
        assertEquals("enumType", CustomFields.checkDefinition(
                new Definition("CUSTOMER", "x1", "X", DataType.ENUM, null, null, false, null, null, null, false, true), null).get(0).field());
        assertTrue(CustomFields.checkDefinition(
                new Definition("CUSTOMER", "x1", "X", DataType.ENUM, "no-values", Set.of(), false, null, null, null, false, true), null)
                .get(0).message().contains("no active values"));
        assertEquals("enumType", CustomFields.checkDefinition(
                new Definition("CUSTOMER", "x1", "X", DataType.TEXT, "sourcing-channel", Set.of("A"), false, null, null, null, false, true), null).get(0).field());
        assertTrue(CustomFields.checkDefinition(
                new Definition("CUSTOMER", "x1", "X", DataType.TEXT, null, null, false, "[A-Z", null, null, false, true), null)
                .get(0).message().contains("not a valid regular expression"));
        assertEquals("regex", CustomFields.checkDefinition(
                new Definition("CUSTOMER", "x1", "X", DataType.NUMBER, null, null, false, "[0-9]+", null, null, false, true), null).get(0).field());
        assertEquals("min", CustomFields.checkDefinition(
                new Definition("CUSTOMER", "x1", "X", DataType.NUMBER, null, null, false, null, BigDecimal.TEN, BigDecimal.ONE, false, true), null).get(0).field());
        assertEquals("min", CustomFields.checkDefinition(
                new Definition("CUSTOMER", "x1", "X", DataType.DATE, null, null, false, null, BigDecimal.ONE, null, false, true), null).get(0).field());
        assertTrue(CustomFields.checkDefinition(
                new Definition("CUSTOMER", "x1", "X", DataType.TEXT, null, null, false, null, new BigDecimal("1.5"), null, false, true), null)
                .get(0).message().contains("lengths"));
        assertEquals("pii", CustomFields.checkDefinition(
                new Definition("CUSTOMER", "x1", "X", DataType.NUMBER, null, null, false, null, null, null, true, true), null).get(0).field());
    }

    @Test
    void type_and_personal_data_flag_of_an_existing_field_cannot_change() {
        Definition existing = DEFS.get(0);
        Definition retyped = new Definition("CUSTOMER", "employeeCode", "Employee code", DataType.NUMBER, null, null, false, null, null, null, false, true);
        assertTrue(CustomFields.checkDefinition(retyped, existing).get(0).message().contains("type of an existing field cannot change"));
        Definition flagged = new Definition("CUSTOMER", "employeeCode", "Employee code", DataType.TEXT, null, null, false, "[A-Z]{2}[0-9]{4}", null, null, true, true);
        assertEquals("pii", CustomFields.checkDefinition(flagged, existing).get(0).field());
        Definition relabelled = new Definition("CUSTOMER", "employeeCode", "Staff code", DataType.TEXT, null, null, true, "[A-Z]{3}", null, null, false, false);
        assertTrue(CustomFields.checkDefinition(relabelled, existing).isEmpty(), "label, required, pattern and active may change");
    }
}
