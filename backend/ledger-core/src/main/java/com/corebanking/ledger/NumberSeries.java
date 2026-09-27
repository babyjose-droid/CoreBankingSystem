package com.corebanking.ledger;

import java.util.Map;
import java.util.Objects;

/**
 * Account number formatting. Each account family has its own prefix so series can never collide
 * (the reference system issued FD numbers that clashed with CASA — see sandbox finding F-09).
 * Format: prefix + zero-padded sequence + Luhn check digit.
 *
 * <p>Sequence values come from a database sequence per family (see V2__platform.sql). A failed save
 * rolls back with its transaction, so gaps are possible but duplicates are not.
 */
public final class NumberSeries {

    public enum Family { LOAN, CASA, TERM_DEPOSIT, CUSTOMER, VOUCHER }

    /** Default prefixes; a tenant may override, but the platform rejects overlapping prefixes. */
    public static final Map<Family, String> DEFAULT_PREFIX = Map.of(
            Family.LOAN, "1001",
            Family.CASA, "2001",
            Family.TERM_DEPOSIT, "3001",
            Family.CUSTOMER, "9001",
            Family.VOUCHER, "8001");

    private final String prefix;
    private final int width;

    public NumberSeries(String prefix, int width) {
        this.prefix = Objects.requireNonNull(prefix);
        if (!prefix.chars().allMatch(Character::isDigit)) throw new LedgerException("prefix must be numeric");
        if (width < 4 || width > 12) throw new LedgerException("width must be 4..12");
        this.width = width;
    }

    public String format(long sequence) {
        if (sequence < 1) throw new LedgerException("sequence must be >= 1");
        String body = prefix + String.format("%0" + width + "d", sequence);
        if (body.length() - prefix.length() > width) throw new LedgerException("sequence exhausted for prefix " + prefix);
        return body + luhnDigit(body);
    }

    public boolean owns(String number) {
        return number.startsWith(prefix) && number.length() == prefix.length() + width + 1 && isValid(number);
    }

    public static boolean isValid(String number) {
        if (number == null || number.length() < 2 || !number.chars().allMatch(Character::isDigit)) return false;
        return luhnDigit(number.substring(0, number.length() - 1)) == number.charAt(number.length() - 1) - '0';
    }

    /** True if no number of one prefix can start with the other (prefix-free). */
    public static boolean nonOverlapping(String a, String b) {
        return !a.startsWith(b) && !b.startsWith(a);
    }

    static int luhnDigit(String body) {
        int sum = 0;
        boolean dbl = true;
        for (int i = body.length() - 1; i >= 0; i--) {
            int d = body.charAt(i) - '0';
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }
}
