package com.corebanking.deposits.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.customer.PiiKeys;
import com.corebanking.deposits.DepositDocuments;
import com.corebanking.deposits.DepositDocuments.Account;
import com.corebanking.deposits.DepositDocuments.CertificateRow;
import com.corebanking.deposits.DepositDocuments.Holder;
import com.corebanking.deposits.DepositDocuments.Institution;
import com.corebanking.deposits.DepositDocuments.InterestCertificate;
import com.corebanking.deposits.DepositDocuments.Quarter;
import com.corebanking.deposits.DepositDocuments.Receipt;
import com.corebanking.deposits.DepositDocuments.Render;
import com.corebanking.deposits.DepositDocuments.Statement;
import com.corebanking.deposits.DepositDocuments.StatementLine;
import com.corebanking.deposits.DepositRules;
import com.corebanking.deposits.TdsOnInterest;
import com.corebanking.kernel.DocumentKey;
import com.corebanking.kernel.Inr;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Depositor documents as PDF (P4-7): the statement of a savings, current, term or recurring deposit account for a
 * period, the receipt of a funded term or recurring deposit, and the certificate of interest and tax deducted for a
 * tax year. This class gathers the data from the books; {@link DepositDocuments} lays it out.
 *
 * <ul>
 *   <li>A document carries personal data (names and the first holder's address in full; PAN masked), so every
 *       generation is written to the audit log: who, which account or depositor, which document.</li>
 *   <li>A statement is built from the movements, and refused if they do not lead from the opening to the closing
 *       balance (the books' own balance after each movement is checked as well).</li>
 *   <li>The line on deposit insurance follows the rule set: at a bank, the DICGC cover from the tenant setting
 *       {@code deposits.dicgc.cover} (printed as "[not configured]" until it is set); at a deposit-taking NBFC, that the
 *       deposit is not insured (NBFC Directions para 31(9)).</li>
 * </ul>
 */
@Service
public class DepositDocumentService {

    /** A generated file and the name to download it under. */
    public record Document(String fileName, byte[] content) {}

    private static final Map<String, String> KIND = Map.ofEntries(
            Map.entry("CASH_DEPOSIT", "Cash deposit"), Map.entry("CASH_WITHDRAWAL", "Cash withdrawal"),
            Map.entry("BANK_TRANSFER_IN", "Transfer from another bank"), Map.entry("BANK_TRANSFER_OUT", "Transfer to another bank"),
            Map.entry("TRANSFER_IN", "Transfer in"), Map.entry("TRANSFER_OUT", "Transfer out"), Map.entry("FUNDING", "Deposit funded"),
            Map.entry("FUNDING_PAID", "Paid to fund a deposit"), Map.entry("REVERSAL", "Reversal"), Map.entry("INTEREST_CREDIT", "Interest credited"),
            Map.entry("INTEREST_PAYOUT", "Interest paid out"), Map.entry("TAX_DEDUCTED", "Tax deducted at source"), Map.entry("CHARGE", "Charge"),
            Map.entry("CLOSURE", "Closure"), Map.entry("INTEREST_RECOVERY", "Interest recovered"), Map.entry("RENEWAL", "Renewed"),
            Map.entry("RD_INSTALMENT", "Instalment"), Map.entry("RD_LATE_CHARGE", "Late instalment charge"), Map.entry("LOAN_REPAYMENT", "Loan repayment"),
            Map.entry("CHEQUE_PAID", "Cheque paid"), Map.entry("CHEQUE_DEPOSIT", "Cheque deposited"),
            Map.entry("UNCLAIMED_TRANSFER", "Transferred as unclaimed (DEA Fund)"), Map.entry("UNCLAIMED_REFUND", "Unclaimed balance refunded"));
    private static final Map<String, String> TYPE = Map.of("SAVINGS", "Savings account", "CURRENT", "Current account",
            "FD_CUMULATIVE", "Fixed deposit (cumulative)", "FD_PAYOUT", "Fixed deposit (interest paid out)", "RD", "Recurring deposit");
    private static final Map<String, String> AT_MATURITY = Map.of("PAY_OUT", "Paid out", "RENEW_PRINCIPAL", "The principal renewed for the same period; interest paid out",
            "RENEW_WITH_INTEREST", "Renewed with interest for the same period");
    private static final Map<String, String> SOURCE = Map.of("ACCOUNT", "Transfer from account", "CASH", "Cash", "BANK_TRANSFER", "Bank transfer",
            "GATEWAY", "Payment link");

    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final BusinessDays days;
    private final BranchScope scope;
    private final PiiKeys keys;
    private final DepositRuleSets rules;
    private final DepositMoneyService money;
    private final DepositAccountService accounts;

    DepositDocumentService(JdbcTemplate jdbc, AuditLog audit, BusinessDays days, BranchScope scope, PiiKeys keys, DepositRuleSets rules,
                           DepositMoneyService money, DepositAccountService accounts) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.days = days;
        this.scope = scope;
        this.keys = keys;
        this.rules = rules;
        this.money = money;
        this.accounts = accounts;
    }

    // ================================================================================================ statement

    /**
     * The statement of an account for a period: by default from the first day of the business date's month (or the day
     * the account was opened) to the business date. Not past the business date; at most two years.
     */
    @Transactional(readOnly = true)
    public Document statement(String family, UUID accountId, LocalDate from, LocalDate to) {
        DepositMoneyService.Account a = money.account(accountId, family, true);
        Map<String, Object> row = accountRow(a.id());
        LocalDate today = days.current().businessDate();
        LocalDate opened = DepositEod.date(row.get("opened_on"));
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? (opened.isAfter(today.withDayOfMonth(1)) ? opened : today.withDayOfMonth(1)) : from;
        List<String> problems = new ArrayList<>();
        if (end.isAfter(today)) problems.add("the statement cannot run past the business date (" + today + ")");
        if (start.isAfter(end)) problems.add("'from' is after 'to'");
        else if (start.plusYears(2).isBefore(end)) problems.add("a statement covers at most two years; ask for it in parts");
        DepositAccountService.refuseIfAny("the statement cannot be made", problems);

        BigDecimal opening = balanceAsAt(a.id(), start.minusDays(1));
        BigDecimal closing = balanceAsAt(a.id(), end);
        List<StatementLine> lines = jdbc.query("""
                SELECT m.business_date, m.value_date, m.kind, m.narration, m.reference, m.direction, m.amount::numeric, m.balance_after::numeric,
                       cp.account_no AS counterparty, m.reversal_reason, m.party_account_last4
                  FROM deposits.movement m LEFT JOIN deposits.account cp ON cp.id = m.counterparty_id
                 WHERE m.account_id = ? AND m.business_date BETWEEN ? AND ? ORDER BY m.seq
                """, (rs, i) -> {
                    String kind = rs.getString(3);
                    String narration = rs.getString(4);
                    StringBuilder p = new StringBuilder(KIND.getOrDefault(kind, kind));
                    if (rs.getString(9) != null) p.append(" (").append("DEBIT".equals(rs.getString(6)) ? "to " : "from ").append(rs.getString(9)).append(')');
                    if (rs.getString(11) != null) p.append(" (account ending ").append(rs.getString(11)).append(')');
                    if (narration != null && !narration.isBlank() && !narration.equals(p.toString())) p.append(": ").append(narration.trim());
                    if (rs.getString(10) != null) p.append(": ").append(rs.getString(10));
                    BigDecimal amount = rs.getBigDecimal(7);
                    boolean debit = "DEBIT".equals(rs.getString(6));
                    return new StatementLine(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class), p.toString(), rs.getString(5),
                            debit ? amount : null, debit ? null : amount, rs.getBigDecimal(8));
                }, a.id(), start, end);
        BigDecimal held = end.equals(today) ? jdbc.queryForObject("""
                SELECT coalesce(sum(amount), 0)::numeric FROM deposits.account_hold WHERE account_id = ? AND status = 'ACTIVE' AND kind = 'LIEN'
                """, BigDecimal.class, a.id()) : null;
        String rate = DepositProductService.CASA.equals(a.family()) ? null : jdbc.queryForObject(
                "SELECT rate_percent::numeric FROM deposits.term_deposit WHERE account_id = ?", BigDecimal.class, a.id()).stripTrailingZeros().toPlainString()
                + "% a year";
        Statement s = new Statement(institution(), holders(a.id()), account(row), start, end, today, opening, closing, lines, held, rate);
        byte[] pdf = DepositDocuments.statement(s, Render.DEFAULT);
        audited(a.accountNo(), "STATEMENT", Map.of("from", start.toString(), "to", end.toString()));
        return new Document(DocumentKey.safeFileName("statement-" + a.accountNo() + "-" + start + "-" + end + ".pdf"), pdf);
    }

    // ================================================================================================ receipt

    /** The receipt of a funded term or recurring deposit (409 while it waits for its funds). */
    @Transactional(readOnly = true)
    public Document receipt(UUID depositId) {
        DepositMoneyService.Account a = money.account(depositId, DepositProductService.TERM_DEPOSIT, true);
        Map<String, Object> view = accounts.get(DepositProductService.TERM_DEPOSIT, a.id());
        @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) view.get("termDeposit");
        if (t == null || !Boolean.TRUE.equals(t.get("funded"))) {
            throw ApiException.conflict("deposit " + a.accountNo() + " is not funded: its receipt is issued once its funds are received");
        }
        Map<String, Object> row = accountRow(a.id());
        boolean recurring = Boolean.TRUE.equals(t.get("recurring"));
        LocalDate start = date(t.get("startDate"));
        int months = ((Number) t.get("tenureMonths")).intValue();
        int extraDays = ((Number) t.get("tenureDays")).intValue();
        String tenure = (months > 0 ? months + (months == 1 ? " month" : " months") : "") + (months > 0 && extraDays > 0 ? " and " : "")
                + (extraDays > 0 ? extraDays + (extraDays == 1 ? " day" : " days") : "");
        int period = ((Number) t.get("interestPeriodMonths")).intValue();
        String every = switch (period) {
            case 1 -> "monthly";
            case 3 -> "quarterly";
            case 6 -> "half-yearly";
            case 12 -> "yearly";
            default -> "every " + period + " months";
        };
        String payTo = "CASA".equals(t.get("payoutTo")) ? "account " + t.get("payoutAccountNo")
                : t.get("payoutBankAccountMasked") == null ? "the depositor's bank account" : "bank account " + t.get("payoutBankAccountMasked");
        String interest = recurring || Boolean.TRUE.equals(t.get("cumulative")) ? "Compounded " + every + " and paid at maturity"
                : "Paid " + every + " to " + payTo;
        String atMaturity = AT_MATURITY.getOrDefault(String.valueOf(t.get("maturityInstruction")), String.valueOf(t.get("maturityInstruction")))
                + ("PAY_OUT".equals(t.get("maturityInstruction")) ? " to " + payTo : "");
        String funded = SOURCE.getOrDefault(String.valueOf(t.get("fundingSource")), String.valueOf(t.get("fundingSource")))
                + (t.get("fundingAccountNo") == null ? "" : " " + t.get("fundingAccountNo"))
                + (t.get("fundingReference") == null ? "" : ", reference " + t.get("fundingReference"));
        List<String> pledged = jdbc.queryForList("""
                SELECT coalesce('Pledged for loan ' || l.loan_no, 'Lien of Rs. ' || h.amount::numeric || ' (' || h.reason || ')')
                  FROM deposits.account_hold h LEFT JOIN lending.loan_account l ON l.id = h.loan_id
                 WHERE h.account_id = ? AND h.status = 'ACTIVE' AND h.kind = 'LIEN' ORDER BY h.placed_on
                """, String.class, a.id());
        BigDecimal principal = new BigDecimal(String.valueOf(t.get("principal")));
        Receipt r = new Receipt(institution(), holders(a.id()), account(row), a.accountNo() + "/" + start, days.current().businessDate(),
                principal, recurring ? principal : null, new BigDecimal(String.valueOf(t.get("ratePercent"))), tenure, start,
                date(t.get("maturityDate")), new BigDecimal(String.valueOf(t.get("maturityValue"))), interest, recurring ? "Paid out to " + payTo : atMaturity,
                funded, pledged.isEmpty() ? null : String.join("; ", pledged));
        byte[] pdf = DepositDocuments.receipt(r, Render.DEFAULT);
        audited(a.accountNo(), "RECEIPT", Map.of());
        return new Document(DocumentKey.safeFileName("receipt-" + a.accountNo() + ".pdf"), pdf);
    }

    // ================================================================================================ interest certificate

    /**
     * The certificate of interest paid or credited, and tax deducted, on a depositor's accounts (where they are the
     * first holder) in a tax year: the current one by default. Only accounts in the caller's branches are included.
     */
    @Transactional(readOnly = true)
    public Document interestCertificate(UUID customerId, Integer taxYear) {
        if (customerId == null) throw ApiException.invalid("customerId is required");
        List<Map<String, Object>> found = jdbc.queryForList("SELECT home_branch, pan_last4 FROM customer.customer WHERE id = ?", customerId);
        if (found.isEmpty()) throw ApiException.notFound("customer " + customerId);
        scope.requireRecord((String) found.get(0).get("home_branch"), "customer " + customerId);
        LocalDate today = days.current().businessDate();
        int year = taxYear == null ? TdsOnInterest.taxYearStarting(today) : taxYear;
        LocalDate from = LocalDate.of(year, 4, 1);
        LocalDate to = LocalDate.of(year + 1, 3, 31);
        if (from.isAfter(today)) throw ApiException.invalid("the tax year " + label(year) + " has not begun");
        LocalDate upto = to.isAfter(today) ? today : to;
        List<CertificateRow> rows = new ArrayList<>();
        BigDecimal[] qi = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal[] qt = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Map<String, Object> x : jdbc.queryForList("""
                SELECT a.id, a.account_no, a.branch_code, p.product_type
                  FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code
                 WHERE a.customer_id = ? ORDER BY a.account_no
                """, customerId)) {
            if (!scope.canSee((String) x.get("branch_code"))) continue;
            UUID id = (UUID) x.get("id");
            BigDecimal interest = BigDecimal.ZERO;
            BigDecimal tax = BigDecimal.ZERO;
            for (Map<String, Object> q : jdbc.queryForList("""
                    SELECT ((extract(month FROM m.business_date)::int + 8) % 12) / 3 + 1 AS q,
                           sum(CASE m.kind WHEN 'INTEREST_CREDIT' THEN m.amount ELSE -m.amount END)::numeric AS amount
                      FROM deposits.movement m
                     WHERE m.account_id = ? AND m.kind IN ('INTEREST_CREDIT','INTEREST_RECOVERY') AND m.reversed_by IS NULL
                       AND m.business_date BETWEEN ? AND ? GROUP BY 1
                    """, id, from, upto)) {
                int n = ((Number) q.get("q")).intValue() - 1;
                qi[n] = qi[n].add((BigDecimal) q.get("amount"));
                interest = interest.add((BigDecimal) q.get("amount"));
            }
            for (Map<String, Object> q : jdbc.queryForList("""
                    SELECT ((extract(month FROM t.business_date)::int + 8) % 12) / 3 + 1 AS q, sum(t.tax)::numeric AS amount
                      FROM deposits.tds_ledger t WHERE t.account_id = ? AND t.business_date BETWEEN ? AND ? GROUP BY 1
                    """, id, from, upto)) {
                int n = ((Number) q.get("q")).intValue() - 1;
                qt[n] = qt[n].add((BigDecimal) q.get("amount"));
                tax = tax.add((BigDecimal) q.get("amount"));
            }
            if (interest.signum() != 0 || tax.signum() != 0) {
                rows.add(new CertificateRow((String) x.get("account_no"), TYPE.getOrDefault((String) x.get("product_type"), (String) x.get("product_type")),
                        interest, tax));
            }
        }
        List<Quarter> quarters = new ArrayList<>();
        for (int q = 0; q < 4; q++) quarters.add(new Quarter(q + 1, qi[q], qt[q]));
        List<String> decl = jdbc.queryForList("""
                SELECT 'Form 121 received on ' || to_char(received_on, 'DD-Mon-YYYY') || coalesce(' (reference ' || reference || ')', '')
                  FROM deposits.tds_declaration WHERE customer_id = ? AND tax_year = ? AND status = 'ACTIVE'
                """, String.class, customerId, year);
        Holder depositor = holder(customerId, true);
        Object last4 = found.get(0).get("pan_last4");
        InterestCertificate c = new InterestCertificate(institution(), depositor, last4 == null ? null : "XXXXX" + last4 + "X", label(year), from, to,
                today, rows, quarters, decl.isEmpty() ? null : decl.get(0));
        byte[] pdf = DepositDocuments.interestCertificate(c, Render.DEFAULT);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("document", "INTEREST_CERTIFICATE");
        detail.put("taxYear", label(year));
        audit.record(CurrentUser.username(), "DOCUMENT_GENERATED", "CUSTOMER", depositor.customerNo(), detail);
        return new Document(DocumentKey.safeFileName("interest-certificate-" + depositor.customerNo() + "-" + label(year) + ".pdf"), pdf);
    }

    // ================================================================================================ shared

    private Map<String, Object> accountRow(UUID id) {
        return jdbc.queryForMap("""
                SELECT a.account_no, a.status, a.opened_on, a.operating_mode, a.nomination_mode, p.name AS product_name, p.product_type,
                       b.name AS branch_name, (SELECT count(*) FROM deposits.nominee n WHERE n.account_id = a.id AND n.status = 'ACTIVE') AS nominees
                  FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code JOIN platform.branch b ON b.code = a.branch_code
                 WHERE a.id = ?
                """, id);
    }

    private static Account account(Map<String, Object> r) {
        long n = ((Number) r.get("nominees")).longValue();
        String nomination = "NONE".equals(r.get("nomination_mode")) || n == 0 ? "No nominee" : "Registered: " + n + (n == 1 ? " nominee" : " nominees");
        String mode = String.valueOf(r.get("operating_mode")).replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        String status = String.valueOf(r.get("status"));
        return new Account((String) r.get("account_no"), TYPE.getOrDefault((String) r.get("product_type"), (String) r.get("product_type")),
                (String) r.get("product_name"), (String) r.get("branch_name"), Character.toUpperCase(mode.charAt(0)) + mode.substring(1),
                DepositEod.date(r.get("opened_on")), status.charAt(0) + status.substring(1).toLowerCase(java.util.Locale.ROOT), nomination);
    }

    /** The holders in order; the first with the address in full. */
    private List<Holder> holders(UUID accountId) {
        List<UUID> ids = jdbc.queryForList("SELECT customer_id FROM deposits.account_holder WHERE account_id = ? ORDER BY holder_no", UUID.class, accountId);
        List<Holder> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) out.add(holder(ids.get(i), i == 0));
        return out;
    }

    private Holder holder(UUID customerId, boolean withAddress) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT c.customer_no, c.display_name, a.line_cipher, a.city, platform.state_name(a.state_code) AS state_name, a.pincode
                  FROM customer.customer c LEFT JOIN customer.address a ON a.customer_id = c.id AND a.address_type = 'COMMUNICATION'
                 WHERE c.id = ? LIMIT 1
                """, customerId);
        String address = null;
        if (withAddress) {
            List<String> parts = new ArrayList<>();
            if (r.get("line_cipher") instanceof byte[] cipher) {
                String line = keys.forTenant(CurrentUser.requireTenant()).decrypt(cipher, "customer.address");
                if (line != null && !line.isBlank()) parts.add(line.trim());
            }
            for (String col : List.of("city", "state_name", "pincode")) {
                if (r.get(col) != null && !String.valueOf(r.get(col)).isBlank()) parts.add(String.valueOf(r.get(col)));
            }
            address = parts.isEmpty() ? null : String.join(", ", parts);
        }
        return new Holder((String) r.get("display_name"), (String) r.get("customer_no"), address);
    }

    /** The institution, with the line on deposit insurance its rule set calls for. */
    private Institution institution() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT legal_name, cin, rbi_registration FROM platform.legal_entity WHERE id = 1");
        Map<String, Object> e = rows.isEmpty() ? Map.of() : rows.get(0);
        Map<String, String> props = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT key, value FROM platform.system_property WHERE key IN ('lender.name', 'lender.registered-address', 'deposits.dicgc.cover')")) {
            props.put(String.valueOf(r.get("key")), String.valueOf(r.get("value")));
        }
        DepositRules r = rules.current();
        boolean insured = r.number(DepositRules.INSURED).map(v -> v.signum() > 0).orElse(r.kind() == DepositRules.Kind.BANK);
        String disclosure;
        if (!insured) {
            disclosure = "This deposit is not insured. The Reserve Bank of India does not guarantee the repayment of deposits accepted by NBFCs.";
        } else {
            String cover = props.get("deposits.dicgc.cover");
            String amount;
            try {
                amount = cover == null || cover.isBlank() ? DepositDocuments.NOT_CONFIGURED : Inr.rs(new BigDecimal(cover.trim()));
            } catch (NumberFormatException x) {
                amount = DepositDocuments.NOT_CONFIGURED;
            }
            disclosure = "Deposits are insured by the Deposit Insurance and Credit Guarantee Corporation (DICGC) up to " + amount
                    + " per depositor, as DICGC's rules provide.";
        }
        String name = e.get("legal_name") == null ? props.getOrDefault("lender.name", "[institution name not configured]") : String.valueOf(e.get("legal_name"));
        return new Institution(name, props.get("lender.registered-address"), (String) e.get("cin"), (String) e.get("rbi_registration"), disclosure);
    }

    private BigDecimal balanceAsAt(UUID id, LocalDate d) {
        return jdbc.queryForObject("SELECT deposits.balance_as_at(?, ?)", BigDecimal.class, id, d);
    }

    private void audited(String accountNo, String document, Map<String, String> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("document", document);
        detail.putAll(extra);
        audit.record(CurrentUser.username(), "DOCUMENT_GENERATED", "DEPOSIT_ACCOUNT", accountNo, detail);
    }

    static String label(int year) {
        return year + "-" + String.valueOf(year + 1).substring(2);
    }

    private static LocalDate date(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
    }
}
