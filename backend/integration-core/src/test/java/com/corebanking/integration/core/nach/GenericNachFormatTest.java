package com.corebanking.integration.core.nach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The GENERIC NACH layout, both encodings. Accounts, UMRNs and codes are fake. */
class GenericNachFormatTest {

    static final NachFile.Header HEADER = new NachFile.Header("NP-20261103-01", "TESTUTIL0000000001", "TESTBANK", LocalDate.of(2026, 11, 5));

    static NachFile.Presentation presentation() {
        return new NachFile.Presentation(HEADER, List.of(
                new NachFile.Debit(1, "NP-20261103-01-000001", "SIMB0000000000000001", "000000CLAUDE1234", "TEST0000001", "SB",
                        "CLAUDE-TEST Borrower", new BigDecimal("5200.00"), "1001000000017"),
                new NachFile.Debit(2, "NP-20261103-01-000002", "SIMB0000000000000002", "000000CLAUDE5678", "TEST0000002", "CA",
                        "CLAUDE-TEST Traders, \"LLP\"", new BigDecimal("12345.67"), "1001000000025")));
    }

    static NachFile.Response response() {
        return new NachFile.Response(HEADER, List.of(
                new NachFile.Outcome(1, "NP-20261103-01-000001", "SIMB0000000000000001", new BigDecimal("5200.00"), true, null, "BANKREF0001"),
                new NachFile.Outcome(2, "NP-20261103-01-000002", "SIMB0000000000000002", new BigDecimal("12345.67"), false, "04", null)));
    }

    static GenericNachFormat fixed() {
        return new GenericNachFormat(GenericNachFormat.Encoding.FIXED);
    }

    static GenericNachFormat csv() {
        return new GenericNachFormat(GenericNachFormat.Encoding.CSV);
    }

    @Test
    void fixed_presentation_has_header_details_and_trailer_of_200_characters() {
        String text = fixed().render(presentation());
        String[] lines = text.split("\r\n");
        assertEquals(4, lines.length);
        for (String l : lines) assertEquals(200, l.length());
        assertTrue(text.endsWith("\r\n"));
        assertEquals("HCBSNACH01PTESTUTIL0000000001TESTBANK   NP-20261103-01                20261105", lines[0].stripTrailing());
        assertEquals("D000001NP-20261103-01-000001         SIMB0000000000000001000000CLAUDE1234                   TEST0000001SB"
                + "CLAUDE TEST BORROWER                    00000005200001001000000017", lines[1].stripTrailing());
        assertEquals("T000002000000001754567", lines[3].stripTrailing());           // 2 records, 17,545.67 in paise
    }

    @Test
    void fixed_presentation_round_trips() {
        NachFile.Presentation back = fixed().parsePresentation(fixed().render(presentation()));
        assertEquals(HEADER, back.header());
        assertEquals(2, back.items().size());
        assertEquals(new BigDecimal("5200.00"), back.items().get(0).amount());
        assertEquals("000000CLAUDE5678", back.items().get(1).accountNumber());
        assertEquals("CLAUDE TEST TRADERS LLP", back.items().get(1).holderName());   // names are reduced to safe characters
        assertEquals(new BigDecimal("17545.67"), back.total());
    }

    @Test
    void csv_presentation_round_trips_and_names_cannot_break_a_row() {
        String text = csv().render(presentation());
        assertTrue(text.startsWith("record_type,file_ref,utility_code,sponsor_bank_code,settlement_date,direction,seq,"));
        NachFile.Presentation back = csv().parsePresentation(text);
        assertEquals(HEADER, back.header());
        assertEquals(presentation().items().get(0).itemRef(), back.items().get(0).itemRef());
        assertEquals("CLAUDE TEST TRADERS LLP", back.items().get(1).holderName());
        assertEquals(new BigDecimal("12345.67"), back.items().get(1).amount());
    }

    @Test
    void responses_round_trip_in_both_encodings() {
        for (GenericNachFormat f : List.of(fixed(), csv())) {
            NachFile.Response back = f.parseResponse(f.renderResponse(response()));
            assertEquals(HEADER, back.header());
            assertEquals(2, back.items().size());
            assertTrue(back.items().get(0).success());
            assertNull(back.items().get(0).returnCode());
            assertEquals("BANKREF0001", back.items().get(0).bankRef());
            assertFalse(back.items().get(1).success());
            assertEquals("04", back.items().get(1).returnCode());
            assertEquals(1, back.successCount());
            assertEquals(new BigDecimal("5200.00"), back.successTotal());
            assertEquals(new BigDecimal("17545.67"), back.total());
        }
    }

    @Test
    void a_response_whose_control_totals_do_not_match_is_refused_as_a_whole() {
        String text = fixed().renderResponse(response());
        // one detail removed: the trailer still says 2
        String[] lines = text.split("\r\n");
        String truncated = lines[0] + "\r\n" + lines[1] + "\r\n" + lines[3] + "\r\n";
        NachFile.FormatException e = assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse(truncated));
        assertTrue(e.getMessage().contains("the trailer says 2 records, the file has 1"), e.getMessage());
        assertEquals(3, e.line());
        // an amount altered in a detail
        String altered = text.replace("0000000520000", "0000000530000");
        assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse(altered));
        // a bounce turned into a success without touching the trailer's success totals
        String flipped = lines[0] + "\r\n" + lines[1] + "\r\n" + lines[2].substring(0, 176) + "1   " + lines[2].substring(180) + "\r\n" + lines[3] + "\r\n";
        NachFile.FormatException f = assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse(flipped));
        assertTrue(f.getMessage().contains("success totals"), f.getMessage());
    }

    @Test
    void an_incomplete_or_foreign_file_is_refused() {
        String text = fixed().renderResponse(response());
        String[] lines = text.split("\r\n");
        assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse(lines[0] + "\r\n" + lines[1] + "\r\n" + lines[2] + "\r\n"));   // no trailer
        assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse(lines[1] + "\r\n" + lines[3] + "\r\n"));                        // no header
        assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse(text + lines[1] + "\r\n"));                                     // after trailer
        assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse(text.replace("CBSNACH01", "OTHERFMT1")));
        assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse(""));
        assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse("H short line\r\n"));
        assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse(fixed().render(presentation())));      // a presentation is not a response
        assertThrows(NachFile.FormatException.class, () -> fixed().parsePresentation(text));
        assertThrows(NachFile.FormatException.class, () -> csv().parseResponse(csv().render(presentation())));
        assertThrows(NachFile.FormatException.class, () -> csv().parseResponse("a,b\r\n1,2\r\n"));
        assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse(text.replace("TESTBANK", "TESTéANK")));
    }

    @Test
    void response_rows_are_checked() {
        NachFile.Response dupSeq = new NachFile.Response(HEADER, List.of(
                new NachFile.Outcome(1, "A-1", null, new BigDecimal("10.00"), true, null, null),
                new NachFile.Outcome(1, "A-2", null, new BigDecimal("10.00"), true, null, null)));
        assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse(fixed().renderResponse(dupSeq)));
        NachFile.Response dupRef = new NachFile.Response(HEADER, List.of(
                new NachFile.Outcome(1, "A-1", null, new BigDecimal("10.00"), true, null, null),
                new NachFile.Outcome(2, "A-1", null, new BigDecimal("10.00"), true, null, null)));
        assertThrows(NachFile.FormatException.class, () -> csv().parseResponse(csv().renderResponse(dupRef)));
        NachFile.Response bounceWithoutCode = new NachFile.Response(HEADER, List.of(
                new NachFile.Outcome(1, "A-1", null, new BigDecimal("10.00"), false, null, null)));
        assertThrows(NachFile.FormatException.class, () -> fixed().parseResponse(fixed().renderResponse(bounceWithoutCode)));
        NachFile.Response successWithCode = new NachFile.Response(HEADER, List.of(
                new NachFile.Outcome(1, "A-1", null, new BigDecimal("10.00"), true, "04", null)));
        assertThrows(NachFile.FormatException.class, () -> csv().parseResponse(csv().renderResponse(successWithCode)));
    }

    @Test
    void presentations_are_validated_before_a_file_is_written() {
        NachFile.Debit ok = presentation().items().get(0);
        assertThrows(NachFile.FormatException.class, () -> fixed().render(new NachFile.Presentation(HEADER, List.of())));
        for (NachFile.Debit bad : List.of(
                new NachFile.Debit(1, ok.itemRef(), "SHORTUMRN", ok.accountNumber(), ok.ifsc(), "SB", "N", ok.amount(), ok.userRef()),
                new NachFile.Debit(1, ok.itemRef(), ok.umrn(), "12 34", ok.ifsc(), "SB", "N", ok.amount(), ok.userRef()),
                new NachFile.Debit(1, ok.itemRef(), ok.umrn(), ok.accountNumber(), "BADIFSC", "SB", "N", ok.amount(), ok.userRef()),
                new NachFile.Debit(1, ok.itemRef(), ok.umrn(), ok.accountNumber(), ok.ifsc(), "XX", "N", ok.amount(), ok.userRef()),
                new NachFile.Debit(1, ok.itemRef(), ok.umrn(), ok.accountNumber(), ok.ifsc(), "SB", "N", BigDecimal.ZERO, ok.userRef()),
                new NachFile.Debit(1, ok.itemRef(), ok.umrn(), ok.accountNumber(), ok.ifsc(), "SB", "N", new BigDecimal("10.001"), ok.userRef()),
                new NachFile.Debit(1, "bad ref!", ok.umrn(), ok.accountNumber(), ok.ifsc(), "SB", "N", ok.amount(), ok.userRef()),
                new NachFile.Debit(2, ok.itemRef(), ok.umrn(), ok.accountNumber(), ok.ifsc(), "SB", "N", ok.amount(), ok.userRef()))) {
            assertThrows(NachFile.FormatException.class, () -> fixed().render(new NachFile.Presentation(HEADER, List.of(bad))), bad.toString());
        }
        NachFile.Header badHeader = new NachFile.Header("ref with space", "TESTUTIL0000000001", "TESTBANK", LocalDate.of(2026, 11, 5));
        assertThrows(NachFile.FormatException.class, () -> csv().render(new NachFile.Presentation(badHeader, List.of(ok))));
    }

    @Test
    void records_do_not_print_account_numbers() {
        assertFalse(presentation().items().get(0).toString().contains("CLAUDE1234"));
    }

    @Test
    void only_generic_exists_and_a_missing_bank_layout_says_so() {
        assertEquals("GENERIC", NachFileFormat.of("GENERIC", "FIXED").code());
        assertEquals("csv", NachFileFormat.of("GENERIC", "CSV").fileExtension());
        assertEquals("txt", NachFileFormat.of("GENERIC", "FIXED").fileExtension());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NachFileFormat.of("HDFC", "FIXED"));
        assertTrue(e.getMessage().contains("sponsor bank"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> NachFileFormat.of("GENERIC", "XML"));
    }
}
