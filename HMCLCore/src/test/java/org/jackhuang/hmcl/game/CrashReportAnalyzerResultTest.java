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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies the immutable diagnostic data consumed by the crash window.
@NotNullByDefault
public class CrashReportAnalyzerResultTest {
    /// Finds the requested diagnosis through the analyzer's public interface.
    private static CrashReportAnalyzer.Result analyze(String log, CrashReportAnalyzer.Rule rule) {
        return CrashReportAnalyzer.analyze(log).stream()
                .filter(result -> result.rule() == rule)
                .findFirst().orElseThrow();
    }

    /// A log without a known crash signature produces no diagnoses.
    @Test
    public void noMatchingRules() {
        assertTrue(CrashReportAnalyzer.analyze("Game started successfully").isEmpty());
    }

    /// A rule without named parameters still produces a diagnosis.
    @Test
    public void ruleWithoutParameters() {
        var result = analyze("Manually triggered debug crash", CrashReportAnalyzer.Rule.DEBUG_CRASH);

        assertTrue(result.parameters().isEmpty());
    }

    /// Message argument order follows the rule declaration, even when capture order differs.
    @Test
    public void preservesMessageArgumentOrder() {
        var result = analyze("Failed loading config file example.toml of type SERVER for modid example",
                CrashReportAnalyzer.Rule.CONFIG);

        assertEquals(List.of("id", "file"), new ArrayList<>(result.parameters().keySet()));
        assertArrayEquals(new String[]{"example", "example.toml"}, result.parameters().values().toArray());
    }

    /// A matching alternative without the named capture retains the corresponding null argument.
    @Test
    public void preservesUnmatchedParameter() {
        var result = analyze("signer information does not match signer information of other classes in the same package",
                CrashReportAnalyzer.Rule.FILE_CHANGED);

        assertEquals(List.of("file"), new ArrayList<>(result.parameters().keySet()));
        assertArrayEquals(new @Nullable String[]{null}, result.parameters().values().toArray());
    }

    /// Empty captures remain empty strings, distinct from unmatched captures.
    @Test
    public void preservesEmptyParameters() {
        var result = analyze("Found a duplicate mod  at ", CrashReportAnalyzer.Rule.DUPLICATED_MOD);

        assertEquals(Map.of("name", "", "path", ""), result.parameters());
    }

    /// Repeated occurrences of the same rule keep the first match's parameters.
    @Test
    public void usesFirstMatch() {
        String log = "java.nio.file.FileAlreadyExistsException: first.txt\n"
                + "java.nio.file.FileAlreadyExistsException: second.txt";
        var results = CrashReportAnalyzer.analyze(log);

        assertEquals(1, results.size());
        assertEquals(Map.of("file", "first.txt"), results.iterator().next().parameters());
    }

    /// Callers cannot change a diagnosis through either its parameter map or its entries.
    @Test
    public void parametersAreUnmodifiable() {
        var result = analyze("java.nio.file.FileAlreadyExistsException: first.txt",
                CrashReportAnalyzer.Rule.FILE_ALREADY_EXISTS);

        assertThrows(UnsupportedOperationException.class, () -> result.parameters().put("file", "changed.txt"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.parameters().entrySet().iterator().next().setValue("changed.txt"));
        assertEquals("first.txt", result.parameters().get("file"));
    }

    /// Constructing a result snapshots its parameters, including their order and null values.
    @Test
    public void copiesParameters() {
        Map<String, @Nullable String> parameters = new LinkedHashMap<>();
        parameters.put("id", "example");
        parameters.put("file", null);
        var result = new CrashReportAnalyzer.Result(CrashReportAnalyzer.Rule.CONFIG, parameters);

        parameters.clear();
        parameters.put("file", "changed.toml");

        assertEquals(List.of("id", "file"), new ArrayList<>(result.parameters().keySet()));
        assertArrayEquals(new @Nullable String[]{"example", null}, result.parameters().values().toArray());
    }
}
