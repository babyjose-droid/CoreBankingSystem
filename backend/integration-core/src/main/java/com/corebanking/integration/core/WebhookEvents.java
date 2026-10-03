package com.corebanking.integration.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The events a tenant can subscribe to (US-121) and what their payloads may contain.
 * <p>
 * A payload is built from an allow-list of fields per event type: a field that is not on the list is dropped, so
 * new data never leaks into webhooks by accident. On top of that every text value is scanned: anything shaped
 * like a PAN or an Aadhaar number is replaced by {@value #REDACTED}, and a field whose name says it holds an
 * account number is sent only in masked form (last four characters). Subscribers get identifiers (loan number,
 * customer id) and fetch personal data through the API, under their own permissions.
 */
public final class WebhookEvents {

    public static final String LOAN_DISBURSED = "loan.disbursed";
    public static final String PAYMENT_RECEIVED = "payment.received";
    public static final String PAYMENT_BOUNCED = "payment.bounced";
    public static final String LOAN_CLOSED = "loan.closed";
    public static final String LOAN_NPA = "loan.npa";
    public static final String MANDATE_STATUS = "mandate.status";
    public static final String PAYOUT_STATUS = "payout.status";

    public static final String REDACTED = "[redacted]";

    private static final Map<String, Set<String>> FIELDS = Map.of(
            LOAN_DISBURSED, Set.of("loanId", "loanNo", "customerId", "externalRef", "branch", "amount", "netDisbursed",
                    "businessDate", "transactionId", "trancheNo"),
            PAYMENT_RECEIVED, Set.of("loanId", "loanNo", "customerId", "externalRef", "branch", "amount", "valueDate",
                    "businessDate", "transactionId", "kind", "channel", "utr", "loanStatus"),
            PAYMENT_BOUNCED, Set.of("loanId", "loanNo", "customerId", "externalRef", "amount", "dueDate", "businessDate",
                    "returnCode", "returnReason", "attempt", "mandateRef", "bounceCharge", "representOn"),
            LOAN_CLOSED, Set.of("loanId", "loanNo", "customerId", "externalRef", "branch", "businessDate", "closure"),
            LOAN_NPA, Set.of("loanId", "loanNo", "customerId", "externalRef", "branch", "businessDate", "assetClass",
                    "npaSince", "dpd"),
            MANDATE_STATUS, Set.of("mandateRef", "loanId", "loanNo", "customerId", "status", "previousStatus", "umrn",
                    "rejectCode", "rejectReason", "debitAccountMasked"),
            PAYOUT_STATUS, Set.of("payoutRef", "loanId", "loanNo", "customerId", "externalRef", "status", "previousStatus",
                    "amount", "utr", "failureCode", "failureReason", "beneficiaryAccountMasked", "action"));

    /** Every event type, in a stable order for documentation and validation messages. */
    public static final List<String> TYPES = List.of(LOAN_DISBURSED, PAYMENT_RECEIVED, PAYMENT_BOUNCED, LOAN_CLOSED,
            LOAN_NPA, MANDATE_STATUS, PAYOUT_STATUS);

    private static final Pattern PAN = Pattern.compile("(?<![A-Za-z0-9])[A-Za-z]{5}[0-9]{4}[A-Za-z](?![A-Za-z0-9])");
    private static final Pattern TWELVE_DIGITS = Pattern.compile("(?<![0-9])[2-9][0-9]{3}[ -]?[0-9]{4}[ -]?[0-9]{4}(?![0-9])");
    private static final Pattern MASKED_ACCOUNT = Pattern.compile("[Xx*]{0,30}[A-Za-z0-9]{0,4}");
    /** Bank references that are 12 digits by design (a UPI RRN) and would otherwise look like an Aadhaar number. */
    private static final Set<String> REFERENCE_FIELDS = Set.of("utr");

    private WebhookEvents() {}

    public static boolean isType(String type) {
        return FIELDS.containsKey(type);
    }

    public static Set<String> fields(String type) {
        Set<String> f = FIELDS.get(type);
        if (f == null) throw new IllegalArgumentException("unknown event type " + type);
        return f;
    }

    /** The payload that may leave: allowed fields only, null values left out, sensitive-looking text redacted. */
    public static Map<String, Object> data(String type, Map<String, ?> source) {
        Set<String> allowed = fields(type);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : source.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            if (!allowed.contains(key) || v == null) continue;
            if (v instanceof Map<?, ?> || v instanceof Iterable<?>) continue;         // payloads are flat
            if (v instanceof String s) {
                if (key.toLowerCase(java.util.Locale.ROOT).contains("account")) {
                    out.put(key, MASKED_ACCOUNT.matcher(s).matches() ? s : REDACTED);
                } else {
                    out.put(key, REFERENCE_FIELDS.contains(key) ? redactPan(s) : redact(s));
                }
            } else {
                out.put(key, v);
            }
        }
        return out;
    }

    /**
     * The body that is signed and sent.
     *
     * @param occurredAt RFC 3339 UTC timestamp of the event
     */
    public static String envelope(String eventId, String type, String tenant, String occurredAt, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", eventId);
        m.put("type", type);
        m.put("apiVersion", "v1");
        m.put("tenant", tenant);
        m.put("occurredAt", occurredAt);
        m.put("data", data);
        return MiniJson.write(m);
    }

    /** True when the text contains something shaped like a PAN or a valid Aadhaar number. */
    public static boolean looksSensitive(String text) {
        return text != null && !redact(text).equals(text);
    }

    static String redact(String s) {
        String t = redactPan(s);
        Matcher m = TWELVE_DIGITS.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            m.appendReplacement(sb, verhoeffValid(digits) ? Matcher.quoteReplacement(REDACTED) : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String redactPan(String s) {
        return PAN.matcher(s).replaceAll(Matcher.quoteReplacement(REDACTED));
    }

    // Verhoeff checksum (the check digit of an Aadhaar number), so that an ordinary 12-digit reference is not redacted.
    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    static boolean verhoeffValid(String digits) {
        int c = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            c = D[c][P[i % 8][digit]];
        }
        return c == 0;
    }
}
