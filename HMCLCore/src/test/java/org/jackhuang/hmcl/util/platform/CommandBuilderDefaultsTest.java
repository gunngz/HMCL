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
package org.jackhuang.hmcl.util.platform;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Verifies default JVM options through the command builder's public interface.
@NotNullByDefault
final class CommandBuilderDefaultsTest {

    /// Boolean defaults are added once, and an existing opposite value still takes precedence.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void addsBooleanDefaultOnce(boolean enabled) {
        CommandBuilder builder = new CommandBuilder();
        String expected = "-XX:" + (enabled ? "+" : "-") + "UseG1GC";

        assertNull(builder.addUnstableDefault("UseG1GC", enabled));
        assertEquals(expected, builder.addUnstableDefault("UseG1GC", !enabled));
        assertEquals(List.of(expected), builder.asList());
    }

    /// Value defaults are added once, including empty values and values containing equals signs.
    @ParameterizedTest
    @ValueSource(strings = {"", "50", "logs/gc log.txt", "a=b"})
    void addsValueDefaultOnce(String value) {
        CommandBuilder builder = new CommandBuilder();
        String expected = "-XX:ErrorFile=" + value;

        assertNull(builder.addUnstableDefault("ErrorFile", value));
        assertEquals(expected, builder.addUnstableDefault("ErrorFile", "replacement"));
        assertEquals(List.of(expected), builder.asList());
    }

    /// Only the exact option name and the matching boolean or value syntax suppress a default.
    @Test
    void matchesExactNameAndSyntax() {
        CommandBuilder builder = new CommandBuilder().addAll(
                "-XX:+UseG1GCExtra", "-XX:UseG1GC=true", "-XX:+UseG1GC-invalid",
                "-XX:MaxGCPauseMillisExtra=10", "-XX:+MaxGCPauseMillis", "-XX:MaxGCPauseMillis");

        assertNull(builder.addUnstableDefault("UseG1GC", false));
        assertNull(builder.addUnstableDefault("MaxGCPauseMillis", "50"));
        assertEquals(List.of(
                "-XX:+UseG1GCExtra", "-XX:UseG1GC=true", "-XX:+UseG1GC-invalid",
                "-XX:MaxGCPauseMillisExtra=10", "-XX:+MaxGCPauseMillis", "-XX:MaxGCPauseMillis",
                "-XX:-UseG1GC", "-XX:MaxGCPauseMillis=50"), builder.asList());
    }

    /// Matching returns the first command-line occurrence without removing or rewriting duplicates.
    @Test
    void returnsFirstCommandLineMatch() {
        @Unmodifiable List<String> arguments = List.of(
                "-XX:-UseG1GC", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100", "-XX:MaxGCPauseMillis=200");
        CommandBuilder builder = new CommandBuilder().addAll(arguments);

        assertEquals("-XX:-UseG1GC", builder.addUnstableDefault("UseG1GC", true));
        assertEquals("-XX:MaxGCPauseMillis=100", builder.addUnstableDefault("MaxGCPauseMillis", "50"));
        assertEquals(arguments, builder.asList());
    }

    /// External options suppress defaults without being copied into the explicit command line.
    @Test
    void respectsExternalOptions(@TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("options.txt"),
                "-XX:-UseG1GC\n\"-XX:ErrorFile=logs/gc log.txt\"\n");
        @Unmodifiable List<String> arguments = List.of("@" + file);
        CommandBuilder builder = new CommandBuilder().addAllWithoutParsingAndReadExternal(arguments);

        assertEquals("-XX:-UseG1GC", builder.addUnstableDefault("UseG1GC", true));
        assertEquals("-XX:ErrorFile=logs/gc log.txt", builder.addUnstableDefault("ErrorFile", "default.log"));
        assertEquals(arguments, builder.asList());
    }

    /// Command-line matches are returned before external matches, even when the file was added first.
    @Test
    void prefersCommandLineOverExternalOptions(@TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("options.txt"),
                "-XX:-UseG1GC\n-XX:MaxGCPauseMillis=100\n");
        CommandBuilder builder = new CommandBuilder()
                .addAllWithoutParsingAndReadExternal(List.of("@" + file))
                .addAll("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200");

        assertEquals("-XX:+UseG1GC", builder.addUnstableDefault("UseG1GC", false));
        assertEquals("-XX:MaxGCPauseMillis=200", builder.addUnstableDefault("MaxGCPauseMillis", "50"));
        assertEquals(List.of("@" + file, "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200"), builder.asList());
    }

    /// External files retain their order when more than one file contains the same option.
    @Test
    void returnsFirstExternalMatch(@TempDir Path directory) throws IOException {
        Path first = Files.writeString(directory.resolve("first.txt"),
                "-XX:-UseG1GC\n-XX:MaxGCPauseMillis=100\n");
        Path second = Files.writeString(directory.resolve("second.txt"),
                "-XX:+UseG1GC\n-XX:MaxGCPauseMillis=200\n");
        @Unmodifiable List<String> arguments = List.of("@" + first, "@" + second);
        CommandBuilder builder = new CommandBuilder().addAllWithoutParsingAndReadExternal(arguments);

        assertEquals("-XX:-UseG1GC", builder.addUnstableDefault("UseG1GC", true));
        assertEquals("-XX:MaxGCPauseMillis=100", builder.addUnstableDefault("MaxGCPauseMillis", "50"));
        assertEquals(arguments, builder.asList());
    }

    /// Bulk default insertion uses external options and keeps the caller's requested escaping mode.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesBulkInsertionAndEscaping(boolean parse, @TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("options.txt"),
                "-XX:-UseG1GC\n-XX:MaxGCPauseMillis=100\n");
        CommandBuilder builder = new CommandBuilder(OperatingSystem.LINUX)
                .addAllWithoutParsingAndReadExternal(List.of("@" + file));
        @Unmodifiable List<String> defaults = List.of(
                "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50", "-XX:ErrorFile=gc logs/error.log", "-XX:-UseZGC");

        if (parse) {
            builder.addAllDefault(defaults);
        } else {
            builder.addAllDefaultWithoutParsing(defaults);
        }

        assertEquals(List.of("@" + file, "-XX:ErrorFile=gc logs/error.log", "-XX:-UseZGC"), builder.asList());
        String expected = "@" + file + (parse ? " \"-XX:ErrorFile=gc logs/error.log\"" : " -XX:ErrorFile=gc logs/error.log")
                + " -XX:-UseZGC";
        assertEquals(expected, builder.toString());
    }
}
