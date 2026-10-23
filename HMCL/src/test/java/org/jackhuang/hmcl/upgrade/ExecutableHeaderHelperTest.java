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
package org.jackhuang.hmcl.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Tests executable headers across launcher updates.
@NotNullByDefault
final class ExecutableHeaderHelperTest {

    /// Updating a renamed shell launcher retains the new version's shell header and JAR payload.
    @ParameterizedTest
    @ValueSource(strings = {"HMCL", "HMCL-3.17.0", "HMCL.sh"})
    void preservesRenamedShellLauncher(String name, @TempDir Path directory) throws IOException {
        byte[] oldHeader = "#!/bin/sh\necho old\n".getBytes(StandardCharsets.UTF_8);
        byte[] newHeader = "#!/bin/sh\necho new\n".getBytes(StandardCharsets.UTF_8);
        Path target = directory.resolve(name);
        Files.write(target, withHeader(oldHeader, createJar(oldHeader)));
        byte[] update = createJar(newHeader);
        Path source = Files.write(directory.resolve("update.jar"), update);

        ExecutableHeaderHelper.copyWithHeader(source, target);

        assertArrayEquals(withHeader(newHeader, update), Files.readAllBytes(target));
    }

    /// A renamed plain JAR must not gain a shell or Windows header from the update's resources.
    @ParameterizedTest
    @ValueSource(strings = {"HMCL", "HMCL-3.17.0", "HMCL.jar"})
    void preservesPlainJar(String name, @TempDir Path directory) throws IOException {
        byte[] update = createJar("#!/bin/sh\necho new\n".getBytes(StandardCharsets.UTF_8));
        Path source = Files.write(directory.resolve("update.jar"), update);
        Path target = Files.write(directory.resolve(name), createJar("#!/bin/sh\necho old\n".getBytes(StandardCharsets.UTF_8)));

        ExecutableHeaderHelper.copyWithHeader(source, target);

        assertArrayEquals(update, Files.readAllBytes(target));
    }

    /// Copying to a new file still uses its explicit executable suffix and strips the source header.
    @ParameterizedTest
    @CsvSource({"sh, sh", "sh, exe", "sh, jar", "exe, sh", "exe, exe", "exe, jar"})
    void convertsToExplicitSuffix(String sourceType, String targetType, @TempDir Path directory) throws IOException {
        byte[] shellHeader = "#!/bin/sh\necho new\n".getBytes(StandardCharsets.UTF_8);
        byte[] exeHeader = "MZexe-header".getBytes(StandardCharsets.UTF_8);
        byte[] jar = createJar(shellHeader);
        byte[] sourceHeader = sourceType.equals("sh") ? shellHeader : exeHeader;
        byte[] targetHeader = targetType.equals("sh") ? shellHeader
                : targetType.equals("exe") ? exeHeader : new byte[0];
        Path source = Files.write(directory.resolve("source"), withHeader(sourceHeader, jar));
        Path target = directory.resolve("HMCL." + targetType);

        ExecutableHeaderHelper.copyWithHeader(source, target);

        assertArrayEquals(withHeader(targetHeader, jar), Files.readAllBytes(target));
    }

    /// An existing renamed Windows launcher must retain its executable format as well.
    @Test
    void preservesRenamedWindowsLauncher(@TempDir Path directory) throws IOException {
        byte[] exeHeader = "MZexe-header".getBytes(StandardCharsets.UTF_8);
        byte[] update = createJar("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
        Path source = Files.write(directory.resolve("update.jar"), update);
        Path target = Files.write(directory.resolve("HMCL"), withHeader(exeHeader, createJar("#!/bin/sh\necho old\n".getBytes(StandardCharsets.UTF_8))));

        ExecutableHeaderHelper.copyWithHeader(source, target);

        assertArrayEquals(withHeader(exeHeader, update), Files.readAllBytes(target));
    }

    /// An explicit JAR suffix still requests a plain archive even if the old file had a header.
    @Test
    void removesHeaderForExplicitJarSuffix(@TempDir Path directory) throws IOException {
        byte[] shellHeader = "#!/bin/sh\necho old\n".getBytes(StandardCharsets.UTF_8);
        byte[] update = createJar("#!/bin/sh\necho new\n".getBytes(StandardCharsets.UTF_8));
        Path source = Files.write(directory.resolve("update.jar"), update);
        Path target = Files.write(directory.resolve("HMCL.jar"), withHeader(shellHeader, createJar(shellHeader)));

        ExecutableHeaderHelper.copyWithHeader(source, target);

        assertArrayEquals(update, Files.readAllBytes(target));
    }

    /// Builds a small launcher JAR containing both supported executable headers.
    private static byte[] createJar(byte[] shellHeader) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("assets/HMCLauncher.sh"));
            zip.write(shellHeader);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/HMCLauncher.exe"));
            zip.write("MZexe-header".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("payload.txt"));
            zip.write("launcher".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /// Prepends the executable header to the JAR payload.
    private static byte[] withHeader(byte[] header, byte[] jar) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(header);
        bytes.write(jar);
        return bytes.toByteArray();
    }
}
