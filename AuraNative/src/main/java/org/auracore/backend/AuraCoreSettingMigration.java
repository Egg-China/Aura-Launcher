package org.auracore.backend;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Describes the allowlisted launcher settings copied into an AuraCore backend data directory.
///
/// Migration only copies explicitly listed keys. Plugins, plugin security state, account tokens and
/// every other launcher-private store stay in the HMCL data directory; the AuraCore data directory
/// never imports them.
@NotNullByDefault
public final class AuraCoreSettingMigration {
    /// The immutable migration allowlist in copy order.
    private static final Map<String, String> ALLOWED_KEYS = buildAllowList();

    /// The immutable key view of the allowlist.
    private static final Set<String> ALLOWED_KEY_SET = Set.copyOf(ALLOWED_KEYS.keySet());

    /// Prevents instantiation.
    private AuraCoreSettingMigration() {
    }

    /// Returns the immutable allowlist mapping launcher setting keys to backend setting keys.
    ///
    /// @return the modifiable-free mapping used by migrations
    @Unmodifiable
    public static Map<String, String> allowList() {
        return ALLOWED_KEYS;
    }

    /// Returns whether a launcher setting key may be migrated.
    ///
    /// @param launcherKey the launcher-side setting key
    /// @return true when the key appears in the allowlist
    public static boolean isAllowed(String launcherKey) {
        return ALLOWED_KEY_SET.contains(launcherKey);
    }

    /// Returns the backend key a launcher key migrates into.
    ///
    /// @param launcherKey the launcher-side setting key
    /// @return the backend-side key, or null when the key is not allowlisted
    public static String backendKey(String launcherKey) {
        return ALLOWED_KEYS.get(launcherKey);
    }

    /// Builds the ordered allowlist.
    ///
    /// @return the immutable launcher-key to backend-key mapping
    @Unmodifiable
    private static Map<String, String> buildAllowList() {
        final Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("download.source.meta", "MetaURLOverride");
        mapping.put("download.concurrent-tasks", "NumberOfConcurrentTasks");
        mapping.put("download.concurrent-downloads", "NumberOfConcurrentDownloads");
        mapping.put("download.retries", "NumberOfManualRetries");
        mapping.put("network.timeout", "RequestTimeout");
        mapping.put("proxy.type", "ProxyType");
        mapping.put("proxy.host", "ProxyAddr");
        mapping.put("proxy.port", "ProxyPort");
        mapping.put("proxy.user", "ProxyUser");
        mapping.put("proxy.password", "ProxyPass");
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(mapping));
    }

    /// Returns the immutable list of launcher keys in copy order.
    ///
    /// @return the ordered launcher key list
    @Unmodifiable
    public static List<String> launcherKeys() {
        return List.copyOf(ALLOWED_KEYS.keySet());
    }
}