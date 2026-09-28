package com.corebanking.kernel;

/**
 * Display masking for personal data (US-133). Used in API responses, logs and exports by default; unmasked
 * values need an explicit permission and are audited.
 */
public final class Masking {

    private Masking() {}

    /** ABCDE1234F → XXXXX1234X (keeps the four digits staff use to confirm identity over the phone). */
    public static String pan(String pan) {
        if (pan == null) return null;
        String p = pan.trim().toUpperCase();
        if (!p.matches("[A-Z]{5}[0-9]{4}[A-Z]")) return "XXXXXXXXXX";
        return "XXXXX" + p.substring(5, 9) + "X";
    }

    /** 9876543210 → XXXXXX3210. */
    public static String mobile(String mobile) {
        if (mobile == null) return null;
        String d = mobile.replaceAll("\\D", "");
        if (d.length() < 4) return "XXXX";
        return "X".repeat(Math.max(0, d.length() - 4)) + d.substring(d.length() - 4);
    }

    /** Aadhaar may only ever be shown as the last four digits (UIDAI). */
    public static String aadhaar(String aadhaar) {
        if (aadhaar == null) return null;
        String d = aadhaar.replaceAll("\\D", "");
        if (d.length() != 12) return "XXXX XXXX XXXX";
        return "XXXX XXXX " + d.substring(8);
    }

    /** jane.doe@example.com → j*******@example.com */
    public static String email(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at < 1) return "***";
        return email.charAt(0) + "*".repeat(Math.max(1, at - 1)) + email.substring(at);
    }

    /** Account numbers: keep last four. */
    public static String account(String account) {
        if (account == null) return null;
        if (account.length() <= 4) return account;
        return "X".repeat(account.length() - 4) + account.substring(account.length() - 4);
    }
}
