package com.corebanking.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.kernel.UcrfConsumerFile.Account;
import com.corebanking.kernel.UcrfConsumerFile.Header;
import com.corebanking.kernel.UcrfConsumerFile.Result;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The UCRF-style consumer file: layout, conventions and what is rejected. All data is fake (CLAUDE-TEST). */
class UcrfConsumerFileTest {

    static BigDecimal bd(String s) { return new BigDecimal(s); }

    static final LocalDate REPORTED = LocalDate.of(2026, 9, 30);
    static final Header HEADER = new Header("CLAUDE-TEST-MBR01", "Claude-Test Finance", REPORTED);

    static Account account(String no, String status, String assetClass, int dpd, String pan, String mobile) {
        return new Account(no, "05", "1", LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 30), null, bd("100000"), bd("93758.42"),
                bd("9794.50"), dpd, assetClass, status, false, bd("9168"), 12, bd("18.000000"), "Claude-Test Borrower One",
                LocalDate.of(1990, 1, 15), "FEMALE", pan, mobile, "12 Sample Street | Flat 3\nKochi", "32", "682001");
    }

    @Test
    void file_has_header_one_line_per_account_and_trailer() {
        Result r = UcrfConsumerFile.format(HEADER, List.of(
                account("10010000000017", "ACTIVE", "SMA0", 11, "abcde1234f", "+91 98765 43210"),
                account("10010000000025", "ACTIVE", "STANDARD", 0, null, "9876543210")));
        String[] lines = r.text().split("\r\n", -1);
        assertEquals(5, lines.length, "header, two accounts, trailer and the empty piece after the last line end");
        assertEquals("", lines[4]);
        assertEquals("HDR|UCRF-STYLE-CONSUMER|1.0|CLAUDE-TEST-MBR01|CLAUDE-TEST FINANCE|30092026", lines[0]);
        assertEquals("ACC|CLAUDE-TEST-MBR01|10010000000017|05|1|30062026|30072026||30092026|100000|93758|9795|11|SMA||9168|12|18.00|"
                + "CLAUDE-TEST BORROWER ONE|15011990|1|ABCDE1234F|9876543210|12 SAMPLE STREET FLAT 3 KOCHI|32|682001", lines[1]);
        assertEquals("ACC|CLAUDE-TEST-MBR01|10010000000025|05|1|30062026|30072026||30092026|100000|93758|9795|0|STD||9168|12|18.00|"
                + "CLAUDE-TEST BORROWER ONE|15011990|1||9876543210|12 SAMPLE STREET FLAT 3 KOCHI|32|682001", lines[2]);
        assertEquals("TRL|2|187516|19590", lines[3]);
        assertEquals(2, r.accepted());
        assertTrue(r.rejected().isEmpty());
        assertEquals(0, bd("187516").compareTo(r.totalCurrentBalance()));
        assertEquals(0, bd("19590").compareTo(r.totalAmountOverdue()));
    }

    @Test
    void every_account_line_has_exactly_the_documented_fields() {
        assertEquals(26, UcrfConsumerFile.FIELDS.size());
        Result r = UcrfConsumerFile.format(HEADER, List.of(account("1", "ACTIVE", "STANDARD", 0, "ABCDE1234F", null)));
        String acc = r.text().split("\r\n")[1];
        assertEquals(UcrfConsumerFile.FIELDS.size(), acc.split("\\|", -1).length, "a delimiter inside the address must not add a field");
        assertFalse(acc.contains("\n"));
    }

    @Test
    void asset_classes_and_status_codes() {
        assertEquals("STD", UcrfConsumerFile.assetCode("STANDARD"));
        for (String sma : List.of("SMA0", "SMA1", "SMA2")) assertEquals("SMA", UcrfConsumerFile.assetCode(sma));
        assertEquals("SUB", UcrfConsumerFile.assetCode("SUBSTANDARD"));
        for (String d : List.of("DOUBTFUL1", "DOUBTFUL2", "DOUBTFUL3")) assertEquals("DBT", UcrfConsumerFile.assetCode(d));
        assertEquals("LSS", UcrfConsumerFile.assetCode("LOSS"));
        assertEquals("", UcrfConsumerFile.assetCode("SOMETHING"));

        Account wo = account("1", "WRITTEN_OFF", "LOSS", 1200, "ABCDE1234F", null);
        String[] f = UcrfConsumerFile.format(HEADER, List.of(wo)).text().split("\r\n")[1].split("\\|", -1);
        assertEquals("900", f[12], "days past due is capped");
        assertEquals("LSS", f[13]);
        assertEquals("02", f[14]);

        Account b = account("2", "ACTIVE", "SUBSTANDARD", 0, "ABCDE1234F", null);
        Account restructured = new Account(b.accountNo(), b.accountType(), b.ownership(), b.dateOpened(), b.dateLastPayment(), b.dateClosed(),
                b.sanctionedAmount(), b.currentBalance(), b.amountOverdue(), b.daysPastDue(), b.assetClass(), b.status(), true, b.emi(),
                b.tenureMonths(), b.ratePercent(), b.name(), b.dateOfBirth(), "MALE", b.pan(), b.mobile(), b.address(), b.stateCode(), b.pincode());
        f = UcrfConsumerFile.format(HEADER, List.of(restructured)).text().split("\r\n")[1].split("\\|", -1);
        assertEquals("SUB", f[13]);
        assertEquals("00", f[14]);
        assertEquals("2", f[20]);
    }

    @Test
    void closed_account_reports_zero_balance_and_its_closure_date() {
        Account b = account("3", "CLOSED", "STANDARD", 5, "ABCDE1234F", null);
        Account closed = new Account(b.accountNo(), b.accountType(), b.ownership(), b.dateOpened(), b.dateLastPayment(), LocalDate.of(2026, 9, 12),
                b.sanctionedAmount(), bd("12.00"), bd("12.00"), b.daysPastDue(), b.assetClass(), b.status(), false, b.emi(), b.tenureMonths(),
                b.ratePercent(), b.name(), b.dateOfBirth(), b.gender(), b.pan(), b.mobile(), b.address(), b.stateCode(), b.pincode());
        Result r = UcrfConsumerFile.format(HEADER, List.of(closed));
        String[] f = r.text().split("\r\n")[1].split("\\|", -1);
        assertEquals("12092026", f[7]);
        assertEquals("0", f[10]);
        assertEquals("0", f[11]);
        assertEquals("0", f[12]);
        assertEquals("TRL|1|0|0", r.text().split("\r\n")[2]);
        // a closed account without a closure date cannot be reported
        assertEquals("a closed account has no closure date", UcrfConsumerFile.problem(b, REPORTED));
    }

    @Test
    void accounts_that_fail_a_check_are_left_out_and_listed() {
        Account good = account("10", "ACTIVE", "STANDARD", 0, "ABCDE1234F", null);
        Account noId = account("11", "ACTIVE", "STANDARD", 0, null, null);
        Account badPan = account("12", "ACTIVE", "STANDARD", 0, "ABCDE12345", "9876543210");
        Account badMobile = account("13", "ACTIVE", "STANDARD", 0, "ABCDE1234F", "12345");
        Result r = UcrfConsumerFile.format(HEADER, List.of(good, noId, badPan, badMobile));
        assertEquals(1, r.accepted());
        assertEquals(List.of("11", "12", "13"), r.rejected().stream().map(UcrfConsumerFile.Rejection::accountNo).toList());
        assertEquals("neither PAN nor mobile number is available", r.rejected().get(0).reason());
        assertEquals("PAN is not in the format AAAAA9999A", r.rejected().get(1).reason());
        assertEquals("mobile number is not a 10-digit Indian mobile number", r.rejected().get(2).reason());
        assertTrue(r.text().endsWith("TRL|1|93758|9795\r\n"));
        assertFalse(r.text().contains("|11|05|"));
        for (UcrfConsumerFile.Rejection x : r.rejected()) {
            assertFalse(x.reason().contains("ABCDE"), "a rejection reason never repeats personal data");
        }
        assertNull(UcrfConsumerFile.problem(good, REPORTED));
    }

    @Test
    void each_required_field_is_checked() {
        Account g = account("20", "ACTIVE", "STANDARD", 0, "ABCDE1234F", "9876543210");
        assertEquals("account number is missing", UcrfConsumerFile.problem(with(g, " ", g.name(), g.dateOfBirth(), g.address(), g.stateCode(), g.pincode()), REPORTED));
        assertEquals("borrower name is missing", UcrfConsumerFile.problem(with(g, "20", null, g.dateOfBirth(), g.address(), g.stateCode(), g.pincode()), REPORTED));
        assertEquals("date of birth is missing", UcrfConsumerFile.problem(with(g, "20", g.name(), null, g.address(), g.stateCode(), g.pincode()), REPORTED));
        assertEquals("date of birth is not before the date opened",
                UcrfConsumerFile.problem(with(g, "20", g.name(), LocalDate.of(2026, 7, 1), g.address(), g.stateCode(), g.pincode()), REPORTED));
        assertEquals("address is missing", UcrfConsumerFile.problem(with(g, "20", g.name(), g.dateOfBirth(), "", g.stateCode(), g.pincode()), REPORTED));
        assertEquals("state code is missing or not a 2-digit code", UcrfConsumerFile.problem(with(g, "20", g.name(), g.dateOfBirth(), g.address(), "KL", g.pincode()), REPORTED));
        assertEquals("pincode is missing or not 6 digits", UcrfConsumerFile.problem(with(g, "20", g.name(), g.dateOfBirth(), g.address(), g.stateCode(), "68200"), REPORTED));
        assertEquals("date opened is after the date reported", UcrfConsumerFile.problem(g, LocalDate.of(2026, 6, 29)));
    }

    static Account with(Account g, String no, String name, LocalDate dob, String address, String state, String pincode) {
        return new Account(no, g.accountType(), g.ownership(), g.dateOpened(), g.dateLastPayment(), g.dateClosed(), g.sanctionedAmount(),
                g.currentBalance(), g.amountOverdue(), g.daysPastDue(), g.assetClass(), g.status(), g.restructured(), g.emi(), g.tenureMonths(),
                g.ratePercent(), name, dob, g.gender(), g.pan(), g.mobile(), address, state, pincode);
    }

    @Test
    void mobile_numbers_are_normalised() {
        assertEquals("9876543210", UcrfConsumerFile.mobile("09876543210"));
        assertEquals("9876543210", UcrfConsumerFile.mobile("+91-98765-43210"));
        assertEquals("", UcrfConsumerFile.mobile("1234567890"));
        assertEquals("", UcrfConsumerFile.mobile(null));
        assertEquals("A B", UcrfConsumerFile.clean(" a|\r\n\tb ", 10));
        assertEquals("ABC", UcrfConsumerFile.clean("abcdef", 3));
    }

    @Test
    void empty_file_and_missing_member_code() {
        Result r = UcrfConsumerFile.format(HEADER, List.of());
        assertEquals("HDR|UCRF-STYLE-CONSUMER|1.0|CLAUDE-TEST-MBR01|CLAUDE-TEST FINANCE|30092026\r\nTRL|0|0|0\r\n", r.text());
        assertThrows(IllegalArgumentException.class, () -> new Header(" ", "x", REPORTED));
    }
}
