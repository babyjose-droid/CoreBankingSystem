package com.corebanking.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.kernel.AmountLimits.Decision;
import com.corebanking.kernel.AmountLimits.Limit;
import com.corebanking.kernel.AmountLimits.Reason;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** P2-5 rules: role amount limits, file signatures, KYC document numbers, masking for approval payloads. */
class CustomerRulesTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    static final String DISB = "LOAN_DISBURSEMENT";

    static BigDecimal bd(String s) { return new BigDecimal(s); }

    static Limit limit(String role, String type, String txn, String day) {
        return new Limit(role, type, bd(txn), day == null ? null : bd(day), LocalDate.of(2026, 4, 1), null);
    }

    static AmountLimits limits(boolean defaultDeny) {
        return new AmountLimits(List.of(
                limit("OFFICER", DISB, "200000", "500000"),
                limit("MANAGER", DISB, "1000000", null),
                limit("OFFICER", "VOUCHER", "50000", "50000"),
                new Limit("TEMP", DISB, bd("5000000"), null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)),
                new Limit("NEXT", DISB, bd("5000000"), null, LocalDate.of(2026, 11, 1), null)), defaultDeny);
    }

    // ---- amount limits ----------------------------------------------------------------------------
    @Test void within_per_transaction_limit_is_allowed() {
        Decision d = limits(false).check(Set.of("OFFICER"), DISB, bd("200000"), BigDecimal.ZERO, DAY);
        assertTrue(d.allowed());
        assertEquals(Reason.WITHIN_LIMIT, d.reason());
        assertEquals(0, bd("200000").compareTo(d.perTxnMax()));
    }

    @Test void above_per_transaction_limit_is_refused() {
        Decision d = limits(false).check(Set.of("OFFICER"), DISB, bd("200000.01"), BigDecimal.ZERO, DAY);
        assertFalse(d.allowed());
        assertEquals(Reason.PER_TRANSACTION_EXCEEDED, d.reason());
    }

    @Test void cumulative_day_limit_counts_what_was_already_used() {
        AmountLimits l = limits(false);
        assertTrue(l.check(Set.of("OFFICER"), DISB, bd("200000"), bd("300000"), DAY).allowed());
        Decision d = l.check(Set.of("OFFICER"), DISB, bd("200000"), bd("300000.01"), DAY);
        assertFalse(d.allowed());
        assertEquals(Reason.PER_DAY_EXCEEDED, d.reason());
        assertEquals(0, bd("500000").compareTo(d.perDayMax()));
    }

    @Test void most_permissive_role_applies() {
        AmountLimits l = limits(false);
        Decision d = l.check(Set.of("OFFICER", "MANAGER"), DISB, bd("900000"), bd("4000000"), DAY);
        assertTrue(d.allowed());                       // MANAGER: 10 lakh per transaction, no day limit
        assertEquals(0, bd("1000000").compareTo(d.perTxnMax()));
        assertNull(d.perDayMax());
        assertFalse(l.check(Set.of("OFFICER", "MANAGER"), DISB, bd("1000000.01"), BigDecimal.ZERO, DAY).allowed());
    }

    @Test void a_role_is_judged_as_a_whole_not_mixed_with_another() {
        // A: small per transaction, large per day. B: large per transaction, small per day. 150 after 400 used:
        // A refuses (150 > 100), B refuses (400 + 150 > 300); A's day limit is not combined with B's transaction limit.
        AmountLimits l = new AmountLimits(List.of(limit("A", DISB, "100", "1000"), limit("B", DISB, "200", "300")), false);
        Decision d = l.check(Set.of("A", "B"), DISB, bd("150"), bd("400"), DAY);
        assertFalse(d.allowed());
        assertEquals(Reason.PER_DAY_EXCEEDED, d.reason());
        assertTrue(l.check(Set.of("A", "B"), DISB, bd("100"), bd("400"), DAY).allowed());   // A allows
    }

    @Test void no_row_means_no_limit_unless_default_deny() {
        Decision open = limits(false).check(Set.of("CASHIER"), DISB, bd("99999999"), BigDecimal.ZERO, DAY);
        assertTrue(open.allowed());
        assertEquals(Reason.NO_LIMIT_SET, open.reason());
        Decision deny = limits(true).check(Set.of("CASHIER"), DISB, bd("1"), BigDecimal.ZERO, DAY);
        assertFalse(deny.allowed());
        assertEquals(Reason.DEFAULT_DENY, deny.reason());
        assertFalse(limits(true).check(Set.of(), DISB, bd("1"), BigDecimal.ZERO, DAY).allowed());   // no roles in the token
        assertTrue(limits(true).check(Set.of("OFFICER"), DISB, bd("1"), BigDecimal.ZERO, DAY).allowed());
    }

    @Test void limits_apply_per_transaction_type() {
        AmountLimits l = limits(false);
        assertFalse(l.check(Set.of("OFFICER"), "VOUCHER", bd("50000.01"), BigDecimal.ZERO, DAY).allowed());
        assertTrue(l.check(Set.of("OFFICER"), "LOAN_WAIVER", bd("50000.01"), BigDecimal.ZERO, DAY).allowed());   // no row for waivers
    }

    @Test void effective_dates_are_respected() {
        AmountLimits l = limits(true);
        assertFalse(l.check(Set.of("TEMP"), DISB, bd("1"), BigDecimal.ZERO, DAY).allowed());     // ended in March
        assertFalse(l.check(Set.of("NEXT"), DISB, bd("1"), BigDecimal.ZERO, DAY).allowed());     // starts in November
        assertTrue(l.check(Set.of("TEMP"), DISB, bd("1"), BigDecimal.ZERO, LocalDate.of(2026, 3, 31)).allowed());
        assertTrue(l.check(Set.of("NEXT"), DISB, bd("1"), BigDecimal.ZERO, LocalDate.of(2026, 11, 1)).allowed());
    }

    @Test void a_transaction_without_an_amount_is_not_limited() {
        Decision d = limits(true).check(Set.of(), DISB, null, null, DAY);
        assertTrue(d.allowed());
        assertEquals(Reason.NO_AMOUNT, d.reason());
    }

    @Test void null_usage_counts_as_zero_and_role_names_are_exact() {
        assertTrue(limits(false).check(Set.of("OFFICER"), DISB, bd("100"), null, DAY).allowed());
        assertEquals(Reason.NO_LIMIT_SET, limits(false).check(Set.of("officer"), DISB, bd("900000000"), null, DAY).reason());
    }

    @Test void invalid_limits_are_rejected() {
        assertThrows(IllegalArgumentException.class, () -> limit("A", DISB, "-1", null));
        assertThrows(IllegalArgumentException.class, () -> limit("A", DISB, "100", "99"));
        assertThrows(IllegalArgumentException.class,
                () -> new Limit("A", DISB, bd("1"), null, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 3, 1)));
    }

    @Test void refusal_messages_name_the_limit() {
        AmountLimits l = limits(false);
        Decision txn = l.check(Set.of("OFFICER"), DISB, bd("250000.00"), BigDecimal.ZERO, DAY);
        assertEquals("the amount 250000 is above your loan disbursement limit of 200000 per transaction; a user with a higher"
                + " limit must approve it", AmountLimits.message(txn, DISB, bd("250000.00"), BigDecimal.ZERO, "approve"));
        Decision day = l.check(Set.of("OFFICER"), DISB, bd("200000"), bd("400000"), DAY);
        assertEquals("the amount 200000 would take today's loan disbursement total to 600000, above your daily limit of 500000",
                AmountLimits.message(day, DISB, bd("200000"), bd("400000"), "propose"));
        Decision deny = limits(true).check(Set.of("X"), DISB, bd("1"), null, DAY);
        assertTrue(AmountLimits.message(deny, DISB, bd("1"), null, "propose").startsWith("no amount limit is set"));
    }

    @Test void limit_types_cover_the_story() {
        assertEquals(Set.of("LOAN_DISBURSEMENT", "LOAN_REPAYMENT", "LOAN_WAIVER", "VOUCHER", "LOAN_PRECLOSURE", "FEE_WAIVER"),
                AmountLimits.TYPES);
    }

    // ---- file signatures ----------------------------------------------------------------------------
    static byte[] bytes(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }

    static final byte[] PDF = "%PDF-1.7 CLAUDE-TEST".getBytes(StandardCharsets.US_ASCII);
    static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F');
    static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0);

    @Test void detects_pdf_jpeg_and_png() {
        assertEquals("application/pdf", FileSignatures.detect(PDF));
        assertEquals("image/jpeg", FileSignatures.detect(JPEG));
        assertEquals("image/png", FileSignatures.detect(PNG));
    }

    @Test void declared_type_must_match_the_bytes() {
        assertTrue(FileSignatures.matches("application/pdf", PDF));
        assertTrue(FileSignatures.matches("Image/PNG; q=1", PNG));
        assertFalse(FileSignatures.matches("image/png", JPEG));
        assertFalse(FileSignatures.matches("application/pdf", PNG));
    }

    @Test void other_content_is_refused() {
        byte[] exe = bytes('M', 'Z', 0x90, 0);
        byte[] html = "<html><script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII);
        assertNull(FileSignatures.detect(exe));
        assertNull(FileSignatures.detect(html));
        assertNull(FileSignatures.detect(new byte[0]));
        assertNull(FileSignatures.detect(null));
        assertFalse(FileSignatures.matches("application/pdf", html));
        assertFalse(FileSignatures.matches("image/gif", "GIF89a".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(FileSignatures.matches(null, PDF));
        assertFalse(FileSignatures.matches("text/html", PDF));
        assertFalse(FileSignatures.matches("image/jpeg", bytes(0xFF, 0xD8)));      // truncated
    }

    // ---- KYC document numbers -------------------------------------------------------------------------
    @Test void document_types_are_normalised() {
        assertEquals("ADDRESS_PROOF", KycDocuments.normaliseType(" address-proof "));
        assertEquals("AADHAAR_MASKED", KycDocuments.normaliseType("aadhaar-masked"));
        assertThrows(IllegalArgumentException.class, () -> KycDocuments.normaliseType(" "));
        assertThrows(IllegalArgumentException.class, () -> KycDocuments.normaliseType("pan; drop table"));
    }

    @Test void a_full_aadhaar_number_is_never_accepted() {
        // made-up 12-digit values in the Aadhaar shape; they belong to nobody
        assertTrue(KycDocuments.looksLikeAadhaar("2000 0000 0000"));
        assertTrue(KycDocuments.looksLikeAadhaar("9999-0000-0000"));
        assertFalse(KycDocuments.looksLikeAadhaar("1000 0000 0000"));
        assertFalse(KycDocuments.looksLikeAadhaar("0000"));
        assertThrows(IllegalArgumentException.class, () -> KycDocuments.reference("AADHAAR_MASKED", "2000 0000 0000"));
        assertThrows(IllegalArgumentException.class, () -> KycDocuments.reference("ADDRESS_PROOF", "200000000000"));
    }

    @Test void aadhaar_keeps_only_the_last_four_digits_and_no_hash() {
        KycDocuments.Reference r = KycDocuments.reference("AADHAAR_MASKED", "XXXX XXXX 0000");
        assertEquals("0000", r.last4());
        assertNull(r.hashInput());
        assertEquals("0000", KycDocuments.reference("AADHAAR_MASKED", "0000").last4());
        assertThrows(IllegalArgumentException.class, () -> KycDocuments.reference("AADHAAR_MASKED", "000"));
        assertThrows(IllegalArgumentException.class, () -> KycDocuments.reference("AADHAAR_MASKED", "0000 0000"));
        assertEquals("XXXX XXXX 0000", KycDocuments.masked("AADHAAR_MASKED", "0000"));
    }

    @Test void other_numbers_keep_last_four_and_a_hash_input() {
        KycDocuments.Reference r = KycDocuments.reference("PASSPORT", " z-000 0000 ");
        assertEquals("0000", r.last4());
        assertEquals("Z0000000", r.hashInput());
        assertEquals("XXXXXX0000", KycDocuments.masked("PASSPORT", r.last4()));
        assertNull(KycDocuments.reference("PHOTO", null).last4());
        assertNull(KycDocuments.reference("PHOTO", "  ").hashInput());
        assertNull(KycDocuments.masked("PHOTO", null));
        assertThrows(IllegalArgumentException.class, () -> KycDocuments.reference("PASSPORT", "Z0#00"));
        assertThrows(IllegalArgumentException.class, () -> KycDocuments.reference("PASSPORT", "AB1"));
    }

    // ---- masking for approval payloads (SEC-03) ---------------------------------------------------------
    @Test void age_band_hides_the_date_of_birth() {
        LocalDate today = LocalDate.of(2026, 10, 20);
        assertEquals("under 18", Masking.ageBand(LocalDate.of(2008, 10, 21), today));
        assertEquals("18-25", Masking.ageBand(LocalDate.of(2008, 10, 20), today));
        assertEquals("26-35", Masking.ageBand(LocalDate.of(1995, 1, 1), today));
        assertEquals("36-45", Masking.ageBand(LocalDate.of(1985, 1, 1), today));
        assertEquals("46-60", Masking.ageBand(LocalDate.of(1966, 10, 21), today));
        assertEquals("over 60", Masking.ageBand(LocalDate.of(1965, 10, 20), today));
        assertNull(Masking.ageBand(null, today));
    }

    @Test void pincode_keeps_the_sorting_district() {
        assertEquals("682XXX", Masking.pincode("682001"));
        assertEquals("XXXXXX", Masking.pincode("68200"));
        assertNull(Masking.pincode(null));
    }
}
