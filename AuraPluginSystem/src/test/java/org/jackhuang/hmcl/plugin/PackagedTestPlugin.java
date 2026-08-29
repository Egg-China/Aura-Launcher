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

import java.util.Objects;

/// Top-level lifecycle fixture whose bytecode is copied into generated `.npl` test packages.
@NotNullByDefault
public final class PackagedTestPlugin implements Plugin {
    /// Manifest received during `onLoad`, or `null` before registration.
    private @Nullable PluginManifest manifest;

    /// Creates the package-owned lifecycle fixture.
    public PackagedTestPlugin() {
    }

    /// Stores the exact package context supplied by the manager.
    ///
    /// @param context plugin runtime context
    @Override
    public void onLoad(PluginContext context) {
        manifest = context.getManifest();
    }

    /// Activates the no-op fixture.
    @Override
    public void onEnable() {
    }

    /// Deactivates the no-op fixture.
    @Override
    public void onDisable() {
    }

    /// Returns the manifest received during registration.
    ///
    /// @return plugin manifest
    @Override
    public PluginManifest getManifest() {
        return Objects.requireNonNull(manifest);
    }
}
