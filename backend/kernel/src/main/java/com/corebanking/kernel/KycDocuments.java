package com.corebanking.kernel;

import java.util.Locale;

/**
 * Rules for KYC document numbers (US-032). A document number is never stored: the system keeps its last four
 * characters for display and, for every type except Aadhaar, a keyed hash for exact-match lookup.
 * <p>
 * Aadhaar (UIDAI guidance on masked Aadhaar; RBI Master Direction on KYC: where the Aadhaar number is not
 * required by law the regulated entity must ensure it is redacted or blacked out): the full 12-digit number is
 * never accepted, hashed or stored. Only the last four digits may be given, and a value that looks like a full
 * Aadhaar number is refused for every document type.
 */
public final class KycDocuments {

    private KycDocuments() {}

    public static final String AADHAAR_MASKED = "AADHAAR_MASKED";

    /** What may be kept of a number: {@code hashInput} is null when nothing may be hashed. */
    public record Reference(String last4, String hashInput) {}

    /** "address-proof" / "Address_Proof" → "ADDRESS_PROOF". */
    public static String normaliseType(String type) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("docType is required");
        String t = type.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!t.matches("[A-Z0-9_]{1,40}")) throw new IllegalArgumentException("docType is not valid");
        return t;
    }

    /** Twelve digits, optionally grouped by spaces or hyphens, first digit 2–9: the shape of an Aadhaar number. */
    public static boolean looksLikeAadhaar(String number) {
        if (number == null) return false;
        String compact = number.replaceAll("[\\s-]", "");
        return compact.matches("[2-9][0-9]{11}");
    }

    /**
     * @param docType normalised document type
     * @param number  the document number as typed, or for Aadhaar only its last four digits; may be null
     * @throws IllegalArgumentException when a full Aadhaar number is supplied, or the number is malformed
     */
    public static Reference reference(String docType, String number) {
        if (number == null || number.isBlank()) return new Reference(null, null);
        if (looksLikeAadhaar(number)) {
            throw new IllegalArgumentException("a full Aadhaar number must never be sent or stored; give only its last four digits");
        }
        String compact = number.replaceAll("[\\s/-]", "").toUpperCase(Locale.ROOT);
        if (AADHAAR_MASKED.equals(docType)) {
            String digits = compact.replace("X", "");
            if (!digits.matches("[0-9]{4}")) {
                throw new IllegalArgumentException("for Aadhaar give only the last four digits");
            }
            return new Reference(digits, null);
        }
        if (!compact.matches("[A-Z0-9]{4,30}")) {
            throw new IllegalArgumentException("the document number must be 4 to 30 letters or digits");
        }
        return new Reference(compact.substring(compact.length() - 4), compact);
    }

    /** Display form of a stored reference: "XXXXXX234F"; Aadhaar "XXXX XXXX 1234". */
    public static String masked(String docType, String last4) {
        if (last4 == null) return null;
        return AADHAAR_MASKED.equals(docType) ? "XXXX XXXX " + last4 : "XXXXXX" + last4;
    }
}
