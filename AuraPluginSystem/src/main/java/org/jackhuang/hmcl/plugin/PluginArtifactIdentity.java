/*
 * Copyright 2026 Aura Launcher contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jackhuang.hmcl.plugin;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// Identifies the exact immutable plugin package whose code may run in one launcher process.
///
/// Version alone is insufficient because a publisher can rebuild the same version with different bytes. The
/// complete `.npl` SHA-256 therefore participates in equality and in every runtime authorization decision.
@NotNullByDefault
public final class PluginArtifactIdentity {
    /// Lower-case SHA-256 representation accepted for complete package digests.
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    /// Validated plugin identifier.
    private final String pluginId;

    /// Validated package version.
    private final String version;

    /// Lower-case SHA-256 of the complete `.npl` bytes.
    private final String sha256;

    /// Creates an exact plugin artifact identity.
    ///
    /// @param pluginId validated plugin identifier
    /// @param version non-blank package version
    /// @param sha256 complete package SHA-256
    public PluginArtifactIdentity(String pluginId, String version, String sha256) {
        if (!PluginManifest.isValidId(pluginId)) {
            throw new IllegalArgumentException("Invalid plugin ID: " + pluginId);
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("Plugin version cannot be blank");
        }
        String normalizedSha256 = sha256.toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(normalizedSha256).matches()) {
            throw new IllegalArgumentException("Invalid plugin package SHA-256: " + sha256);
        }
        this.pluginId = pluginId;
        this.version = version;
        this.sha256 = normalizedSha256;
    }

    /// Creates an identity from one validated manifest and complete package digest.
    ///
    /// @param manifest validated plugin manifest
    /// @param sha256 complete package SHA-256
    /// @return exact artifact identity
    public static PluginArtifactIdentity of(PluginManifest manifest, String sha256) {
        return new PluginArtifactIdentity(manifest.getId(), manifest.getVersion(), sha256);
    }

    /// Returns the plugin identifier.
    ///
    /// @return plugin ID
    public String getPluginId() {
        return pluginId;
    }

    /// Returns the package version.
    ///
    /// @return package version
    public String getVersion() {
        return version;
    }

    /// Returns the complete package digest.
    ///
    /// @return lower-case SHA-256
    public String getSha256() {
        return sha256;
    }

    /// Compares exact artifact identity fields.
    ///
    /// @param other candidate object
    /// @return whether both values identify identical package bytes
    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PluginArtifactIdentity identity)) {
            return false;
        }
        return pluginId.equals(identity.pluginId)
                && version.equals(identity.version)
                && sha256.equals(identity.sha256);
    }

    /// Returns a stable hash of all identity fields.
    ///
    /// @return identity hash
    @Override
    public int hashCode() {
        return Objects.hash(pluginId, version, sha256);
    }

    /// Returns a concise diagnostic representation.
    ///
    /// @return plugin ID, version, and SHA-256
    @Override
    public String toString() {
        return pluginId + "@" + version + "#" + sha256;
    }
}
