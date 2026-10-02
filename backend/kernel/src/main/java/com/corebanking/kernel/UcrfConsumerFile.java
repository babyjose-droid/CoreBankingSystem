package com.corebanking.kernel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Monthly consumer credit-bureau file (US-114) in a <b>UCRF-style</b> layout: pipe-delimited text, one header record,
 * one record per loan account, one trailer record.
 *
 * <p><b>This is not a certified bureau format.</b> The four Indian credit information companies (TransUnion CIBIL,
 * Equifax, Experian, CRIF High Mark) accept data in the Uniform Credit Reporting Format, but the exact field
 * positions, lengths, code lists and file naming come from each bureau's current format specification, which is
 * given to members only. Before the first submission the layout below must be checked field by field against
 * each bureau's specification and run through that bureau's validation utility, using the tenant's membership
 * documents. Until that is done, treat this file as an extract with the right content in a documented order.
 *
 * <p>Layout (fields separated by {@code |}, records by CR LF, no quoting; a {@code |} or line break inside a value
 * is replaced by a space):
 * <pre>
 * HDR|UCRF-STYLE-CONSUMER|1.0|member code|member short name|date reported
 * ACC|member code|account number|account type|ownership|date opened|date of last payment|date closed|date reported|
 *     sanctioned amount|current balance|amount overdue|days past due|asset classification|written-off or settled status|
 *     EMI amount|repayment tenure (months)|rate of interest|consumer name|date of birth|gender|PAN|mobile|address|
 *     state code|pincode                                                        (one line; {@link #FIELDS} in order)
 * TRL|number of ACC records|total current balance|total amount overdue
 * </pre>
 * Conventions: dates are DDMMYYYY; amounts are whole rupees (half-up), never negative; days past due is capped at
 * 900; text is upper case. Codes: ownership 1 individual, 2 authorised user, 3 guarantor, 4 joint; gender 1 female,
 * 2 male, 3 transgender; asset classification STD, SMA, SUB, DBT, LSS; written-off or settled status blank (none),
 * 00 restructured, 02 written off, 03 settled; state code is the GST state code.
 */
public final class UcrfConsumerFile {

    public static final String FORMAT = "UCRF-STYLE-CONSUMER";
    public static final String VERSION = "1.0";
    public static final String LINE_END = "\r\n";

    /** The fields of an ACC record, in file order. */
    public static final List<String> FIELDS = List.of("record_type", "member_code", "account_number", "account_type", "ownership",
            "date_opened", "date_last_payment", "date_closed", "date_reported", "sanctioned_amount", "current_balance",
            "amount_overdue", "days_past_due", "asset_classification", "written_off_settled_status", "emi_amount",
            "repayment_tenure_months", "rate_of_interest", "consumer_name", "date_of_birth", "gender", "pan", "mobile", "address",
            "state_code", "pincode");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("ddMMuuuu", Locale.ROOT);
    private static final int MAX_DPD = 900;

    public record Header(String memberCode, String memberShortName, LocalDate reportedOn) {
        public Header {
            if (memberCode == null || memberCode.isBlank()) throw new IllegalArgumentException("the bureau member code is not configured");
            Objects.requireNonNull(reportedOn, "date reported");
        }
    }

    /**
     * One loan as reported. {@code assetClass} is the system's class (STANDARD, SMA0 … LOSS); {@code status} the
     * loan status (ACTIVE, FROZEN, CLOSED, CANCELLED, WRITTEN_OFF); {@code gender} FEMALE, MALE or OTHER.
     */
    public record Account(String accountNo, String accountType, String ownership, LocalDate dateOpened, LocalDate dateLastPayment,
                          LocalDate dateClosed, BigDecimal sanctionedAmount, BigDecimal currentBalance, BigDecimal amountOverdue,
                          int daysPastDue, String assetClass, String status, boolean restructured, BigDecimal emi, int tenureMonths,
                          BigDecimal ratePercent, String name, LocalDate dateOfBirth, String gender, String pan, String mobile,
                          String address, String stateCode, String pincode) {}

    /** An account left out of the file, and why. The account number is the only identifier kept. */
    public record Rejection(String accountNo, String reason) {}

    public record Result(String text, int accepted, List<Rejection> rejected, BigDecimal totalCurrentBalance, BigDecimal totalAmountOverdue) {}

    private UcrfConsumerFile() {}

    /**
     * Builds the file. An account that fails a check is left out and listed in {@link Result#rejected()} so it can
     * be corrected and reported in the next cycle; it never produces a malformed line.
     */
    public static Result format(Header h, List<Account> accounts) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("|", "HDR", FORMAT, VERSION, clean(h.memberCode(), 30), clean(h.memberShortName(), 40), DATE.format(h.reportedOn())))
                .append(LINE_END);
        List<Rejection> rejected = new ArrayList<>();
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        int n = 0;
        for (Account a : accounts) {
            String problem = problem(a, h.reportedOn());
            if (problem != null) {
                rejected.add(new Rejection(a.accountNo(), problem));
                continue;
            }
            BigDecimal bal = rupees(closed(a) ? BigDecimal.ZERO : a.currentBalance());
            BigDecimal od = rupees(closed(a) ? BigDecimal.ZERO : a.amountOverdue());
            List<String> f = new ArrayList<>(FIELDS.size());
            f.add("ACC");
            f.add(clean(h.memberCode(), 30));
            f.add(clean(a.accountNo(), 25));
            f.add(clean(a.accountType(), 2));
            f.add(a.ownership() == null || a.ownership().isBlank() ? "1" : clean(a.ownership(), 1));
            f.add(DATE.format(a.dateOpened()));
            f.add(date(a.dateLastPayment()));
            f.add(date(a.dateClosed()));
            f.add(DATE.format(h.reportedOn()));
            f.add(rupees(a.sanctionedAmount()).toPlainString());
            f.add(bal.toPlainString());
            f.add(od.toPlainString());
            f.add(String.valueOf(closed(a) ? 0 : Math.min(Math.max(a.daysPastDue(), 0), MAX_DPD)));
            f.add(assetCode(a.assetClass()));
            f.add(statusCode(a));
            f.add(a.emi() == null ? "" : rupees(a.emi()).toPlainString());
            f.add(String.valueOf(a.tenureMonths()));
            f.add(a.ratePercent() == null ? "" : a.ratePercent().setScale(2, RoundingMode.HALF_UP).toPlainString());
            f.add(clean(a.name(), 100));
            f.add(DATE.format(a.dateOfBirth()));
            f.add(genderCode(a.gender()));
            f.add(a.pan() == null ? "" : a.pan().trim().toUpperCase(Locale.ROOT));
            f.add(mobile(a.mobile()));
            f.add(clean(a.address(), 200));
            f.add(clean(a.stateCode(), 2));
            f.add(a.pincode());
            if (f.size() != FIELDS.size()) throw new IllegalStateException("ACC record has " + f.size() + " fields, layout has " + FIELDS.size());
            sb.append(String.join("|", f)).append(LINE_END);
            balance = balance.add(bal);
            overdue = overdue.add(od);
            n++;
        }
        sb.append(String.join("|", "TRL", String.valueOf(n), balance.toPlainString(), overdue.toPlainString())).append(LINE_END);
        return new Result(sb.toString(), n, List.copyOf(rejected), balance, overdue);
    }

    /** The reason an account cannot be reported, or null when it can. */
    static String problem(Account a, LocalDate reportedOn) {
        if (blank(a.accountNo())) return "account number is missing";
        if (blank(a.accountType())) return "account type code is not mapped for the product";
        if (blank(a.name())) return "borrower name is missing";
        if (a.dateOpened() == null) return "date opened is missing";
        if (a.dateOpened().isAfter(reportedOn)) return "date opened is after the date reported";
        if (a.dateOfBirth() == null) return "date of birth is missing";
        if (!a.dateOfBirth().isBefore(a.dateOpened())) return "date of birth is not before the date opened";
        if (a.sanctionedAmount() == null || a.sanctionedAmount().signum() <= 0) return "sanctioned amount is missing";
        boolean pan = !blank(a.pan());
        if (pan && !a.pan().trim().toUpperCase(Locale.ROOT).matches("[A-Z]{5}[0-9]{4}[A-Z]")) return "PAN is not in the format AAAAA9999A";
        boolean mobile = !mobile(a.mobile()).isEmpty();
        if (!blank(a.mobile()) && !mobile) return "mobile number is not a 10-digit Indian mobile number";
        if (!pan && !mobile) return "neither PAN nor mobile number is available";
        if (blank(a.address())) return "address is missing";
        if (blank(a.stateCode()) || !a.stateCode().trim().matches("[0-9]{2}")) return "state code is missing or not a 2-digit code";
        if (a.pincode() == null || !a.pincode().matches("[1-9][0-9]{5}")) return "pincode is missing or not 6 digits";
        if (closed(a) && a.dateClosed() == null) return "a closed account has no closure date";
        if (assetCode(a.assetClass()).isEmpty()) return "asset classification " + a.assetClass() + " is not known";
        return null;
    }

    private static boolean closed(Account a) {
        return "CLOSED".equals(a.status()) || "CANCELLED".equals(a.status());
    }

    /** STANDARD → STD; SMA0, SMA1, SMA2 → SMA; SUBSTANDARD → SUB; DOUBTFUL1–3 → DBT; LOSS → LSS. */
    static String assetCode(String assetClass) {
        if (assetClass == null) return "";
        if (assetClass.equals("STANDARD")) return "STD";
        if (assetClass.startsWith("SMA")) return "SMA";
        if (assetClass.equals("SUBSTANDARD")) return "SUB";
        if (assetClass.startsWith("DOUBTFUL")) return "DBT";
        if (assetClass.equals("LOSS")) return "LSS";
        return "";
    }

    private static String statusCode(Account a) {
        if ("WRITTEN_OFF".equals(a.status())) return "02";
        return a.restructured() ? "00" : "";
    }

    private static String genderCode(String gender) {
        if (gender == null) return "";
        return switch (gender) {
            case "FEMALE" -> "1";
            case "MALE" -> "2";
            case "OTHER" -> "3";
            default -> "";
        };
    }

    /** Ten digits starting 6–9, after removing a +91 / 0 prefix and separators; empty when it is not a mobile number. */
    static String mobile(String m) {
        if (m == null) return "";
        String d = m.replaceAll("\\D", "");
        if (d.length() == 12 && d.startsWith("91")) d = d.substring(2);
        if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        return d.matches("[6-9][0-9]{9}") ? d : "";
    }

    private static BigDecimal rupees(BigDecimal v) {
        if (v == null || v.signum() < 0) return BigDecimal.ZERO;
        return v.setScale(0, RoundingMode.HALF_UP);
    }

    private static String date(LocalDate d) {
        return d == null ? "" : DATE.format(d);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Upper case, delimiter and control characters replaced by a space, runs of spaces collapsed, cut to {@code max}. */
    static String clean(String s, int max) {
        if (s == null) return "";
        String v = s.replaceAll("[|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        return v.length() > max ? v.substring(0, max).trim() : v;
    }
}
