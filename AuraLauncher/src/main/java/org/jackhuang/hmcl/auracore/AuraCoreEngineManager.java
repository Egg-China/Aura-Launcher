package org.jackhuang.hmcl.auracore;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.auracore.backend.AuraCoreClient;
import org.auracore.backend.AuraCoreLibraryLoader;
import org.auracore.backend.AuraCoreNative;
import org.auracore.backend.AuraCoreSettingMigration;
import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Owns the opt-in AuraCore native backend lifecycle and the allowlisted settings migration.
///
/// The HMCL-derived Java core stays the default engine. Switching `coreEngine` to `auracore`
/// starts this manager's backend against `<local home>/auracore`; the directory is separate from
/// every HMCL store, and migration only copies keys listed by
/// [AuraCoreSettingMigration#allowList].
@NotNullByDefault
public final class AuraCoreEngineManager {
    /// Engine identifier for the built-in HMCL-derived Java core.
    public static final String ENGINE_HMCL = "hmcl";

    /// Engine identifier for the native AuraCore backend.
    public static final String ENGINE_AURACORE = "auracore";

    /// The single process-wide engine manager.
    private static final AuraCoreEngineManager INSTANCE = new AuraCoreEngineManager();

    /// The data directory used by the native backend, isolated from HMCL stores.
    private final Path dataDirectory = Metadata.HMCL_LOCAL_HOME.resolve("auracore");

    /// The running client, null while the native engine is stopped.
    private volatile @Nullable AuraCoreClient client;

    /// Prevents external instantiation; use [AuraCoreEngineManager#getInstance].
    private AuraCoreEngineManager() {
    }

    /// Returns the process-wide engine manager.
    ///
    /// @return the shared manager instance
    public static AuraCoreEngineManager getInstance() {
        return INSTANCE;
    }

    /// Returns the native backend data directory.
    ///
    /// @return the isolated AuraCore data path
    public Path getDataDirectory() {
        return dataDirectory;
    }

    /// Starts the native backend when its library is available.
    ///
    /// @return the started client
    /// @throws IllegalStateException when the native library cannot be located
    public synchronized AuraCoreClient start() {
        AuraCoreClient current = client;
        if (current != null) {
            return current;
        }
        final Path libraryPath = locateLibrary().orElseThrow(() ->
                new IllegalStateException("AuraCore native backend library not found"));
        final AuraCoreNative nativeLibrary = AuraCoreNative.load(libraryPath.toString());
        final AuraCoreClient created = new AuraCoreClient(nativeLibrary, dataDirectory);
        client = created;
        return created;
    }

    /// Stops the running native backend, if any.
    public synchronized void stop() {
        final AuraCoreClient current = client;
        client = null;
        if (current != null) {
            current.close();
        }
    }

    /// Returns the immutable launcher-side migration allowlist.
    ///
    /// @return the ordered launcher keys copied into the backend
    @Unmodifiable
    public List<String> migrationAllowList() {
        return AuraCoreSettingMigration.launcherKeys();
    }

    /// Builds a status snapshot for diagnostics and the native UI bridge.
    ///
    /// @return the immutable status fields consumed by `core.auracore.status`
    @Unmodifiable
    public Map<String, Object> status() {
        final Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("dataDirectory", dataDirectory.toString());
        fields.put("libraryPath", locateLibrary().map(Path::toString).orElse(null));
        fields.put("libraryAvailable", locateLibrary().isPresent());
        fields.put("backendRunning", client != null);
        fields.put("migrationAllowList", AuraCoreSettingMigration.launcherKeys());
        return fields;
    }

    /// Copies allowlisted launcher settings into the native backend.
    ///
    /// @param launcherValues the launcher-side values keyed by launcher setting key
    /// @return the per-key migration outcomes, keyed by launcher setting key
    public CompletionStage<Map<String, String>> migrateSettings(Map<String, Object> launcherValues) {
        final AuraCoreClient target = client != null ? client : start();
        final Map<String, CompletableFuture<String>> pending = new LinkedHashMap<>();
        for (String launcherKey : AuraCoreSettingMigration.launcherKeys()) {
            final Object value = launcherValues.get(launcherKey);
            if (value == null) {
                continue;
            }
            final String backendKey = AuraCoreSettingMigration.backendKey(launcherKey);
            final JsonElement element = toElement(value);
            if (element == null) {
                continue;
            }
            pending.put(launcherKey, target.setSetting(backendKey, element)
                    .thenApply(ignored -> "copied")
                    .exceptionally(failure -> "failed: " + failure.getMessage()));
        }
        final List<CompletableFuture<String>> futures = List.copyOf(pending.values());
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> {
                    final Map<String, String> results = new LinkedHashMap<>();
                    pending.forEach((key, future) -> results.put(key, future.join()));
                    return results;
                });
    }

    /// Converts a migration value into its JSON representation.
    ///
    /// @param value the launcher-side setting value
    /// @return the JSON primitive, or null for unsupported value types
    private static @Nullable JsonElement toElement(Object value) {
        if (value instanceof Boolean booleanValue) {
            return new JsonPrimitive(booleanValue);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof String string) {
            return new JsonPrimitive(string);
        }
        return null;
    }

    /// Locates the native library for status reporting.
    ///
    /// @return the absolute library path when present
    private Optional<Path> locateLibrary() {
        return AuraCoreLibraryLoader.locate(dataDirectory);
    }
}