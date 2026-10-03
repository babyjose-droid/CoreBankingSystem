package com.corebanking.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link DocumentStore} on a directory ({@code corebanking.documents.dir}). Files and directories are created
 * with owner-only permissions. The content type is not stored here: callers keep it with their own metadata.
 * <p>
 * This is the only implementation today. The S3 implementation is pending (it needs the AWS SDK dependency); in
 * the cloud tiers point the directory at an encrypted volume until then.
 */
@Component
public class FileDocumentStore implements DocumentStore {

    private static final Set<PosixFilePermission> DIR_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    private final Path root;

    public FileDocumentStore(@Value("${corebanking.documents.dir:${java.io.tmpdir}/corebanking-documents}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        Path target = resolve(key);
        try {
            createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                ownerOnly(temp, FILE_PERMISSIONS);
                Files.write(temp, content);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not store the document", e);
        }
    }

    @Override
    public byte[] get(String key) {
        Path target = resolve(key);
        try {
            return Files.readAllBytes(target);
        } catch (NoSuchFileException e) {
            throw ApiException.notFound("document");
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the document", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("could not delete the document", e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public long bytesUnder(String prefix) {
        Path dir = resolve(prefix);
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(FileDocumentStore::sizeOf).sum();
        } catch (IOException e) {
            throw new UncheckedIOException("could not measure the document store", e);
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;                               // removed while we were counting
        }
    }

    /** The file for a key, always inside the root. */
    Path resolve(String key) {
        checkKey(key);
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root) || p.equals(root)) throw new IllegalArgumentException("document key is not valid");
        return p;
    }

    /** Keys are relative, forward-slash paths of plain segments: no "..", no leading "/", no backslash or control character. */
    static void checkKey(String key) {
        if (key == null || key.isBlank() || key.length() > 512) throw new IllegalArgumentException("document key is not valid");
        if (key.startsWith("/") || key.contains("..") || key.contains("\\") || key.contains("//") || key.endsWith("/")) {
            throw new IllegalArgumentException("document key is not valid");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x20 || c == 0x7F || c == ':') throw new IllegalArgumentException("document key is not valid");
        }
    }

    private void createDirectories(Path dir) throws IOException {
        if (Files.isDirectory(dir)) return;
        createDirectories(dir.getParent());
        try {
            Files.createDirectory(dir);
            ownerOnly(dir, DIR_PERMISSIONS);
        } catch (FileAlreadyExistsException e) {
            // created by a concurrent upload
        }
    }

    private static void ownerOnly(Path p, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(p, permissions);
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system (Windows developer machine): rely on the directory's ACL
        }
    }
}
