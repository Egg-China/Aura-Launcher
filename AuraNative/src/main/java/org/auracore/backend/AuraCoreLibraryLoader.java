package org.auracore.backend;

import com.sun.jna.Platform;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Locates the AuraCore native backend shared library on the host.
///
/// The explicit `AURACORE_BACKEND_PATH` environment variable wins, followed by well-known
/// file names placed beside the launcher or inside its data directory.
@NotNullByDefault
public final class AuraCoreLibraryLoader {
    /// Environment variable holding an absolute library path override.
    public static final String LIBRARY_PATH_PROPERTY = "AURACORE_BACKEND_PATH";

    /// Platform-specific candidate library file names.
    private static final List<String> LIBRARY_FILE_NAMES = loadLibraryFileNames();

    /// Prevents instantiation.
    private AuraCoreLibraryLoader() {
    }

    /// Resolves the native library path from the override or search directories.
    ///
    /// @param dataDirectory the launcher data directory searched as a fallback
    /// @return the absolute library path when one exists
    public static Optional<Path> locate(Path dataDirectory) {
        final List<Path> candidates = new ArrayList<>();
        final String override = System.getenv(LIBRARY_PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            candidates.add(Path.of(override));
        }
        candidates.add(Path.of(".").toAbsolutePath());
        candidates.add(dataDirectory.toAbsolutePath());
        for (Path directory : candidates) {
            for (String fileName : LIBRARY_FILE_NAMES) {
                final Path candidate = directory.resolve(fileName);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate.toAbsolutePath().normalize());
                }
            }
        }
        return Optional.empty();
    }

    /// Builds the platform-specific candidate file names.
    ///
    /// @return the immutable candidate name list
    private static List<String> loadLibraryFileNames() {
        final List<String> names = new ArrayList<>();
        if (Platform.isWindows()) {
            names.add("auracore_backend.dll");
            names.add("libauracore_backend.dll");
        } else if (Platform.isMac()) {
            names.add("libauracore_backend.dylib");
        } else {
            names.add("libauracore_backend.so");
        }
        return List.copyOf(names);
    }
}