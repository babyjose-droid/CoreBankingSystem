package com.corebanking.kernel;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * Customer de-duplication keys (US-030). The database stores keyed hashes of these keys; matching happens on
 * hashes so plaintext PII is never compared in SQL.
 * <ul>
 *   <li>PAN — EXACT</li>
 *   <li>mobile — STRONG (families share phones, so it is a prompt, not a block)</li>
 *   <li>normalised name + date of birth — POSSIBLE</li>
 * </ul>
 */
public final class Dedupe {

    private Dedupe() {}

    public enum Rule { PAN, MOBILE, NAME_DOB }
    public enum Strength { EXACT, STRONG, POSSIBLE }

    private static final Set<String> TITLES = Set.of("MR", "MRS", "MS", "MISS", "DR", "SHRI", "SMT", "KUM", "M/S");

    public static Strength strength(Rule rule) {
        return switch (rule) {
            case PAN -> Strength.EXACT;
            case MOBILE -> Strength.STRONG;
            case NAME_DOB -> Strength.POSSIBLE;
        };
    }

    /** "Mr. Rahul  K. Sharma" → "RAHUL K SHARMA"; accents removed, titles dropped. */
    public static String normaliseName(String first, String middle, String last) {
        String joined = String.join(" ", nz(first), nz(middle), nz(last));
        String ascii = Normalizer.normalize(joined, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder sb = new StringBuilder();
        for (String tok : ascii.toUpperCase(Locale.ROOT).replaceAll("[^A-Z ]", " ").trim().split("\\s+")) {
            if (tok.isEmpty() || TITLES.contains(tok)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(tok);
        }
        return sb.toString();
    }

    public static String nameDobKey(String first, String middle, String last, LocalDate dob) {
        return normaliseName(first, middle, last) + "|" + dob;
    }

    /** Indian mobile: strip +91 / leading 0, keep the 10 digits. */
    public static String normaliseMobile(String mobile) {
        String d = mobile.replaceAll("\\D", "");
        if (d.length() == 12 && d.startsWith("91")) d = d.substring(2);
        if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        if (!d.matches("[6-9][0-9]{9}")) throw new IllegalArgumentException("invalid Indian mobile number");
        return d;
    }

    public static String normalisePan(String pan) {
        String p = pan.trim().toUpperCase(Locale.ROOT);
        if (!p.matches("[A-Z]{5}[0-9]{4}[A-Z]")) throw new IllegalArgumentException("invalid PAN format");
        return p;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
