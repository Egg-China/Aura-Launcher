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

/// Package-owned lifecycle fixture that fails deterministically during activation.
@NotNullByDefault
public final class PackagedThrowingOnEnablePlugin implements Plugin {
    /// Manifest received during `onLoad`, or `null` before registration.
    private @Nullable PluginManifest manifest;

    /// Creates the activation-failure fixture.
    public PackagedThrowingOnEnablePlugin() {
    }

    /// Stores the package manifest before activation is attempted.
    ///
    /// @param context plugin runtime context
    @Override
    public void onLoad(PluginContext context) {
        manifest = context.getManifest();
    }

    /// Fails activation so dependent lifecycle isolation can be verified.
    @Override
    public void onEnable() {
        throw new IllegalStateException("Expected onEnable failure");
    }

    /// Performs no cleanup because activation never succeeds.
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
