/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.game;

import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Characterizes whole-file and legacy Forge library checksums and their cache consumers.
@NotNullByDefault
public class LibraryChecksumTest {
    /// Exercises validation through the library that owns the supplied checksum metadata.
    private static boolean validate(Path file, @Nullable List<String> checksums) {
        return library(checksums).validateChecksums(file);
    }

    /// Creates library metadata with only the legacy checksum list populated.
    private static Library library(@Nullable List<String> checksums) {
        return new Library(new Artifact("org.example", "library", "1.0"),
                null, null, checksums, null, null, null, null, null);
    }

    /// Calculates an expected SHA-1 independently of the production digest utility.
    private static String sha1(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    /// Calculates the checksum of UTF-8 fixture text.
    private static String sha1(String text) throws NoSuchAlgorithmException {
        return sha1(text.getBytes(StandardCharsets.UTF_8));
    }

    /// Writes the optional checksum index before the payload, as supported by legacy Forge archives.
    private static Path writeJar(Path file, @Nullable String index, Map<String, String> entries) throws IOException {
        try (var jar = new JarOutputStream(Files.newOutputStream(file))) {
            if (index != null) {
                jar.putNextEntry(new JarEntry("checksums.sha1"));
                jar.write(index.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            for (var entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return file;
    }

    /// Missing checksum metadata does not require the file to exist.
    @Test
    public void absentChecksumsSkipReading(@TempDir Path directory) {
        assertTrue(validate(directory.resolve("missing.jar"), null));
        assertTrue(validate(directory.resolve("missing.jar"), List.of()));
    }

    /// A file is accepted when any declared checksum matches its complete contents.
    @Test
    public void acceptsWholeFileChecksum(@TempDir Path directory) throws Exception {
        Path file = Files.writeString(directory.resolve("library.bin"), "payload");
        assertTrue(validate(file, List.of("incorrect", sha1("payload"))));
    }

    /// A mismatched complete-file digest is rejected.
    @Test
    public void rejectsWrongChecksum(@TempDir Path directory) throws Exception {
        Path file = Files.writeString(directory.resolve("library.bin"), "payload");
        assertFalse(validate(file, List.of(sha1("other payload"))));
    }

    /// Legacy checksum comparison remains case sensitive.
    @Test
    public void checksumComparisonIsCaseSensitive(@TempDir Path directory) throws Exception {
        Path file = Files.writeString(directory.resolve("library.bin"), "payload");
        assertFalse(validate(file, List.of(sha1("payload").toUpperCase(Locale.ROOT))));
    }

    /// An unreadable file with required checksums fails validation without propagating the I/O error.
    @Test
    public void missingFileFailsValidation(@TempDir Path directory) {
        assertFalse(validate(directory.resolve("missing.jar"), List.of("required")));
    }

    /// The trusted checksum index verifies its listed payload and supports file names containing spaces.
    @Test
    public void acceptsTrustedIndex(@TempDir Path directory) throws Exception {
        String index = sha1("payload") + " folder/file name.txt\n";
        Path file = writeJar(directory.resolve("library.jar"), index,
                Map.of("folder/", "", "folder/file name.txt", "payload", "unlisted.txt", "extra"));
        assertTrue(validate(file, List.of(sha1(index))));
    }

    /// Matching entries alone cannot authorize an untrusted checksum index.
    @Test
    public void rejectsUntrustedIndex(@TempDir Path directory) throws Exception {
        String index = sha1("payload") + " file.txt\n";
        Path file = writeJar(directory.resolve("library.jar"), index, Map.of("file.txt", "payload"));
        assertFalse(validate(file, List.of(sha1("untrusted index"))));
    }

    /// A trusted index still rejects modified payload contents.
    @Test
    public void rejectsModifiedEntry(@TempDir Path directory) throws Exception {
        String index = sha1("payload") + " file.txt\n";
        Path file = writeJar(directory.resolve("library.jar"), index, Map.of("file.txt", "modified"));
        assertFalse(validate(file, List.of(sha1(index))));
    }

    /// Every file named by a trusted index must be present.
    @Test
    public void rejectsMissingEntry(@TempDir Path directory) throws Exception {
        String index = sha1("payload") + " file.txt\n";
        Path file = writeJar(directory.resolve("library.jar"), index, Map.of("other.txt", "payload"));
        assertFalse(validate(file, List.of(sha1(index))));
    }

    /// Without a matching whole-file digest, an archive needs a checksum index.
    @Test
    public void rejectsMissingIndex(@TempDir Path directory) throws Exception {
        Path file = writeJar(directory.resolve("library.jar"), null, Map.of("file.txt", "payload"));
        assertFalse(validate(file, List.of(sha1("payload"))));
    }

    /// Legacy index fallback is restricted to the exact lowercase .jar suffix.
    @ParameterizedTest
    @ValueSource(strings = {"library.zip", "library.JAR", "library.jar.disabled"})
    public void requiresJarSuffix(String name, @TempDir Path directory) throws Exception {
        String index = sha1("payload") + " file.txt\n";
        Path file = writeJar(directory.resolve(name), index, Map.of("file.txt", "payload"));
        assertFalse(validate(file, List.of(sha1(index))));
    }

    /// A valid whole-file checksum takes precedence over a broken internal index.
    @Test
    public void wholeFileChecksumBypassesIndex(@TempDir Path directory) throws Exception {
        Path file = writeJar(directory.resolve("library.jar"), "incorrect missing.txt\n", Map.of());
        assertTrue(validate(file, List.of(sha1(Files.readAllBytes(file)))));
    }

    /// Blank lines and lines without a space retain the legacy parser's ignored-line behavior.
    @Test
    public void ignoresNonChecksumLines(@TempDir Path directory) throws Exception {
        String index = "\n   \nignored\n" + sha1("payload") + " file.txt\n";
        Path file = writeJar(directory.resolve("library.jar"), index, Map.of("file.txt", "payload"));
        assertTrue(validate(file, List.of(sha1(index))));
    }

    /// Legacy parsing preserves a carriage return as part of the entry name rather than trimming it.
    @Test
    public void preservesIndexFileNames(@TempDir Path directory) throws Exception {
        String index = sha1("payload") + " file.txt\r\n";
        Path file = writeJar(directory.resolve("library.jar"), index, Map.of("file.txt", "payload"));
        assertFalse(validate(file, List.of(sha1(index))));
    }

    /// The cache stores and retrieves a dependency authorized by its complete-file checksum.
    @Test
    public void cachesVerifiedLibrary(@TempDir Path directory) throws Exception {
        var repository = new DefaultCacheRepository(directory.resolve("common"));
        var library = library(List.of(sha1("payload")));
        Path file = Files.writeString(directory.resolve("library.jar"), "payload");

        repository.tryCacheLibrary(library, file);

        assertEquals("payload", Files.readString(repository.getLibrary(library).orElseThrow()));
    }

    /// A failed checksum cannot populate the library cache.
    @Test
    public void doesNotCacheMismatchedLibrary(@TempDir Path directory) throws Exception {
        var repository = new DefaultCacheRepository(directory.resolve("common"));
        var library = library(List.of(sha1("expected")));
        Path file = Files.writeString(directory.resolve("library.bin"), "modified");

        repository.tryCacheLibrary(library, file);

        assertTrue(repository.getLibrary(library).isEmpty());
    }

    /// Skipping validation for absent metadata does not authorize adding unverified files to the cache.
    @Test
    public void doesNotCacheLibraryWithoutChecksums(@TempDir Path directory) throws Exception {
        var repository = new DefaultCacheRepository(directory.resolve("common"));
        Path file = Files.writeString(directory.resolve("library.jar"), "payload");

        repository.tryCacheLibrary(library(null), file);
        repository.tryCacheLibrary(library(List.of()), file);

        assertTrue(repository.getLibrary(library(null)).isEmpty());
    }

    /// Looking up an old library location accepts the trusted Forge index before restoring its file.
    @Test
    public void restoresLegacyLibrary(@TempDir Path directory) throws Exception {
        var repository = new DefaultCacheRepository(directory.resolve("common"));
        String index = sha1("payload") + " file.txt\n";
        var library = library(List.of(sha1(index)));
        Path oldFile = repository.getCommonDirectory().resolve("libraries").resolve(library.getPath());
        Files.createDirectories(oldFile.getParent());
        writeJar(oldFile, index, Map.of("file.txt", "payload"));

        Path restored = repository.getLibrary(library).orElseThrow();

        assertNotEquals(oldFile, restored);
        assertArrayEquals(Files.readAllBytes(oldFile), Files.readAllBytes(restored));
    }
}
