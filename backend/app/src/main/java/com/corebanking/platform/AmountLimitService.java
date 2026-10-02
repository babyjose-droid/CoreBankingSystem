package com.corebanking.platform;

import com.corebanking.kernel.AmountLimits;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Enforces role amount limits (US-021). The rule is the pure {@link AmountLimits}; this service supplies the
 * user's roles from the token, the limits in force and what the user has already put through today, and records
 * what was allowed.
 * <ul>
 *   <li>Makers: called by the module before it posts or proposes ({@link #MAKE}); above the limit is 403.</li>
 *   <li>Checkers: called by {@link ApprovalService} before a decision is recorded ({@link #APPROVE}); above the
 *       limit is 403, so a checker with a higher limit must approve.</li>
 *   <li>Batch work (no person acting) is not limited.</li>
 * </ul>
 * Usage is recorded in the caller's transaction: a transaction that fails later leaves no usage behind. Usage is
 * recorded only while a limit applies to the user, so a limit introduced during the day counts from then on.
 */
@Service
public class AmountLimitService {

    public static final String MAKE = "MAKE";
    public static final String APPROVE = "APPROVE";

    private static final Logger log = LoggerFactory.getLogger(AmountLimitService.class);

    private final JdbcTemplate jdbc;
    private final BusinessDays days;

    public AmountLimitService(JdbcTemplate jdbc, BusinessDays days) {
        this.jdbc = jdbc;
        this.days = days;
    }

    /**
     * @param txnType   one of {@link AmountLimits#TYPES}
     * @param stage     {@link #MAKE} or {@link #APPROVE}
     * @param amount    the transaction amount; null is not limited
     * @param reference what the usage row points at (loan number, approval id)
     * @throws ApiException 403 with the reason when the user's roles do not allow the amount
     */
    public void require(String txnType, String stage, BigDecimal amount, String reference) {
        CurrentUser user = CurrentUser.get();
        if (amount == null || user.isSystem()) return;
        BusinessDays.BusinessDay bd = days.current();
        LocalDate day = bd == null ? LocalDate.now() : bd.businessDate();
        AmountLimits limits = new AmountLimits(load(txnType, day), defaultDeny());
        AmountLimits.Decision first = limits.check(user.roles(), txnType, amount, BigDecimal.ZERO, day);
        if (first.reason() == AmountLimits.Reason.NO_LIMIT_SET) return;
        String verb = APPROVE.equals(stage) ? "approve" : "post or propose";
        if (!first.allowed()) throw refuse(user, first, txnType, amount, BigDecimal.ZERO, verb);
        // A limit applies: serialise this user's transactions of this type for the day and count what is used.
        BigDecimal used = jdbc.queryForObject("SELECT platform.limit_used(?, ?, ?, ?)", BigDecimal.class,
                user.login(), txnType, stage, day);
        AmountLimits.Decision d = limits.check(user.roles(), txnType, amount, used, day);
        if (!d.allowed()) throw refuse(user, d, txnType, amount, used, verb);
        jdbc.update("""
                INSERT INTO platform.amount_limit_usage (username, txn_type, stage, business_date, amount, reference)
                VALUES (?, ?, ?, ?, ?, ?)
                """, user.login(), txnType, stage, day, amount, reference);
    }

    /** The limit type an approval is checked against, or null when approvals of this kind carry no amount limit. */
    public static String typeOf(ApprovalRequest r) {
        return switch (r.entityType()) {
            case "LOAN_DISBURSEMENT" -> "LOAN_DISBURSEMENT";
            case "LOAN_WAIVER" -> "FEE_WAIVER".equals(r.payload().get("limitType")) ? "FEE_WAIVER" : "LOAN_WAIVER";
            case "VOUCHER" -> "VOUCHER";
            default -> null;
        };
    }

    /** Limits in force on the day for one transaction type, every role. */
    private List<AmountLimits.Limit> load(String txnType, LocalDate day) {
        return jdbc.query("""
                SELECT role_name, txn_type, per_txn_max, per_day_max, effective_from, effective_to
                  FROM platform.amount_limit
                 WHERE txn_type = ? AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                """, (rs, i) -> new AmountLimits.Limit(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class)), txnType, day, day);
    }

    /** Tenant property {@code limits.default-deny}: a role without a limit row may not transact at all. */
    private boolean defaultDeny() {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = 'limits.default-deny'", String.class);
        return !v.isEmpty() && "true".equalsIgnoreCase(v.get(0).trim());
    }

    private static ApiException refuse(CurrentUser user, AmountLimits.Decision d, String txnType, BigDecimal amount,
                                       BigDecimal used, String verb) {
        log.warn("amount limit refused: user={} type={} reason={}", user.login(), txnType, d.reason());
        return ApiException.forbidden(AmountLimits.message(d, txnType, amount, used, verb));
    }
}
