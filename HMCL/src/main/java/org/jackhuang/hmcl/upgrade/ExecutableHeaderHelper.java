/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jackhuang.hmcl.util.io.IOUtils;
import org.jetbrains.annotations.Nullable;

import static java.nio.file.StandardOpenOption.*;
import static org.jackhuang.hmcl.util.Lang.mapOf;
import static org.jackhuang.hmcl.util.Pair.pair;

/**
 * Helper class for adding/removing executable header from HMCL file.
 *
 * @author yushijinhun
 */
final class ExecutableHeaderHelper {
    private ExecutableHeaderHelper() {}

    private static Map<String, String> suffix2header = mapOf(
            pair("exe", "assets/HMCLauncher.exe"),
            pair("sh", "assets/HMCLauncher.sh")
    );

    private static Optional<String> getSuffix(Path file) {
        String filename = file.getFileName().toString();
        int idxDot = filename.lastIndexOf('.');
        if (idxDot < 0) {
            return Optional.empty();
        } else {
            return Optional.of(filename.substring(idxDot + 1));
        }
    }

    /// Reads the bundled executable header for a supported suffix, when present.
    private static Optional<byte[]> readHeader(ZipFile zip, String suffix) throws IOException {
        @Nullable String location = suffix2header.get(suffix);
        if (location != null) {
            @Nullable ZipEntry entry = zip.getEntry(location);
            if (entry != null && !entry.isDirectory()) {
                try (InputStream in = zip.getInputStream(entry)) {
                    return Optional.of(IOUtils.readFully(in));
                }
            }
        }
        return Optional.empty();
    }

    /// Identifies an existing launcher's header using the resources bundled in that launcher.
    /// The file is read without memory mapping so it can be overwritten after this method returns.
    private static Optional<String> detectHeaderSuffix(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (String suffix : suffix2header.keySet()) {
                Optional<byte[]> header = readHeader(zip, suffix);
                if (header.isPresent()) {
                    try (InputStream in = Files.newInputStream(file)) {
                        if (Arrays.equals(header.get(), in.readNBytes(header.get().length))) {
                            return Optional.of(suffix);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static int detectHeaderLength(ZipFile zip, FileChannel channel) throws IOException {
        ByteBuffer buf = channel.map(MapMode.READ_ONLY, 0, channel.size());
        suffixLoop: for (String suffix : suffix2header.keySet()) {
            Optional<byte[]> header = readHeader(zip, suffix);
            if (header.isPresent()) {
                ((Buffer) buf).rewind();
                for (byte b : header.get()) {
                    if (!buf.hasRemaining() || b != buf.get()) {
                        continue suffixLoop;
                    }
                }
                return header.get().length;
            }
        }
        return 0;
    }

    /**
     * Copies the executable and removes its header.
     */
    public static void copyWithoutHeader(Path from, Path to) throws IOException {
        try (
                FileChannel in = FileChannel.open(from, READ);
                FileChannel out = FileChannel.open(to, CREATE, WRITE, TRUNCATE_EXISTING);
                ZipFile zip = new ZipFile(from.toFile())
        ) {
            in.transferTo(detectHeaderLength(zip, in), Long.MAX_VALUE, out);
        }
    }

    /// Copies the executable using the destination's suffix, or its existing header when renamed.
    /// The replacement header comes from the new launcher so updates also refresh the bootstrap code.
    public static void copyWithHeader(Path from, Path to) throws IOException {
        Optional<String> suffix = getSuffix(to);
        if (suffix.filter(s -> suffix2header.containsKey(s) || "jar".equals(s)).isEmpty() && Files.isRegularFile(to)) {
            // Inspect the old launcher before opening the output channel truncates it.
            suffix = detectHeaderSuffix(to);
        }

        try (
                FileChannel in = FileChannel.open(from, READ);
                FileChannel out = FileChannel.open(to, CREATE, WRITE, TRUNCATE_EXISTING);
                ZipFile zip = new ZipFile(from.toFile())
        ) {
            if (suffix.isPresent()) {
                Optional<byte[]> header = readHeader(zip, suffix.get());
                if (header.isPresent()) {
                    out.write(ByteBuffer.wrap(header.get()));
                }
            }

            in.transferTo(detectHeaderLength(zip, in), Long.MAX_VALUE, out);
        }
    }
}
