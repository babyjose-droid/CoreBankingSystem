package com.corebanking.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentKeyTest {

    @Test
    void keys_are_tenant_prefixed() {
        assertEquals("tenants/claude-test/reports/2026-09-10/LOAN_BOOK-1.csv",
                DocumentKey.forTenant("claude-test", "reports", "2026-09-10", "LOAN_BOOK-1.csv"));
        assertThrows(IllegalArgumentException.class, () -> DocumentKey.forTenant("Bad Tenant", "reports", "x"));
        assertThrows(IllegalArgumentException.class, () -> DocumentKey.forTenant(null, "reports", "x"));
        assertThrows(IllegalArgumentException.class, () -> DocumentKey.forTenant("claude-test"));
    }

    @Test
    void path_tricks_are_refused() {
        for (String bad : List.of("../etc/passwd", "tenants/a/../b", "tenants/abc/./x", "/abs/path", "tenants//x", "tenants/abc/x/",
                "tenants/abc/.hidden", "tenants/abc/a b", "tenants/abc/a\\b", "tenants/abc/a\u0000b", "tenants/abc/x%2e%2e", "", "C:/x",
                "tenants/abc/" + "x".repeat(600))) {
            assertThrows(IllegalArgumentException.class, () -> DocumentKey.validate(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> DocumentKey.validate(null));
        assertThrows(IllegalArgumentException.class, () -> DocumentKey.forTenant("claude-test", "reports", "..", "x"));
        assertThrows(IllegalArgumentException.class, () -> DocumentKey.forTenant("claude-test", "reports/../../other"));
    }

    @Test
    void another_tenants_key_is_refused() {
        String key = DocumentKey.forTenant("claude-test", "reports", "a.csv");
        assertEquals(key, DocumentKey.requireTenant(key, "claude-test"));
        assertThrows(IllegalArgumentException.class, () -> DocumentKey.requireTenant(key, "claude-other"));
        assertThrows(IllegalArgumentException.class, () -> DocumentKey.requireTenant(key, "claude"));   // a prefix of the code is not the tenant
        assertThrows(IllegalArgumentException.class, () -> DocumentKey.requireTenant(key, null));
    }

    @Test
    void file_names_are_made_safe_for_a_header() {
        assertEquals("statement-10010000000017.pdf", DocumentKey.safeFileName("statement-10010000000017.pdf"));
        assertEquals("a_b__c_.csv", DocumentKey.safeFileName("a b\"\nc;.csv"));
        assertEquals("hidden", DocumentKey.safeFileName("..hidden"));
        assertEquals("file", DocumentKey.safeFileName(null));
    }
}
