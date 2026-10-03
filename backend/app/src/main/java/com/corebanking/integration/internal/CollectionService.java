package com.corebanking.integration.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.customer.CustomerContacts;
import com.corebanking.integration.core.EndpointGuard;
import com.corebanking.integration.core.Lifecycle;
import com.corebanking.integration.core.RetrySchedule;
import com.corebanking.integration.core.ValueDateRule;
import com.corebanking.integration.core.provider.CollectionGateway;
import com.corebanking.integration.core.provider.InboundEvent;
import com.corebanking.integration.core.provider.ProviderException;
import com.corebanking.kernel.Csv;
import com.corebanking.lending.LoanOperations;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Collections through a payment gateway (US-073).
 * <ul>
 *   <li>An order (payment link) is created for a loan and an amount; the payment methods are passed through.</li>
 *   <li>A payment arrives as a verified callback or from a status poll. It is recorded under the provider's
 *       payment id — unique, so the same payment can never post twice — and then posted through the loan engine,
 *       with value date = payment date as far as {@link ValueDateRule} allows.</li>
 *   <li>Money that cannot be posted (no such order, the loan cannot take a receipt) goes to the unmatched
 *       receipts queue for operations: assign it to a loan or mark it for refund.</li>
 *   <li>The gateway's settlement report is loaded as CSV; {@code integration.collection_reconciliation} shows
 *       what does not match, in both directions.</li>
 * </ul>
 */
@Service
class CollectionService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<String> METHODS = List.of("UPI", "CARD", "NETBANKING");

    /** openapi.yaml#/components/schemas/CollectionOrderInput. */
    record OrderInput(BigDecimal amount, List<String> methods, String returnUrl) {}

    /** openapi.yaml#/components/schemas/PaymentResolution. */
    record Resolution(UUID loanId, Boolean refund, String note) {}

    private final JdbcTemplate jdbc;
    private final ProviderConfigService providers;
    private final LoanOperations loans;
    private final CustomerContacts contacts;
    private final BranchScope scope;
    private final BusinessDays days;
    private final TenantProps props;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    CollectionService(JdbcTemplate jdbc, ProviderConfigService providers, LoanOperations loans, CustomerContacts contacts, BranchScope scope,
                      BusinessDays days, TenantProps props, AuditLog audit, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.loans = loans;
        this.contacts = contacts;
        this.scope = scope;
        this.days = days;
        this.props = props;
        this.audit = audit;
        this.tx = new TransactionTemplate(manager);
    }

    // ------------------------------------------------------------------------------------------------ orders
    Map<String, Object> createOrder(UUID loanId, OrderInput in, String idempotencyKey) {
        LoanOperations.LoanRef loan = loans.find(loanId);
        scope.requireRecord(loan.branch(), "loan " + loanId);
        String user = CurrentUser.username();
        if (idempotencyKey != null) {
            List<UUID> known = jdbc.queryForList("SELECT id FROM integration.collection_order WHERE created_by = ? AND idempotency_key = ?",
                    UUID.class, user, idempotencyKey);
            if (!known.isEmpty()) return order(known.get(0));
        }
        if (in == null || in.amount() == null || in.amount().signum() <= 0 || in.amount().stripTrailingZeros().scale() > 2) {
            throw ApiException.invalid("amount must be positive with at most two decimals");
        }
        if (!"ACTIVE".equals(loan.status())) throw ApiException.conflict("loan is " + loan.status() + ": it cannot take a payment");
        List<String> methods = in.methods() == null ? List.of() : in.methods().stream().distinct().toList();
        for (String m : methods) {
            if (!METHODS.contains(m)) throw ApiException.invalid("methods may contain UPI, CARD and NETBANKING");
        }
        if (in.returnUrl() != null) {
            try {
                EndpointGuard.checkUrl(in.returnUrl(), EndpointGuard.DEFAULT_PORTS);
            } catch (EndpointGuard.BlockedException e) {
                throw ApiException.invalid("returnUrl: " + e.getMessage());
            }
        }
        ProviderConfigService.Active<CollectionGateway> gateway = providers.collection();
        if (gateway == null) throw ApiException.conflict("no collection provider is active for this tenant");
        Integer seq = jdbc.queryForObject("SELECT count(*) + 1 FROM integration.collection_order WHERE loan_id = ?", Integer.class, loanId);
        String reference = "CO" + loan.loanNo() + "N" + seq;
        Instant expires = Instant.now().plusSeconds(3600L * props.number("collections.link-validity-hours", 72, 1, 720));
        CustomerContacts.Contact payer = contacts.of(loan.customerId());
        CollectionGateway.Created created;
        try {
            created = gateway.port().create(new CollectionGateway.Order(reference, in.amount().setScale(2, RoundingMode.UNNECESSARY), methods,
                    "Loan repayment " + loan.loanNo(), payer.displayName(), payer.mobile(), payer.email(), expires, in.returnUrl()));
        } catch (ProviderException e) {
            throw new ApiException(e.retryable() ? org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE : org.springframework.http.HttpStatus.CONFLICT,
                    "the gateway did not create the payment: " + e.getMessage());
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integration.collection_order (id, reference, loan_id, loan_no, branch_code, amount, methods, provider, provider_ref,
                    payment_url, expires_at, idempotency_key, next_poll_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, now() + interval '1 minute', ?)
                """, id, reference, loanId, loan.loanNo(), loan.branch(), in.amount(), "{" + String.join(",", methods) + "}", gateway.code(),
                created.providerRef(), created.paymentUrl(), OffsetDateTime.ofInstant(expires, ZoneOffset.UTC), idempotencyKey, user);
        audit.record(user, "COLLECTION_ORDER", "LOAN", loan.loanNo(), Map.of("reference", reference, "amount", in.amount().toPlainString()));
        return order(id);
    }

    private static final String ORDER = """
            SELECT o.id, o.reference, o.loan_id AS "loanId", o.loan_no AS "loanNo", o.amount::text AS amount,
                   array_to_string(o.methods, ',') AS methods, o.provider, o.payment_url AS "paymentUrl", o.status,
                   o.expires_at AS "expiresAt", o.created_by AS "createdBy", o.created_at AS "createdAt"
              FROM integration.collection_order o
             WHERE o.branch_code IN (SELECT branch_code FROM platform.visible_branches(?))""";

    private static Map<String, Object> orderView(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>(row);
        String methods = (String) row.get("methods");
        m.put("methods", methods == null || methods.isEmpty() ? List.of() : List.of(methods.split(",")));
        return m;
    }

    Map<String, Object> order(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(ORDER + " AND o.id = ?", scope.user(), id);
        if (rows.isEmpty()) throw ApiException.notFound("collection order " + id);
        return orderView(rows.get(0));
    }

    List<Map<String, Object>> orders(UUID loanId) {
        return jdbc.queryForList(ORDER + " AND o.loan_id = ? ORDER BY o.created_at DESC", scope.user(), loanId).stream()
                .map(CollectionService::orderView).toList();
    }

    // ------------------------------------------------------------------------------------------------ payments
    /** A verified provider callback about a payment. */
    String applyInbound(InboundEvent e, String provider, UUID inboundId) {
        List<Map<String, Object>> orders = jdbc.queryForList(
                "SELECT id, loan_id, amount, status FROM integration.collection_order WHERE reference = ? AND provider = ? FOR UPDATE",
                e.reference(), provider);
        Map<String, Object> order = orders.isEmpty() ? null : orders.get(0);
        if (!"PAID".equals(e.status())) {
            if (order != null) moveOrder(order, Lifecycle.CollectionOrder.FAILED);
            return order == null ? "IGNORED: failure notice for an unknown order" : "ORDER_FAILED";
        }
        if (e.amount() == null || e.amount().signum() <= 0) return "IGNORED: payment without an amount";
        String paymentId = e.providerRef() == null ? e.eventId() : e.providerRef();
        return received(provider, paymentId, order, e.amount(), e.method(), e.utr(), e.occurredAt(), "WEBHOOK", inboundId);
    }

    private String received(String provider, String paymentId, Map<String, Object> order, BigDecimal amount, String method, String utr,
                            Instant paidAt, String source, UUID inboundId) {
        UUID loanId = order == null ? null : (UUID) order.get("loan_id");
        // An order is paid once. A second, different payment on an order that already has one is not posted by
        // itself: it goes to the unmatched receipts queue for a person to assign or refund.
        boolean second = order != null && !jdbc.queryForList(
                "SELECT 1 FROM integration.collection_payment WHERE order_id = ? AND provider_payment_id <> ? AND status <> 'FAILED'",
                order.get("id"), paymentId).isEmpty();
        // the unique key (provider, provider_payment_id) is what makes a repeated notification harmless
        int n = jdbc.update("""
                INSERT INTO integration.collection_payment (id, provider, provider_payment_id, order_id, loan_id, amount, method, utr, paid_at,
                    status, last_error, source, inbound_event_id, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END)
                ON CONFLICT (provider, provider_payment_id) DO NOTHING
                """, UUID.randomUUID(), provider, paymentId, order == null ? null : order.get("id"), loanId, amount, method, utr,
                OffsetDateTime.ofInstant(paidAt == null ? Instant.now() : paidAt, ZoneOffset.UTC),
                loanId == null || second ? "UNMATCHED" : "RECEIVED",
                loanId == null ? "no order with this reference" : second ? "the order already has another payment" : null,
                source, inboundId, loanId != null && !second);
        if (n == 0) return "DUPLICATE";
        if (order != null) moveOrder(order, Lifecycle.CollectionOrder.PAID);
        return loanId == null || second ? "UNMATCHED" : "RECEIVED";
    }

    private void moveOrder(Map<String, Object> order, Lifecycle.CollectionOrder to) {
        Lifecycle.CollectionOrder from = Lifecycle.CollectionOrder.valueOf((String) order.get("status"));
        if (Lifecycle.decide(from, to) == Lifecycle.Decision.APPLY) {
            jdbc.update("UPDATE integration.collection_order SET status = ?, next_poll_at = NULL, updated_at = now() WHERE id = ?", to.name(), order.get("id"));
        }
    }

    private record Due(UUID id, UUID loanId, BigDecimal amount, OffsetDateTime paidAt, String utr, String paymentId, int attempts) {}

    /** Posts received payments as repayments. */
    void post() {
        List<Due> due = jdbc.query("""
                UPDATE integration.collection_payment SET next_attempt_at = now() + interval '3 minutes', attempts = attempts + 1
                 WHERE id IN (SELECT id FROM integration.collection_payment WHERE next_attempt_at <= now() AND status = 'RECEIVED'
                               ORDER BY paid_at LIMIT 50 FOR UPDATE SKIP LOCKED)
                RETURNING id, loan_id, amount, paid_at, utr, provider_payment_id, attempts
                """, (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBigDecimal(3),
                        rs.getObject(4, OffsetDateTime.class), rs.getString(5), rs.getString(6), rs.getInt(7)));
        for (Due d : due) {
            BusinessDays.BusinessDay day = days.current();
            if (day == null || !"OPEN".equals(day.status())) {
                retryLater(d, "the books are not open (end of day)");
                continue;
            }
            LocalDate paidOn = d.paidAt().atZoneSameInstant(IST).toLocalDate();
            ValueDateRule.Decision v = ValueDateRule.decide(paidOn, day.businessDate(), props.number("collections.max-back-value-days", 3, 0, 31));
            try {
                tx.executeWithoutResult(s -> {
                    LoanOperations.Posting p = loans.postRepayment(d.loanId(), d.amount(), v.valueDate(), "PG", d.utr() == null ? d.paymentId() : d.utr());
                    jdbc.update("""
                            UPDATE integration.collection_payment SET status = 'POSTED', loan_txn_id = ?, value_date = ?, value_date_note = ?,
                                   review = ?, next_attempt_at = NULL, last_error = NULL, posted_at = now() WHERE id = ?
                            """, p.txnId(), v.valueDate(), v.note(), v.review(), d.id());
                });
            } catch (RuntimeException e) {
                if (e instanceof ApiException && "OPEN".equals(days.current().status())) {
                    // the loan cannot take it (closed, frozen …): operations decide
                    jdbc.update("UPDATE integration.collection_payment SET status = 'UNMATCHED', next_attempt_at = NULL, last_error = ? WHERE id = ?",
                            e.getMessage(), d.id());
                } else {
                    retryLater(d, e instanceof ApiException ? e.getMessage() : e.getClass().getSimpleName());
                }
            }
        }
    }

    private void retryLater(Due d, String why) {
        RetrySchedule s = RetrySchedule.POSTING;
        if (s.exhausted(d.attempts())) {
            jdbc.update("UPDATE integration.collection_payment SET status = 'UNMATCHED', next_attempt_at = NULL, last_error = ? WHERE id = ?",
                    "not posted after " + d.attempts() + " attempts: " + why, d.id());
        } else {
            jdbc.update("UPDATE integration.collection_payment SET next_attempt_at = now() + make_interval(secs => ?), last_error = ? WHERE id = ?",
                    s.delaySeconds(d.attempts(), ThreadLocalRandom.current().nextDouble()), why, d.id());
        }
    }

    /** Asks the gateway about orders still open (a callback may never come) and expires those past their time. */
    void poll() {
        jdbc.update("UPDATE integration.collection_order SET status = 'EXPIRED', next_poll_at = NULL, updated_at = now() WHERE status = 'CREATED' AND expires_at < now()");
        ProviderConfigService.Active<CollectionGateway> gateway = providers.collection();
        if (gateway == null) return;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                UPDATE integration.collection_order SET next_poll_at = now() + least(polls + 1, 12) * interval '5 minutes', polls = polls + 1
                 WHERE id IN (SELECT id FROM integration.collection_order WHERE next_poll_at <= now() AND status = 'CREATED' AND provider = ?
                               ORDER BY next_poll_at LIMIT 20 FOR UPDATE SKIP LOCKED)
                RETURNING id, reference, provider_ref, loan_id, amount, status
                """, gateway.code());
        for (Map<String, Object> o : rows) {
            try {
                CollectionGateway.Payment p = gateway.port().status((String) o.get("reference"), (String) o.get("provider_ref"));
                tx.executeWithoutResult(s -> {
                    if (p.status() == Lifecycle.CollectionOrder.PAID) {
                        received(gateway.code(), p.providerPaymentId() == null ? "ORDER-" + o.get("reference") : p.providerPaymentId(), o,
                                p.amount() == null ? (BigDecimal) o.get("amount") : p.amount(), p.method(), p.utr(), p.paidAt(), "POLL", null);
                    } else if (p.status() == Lifecycle.CollectionOrder.FAILED) {
                        moveOrder(o, Lifecycle.CollectionOrder.FAILED);
                    }
                });
            } catch (ProviderException e) {
                // asked again at the next poll time
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ operations
    /** Unmatched receipts queue: assign the money to a loan (it is then posted) or mark it for refund. */
    @Transactional
    Map<String, Object> resolve(UUID paymentId, Resolution r) {
        if (r == null || r.note() == null || r.note().isBlank()) throw ApiException.invalid("a note is required");
        if (!scope.seesAll()) throw ApiException.forbidden("unmatched receipts are resolved by users with all-branch access");
        List<String> status = jdbc.queryForList("SELECT status FROM integration.collection_payment WHERE id = ? FOR UPDATE", String.class, paymentId);
        if (status.isEmpty()) throw ApiException.notFound("gateway payment " + paymentId);
        if (!"UNMATCHED".equals(status.get(0))) throw ApiException.conflict("the payment is " + status.get(0));
        String user = CurrentUser.username();
        if (Boolean.TRUE.equals(r.refund())) {
            jdbc.update("UPDATE integration.collection_payment SET status = 'REFUND_DUE', resolved_by = ?, resolution_note = ? WHERE id = ?",
                    user, r.note().trim(), paymentId);
        } else {
            if (r.loanId() == null) throw ApiException.invalid("give the loanId to post to, or refund = true");
            LoanOperations.LoanRef loan = loans.find(r.loanId());
            if (!"ACTIVE".equals(loan.status())) throw ApiException.conflict("loan is " + loan.status());
            jdbc.update("""
                    UPDATE integration.collection_payment SET status = 'RECEIVED', loan_id = ?, attempts = 0, next_attempt_at = now(), last_error = NULL,
                           resolved_by = ?, resolution_note = ? WHERE id = ?
                    """, r.loanId(), user, r.note().trim(), paymentId);
        }
        audit.record(user, "GATEWAY_PAYMENT_RESOLVE", "GATEWAY_PAYMENT", paymentId.toString(),
                Map.of("refund", String.valueOf(Boolean.TRUE.equals(r.refund())), "loanId", String.valueOf(r.loanId())));
        return jdbc.queryForMap("SELECT id, status, loan_id AS \"loanId\", resolution_note AS \"resolutionNote\" FROM integration.collection_payment WHERE id = ?", paymentId);
    }

    /** Loads a settlement report: CSV with provider_payment_id, amount, settled_on and optionally fee and utr. */
    @Transactional
    Map<String, Object> importSettlements(String fileRef, String csv) {
        if (fileRef == null || !fileRef.matches("[A-Za-z0-9._-]{1,60}")) throw ApiException.invalid("fileRef: 1 to 60 letters, digits, '.', '_' or '-'");
        ProviderConfigService.Active<CollectionGateway> gateway = providers.collection();
        if (gateway == null) throw ApiException.conflict("no collection provider is active for this tenant");
        Csv.Table t;
        try {
            t = Csv.parse(csv, List.of("provider_payment_id", "amount", "settled_on"), 20_000);
        } catch (Csv.CsvException e) {
            throw ApiException.invalid(e.getMessage());
        }
        List<Object[]> rows = new ArrayList<>();
        for (Csv.Row r : t.rows()) {
            try {
                String id = r.get("provider_payment_id");
                if (id == null) throw new IllegalArgumentException("provider_payment_id is required");
                BigDecimal amount = new BigDecimal(String.valueOf(r.get("amount")));
                BigDecimal fee = t.header().contains("fee") && r.get("fee") != null ? new BigDecimal(r.get("fee")) : BigDecimal.ZERO;
                rows.add(new Object[] {gateway.code(), id, amount, fee, java.sql.Date.valueOf(LocalDate.parse(String.valueOf(r.get("settled_on")))),
                        t.header().contains("utr") ? r.get("utr") : null, fileRef, CurrentUser.username()});
            } catch (RuntimeException e) {
                throw ApiException.invalid("line " + r.line() + ": amount, fee or settled_on (YYYY-MM-DD) is not valid");
            }
        }
        int added = 0;
        for (Object[] row : rows) {
            added += jdbc.update("""
                    INSERT INTO integration.gateway_settlement (provider, provider_payment_id, amount, fee, settled_on, utr, file_ref, imported_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (provider, provider_payment_id) DO NOTHING
                    """, row);
        }
        audit.record(CurrentUser.username(), "SETTLEMENT_IMPORT", "GATEWAY_SETTLEMENT", fileRef, Map.of("rows", rows.size(), "added", added));
        return Map.of("fileRef", fileRef, "rows", rows.size(), "added", added, "alreadyLoaded", rows.size() - added);
    }

    /** Settlements against postings; without a category, everything that is not MATCHED. */
    List<Map<String, Object>> reconciliation(String category, LocalDate from, LocalDate to) {
        boolean all = scope.seesAll();
        return jdbc.queryForList("""
                SELECT category, provider, provider_payment_id AS "providerPaymentId", payment_id AS "paymentId", loan_id AS "loanId",
                       loan_no AS "loanNo", payment_status AS "paymentStatus", payment_amount::text AS "paymentAmount", paid_at AS "paidAt",
                       value_date::text AS "valueDate", loan_txn_id AS "loanTxnId", review, last_error AS "lastError",
                       settled_amount::text AS "settledAmount", settlement_fee::text AS "settlementFee", settled_on::text AS "settledOn",
                       settlement_utr AS "settlementUtr"
                  FROM integration.collection_reconciliation
                 WHERE (CASE WHEN ?::text IS NULL THEN category <> 'MATCHED' ELSE category = ? END)
                   AND (?::date IS NULL OR coalesce(paid_at::date, settled_on) >= ?::date)
                   AND (?::date IS NULL OR coalesce(paid_at::date, settled_on) <= ?::date)
                   AND (CASE WHEN branch_code IS NULL THEN ? ELSE branch_code IN (SELECT branch_code FROM platform.visible_branches(?)) END)
                 ORDER BY coalesce(paid_at::date, settled_on) DESC LIMIT 1000
                """, category, category, from, from, to, to, all, scope.user());
    }

    @Component
    static class PostTask implements IntegrationTask {
        private final CollectionService collections;

        PostTask(@Lazy CollectionService collections) {
            this.collections = collections;
        }

        @Override public String name() { return "collection-post-and-poll"; }

        @Override
        public void run() {
            collections.poll();
            collections.post();
        }
    }
}
