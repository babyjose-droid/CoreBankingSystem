package com.corebanking.kernel;

import java.util.List;

/** Which rate-limit class a request path belongs to: exempt (batch and internal paths), strict (enumeration-prone lookups) or normal. */
public final class RateLimitPaths {

    public enum Kind { EXEMPT, STRICT, NORMAL }

    private final List<String> exempt;
    private final List<String> strict;

    public RateLimitPaths(List<String> exempt, List<String> strict) {
        this.exempt = exempt.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.strict = strict.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** A prefix matches the path itself and anything below it ("/api/v1/eod" matches "/api/v1/eod/run" but not "/api/v1/eodx"). */
    static boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
    }

    public Kind classify(String path) {
        String p = path == null ? "" : path;
        if (exempt.stream().anyMatch(x -> matches(p, x))) return Kind.EXEMPT;
        if (strict.stream().anyMatch(x -> matches(p, x))) return Kind.STRICT;
        return Kind.NORMAL;
    }
}
