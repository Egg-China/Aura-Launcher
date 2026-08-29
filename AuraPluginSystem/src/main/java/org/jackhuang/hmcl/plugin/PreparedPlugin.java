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

import java.nio.file.Path;

/// Holds one internally verified lifecycle instance during startup discovery before JavaFX registration.
@NotNullByDefault
public final class PreparedPlugin {
    /// Loaded lifecycle implementation.
    final Plugin plugin;

    /// Context prepared for the lifecycle implementation.
    final PluginContext context;

    /// Validated package manifest.
    final PluginManifest manifest;

    /// Installed package path.
    final Path nplFile;

    /// Creates a prepared plugin value.
    ///
    /// @param plugin lifecycle implementation
    /// @param context plugin context
    /// @param manifest validated manifest
    /// @param nplFile installed package path
    PreparedPlugin(
            Plugin plugin,
            PluginContext context,
            PluginManifest manifest,
            Path nplFile
    ) {
        this.plugin = plugin;
        this.context = context;
        this.manifest = manifest;
        this.nplFile = nplFile;
    }

    /// Returns the validated manifest for installation UI decisions.
    ///
    /// @return plugin manifest
    public PluginManifest getManifest() {
        return manifest;
    }
}
