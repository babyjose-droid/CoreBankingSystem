package com.corebanking.integration.core.keycloak;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What an API client (an LOS, a partner) may be granted (US-120). Scopes are the product's permissions, from an
 * allow-list: an API client can never hold a permission that approves, administers or configures.
 * <p>
 * Scopes that move money, or decide where money goes, are sensitive: granting them needs two checkers.
 */
public final class ApiClientScopes {

    /** Everything an API client may hold. */
    public static final List<String> GRANTABLE = List.of(
            "customer:view", "customer:create", "consent:view", "consent:record", "kyc:upload",
            "product:view", "loan:view", "loan:create", "loan:stp", "loan:repay",
            "payout:view", "payout:beneficiary", "collection:view", "collection:create",
            "mandate:view", "mandate:register", "report:run");

    /** Straight-through disbursement, posting repayments, and setting the account a disbursement is paid to. */
    public static final Set<String> SENSITIVE = Set.of("loan:stp", "loan:repay", "payout:beneficiary");

    private ApiClientScopes() {}

    /**
     * The scopes, de-duplicated and sorted.
     *
     * @throws IllegalArgumentException naming the scopes that cannot be granted to an API client
     */
    public static List<String> validate(Collection<String> scopes) {
        if (scopes == null || scopes.isEmpty()) throw new IllegalArgumentException("at least one scope is required");
        TreeSet<String> ok = new TreeSet<>();
        TreeSet<String> refused = new TreeSet<>();
        for (String s : scopes) {
            if (s != null && GRANTABLE.contains(s)) ok.add(s); else refused.add(String.valueOf(s));
        }
        if (!refused.isEmpty()) throw new IllegalArgumentException("these scopes cannot be granted to an API client: " + refused);
        return List.copyOf(ok);
    }

    public static boolean sensitive(Collection<String> scopes) {
        return scopes.stream().anyMatch(SENSITIVE::contains);
    }

    /**
     * The maker-checker action for a change: the plain action, or the same with {@code _SENSITIVE} when the scopes
     * after the change include a sensitive one — the approval rules ask for two checkers on those (V20).
     */
    public static String action(String base, Collection<String> scopesAfter) {
        return sensitive(scopesAfter) ? base + "_SENSITIVE" : base;
    }
}
