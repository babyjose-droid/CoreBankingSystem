package com.corebanking.lending.internal;

import com.corebanking.customer.PiiKeys;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.CustomFieldService;
import com.corebanking.platform.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom fields on loan accounts and loan products (US-014).
 * <ul>
 *   <li><b>Loan account:</b> the {@code custom} object of the create request is checked first, the loan is booked,
 *       and the values are stored in the same transaction (the database checks them again at commit). The loan
 *       detail returns {@code custom}, personal-data fields masked.</li>
 *   <li><b>Loan product:</b> custom values are kept beside the versioned product and change through their own
 *       maker-checker request (entity LOAN_PRODUCT_CUSTOM), so the product factory is untouched.</li>
 * </ul>
 */
@Service
public class LoanCustomFields {

    private final JdbcTemplate jdbc;
    private final LoanService loans;
    private final CustomFieldService fields;
    private final PiiKeys keys;
    private final Json json;
    private final ApprovalService approvals;

    public LoanCustomFields(JdbcTemplate jdbc, LoanService loans, CustomFieldService fields, PiiKeys keys, Json json, ApprovalService approvals) {
        this.jdbc = jdbc;
        this.loans = loans;
        this.fields = fields;
        this.keys = keys;
        this.json = json;
        this.approvals = approvals;
    }

    /** Books a loan with its custom values. A repeat of an {@code externalRef} returns the loan as it is, unchanged. */
    @Transactional
    public Map<String, Object> create(LoanService.Application application, Map<String, Object> custom) {
        boolean repeat = application.externalRef() != null
                && !jdbc.queryForList("SELECT 1 FROM lending.loan_account WHERE external_ref = ?", application.externalRef()).isEmpty();
        Map<String, Object> stored = repeat ? Map.of()
                : fields.prepare("LOAN_ACCOUNT", custom, () -> keys.forTenant(CurrentUser.requireTenant()));
        Map<String, Object> loan = loans.create(application);
        if (!stored.isEmpty()) {
            jdbc.update("UPDATE lending.loan_account SET custom = ?::jsonb WHERE id = ?", json.write(stored), loan.get("id"));
        }
        return withCustom(loan);
    }

    /** Adds {@code custom} to a loan as returned by {@link LoanService#get}. */
    public Map<String, Object> withCustom(Map<String, Object> loan) {
        Map<String, Object> out = new LinkedHashMap<>(loan);
        List<String> stored = jdbc.queryForList("SELECT custom::text FROM lending.loan_account WHERE id = ?", String.class, loan.get("id"));
        out.put("custom", fields.view(stored.isEmpty() ? null : stored.get(0)));
        return out;
    }

    public Map<String, Object> get(UUID loanId) {
        return withCustom(loans.get(loanId));
    }

    // ------------------------------------------------------------------------------------------------ products
    public Map<String, Object> productCustom(String productCode) {
        List<String> stored = jdbc.queryForList("SELECT custom::text FROM lending.loan_product WHERE code = ?", String.class, productCode);
        if (stored.isEmpty()) throw ApiException.notFound("loan product " + productCode);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", productCode);
        m.put("custom", fields.view(stored.get(0)));
        return m;
    }

    /** Proposes the complete set of custom values of a product (maker-checker). */
    @Transactional
    public ApprovalRequest proposeProductCustom(String productCode, Map<String, Object> custom) {
        Map<String, Object> current = productCustom(productCode);
        Map<String, Object> stored = fields.prepare("LOAN_PRODUCT", custom, () -> keys.forTenant(CurrentUser.requireTenant()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productCode", productCode);
        payload.put("custom", stored);          // personal-data fields are already sealed: the checker sees their masks
        return approvals.propose("LOAN_PRODUCT_CUSTOM", "UPDATE", productCode, payload, current, null, null, null);
    }

    @Service
    static class ProductCustomApplier implements ApprovalApplier {
        private final JdbcTemplate jdbc;
        private final Json json;

        ProductCustomApplier(JdbcTemplate jdbc, Json json) {
            this.jdbc = jdbc;
            this.json = json;
        }

        @Override public String entityType() { return "LOAN_PRODUCT_CUSTOM"; }

        @Override
        public String apply(ApprovalRequest r) {
            Object custom = r.payload().get("custom") == null ? Map.of() : r.payload().get("custom");
            int n = jdbc.update("UPDATE lending.loan_product SET custom = ?::jsonb WHERE code = ?", json.write(custom), r.payload().get("productCode"));
            if (n == 0) throw ApiException.notFound("loan product " + r.payload().get("productCode"));
            return String.valueOf(r.payload().get("productCode"));
        }
    }
}
