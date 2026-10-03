package com.corebanking.integration.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.integration.core.Lifecycle;
import com.corebanking.integration.core.RetrySchedule;
import com.corebanking.integration.core.WebhookEvents;
import com.corebanking.integration.core.provider.InboundEvent;
import com.corebanking.integration.core.provider.PayoutGateway;
import com.corebanking.integration.core.provider.ProviderException;
import com.corebanking.lending.LoanEvents;
import com.corebanking.lending.LoanOperations;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.Json;
import com.corebanking.platform.Outbox;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * Payout of disbursements through the gateway (US-051).
 * <ol>
 *   <li>The disbursement's own transaction writes {@code loan.disbursed} to the outbox. The relay turns it into a
 *       payout instruction (INITIATED, or ON_HOLD when the loan has no usable beneficiary account). The provider
 *       is never called inside the posting transaction.</li>
 *   <li>The worker sends INITIATED instructions. Our reference is the idempotency key with the provider; a call
 *       without an answer is retried with the same reference and never treated as a failure. When retries run
 *       out the instruction is flagged for operations with its outcome still unknown.</li>
 *   <li>Status arrives by callback and by polling; both go through {@link Lifecycle#decide}, so repeats and
 *       late news are ignored and a contradiction (FAILED after SUCCESS) is flagged, not applied.</li>
 *   <li>FAILED or RETURNED: a loan disbursed straight through by an API client has its disbursement reversed at
 *       once; a staff-approved one gets a reversal proposed for a checker (or is only parked, by tenant property
 *       {@code payout.failure-action}). Either way the instruction is flagged, and a new attempt can be made.</li>
 * </ol>
 * Beneficiary account numbers are stored encrypted and leave only masked.
 */
@Service
class PayoutService implements OutboxConsumer {

    static final String REVERSAL_ENTITY = "LOAN_DISBURSEMENT_REVERSAL";
    private static final String ACCOUNT_AAD = "integration.beneficiary.account";
    private static final String NAME_AAD = "integration.beneficiary.holder_name";

    /** openapi.yaml#/components/schemas/BeneficiaryInput. */
    record BeneficiaryInput(String holderName, String accountNumber, String ifsc) {}

    /** What a provider told us about a payout. */
    record News(Lifecycle.Payout status, String providerRef, String utr, String failureCode, String failureReason) {}

    private final JdbcTemplate jdbc;
    private final Secrets secrets;
    private final ProviderConfigService providers;
    private final LoanOperations loans;
    private final ApprovalService approvals;
    private final Outbox outbox;
    private final AuditLog audit;
    private final BranchScope scope;
    private final BusinessDays days;
    private final TenantProps props;
    private final Json json;
    private final TransactionTemplate tx;

    PayoutService(JdbcTemplate jdbc, Secrets secrets, ProviderConfigService providers, LoanOperations loans, ApprovalService approvals,
                  Outbox outbox, AuditLog audit, BranchScope scope, BusinessDays days, TenantProps props, Json json,
                  PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.providers = providers;
        this.loans = loans;
        this.approvals = approvals;
        this.outbox = outbox;
        this.audit = audit;
        this.scope = scope;
        this.days = days;
        this.props = props;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    // ------------------------------------------------------------------------------------------------ beneficiary
    /** Records (or replaces) the account a loan's disbursement is paid to, after the gateway's validation. */
    @Transactional
    Map<String, Object> setBeneficiary(UUID loanId, BeneficiaryInput in) {
        LoanOperations.LoanRef loan = loans.find(loanId);
        scope.requireRecord(loan.branch(), "loan " + loanId);
        if (in == null || in.holderName() == null || in.holderName().isBlank() || in.holderName().length() > 100) {
            throw ApiException.invalid("holderName is required (at most 100 characters)");
        }
        String account = in.accountNumber() == null ? "" : in.accountNumber().replace(" ", "");
        if (!account.matches("[A-Za-z0-9]{6,35}")) throw ApiException.invalid("accountNumber must be 6 to 35 letters or digits");
        String ifsc = in.ifsc() == null ? "" : in.ifsc().trim().toUpperCase(java.util.Locale.ROOT);
        if (!ifsc.matches("[A-Z]{4}0[A-Z0-9]{6}")) throw ApiException.invalid("ifsc is not a valid IFSC");
        if (!jdbc.queryForList("SELECT 1 FROM integration.payout_instruction WHERE loan_id = ? AND status IN ('SENT','SUCCESS')", loanId).isEmpty()) {
            throw ApiException.conflict("a payout for this loan is already sent or paid; the beneficiary can no longer change");
        }
        if (List.of("CLOSED", "CANCELLED", "WRITTEN_OFF").contains(loan.status())) throw ApiException.conflict("loan is " + loan.status());
        // Penny-drop hook: the active payout provider validates the account; without one the check is left pending.
        ProviderConfigService.Active<PayoutGateway> gateway = providers.payout();
        PayoutGateway.Validation v = new PayoutGateway.Validation(PayoutGateway.Validity.UNAVAILABLE, null, null, "no payout provider is configured");
        UUID id = UUID.randomUUID();
        if (gateway != null) {
            try {
                v = gateway.port().validate(new PayoutGateway.Beneficiary(in.holderName().trim(), account, ifsc), "BV" + id.toString().replace("-", ""));
            } catch (ProviderException e) {
                v = new PayoutGateway.Validation(PayoutGateway.Validity.UNAVAILABLE, null, null, e.getMessage());
            }
        }
        if (v.validity() == PayoutGateway.Validity.INVALID) {
            audit.record(CurrentUser.username(), "BENEFICIARY_INVALID", "LOAN", loan.loanNo(), Map.of("accountLast4", Secrets.last4(account)));
            throw ApiException.invalid("the bank did not confirm this account: " + (v.reason() == null ? "validation failed" : v.reason()));
        }
        jdbc.update("UPDATE integration.beneficiary SET status = 'REPLACED' WHERE loan_id = ? AND status = 'ACTIVE'", loanId);
        jdbc.update("""
                INSERT INTO integration.beneficiary (id, loan_id, customer_id, holder_name_cipher, account_cipher, account_last4, account_hash,
                                                     ifsc, validation, validation_ref, validation_note, name_at_bank_cipher, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, loanId, loan.customerId(), secrets.seal(in.holderName().trim(), NAME_AAD), secrets.seal(account, ACCOUNT_AAD),
                Secrets.last4(account), secrets.hash("BANK_ACCOUNT", ifsc + ":" + account), ifsc, v.validity().name(), v.providerRef(),
                v.reason(), v.nameAtBank() == null ? null : secrets.seal(v.nameAtBank(), NAME_AAD), CurrentUser.username());
        audit.record(CurrentUser.username(), "BENEFICIARY_SET", "LOAN", loan.loanNo(),
                Map.of("accountLast4", Secrets.last4(account), "ifsc", ifsc, "validation", v.validity().name()));
        return beneficiary(loanId);
    }

    Map<String, Object> beneficiary(UUID loanId) {
        scope.requireRecord(loans.find(loanId).branch(), "loan " + loanId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, 'XXXXXXXX' || account_last4 AS "accountMasked", ifsc, validation, validation_note AS "validationNote",
                       created_by AS "createdBy", created_at AS "createdAt"
                  FROM integration.beneficiary WHERE loan_id = ? AND status = 'ACTIVE'
                """, loanId);
        if (rows.isEmpty()) throw ApiException.notFound("payout beneficiary of loan " + loanId);
        return rows.get(0);
    }

    // ------------------------------------------------------------------------------------------------ instruction
    @Override
    public void on(long outboxId, String topic, String aggregateId, Map<String, Object> payload, OffsetDateTime at) {
        if (!LoanEvents.DISBURSED.equals(topic)) return;
        UUID loanId = UUID.fromString(String.valueOf(payload.get("loanId")));
        UUID txn = UUID.fromString(String.valueOf(payload.get("transactionId")));
        if (!jdbc.queryForList("SELECT 1 FROM integration.payout_instruction WHERE disbursement_txn = ?", txn).isEmpty()) return;
        LoanOperations.LoanRef loan = loans.find(loanId);
        BigDecimal net = new BigDecimal(String.valueOf(payload.get("netDisbursed")));
        if (net.signum() <= 0) return;
        create(loan, net, txn, "SYSTEM", "disbursement posted");
    }

    private UUID create(LoanOperations.LoanRef loan, BigDecimal amount, UUID disbursementTxn, String source, String note) {
        Integer attempt = jdbc.queryForObject("SELECT coalesce(max(attempt_no), 0) + 1 FROM integration.payout_instruction WHERE loan_id = ?",
                Integer.class, loan.id());
        List<Map<String, Object>> b = jdbc.queryForList(
                "SELECT id, validation FROM integration.beneficiary WHERE loan_id = ? AND status = 'ACTIVE'", loan.id());
        boolean usable = !b.isEmpty() && !"INVALID".equals(b.get(0).get("validation"));
        UUID id = UUID.randomUUID();
        String status = usable ? "INITIATED" : "ON_HOLD";
        jdbc.update("""
                INSERT INTO integration.payout_instruction (id, reference, loan_id, loan_no, customer_id, branch_code, attempt_no, amount,
                    beneficiary_id, status, stp, disbursement_txn, needs_action, action_note, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END)
                """, id, "PO" + loan.loanNo() + "A" + attempt, loan.id(), loan.loanNo(), loan.customerId(), loan.branch(), attempt, amount,
                b.isEmpty() ? null : b.get(0).get("id"), status, "API".equals(loan.source()), disbursementTxn, !usable,
                usable ? null : "no validated beneficiary account: record one and retry", usable);
        event(id, null, status, source, Map.of("note", note));
        return id;
    }

    private void event(UUID payoutId, String from, String to, String source, Map<String, ?> detail) {
        jdbc.update("INSERT INTO integration.payout_event (payout_id, from_status, to_status, source, actor, detail) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                payoutId, from, to, source, CurrentUser.username(), json.write(detail));
    }

    // ------------------------------------------------------------------------------------------------ status news
    /**
     * Applies what a provider said about a payout, in its own transaction.
     *
     * @return what was done: APPLY, DUPLICATE, STALE or CONFLICT
     */
    Lifecycle.Decision apply(UUID payoutId, News news, String source) {
        return tx.execute(s -> {
            Map<String, Object> p = jdbc.queryForMap("SELECT * FROM integration.payout_instruction WHERE id = ? FOR UPDATE", payoutId);
            Lifecycle.Payout from = Lifecycle.Payout.valueOf((String) p.get("status"));
            Lifecycle.Decision d = Lifecycle.decide(from, news.status());
            if (d == Lifecycle.Decision.CONFLICT) {
                jdbc.update("UPDATE integration.payout_instruction SET needs_action = true, action_note = ?, updated_at = now() WHERE id = ?",
                        "the provider reported " + news.status() + " while the payout is " + from + ": check with the provider", payoutId);
                event(payoutId, from.name(), from.name(), source, Map.of("conflict", news.status().name()));
                return d;
            }
            if (d != Lifecycle.Decision.APPLY) {
                // a repeat can still bring the reference or UTR that the first notice lacked
                jdbc.update("UPDATE integration.payout_instruction SET provider_ref = coalesce(provider_ref, ?), utr = coalesce(utr, ?) WHERE id = ?",
                        news.providerRef(), news.utr(), payoutId);
                return d;
            }
            Lifecycle.Payout to = news.status();
            boolean bad = to == Lifecycle.Payout.FAILED || to == Lifecycle.Payout.RETURNED;
            boolean pending = to == Lifecycle.Payout.SENT;
            jdbc.update("""
                    UPDATE integration.payout_instruction
                       SET status = ?, provider_ref = coalesce(?, provider_ref), utr = coalesce(?, utr), failure_code = ?, failure_reason = ?,
                           sent_at = coalesce(sent_at, now()), completed_at = CASE WHEN ? THEN NULL ELSE now() END,
                           attempts = CASE WHEN ? THEN 0 ELSE attempts END,
                           next_attempt_at = CASE WHEN ? THEN now() + interval '2 minutes' END,
                           needs_action = ?, action_note = ?, last_error = NULL, updated_at = now()
                     WHERE id = ?
                    """, to.name(), news.providerRef(), news.utr(), news.failureCode(), news.failureReason(), pending, pending, pending,
                    bad, bad ? "payout " + to + ": the disbursement is being reversed or proposed for reversal" : null, payoutId);
            event(payoutId, from.name(), to.name(), source, Map.of("utr", String.valueOf(news.utr()), "failureCode", String.valueOf(news.failureCode())));
            publish(payoutId, from.name(), null);
            return d;
        });
    }

    private void publish(UUID payoutId, String previous, String action) {
        Map<String, Object> p = jdbc.queryForMap("""
                SELECT i.reference, i.loan_id, i.loan_no, i.customer_id, i.status, i.amount, i.utr, i.failure_code, i.failure_reason,
                       b.account_last4, l.external_ref
                  FROM integration.payout_instruction i JOIN lending.loan_account l ON l.id = i.loan_id
                  LEFT JOIN integration.beneficiary b ON b.id = i.beneficiary_id WHERE i.id = ?
                """, payoutId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payoutRef", p.get("reference"));
        m.put("loanId", String.valueOf(p.get("loan_id")));
        m.put("loanNo", p.get("loan_no"));
        m.put("customerId", String.valueOf(p.get("customer_id")));
        m.put("externalRef", p.get("external_ref"));
        m.put("status", p.get("status"));
        m.put("previousStatus", previous);
        m.put("amount", ((BigDecimal) p.get("amount")).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        m.put("utr", p.get("utr"));
        m.put("failureCode", p.get("failure_code"));
        m.put("failureReason", p.get("failure_reason"));
        m.put("beneficiaryAccountMasked", p.get("account_last4") == null ? null : "XXXXXXXX" + p.get("account_last4"));
        m.put("action", action);
        outbox.publish(jdbc, WebhookEvents.PAYOUT_STATUS, (String) p.get("reference"), m);
    }

    /** A provider callback about a payout. Returns a short outcome for the inbound event log. */
    String applyInbound(InboundEvent e) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM integration.payout_instruction WHERE reference = ? OR (?::text IS NOT NULL AND provider_ref = ?)",
                UUID.class, e.reference(), e.providerRef(), e.providerRef());
        if (ids.isEmpty()) return "IGNORED: no payout with this reference";
        Lifecycle.Payout status;
        try {
            status = Lifecycle.Payout.valueOf(e.status());
        } catch (IllegalArgumentException x) {
            return "IGNORED: unknown payout status";
        }
        return apply(ids.get(0), new News(status, e.providerRef(), e.utr(), e.reasonCode(), e.reason()), "WEBHOOK").name();
    }

    // ------------------------------------------------------------------------------------------------ worker
    private record Claim(UUID id, String reference, String status, String providerRef, BigDecimal amount, String mode, UUID beneficiaryId,
                         String loanNo, int attempts) {}

    private List<Claim> claim() {
        // the lease keeps another instance (or the next tick) away while the provider is being called
        return jdbc.query("""
                UPDATE integration.payout_instruction SET next_attempt_at = now() + interval '3 minutes', attempts = attempts + 1
                 WHERE id IN (SELECT id FROM integration.payout_instruction
                               WHERE next_attempt_at <= now() AND status IN ('INITIATED','SENT')
                               ORDER BY next_attempt_at LIMIT 20 FOR UPDATE SKIP LOCKED)
                RETURNING id, reference, status, provider_ref, amount, mode, beneficiary_id, loan_no, attempts
                """, (rs, i) -> new Claim(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                        rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8), rs.getInt(9)));
    }

    /** Sends INITIATED payouts and polls SENT ones. */
    void work() {
        for (Claim c : claim()) {
            ProviderConfigService.Active<PayoutGateway> gateway = providers.payout();
            if (gateway == null) {
                park(c.id(), "no payout provider is active for this tenant", false);
                continue;
            }
            try {
                PayoutGateway.Result r;
                if ("SENT".equals(c.status())) {
                    r = gateway.port().status(c.reference(), c.providerRef());
                } else {
                    Map<String, Object> b = jdbc.queryForMap("SELECT holder_name_cipher, account_cipher, ifsc FROM integration.beneficiary WHERE id = ?",
                            c.beneficiaryId());
                    jdbc.update("UPDATE integration.payout_instruction SET provider = ? WHERE id = ?", gateway.code(), c.id());
                    r = gateway.port().send(new PayoutGateway.Request(c.reference(), c.amount().setScale(2, java.math.RoundingMode.HALF_UP),
                            new PayoutGateway.Beneficiary(secrets.open((byte[]) b.get("holder_name_cipher"), NAME_AAD),
                                    secrets.open((byte[]) b.get("account_cipher"), ACCOUNT_AAD), (String) b.get("ifsc")),
                            c.mode(), "Loan " + c.loanNo()));
                }
                Lifecycle.Decision d = apply(c.id(), new News(r.status(), r.providerRef(), r.utr(), r.failureCode(), r.failureReason()),
                        "SENT".equals(c.status()) ? "POLL" : "API");
                if (r.status() == Lifecycle.Payout.SENT && d != Lifecycle.Decision.APPLY) backoff(c, RetrySchedule.POSTING, "still pending at the provider");
            } catch (ProviderException e) {
                if (!e.retryable()) {
                    // INITIATED: nothing was sent. SENT: this provider cannot be polled, the callback will tell.
                    park(c.id(), e.getMessage(), "SENT".equals(c.status()));
                } else {
                    backoff(c, RetrySchedule.PROVIDER.withMaxAttempts(props.number("payout.max-send-attempts", 8, 1, 20)), e.getMessage());
                }
            }
        }
    }

    /** No answer: try again later with the same reference. The outcome stays unknown — never FAILED on our own. */
    private void backoff(Claim c, RetrySchedule schedule, String error) {
        if (schedule.exhausted(c.attempts())) {
            park(c.id(), "gave up after " + c.attempts() + " attempts (" + error + "); the outcome is unknown: check with the provider", false);
            return;
        }
        jdbc.update("UPDATE integration.payout_instruction SET next_attempt_at = now() + make_interval(secs => ?), last_error = ?, updated_at = now() WHERE id = ?",
                schedule.delaySeconds(c.attempts(), ThreadLocalRandom.current().nextDouble()), error, c.id());
    }

    private void park(UUID id, String note, boolean quiet) {
        jdbc.update("""
                UPDATE integration.payout_instruction
                   SET next_attempt_at = NULL, last_error = ?, needs_action = needs_action OR NOT ?, action_note = CASE WHEN ? THEN action_note ELSE ? END,
                       updated_at = now()
                 WHERE id = ?
                """, note, quiet, quiet, note, id);
    }

    /** Failed and returned payouts whose disbursement has not been dealt with yet. */
    void handleFailures() {
        if (!"OPEN".equals(days.current() == null ? null : days.current().status())) return;       // reversals post: wait for the books
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, reference, loan_id, loan_no, branch_code, amount, stp, status, failure_reason
                  FROM integration.payout_instruction
                 WHERE status IN ('FAILED','RETURNED') AND failure_action IS NULL ORDER BY updated_at LIMIT 20
                """);
        for (Map<String, Object> p : rows) {
            UUID id = (UUID) p.get("id");
            String reason = "payout " + p.get("reference") + " " + p.get("status") + (p.get("failure_reason") == null ? "" : ": " + p.get("failure_reason"));
            String mode = Boolean.TRUE.equals(p.get("stp")) ? "REVERSE" : props.text("payout.failure-action", "PROPOSE");
            try {
                tx.executeWithoutResult(s -> {
                    if ("REVERSE".equals(mode)) {
                        loans.reverseDisbursement((UUID) p.get("loan_id"), reason, CurrentUser.username());
                        settle(id, "REVERSED", null, "disbursement reversed; the loan is SANCTIONED again", true);
                    } else if ("PARK".equalsIgnoreCase(mode)) {
                        settle(id, "PARKED", null, "parked by tenant setting: retry the payout or propose the reversal", true);
                    } else {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("loanId", String.valueOf(p.get("loan_id")));
                        payload.put("loanNo", p.get("loan_no"));
                        payload.put("payoutId", id.toString());
                        payload.put("reason", reason);
                        ApprovalRequest a = approvals.propose(REVERSAL_ENTITY, "REVERSE", (String) p.get("loan_no"), payload, null,
                                (BigDecimal) p.get("amount"), (String) p.get("branch_code"), "payout-" + p.get("reference"));
                        settle(id, "PROPOSED", a.id(), "reversal of the disbursement proposed: approve it, or reject it and retry the payout", true);
                    }
                });
            } catch (RuntimeException e) {
                String why = e instanceof ApiException ? e.getMessage() : "the reversal could not be made";
                tx.executeWithoutResult(s -> settle(id, "PARKED", null, "not reversed automatically: " + why, false));
            }
        }
    }

    private void settle(UUID id, String action, UUID approvalId, String note, boolean announce) {
        jdbc.update("""
                UPDATE integration.payout_instruction
                   SET failure_action = ?, reversal_approval_id = ?, needs_action = ?, action_note = ?, updated_at = now() WHERE id = ?
                """, action, approvalId, !"REVERSED".equals(action), note, id);
        String status = jdbc.queryForObject("SELECT status FROM integration.payout_instruction WHERE id = ?", String.class, id);
        event(id, status, status, "SYSTEM", Map.of("action", action, "note", note));
        if (announce) publish(id, status, action);
    }

    /** A checker approved the reversal of a disbursement whose payout failed. */
    String applyReversal(ApprovalRequest r) {
        UUID loanId = UUID.fromString(String.valueOf(r.payload().get("loanId")));
        UUID payoutId = UUID.fromString(String.valueOf(r.payload().get("payoutId")));
        String loanNo = loans.reverseDisbursement(loanId, String.valueOf(r.payload().get("reason")), r.maker());
        settle(payoutId, "REVERSED", r.id(), "disbursement reversed; the loan is SANCTIONED again", true);
        return loanNo;
    }

    // ------------------------------------------------------------------------------------------------ operations
    /**
     * Operations: resume an ON_HOLD payout once a beneficiary is recorded, send again one whose outcome stayed
     * unknown (same reference: the provider de-duplicates), or make a new attempt after a failure that was parked.
     */
    @Transactional
    Map<String, Object> retry(UUID payoutId) {
        Map<String, Object> p = one(payoutId);
        String status = (String) p.get("status");
        UUID loanId = (UUID) p.get("loanId");
        if ("ON_HOLD".equals(status)) {
            List<Map<String, Object>> b = jdbc.queryForList(
                    "SELECT id FROM integration.beneficiary WHERE loan_id = ? AND status = 'ACTIVE' AND validation <> 'INVALID'", loanId);
            if (b.isEmpty()) throw ApiException.conflict("record a beneficiary account for the loan first");
            jdbc.update("""
                    UPDATE integration.payout_instruction SET status = 'INITIATED', beneficiary_id = ?, next_attempt_at = now(), attempts = 0,
                           needs_action = false, action_note = NULL, updated_at = now() WHERE id = ?
                    """, b.get(0).get("id"), payoutId);
            event(payoutId, "ON_HOLD", "INITIATED", "OPERATOR", Map.of("note", "resumed"));
        } else if ("INITIATED".equals(status)) {
            jdbc.update("UPDATE integration.payout_instruction SET next_attempt_at = now(), attempts = 0, needs_action = false, action_note = NULL, updated_at = now() WHERE id = ?",
                    payoutId);
            event(payoutId, status, status, "OPERATOR", Map.of("note", "send again with the same reference"));
        } else if (("FAILED".equals(status) || "RETURNED".equals(status)) && "PARKED".equals(p.get("failureAction"))) {
            LoanOperations.LoanRef loan = loans.find(loanId);
            if (!"ACTIVE".equals(loan.status())) throw ApiException.conflict("loan is " + loan.status() + ": disburse it again instead");
            jdbc.update("UPDATE integration.payout_instruction SET needs_action = false, action_note = 'a new attempt was made', updated_at = now() WHERE id = ?", payoutId);
            // the new attempt pays the same disbursement (tranche): the one-live-payout rule is per disbursement (V21)
            UUID next = create(loan, jdbc.queryForObject("SELECT amount FROM integration.payout_instruction WHERE id = ?", BigDecimal.class, payoutId),
                    jdbc.queryForObject("SELECT disbursement_txn FROM integration.payout_instruction WHERE id = ?", UUID.class, payoutId),
                    "OPERATOR", "new attempt after " + p.get("reference"));
            audit.record(CurrentUser.username(), "PAYOUT_RETRY", "PAYOUT", (String) p.get("reference"), Map.of("newPayoutId", next.toString()));
            return one(next);
        } else {
            throw ApiException.conflict("a payout that is " + status + (p.get("failureAction") == null ? "" : " (" + p.get("failureAction") + ")") + " cannot be retried");
        }
        audit.record(CurrentUser.username(), "PAYOUT_RETRY", "PAYOUT", (String) p.get("reference"), Map.of("from", status));
        return one(payoutId);
    }

    /** Operations: ask the provider now. */
    Map<String, Object> refresh(UUID payoutId) {
        Map<String, Object> p = one(payoutId);
        if (!List.of("SENT", "SUCCESS").contains(p.get("status"))) throw ApiException.conflict("only a sent or paid payout can be refreshed");
        ProviderConfigService.Active<PayoutGateway> gateway = providers.payout();
        if (gateway == null) throw ApiException.conflict("no payout provider is active");
        try {
            PayoutGateway.Result r = gateway.port().status((String) p.get("reference"), (String) p.get("providerRef"));
            apply(payoutId, new News(r.status(), r.providerRef(), r.utr(), r.failureCode(), r.failureReason()), "POLL");
        } catch (ProviderException e) {
            throw ApiException.conflict("the provider could not be asked: " + e.getMessage());
        }
        return one(payoutId);
    }

    private static final String SELECT = """
            SELECT i.id, i.reference, i.loan_id AS "loanId", i.loan_no AS "loanNo", i.branch_code AS branch, i.attempt_no AS "attemptNo",
                   i.amount::text AS amount, i.mode, i.provider, i.status, i.provider_ref AS "providerRef", i.utr,
                   i.failure_code AS "failureCode", i.failure_reason AS "failureReason", i.stp, i.needs_action AS "needsAction",
                   i.action_note AS "actionNote", i.failure_action AS "failureAction", i.reversal_approval_id AS "reversalApprovalId",
                   i.attempts, i.last_error AS "lastError", i.created_at AS "createdAt", i.sent_at AS "sentAt", i.completed_at AS "completedAt",
                   CASE WHEN b.id IS NULL THEN NULL ELSE 'XXXXXXXX' || b.account_last4 END AS "beneficiaryAccountMasked", b.ifsc AS "beneficiaryIfsc"
              FROM integration.payout_instruction i LEFT JOIN integration.beneficiary b ON b.id = i.beneficiary_id
             WHERE i.branch_code IN (SELECT branch_code FROM platform.visible_branches(?))""";

    Map<String, Object> one(UUID payoutId) {
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " AND i.id = ?", scope.user(), payoutId);
        if (rows.isEmpty()) throw ApiException.notFound("payout " + payoutId);
        Map<String, Object> m = new LinkedHashMap<>(rows.get(0));
        m.put("events", jdbc.queryForList("""
                SELECT at, from_status AS "from", to_status AS "to", source, actor FROM integration.payout_event WHERE payout_id = ? ORDER BY id
                """, payoutId));
        return m;
    }

    /** Reconciliation query: payouts by status, attention flag, loan and creation date. */
    List<Map<String, Object>> search(String status, Boolean needsAction, UUID loanId, java.time.LocalDate from, java.time.LocalDate to) {
        return jdbc.queryForList(SELECT + """
                 AND (?::text IS NULL OR i.status = ?) AND (?::boolean IS NULL OR i.needs_action = ?) AND (?::uuid IS NULL OR i.loan_id = ?)
                 AND (?::date IS NULL OR i.created_at >= ?::date) AND (?::date IS NULL OR i.created_at < ?::date + 1)
                 ORDER BY i.created_at DESC LIMIT 500
                """, scope.user(), status, status, needsAction, needsAction, loanId, loanId, from, from, to, to);
    }

    @Component
    static class SendTask implements IntegrationTask {
        private final PayoutService payouts;

        SendTask(@Lazy PayoutService payouts) {
            this.payouts = payouts;
        }

        @Override public String name() { return "payout-send-and-poll"; }

        @Override public void run() { payouts.work(); }
    }

    @Component
    static class FailureTask implements IntegrationTask {
        private final PayoutService payouts;

        FailureTask(@Lazy PayoutService payouts) {
            this.payouts = payouts;
        }

        @Override public String name() { return "payout-failures"; }

        @Override public void run() { payouts.handleFailures(); }
    }

    @Component
    static class ReversalApplier implements ApprovalApplier {
        private final PayoutService payouts;

        ReversalApplier(@Lazy PayoutService payouts) {
            this.payouts = payouts;
        }

        @Override public String entityType() { return REVERSAL_ENTITY; }

        @Override public String apply(ApprovalRequest r) { return payouts.applyReversal(r); }
    }
}
