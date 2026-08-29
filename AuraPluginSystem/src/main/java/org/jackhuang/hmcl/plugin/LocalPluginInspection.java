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

import java.nio.file.Path;

/// Immutable read-only metadata used to confirm a local package before installation changes launcher state.
@NotNullByDefault
public final class LocalPluginInspection {
    /// Normalized source package path inspected by the manager.
    final Path sourcePackage;

    /// Validated source package manifest displayed for confirmation.
    final PluginManifest manifest;

    /// Lower-case SHA-256 digest binding preparation to the inspected source bytes.
    final String sha256;

    /// Currently installed manifest for the same ID, or `null` for a new plugin.
    final @Nullable PluginManifest oldManifest;

    /// Exact prior artifact observed while the package was inspected, or `null` for a confirmed new ID.
    final @Nullable PluginArtifactIdentity priorArtifactIdentity;

    /// Creates an immutable local package inspection.
    ///
    /// @param sourcePackage normalized inspected source path
    /// @param manifest validated source manifest
    /// @param sha256 lower-case source digest
    /// @param oldManifest currently installed manifest or `null`
    /// @param priorArtifactIdentity exact prior artifact or `null` when absent
    LocalPluginInspection(
            Path sourcePackage,
            PluginManifest manifest,
            String sha256,
            @Nullable PluginManifest oldManifest,
            @Nullable PluginArtifactIdentity priorArtifactIdentity
    ) {
        this.sourcePackage = sourcePackage;
        this.manifest = manifest;
        this.sha256 = sha256;
        this.oldManifest = oldManifest;
        this.priorArtifactIdentity = priorArtifactIdentity;
    }

    /// Returns the normalized package path that was inspected.
    ///
    /// @return inspected source package
    public Path getSourcePackage() {
        return sourcePackage;
    }

    /// Returns the validated source package manifest.
    ///
    /// @return inspected manifest
    public PluginManifest getManifest() {
        return manifest;
    }

    /// Returns the lower-case SHA-256 digest of the inspected source bytes.
    ///
    /// @return package SHA-256
    public String getSha256() {
        return sha256;
    }

    /// Returns the currently installed manifest for the same plugin ID.
    ///
    /// @return old manifest or `null` when installing a new plugin ID
    public @Nullable PluginManifest getOldManifest() {
        return oldManifest;
    }

    /// Returns the exact prior artifact observed during inspection.
    ///
    /// @return prior artifact identity or `null` when the plugin ID was absent
    public @Nullable PluginArtifactIdentity getPriorArtifactIdentity() {
        return priorArtifactIdentity;
    }
}
