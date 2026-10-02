package com.corebanking.kernel;

import java.util.regex.Pattern;

/**
 * Keys of stored documents and report files (P2-4). A key is a relative path of plain segments, always under the
 * tenant's own prefix: {@code tenants/<tenant code>/<area>/…}. The same key works for a directory on disk and for an
 * object store. Keys are checked here, in one place, so that no store has to guard against path tricks itself.
 */
public final class DocumentKey {

    /** Segments start with a letter or digit, so "." and ".." can never be a segment. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*");
    private static final Pattern TENANT = Pattern.compile("[a-z][a-z0-9-]{2,30}");
    private static final int MAX_LENGTH = 512;

    private DocumentKey() {}

    /** Builds {@code tenants/<tenant>/<part>/<part>…} and checks it. */
    public static String forTenant(String tenant, String... parts) {
        if (tenant == null || !TENANT.matcher(tenant).matches()) throw new IllegalArgumentException("invalid tenant code");
        if (parts.length == 0) throw new IllegalArgumentException("a document key needs at least one part after the tenant");
        return validate("tenants/" + tenant + "/" + String.join("/", parts));
    }

    /** Returns the key when it is well formed; refuses anything that could leave the store's root. */
    public static String validate(String key) {
        if (key == null || key.length() > MAX_LENGTH || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid document key");
        }
        return key;
    }

    /** The key, when it belongs to the tenant; a key of another tenant is refused. */
    public static String requireTenant(String key, String tenant) {
        validate(key);
        if (tenant == null || !TENANT.matcher(tenant).matches() || !key.startsWith("tenants/" + tenant + "/")) {
            throw new IllegalArgumentException("the document belongs to another tenant");
        }
        return key;
    }

    /** A file name safe for a Content-Disposition header: anything but letters, digits, dot, dash and underscore becomes "_". */
    public static String safeFileName(String name) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        while (n.startsWith(".")) n = n.substring(1);
        return n.isEmpty() ? "file" : n.length() > 120 ? n.substring(n.length() - 120) : n;
    }
}
