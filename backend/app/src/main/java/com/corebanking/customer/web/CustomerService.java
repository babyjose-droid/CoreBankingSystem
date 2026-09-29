package com.corebanking.customer.web;

import com.corebanking.customer.PiiKeys;
import com.corebanking.kernel.Dedupe;
import com.corebanking.kernel.Masking;
import com.corebanking.kernel.PiiCipher;
import com.corebanking.ledger.NumberSeries;
import com.corebanking.ledger.NumberSeriesService;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.Json;
import com.corebanking.platform.CurrentUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer creation with dedupe and maker-checker (US-028, US-029, US-030). Personal data is sealed (encrypted)
 * inside the approval payload so it is never stored in clear, and the checker sees only masked values.
 */
@Service
class CustomerService {

    record Address(String line1, String line2, String city, String stateCode, String pincode) {}
    record Input(String customerType, String firstName, String middleName, String lastName, LocalDate dateOfBirth,
                 String gender, String pan, String mobile, String email, String homeBranch, Address address,
                 Boolean overrideDedupe, String overrideReason) {}
    record Match(UUID customerId, String customerNo, String displayName, String rule, String strength) {}
    /** Outcome of a create: either the existing customer (same PAN) or a pending approval. */
    record Created(Map<String, Object> existingCustomer, ApprovalRequest approval) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final PiiKeys keys;
    private final Json json;
    private final BranchScope scope;

    CustomerService(JdbcTemplate jdbc, ApprovalService approvals, PiiKeys keys, Json json, BranchScope scope) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.keys = keys;
        this.json = json;
        this.scope = scope;
    }

    static void validate(Input in) {
        if (in.customerType() == null || !in.customerType().matches("INDIVIDUAL|NON_INDIVIDUAL")) {
            throw ApiException.invalid("customerType must be INDIVIDUAL or NON_INDIVIDUAL");
        }
        if (in.firstName() == null || in.firstName().isBlank()) throw ApiException.invalid("firstName is required");
        if (in.dateOfBirth() == null) throw ApiException.invalid("dateOfBirth is required");
        if (in.homeBranch() == null) throw ApiException.invalid("homeBranch is required");
        if (in.mobile() == null) throw ApiException.invalid("mobile is required");
        Dedupe.normaliseMobile(in.mobile());
        if (in.pan() != null && !in.pan().isBlank()) Dedupe.normalisePan(in.pan());
        if ("INDIVIDUAL".equals(in.customerType()) && Period.between(in.dateOfBirth(), LocalDate.now()).getYears() < 18) {
            throw ApiException.invalid("an individual customer must be at least 18 years old");
        }
        if (in.address() != null && in.address().pincode() != null && !in.address().pincode().matches("[1-9][0-9]{5}")) {
            throw ApiException.invalid("pincode must be 6 digits");
        }
    }

    List<Match> duplicates(Input in) {
        validate(in);
        Hashes h = hashes(keys.forTenant(CurrentUser.requireTenant()), in);
        // Dedupe looks across every branch (a duplicate elsewhere still matters), but only matches inside the
        // caller's branch scope carry the customer's number and name; others show just the rule and strength.
        return jdbc.query("""
                SELECT d.customer_id, d.customer_no, d.display_name, d.rule, d.strength,
                       c.home_branch IN (SELECT branch_code FROM platform.visible_branches(?)) AS visible
                  FROM customer.find_duplicates(?, ?, ?) d JOIN customer.customer c ON c.id = d.customer_id
                """, (rs, i) -> rs.getBoolean(6)
                        ? new Match(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))
                        : new Match(null, null, null, rs.getString(4), rs.getString(5)),
                scope.user(), h.pan(), h.mobile(), h.nameDob());
    }

    @Transactional
    Created create(Input in, String idempotencyKey) {
        List<Match> matches = duplicates(in);
        for (Match m : matches) {
            if (m.rule().equals("PAN")) {
                if (m.customerId() == null) throw ApiException.conflict("a customer with this PAN already exists in a branch outside your scope");
                return new Created(summary(m.customerId()), null);
            }
        }
        scope.require(in.homeBranch());
        if (!matches.isEmpty()) {
            if (!Boolean.TRUE.equals(in.overrideDedupe())) {
                throw new ApiException(HttpStatus.CONFLICT, "possible duplicate customers found",
                        Map.of("matches", matches.stream().map(json::toMap).toList()));
            }
            if (in.overrideReason() == null || in.overrideReason().trim().length() < 10) {
                throw ApiException.invalid("overrideReason (at least 10 characters) is required to override dedupe");
            }
        }
        PiiCipher cipher = keys.forTenant(CurrentUser.requireTenant());
        Map<String, Object> sealed = new LinkedHashMap<>();
        sealed.put("firstName", in.firstName());
        sealed.put("middleName", in.middleName());
        sealed.put("lastName", in.lastName());
        sealed.put("pan", in.pan());
        sealed.put("mobile", in.mobile());
        sealed.put("email", in.email());
        sealed.put("addressLine1", in.address() == null ? null : in.address().line1());
        sealed.put("addressLine2", in.address() == null ? null : in.address().line2());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerType", in.customerType());
        payload.put("displayName", displayName(in));
        payload.put("dateOfBirth", in.dateOfBirth().toString());
        payload.put("gender", in.gender());
        payload.put("homeBranch", in.homeBranch());
        payload.put("panMasked", in.pan() == null ? null : Masking.pan(in.pan()));
        payload.put("mobileMasked", Masking.mobile(in.mobile()));
        payload.put("emailMasked", Masking.email(in.email()));
        if (in.address() != null) {
            payload.put("city", in.address().city());
            payload.put("stateCode", in.address().stateCode());
            payload.put("pincode", in.address().pincode());
        }
        List<Map<String, Object>> overrides = new ArrayList<>();
        for (Match m : matches) {
            overrides.add(Map.of("customerNo", m.customerNo() == null ? "outside your branch scope" : m.customerNo(),
                    "rule", m.rule(), "strength", m.strength()));
        }
        payload.put("dedupeOverrides", overrides);
        payload.put("overrideReason", in.overrideReason());
        payload.put("sealed", Base64.getEncoder().encodeToString(cipher.encrypt(json.write(sealed), "approval.customer")));
        return new Created(null, approvals.propose("CUSTOMER", "CREATE", null, payload, null, null, in.homeBranch(), idempotencyKey));
    }

    List<Map<String, Object>> search(String q, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        if (q == null || q.isBlank()) {
            return jdbc.query(SCOPED + " ORDER BY created_at DESC LIMIT ? OFFSET ?", this::row, scope.user(), limit, offset);
        }
        String t = q.trim();
        PiiCipher cipher = keys.forTenant(CurrentUser.requireTenant());
        String u = scope.user();
        if (t.matches("[0-9]{14}")) return jdbc.query(SCOPED + " AND customer_no = ?", this::row, u, t);
        if (t.toUpperCase().matches("[A-Z]{5}[0-9]{4}[A-Z]")) {
            return jdbc.query(SCOPED + " AND pan_hash = ?", this::row, u, cipher.blindIndex("PAN", Dedupe.normalisePan(t)));
        }
        if (t.replaceAll("\\D", "").length() >= 10 && t.matches("[+0-9 -]+")) {
            return jdbc.query(SCOPED + " AND mobile_hash = ?", this::row, u, cipher.blindIndex("MOBILE", Dedupe.normaliseMobile(t)));
        }
        return jdbc.query(SCOPED + " AND display_name ILIKE ? ORDER BY display_name LIMIT ? OFFSET ?", this::row, u,
                t.replace("%", "").replace("_", "") + "%", limit, offset);
    }

    Map<String, Object> summary(UUID id) {
        List<Map<String, Object>> l = jdbc.query(SCOPED + " AND id = ?", this::row, scope.user(), id);
        if (l.isEmpty()) throw ApiException.notFound("customer " + id);
        return l.get(0);
    }

    private static final String SELECT = """
            SELECT id, customer_no, display_name, customer_type, date_of_birth, pan_last4, mobile_last4, home_branch,
                   kyc_status, status FROM customer.customer""";
    /** Customers of the caller's branch scope (US-020); binds the username first. */
    private static final String SCOPED = SELECT + " WHERE home_branch" + BranchScope.SQL_VISIBLE;

    private Map<String, Object> row(ResultSet rs, int i) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id", UUID.class));
        m.put("customerNo", rs.getString("customer_no"));
        m.put("displayName", rs.getString("display_name"));
        m.put("customerType", rs.getString("customer_type"));
        m.put("dateOfBirth", rs.getObject("date_of_birth", LocalDate.class));
        String pan4 = rs.getString("pan_last4");
        String mob4 = rs.getString("mobile_last4");
        m.put("panMasked", pan4 == null ? null : "XXXXX" + pan4 + "X");
        m.put("mobileMasked", mob4 == null ? null : "XXXXXX" + mob4);
        m.put("homeBranch", rs.getString("home_branch"));
        m.put("kycStatus", rs.getString("kyc_status"));
        m.put("status", rs.getString("status"));
        return m;
    }

    record Hashes(byte[] pan, byte[] mobile, byte[] nameDob) {}

    static Hashes hashes(PiiCipher c, Input in) {
        return new Hashes(
                in.pan() == null || in.pan().isBlank() ? null : c.blindIndex("PAN", Dedupe.normalisePan(in.pan())),
                c.blindIndex("MOBILE", Dedupe.normaliseMobile(in.mobile())),
                c.blindIndex("NAME_DOB", Dedupe.nameDobKey(in.firstName(), in.middleName(), in.lastName(), in.dateOfBirth())));
    }

    static String displayName(Input in) {
        StringBuilder sb = new StringBuilder(in.firstName().trim());
        if (in.middleName() != null && !in.middleName().isBlank()) sb.append(' ').append(in.middleName().trim());
        if (in.lastName() != null && !in.lastName().isBlank()) sb.append(' ').append(in.lastName().trim());
        return sb.toString();
    }

    /** Creates the customer when the request is approved. */
    @Service
    static class Applier implements ApprovalApplier {
        private final JdbcTemplate jdbc;
        private final PiiKeys keys;
        private final NumberSeriesService numbers;
        private final Json json;

        Applier(JdbcTemplate jdbc, PiiKeys keys, NumberSeriesService numbers, Json json) {
            this.jdbc = jdbc;
            this.keys = keys;
            this.numbers = numbers;
            this.json = json;
        }

        @Override public String entityType() { return "CUSTOMER"; }

        @Override
        public String apply(ApprovalRequest r) {
            PiiCipher c = keys.forTenant(CurrentUser.requireTenant());
            Map<String, Object> p = r.payload();
            Map<String, Object> s = json.readMap(c.decrypt(Base64.getDecoder().decode((String) p.get("sealed")), "approval.customer"));
            String pan = (String) s.get("pan");
            String mobile = (String) s.get("mobile");
            Input in = new Input((String) p.get("customerType"), (String) s.get("firstName"), (String) s.get("middleName"),
                    (String) s.get("lastName"), LocalDate.parse((String) p.get("dateOfBirth")), (String) p.get("gender"),
                    pan, mobile, (String) s.get("email"), (String) p.get("homeBranch"), null, null, null);
            Hashes h = hashes(c, in);
            if (h.pan() != null && !jdbc.queryForList("SELECT 1 FROM customer.customer WHERE pan_hash = ? AND status <> 'ERASED'", h.pan()).isEmpty()) {
                throw ApiException.conflict("a customer with this PAN was created after the request was raised");
            }
            UUID id = UUID.randomUUID();
            String no = numbers.next(NumberSeries.Family.CUSTOMER);
            String normPan = pan == null || pan.isBlank() ? null : Dedupe.normalisePan(pan);
            String normMobile = Dedupe.normaliseMobile(mobile);
            jdbc.update("""
                    INSERT INTO customer.customer (id, customer_no, customer_type, display_name, date_of_birth, gender,
                        first_name_cipher, middle_name_cipher, last_name_cipher, pan_cipher, pan_hash, pan_last4,
                        mobile_cipher, mobile_hash, mobile_last4, email_cipher, name_dob_hash, home_branch,
                        approval_id, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, no, p.get("customerType"), p.get("displayName"), in.dateOfBirth(), p.get("gender"),
                    c.encrypt(in.firstName(), "customer.first_name"), c.encrypt(in.middleName(), "customer.middle_name"),
                    c.encrypt(in.lastName(), "customer.last_name"), c.encrypt(normPan, "customer.pan"), h.pan(),
                    normPan == null ? null : normPan.substring(5, 9),
                    c.encrypt(normMobile, "customer.mobile"), h.mobile(), normMobile.substring(6),
                    c.encrypt(in.email(), "customer.email"), h.nameDob(), p.get("homeBranch"), r.id(), r.maker());
            if (p.get("pincode") != null) {
                String line = String.join(", ", nonNull((String) s.get("addressLine1")), nonNull((String) s.get("addressLine2")));
                jdbc.update("""
                        INSERT INTO customer.address (customer_id, address_type, line_cipher, city, state_code, pincode)
                        VALUES (?, 'COMMUNICATION', ?, ?, ?, ?)
                        """, id, c.encrypt(line, "customer.address"), p.get("city"), p.get("stateCode"), p.get("pincode"));
            }
            Object overrides = p.get("dedupeOverrides");
            if (overrides instanceof List<?> list) {
                for (Object o : list) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    jdbc.update("INSERT INTO customer.dedupe_override (approval_id, matched_customer_no, rule, reason) VALUES (?, ?, ?, ?)",
                            r.id(), m.get("customerNo"), m.get("rule"), p.get("overrideReason"));
                }
            }
            return no;
        }

        private static String nonNull(String s) {
            return s == null ? "" : s;
        }
    }
}
