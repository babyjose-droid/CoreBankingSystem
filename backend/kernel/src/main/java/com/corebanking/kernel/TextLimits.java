package com.corebanking.kernel;

import java.util.Locale;
import java.util.Map;

/**
 * Maximum lengths of free-text fields in request bodies (SEC-05, ASVS V5). One table for the whole API, looked up by the
 * field's name, so a reason is limited to the same length wherever it is asked for. A service may still be stricter
 * (a name of at most 80 characters, a reason of at least ten); these are the outer bounds that keep a request from
 * storing or rendering an unbounded amount of text. Lengths count characters (UTF-16 units), not bytes.
 */
public final class TextLimits {

    public static final int NAME_PART = 100;       // first, middle, last name
    public static final int NAME = 200;            // display, legal, trade, holder names; names and labels of masters
    public static final int ADDRESS_LINE = 200;
    public static final int PLACE = 100;           // city, district
    public static final int SUBJECT = 200;
    public static final int REASON = 500;          // a reason given for an action
    public static final int DESCRIPTION = 500;     // voucher narration, descriptions of masters
    public static final int NOTE = 1000;           // notes, remarks, comments, decision notes

    private static final Map<String, Integer> BY_NAME = Map.ofEntries(
            Map.entry("firstname", NAME_PART), Map.entry("middlename", NAME_PART), Map.entry("lastname", NAME_PART),
            Map.entry("displayname", NAME), Map.entry("legalname", NAME), Map.entry("tradename", NAME), Map.entry("holdername", NAME),
            Map.entry("name", NAME), Map.entry("label", NAME),
            Map.entry("line1", ADDRESS_LINE), Map.entry("line2", ADDRESS_LINE), Map.entry("addressline1", ADDRESS_LINE),
            Map.entry("addressline2", ADDRESS_LINE), Map.entry("address", ADDRESS_LINE), Map.entry("landmark", ADDRESS_LINE),
            Map.entry("city", PLACE), Map.entry("district", PLACE),
            Map.entry("subject", SUBJECT),
            Map.entry("description", DESCRIPTION), Map.entry("narration", DESCRIPTION),
            Map.entry("note", NOTE), Map.entry("notes", NOTE), Map.entry("remark", NOTE), Map.entry("remarks", NOTE),
            Map.entry("comment", NOTE), Map.entry("resolutionnote", NOTE));

    private TextLimits() {}

    /** The limit for a field, or -1 when the field is not a limited free-text field. Any field named "...reason" is a reason. */
    public static int maxFor(String fieldName) {
        if (fieldName == null) return -1;
        String n = fieldName.toLowerCase(Locale.ROOT);
        if (n.endsWith("reason")) return REASON;
        return BY_NAME.getOrDefault(n, -1);
    }

    /** A message when the value is too long for the field, else null. */
    public static String violation(String fieldName, String value) {
        int max = maxFor(fieldName);
        if (max < 0 || value == null || value.length() <= max) return null;
        return fieldName + " is at most " + max + " characters";
    }
}
