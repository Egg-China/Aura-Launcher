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

/// Describes a plugin package published for execution after the next launcher restart.
@NotNullByDefault
public final class LocalPluginInstallation {
    /// Validated package manifest.
    private final PluginManifest manifest;

    /// Legacy prepared lifecycle value, always `null` because every installation is staged-only.
    private final @Nullable PreparedPlugin preparedPlugin;

    /// Creates a local installation result.
    ///
    /// @param manifest validated package manifest
    /// @param preparedPlugin legacy prepared plugin value or `null` for the required staged installation
    private LocalPluginInstallation(
            PluginManifest manifest,
            @Nullable PreparedPlugin preparedPlugin
    ) {
        this.manifest = manifest;
        this.preparedPlugin = preparedPlugin;
    }

    /// Creates a result for a package staged for restart.
    ///
    /// @param manifest installed package manifest
    /// @return restart-staged installation result
    static LocalPluginInstallation staged(PluginManifest manifest) {
        return new LocalPluginInstallation(manifest, null);
    }

    /// Returns the validated replacement or installation manifest.
    ///
    /// @return package manifest
    public PluginManifest getManifest() {
        return manifest;
    }

    /// Returns whether this installation waits for a launcher restart.
    ///
    /// @return whether no runtime registration should be attempted
    public boolean isRestartRequired() {
        return preparedPlugin == null;
    }

    /// Rejects access to the removed immediate-registration result.
    ///
    /// @return prepared plugin
    /// @throws IllegalStateException because public installations are always restart-staged
    public PreparedPlugin getPreparedPlugin() {
        if (preparedPlugin == null) {
            throw new IllegalStateException("Plugin installation is staged for restart");
        }
        return preparedPlugin;
    }
}
