package com.corebanking.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Field-level difference between the current and proposed state, for the checker's review screen. */
public final class FieldDiff {

    private FieldDiff() {}

    public record Change(String path, Object before, Object after) {}

    public static List<Change> between(Map<String, ?> before, Map<String, ?> after) {
        List<Change> out = new ArrayList<>();
        walk("", before == null ? Map.of() : before, after == null ? Map.of() : after, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void walk(String prefix, Map<String, ?> a, Map<String, ?> b, List<Change> out) {
        TreeSet<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            Object x = a.get(k), y = b.get(k);
            String path = prefix.isEmpty() ? k : prefix + "." + k;
            if (x instanceof Map<?, ?> mx && y instanceof Map<?, ?> my) {
                walk(path, (Map<String, ?>) mx, (Map<String, ?>) my, out);
            } else if (!equalValues(x, y)) {
                out.add(new Change(path, x, y));
            }
        }
    }

    private static boolean equalValues(Object x, Object y) {
        if (x instanceof java.math.BigDecimal bx && y instanceof java.math.BigDecimal by) return bx.compareTo(by) == 0;
        return Objects.equals(x, y);
    }
}
