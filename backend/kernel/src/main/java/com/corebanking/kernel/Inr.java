package com.corebanking.kernel;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rupee amounts for printed documents. The standard PDF fonts have no rupee glyph, so amounts print as
 * "Rs. 1,23,45,678.90" with Indian digit grouping (last three digits, then groups of two).
 */
public final class Inr {

    private Inr() {}

    /** 12345678.9 → "1,23,45,678.90"; null → "-". Always two decimals, half-up. */
    public static String amount(BigDecimal value) {
        if (value == null) return "-";
        BigDecimal v = value.setScale(2, RoundingMode.HALF_UP);
        String plain = v.abs().toPlainString();
        int dot = plain.indexOf('.');
        return (v.signum() < 0 ? "-" : "") + group(plain.substring(0, dot)) + plain.substring(dot);
    }

    /** "Rs. 1,23,45,678.90". */
    public static String rs(BigDecimal value) {
        return value == null ? "-" : "Rs. " + amount(value);
    }

    /** Whole numbers with Indian grouping: 1234567 → "12,34,567". */
    public static String count(long value) {
        return (value < 0 ? "-" : "") + group(Long.toString(Math.abs(value)));
    }

    /** A percentage without trailing zeros: 18.5000 → "18.5%". */
    public static String percent(BigDecimal value) {
        if (value == null) return "-";
        BigDecimal v = value.stripTrailingZeros();
        return (v.scale() < 0 ? v.setScale(0, RoundingMode.UNNECESSARY) : v).toPlainString() + "%";
    }

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
        "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    /**
     * Amount in words for invoices and letters, in the Indian system: 123456.50 →
     * "Rupees One Lakh Twenty Three Thousand Four Hundred Fifty Six and Paise Fifty Only".
     */
    public static String words(BigDecimal value) {
        if (value == null) return "-";
        BigDecimal v = value.setScale(2, RoundingMode.HALF_UP);
        if (v.signum() < 0) return "Minus " + words(v.negate());
        java.math.BigInteger rupees = v.toBigInteger();
        int paise = v.subtract(new BigDecimal(rupees)).movePointRight(2).intValueExact();
        String r = rupees.signum() == 0 ? "Zero" : whole(rupees);
        return "Rupees " + r + (paise > 0 ? " and Paise " + below1000(paise) : "") + " Only";
    }

    private static String whole(java.math.BigInteger n) {
        java.math.BigInteger crore = java.math.BigInteger.valueOf(10_000_000);
        if (n.compareTo(crore) >= 0) {
            java.math.BigInteger[] qr = n.divideAndRemainder(crore);
            String rest = qr[1].signum() == 0 ? "" : " " + whole(qr[1]);
            return whole(qr[0]) + " Crore" + rest;
        }
        int v = n.intValueExact();
        StringBuilder sb = new StringBuilder();
        if (v >= 100_000) sb.append(below1000(v / 100_000)).append(" Lakh ");
        v %= 100_000;
        if (v >= 1000) sb.append(below1000(v / 1000)).append(" Thousand ");
        v %= 1000;
        if (v > 0) sb.append(below1000(v));
        return sb.toString().trim();
    }

    private static String below1000(int v) {
        StringBuilder sb = new StringBuilder();
        if (v >= 100) sb.append(ONES[v / 100]).append(" Hundred ");
        v %= 100;
        if (v >= 20) sb.append(TENS[v / 10]).append(v % 10 > 0 ? " " + ONES[v % 10] : "");
        else if (v > 0) sb.append(ONES[v]);
        return sb.toString().trim();
    }

    private static String group(String digits) {
        if (digits.length() <= 3) return digits;
        StringBuilder sb = new StringBuilder(digits.substring(digits.length() - 3));
        int i = digits.length() - 3;
        while (i > 0) {
            int from = Math.max(0, i - 2);
            sb.insert(0, digits.substring(from, i) + ",");
            i = from;
        }
        return sb.toString();
    }
}
