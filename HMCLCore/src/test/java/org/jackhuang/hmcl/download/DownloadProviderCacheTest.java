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
package org.jackhuang.hmcl.download;

import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises version-list caching through the public asynchronous lookup interface without network access.
@NotNullByDefault
@Timeout(30)
public class DownloadProviderCacheTest {
    /// Game version used for component-specific lookups.
    private static final GameVersionNumber GAME_VERSION = GameVersionNumber.asGameVersion("1.20.4");

    /// Runs the complete task graph and returns its successfully published result.
    private static @Unmodifiable ComponentRemoteVersionList<?> run(Task<ComponentRemoteVersionList<?>> task) {
        assertTrue(task.test(), () -> "Lookup failed: " + task.getException());
        return task.getResult();
    }

    /// Supplies predetermined fetch results while counting cache misses and explicit refreshes.
    @NotNullByDefault
    private static final class RecordingProvider extends DownloadProvider {
        /// Fetch tasks in request order; concurrent requests consume distinct tasks.
        private final Queue<Task<ComponentRemoteVersionList<?>>> responses = new ConcurrentLinkedQueue<>();
        /// Number of fetch requests, including requests whose tasks have not run yet.
        private final AtomicInteger fetchCount = new AtomicInteger();

        /// Queues a distinct empty result so identity assertions also verify that empty lists are cached.
        private @Unmodifiable ComponentRemoteVersionList<?> enqueue(GameComponentType type) {
            @Unmodifiable ComponentRemoteVersionList<?> result = ComponentRemoteVersionList.of(type, new TreeSet<>());
            responses.add(Task.completed(result));
            return result;
        }

        /// Queues a failed fetch without performing any network access.
        private void enqueueFailure() {
            responses.add(Task.supplyAsync(() -> {
                throw new IOException("Expected test failure");
            }));
        }

        /// Returns the next prepared fetch; an unexpected extra request fails the test.
        @Override
        protected Task<? extends ComponentRemoteVersionList<?>> fetchVersionsAsync(
                GameComponentType type, @Nullable GameVersionNumber gameVersion) {
            fetchCount.incrementAndGet();
            return responses.remove();
        }
    }

    /// Each component caches its successful result, including the game's null version key.
    @ParameterizedTest
    @EnumSource(GameComponentType.class)
    public void reusesSuccessfulLookup(GameComponentType type) {
        var provider = new RecordingProvider();
        @Unmodifiable ComponentRemoteVersionList<?> expected = provider.enqueue(type);
        @Nullable GameVersionNumber gameVersion = type == GameComponentType.GAME ? null : GAME_VERSION;

        assertSame(expected, run(provider.getVersionsAsync(type, gameVersion, false)));
        assertSame(expected, run(provider.getVersionsAsync(type, gameVersion, false)));
        assertEquals(1, provider.fetchCount.get());
    }

    /// Component type and game version both participate in cache isolation.
    @Test
    public void isolatesTypesAndGameVersions() {
        var provider = new RecordingProvider();
        @Unmodifiable ComponentRemoteVersionList<?> forge = provider.enqueue(GameComponentType.FORGE);
        @Unmodifiable ComponentRemoteVersionList<?> fabric = provider.enqueue(GameComponentType.FABRIC);
        @Unmodifiable ComponentRemoteVersionList<?> otherForge = provider.enqueue(GameComponentType.FORGE);
        var otherVersion = GameVersionNumber.asGameVersion("1.21");

        assertSame(forge, run(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));
        assertSame(fabric, run(provider.getVersionsAsync(GameComponentType.FABRIC, GAME_VERSION, false)));
        assertSame(otherForge, run(provider.getVersionsAsync(GameComponentType.FORGE, otherVersion, false)));
        assertSame(forge, run(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));
        assertSame(fabric, run(provider.getVersionsAsync(GameComponentType.FABRIC, GAME_VERSION, false)));
        assertSame(otherForge, run(provider.getVersionsAsync(GameComponentType.FORGE, otherVersion, false)));
        assertEquals(3, provider.fetchCount.get());
    }

    /// A refresh bypasses the cache but only replaces its previous value after successful execution.
    @Test
    public void refreshReplacesSuccessfulResult() {
        var provider = new RecordingProvider();
        @Unmodifiable ComponentRemoteVersionList<?> original = provider.enqueue(GameComponentType.FORGE);
        @Unmodifiable ComponentRemoteVersionList<?> refreshed = provider.enqueue(GameComponentType.FORGE);
        assertSame(original, run(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));

        var refresh = provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, true);

        assertSame(original, run(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));
        assertSame(refreshed, run(refresh));
        assertSame(refreshed, run(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));
        assertEquals(2, provider.fetchCount.get());
    }

    /// A failed refresh leaves the previous successful result available.
    @Test
    public void failedRefreshPreservesCachedResult() {
        var provider = new RecordingProvider();
        @Unmodifiable ComponentRemoteVersionList<?> original = provider.enqueue(GameComponentType.FORGE);
        provider.enqueueFailure();
        assertSame(original, run(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));

        assertFalse(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, true).test());

        assertSame(original, run(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));
        assertEquals(2, provider.fetchCount.get());
    }

    /// An initial failure is not cached, allowing the next ordinary lookup to retry.
    @Test
    public void retriesFailedInitialLookup() {
        var provider = new RecordingProvider();
        provider.enqueueFailure();
        @Unmodifiable ComponentRemoteVersionList<?> expected = provider.enqueue(GameComponentType.FORGE);

        assertFalse(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false).test());
        assertSame(expected, run(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));
        assertSame(expected, run(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));
        assertEquals(2, provider.fetchCount.get());
    }

    /// Separate providers maintain separate caches even when their lookup keys are identical.
    @Test
    public void isolatesProviders() {
        var first = new RecordingProvider();
        var second = new RecordingProvider();
        @Unmodifiable ComponentRemoteVersionList<?> firstResult = first.enqueue(GameComponentType.FORGE);
        @Unmodifiable ComponentRemoteVersionList<?> secondResult = second.enqueue(GameComponentType.FORGE);

        assertSame(firstResult, run(first.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));
        assertSame(secondResult, run(second.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));
        assertSame(firstResult, run(first.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));
        assertEquals(1, first.fetchCount.get());
        assertEquals(1, second.fetchCount.get());
    }

    /// Pending misses remain independent, and the last completed lookup supplies the cached value.
    @Test
    public void pendingLookupsPublishInCompletionOrder() {
        var provider = new RecordingProvider();
        @Unmodifiable ComponentRemoteVersionList<?> firstResult = provider.enqueue(GameComponentType.FORGE);
        @Unmodifiable ComponentRemoteVersionList<?> secondResult = provider.enqueue(GameComponentType.FORGE);
        var first = provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false);
        var second = provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false);

        assertEquals(2, provider.fetchCount.get());
        assertSame(secondResult, run(second));
        assertSame(secondResult, run(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));
        assertSame(firstResult, run(first));
        assertSame(firstResult, run(provider.getVersionsAsync(GameComponentType.FORGE, GAME_VERSION, false)));
    }

    /// Concurrent first access and writes share one component cache without losing entries.
    @Test
    public void concurrentLookupsRetainEveryGameVersion() throws Exception {
        var provider = new RecordingProvider();
        int count = 8;
        List<GameVersionNumber> versions = new ArrayList<>();
        List<ComponentRemoteVersionList<?>> retainedResults = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            versions.add(GameVersionNumber.asGameVersion("1.20." + i));
            retainedResults.add(provider.enqueue(GameComponentType.FORGE));
        }

        var start = new CyclicBarrier(count);
        var executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<ComponentRemoteVersionList<?>>> lookups = new ArrayList<>();
            for (GameVersionNumber version : versions) {
                lookups.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return run(provider.getVersionsAsync(GameComponentType.FORGE, version, false));
                }));
            }
            for (int i = 0; i < count; i++) {
                @Unmodifiable ComponentRemoteVersionList<?> result = lookups.get(i).get(10, TimeUnit.SECONDS);
                assertSame(result, run(provider.getVersionsAsync(GameComponentType.FORGE, versions.get(i), false)));
            }
            assertEquals(count, provider.fetchCount.get());
            // Keep the soft-referenced values strongly reachable throughout the concurrency assertions.
            java.lang.ref.Reference.reachabilityFence(retainedResults);
        } finally {
            executor.shutdownNow();
        }
    }
}
