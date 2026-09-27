package com.corebanking.platform.tenancy;

/**
 * The tenant of the current request or job. Set once at the edge (HTTP filter, queue listener, EOD
 * scheduler) and cleared in a finally block. Never inferred from request parameters.
 */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(String tenantCode) {
        if (tenantCode == null || !tenantCode.matches("^[a-z][a-z0-9-]{2,30}$")) {
            throw new IllegalArgumentException("invalid tenant code");
        }
        CURRENT.set(tenantCode);
    }

    public static String require() {
        String t = CURRENT.get();
        if (t == null) throw new IllegalStateException("no tenant bound to this thread");
        return t;
    }

    public static String currentOrNull() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
