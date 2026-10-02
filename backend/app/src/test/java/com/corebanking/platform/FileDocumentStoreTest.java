package com.corebanking.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FileDocumentStoreTest {

    static final String KEY = "tenants/claude-test/kyc/00000000-0000-0000-0000-0000000000c1/00000000-0000-0000-0000-0000000d0001";
    static final byte[] CONTENT = "%PDF-1.7 CLAUDE-TEST".getBytes(StandardCharsets.US_ASCII);

    static FileDocumentStore store() throws Exception {
        return new FileDocumentStore(Files.createTempDirectory("claude-test-docs").toString());
    }

    @Test
    void put_get_exists_delete() throws Exception {
        FileDocumentStore s = store();
        assertFalse(s.exists(KEY));
        s.put(KEY, CONTENT, "application/pdf");
        assertTrue(s.exists(KEY));
        assertArrayEquals(CONTENT, s.get(KEY));
        s.put(KEY, new byte[] {1, 2, 3}, "application/pdf");                 // replaces
        assertArrayEquals(new byte[] {1, 2, 3}, s.get(KEY));
        s.delete(KEY);
        assertFalse(s.exists(KEY));
        s.delete(KEY);                                                       // deleting twice is not an error
        ApiException e = assertThrows(ApiException.class, () -> s.get(KEY));
        assertEquals(404, e.status().value());
    }

    @Test
    void files_and_directories_are_owner_only() throws Exception {
        FileDocumentStore s = store();
        s.put(KEY, CONTENT, "application/pdf");
        Path file = s.resolve(KEY);
        if (!Files.getFileStore(file).supportsFileAttributeView("posix")) return;
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        try (var left = Files.list(file.getParent())) {
            assertEquals(1L, left.count());                                  // no temporary file stays behind
        }
    }

    @Test
    void keys_that_could_leave_the_root_are_rejected() throws Exception {
        FileDocumentStore s = store();
        for (String bad : List.of("../outside", "tenants/../../etc/passwd", "/etc/passwd", "tenants/a/..", "a\\b", "a//b", "a/",
                "", " ", "c:/windows", "a/b\u0000c")) {
            assertThrows(IllegalArgumentException.class, () -> s.put(bad, CONTENT, "application/pdf"));
            assertThrows(IllegalArgumentException.class, () -> s.get(bad));
            assertThrows(IllegalArgumentException.class, () -> s.exists(bad));
            assertThrows(IllegalArgumentException.class, () -> s.delete(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> s.get(null));
    }

    @Test
    void roles_come_from_roles_claim_and_realm_access() {
        assertEquals(Set.of("MAKER", "BRANCH_MANAGER"),
                CurrentUser.roles(Map.of("roles", List.of("MAKER"), "realm_access", Map.of("roles", List.of("BRANCH_MANAGER", "MAKER")))));
        assertEquals(Set.of("CHECKER"), CurrentUser.roles(Map.of("realm_access", Map.of("roles", List.of("CHECKER")))));
        assertEquals(Set.of(), CurrentUser.roles(Map.of("permissions", List.of("customer:view"))));
        assertEquals(Set.of(), CurrentUser.roles(Map.of("roles", "MAKER", "realm_access", "x")));   // wrong shapes are ignored
        assertEquals(Set.of("MAKER"), CurrentUser.roles(Map.of("roles", List.of("MAKER", 7, " "))));
    }
}
