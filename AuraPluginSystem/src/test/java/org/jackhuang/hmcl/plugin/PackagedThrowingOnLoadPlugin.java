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

/// Package-owned lifecycle fixture that fails deterministically during `onLoad`.
@NotNullByDefault
public final class PackagedThrowingOnLoadPlugin implements Plugin {
    /// Creates the failing lifecycle fixture.
    public PackagedThrowingOnLoadPlugin() {
    }

    /// Throws the deterministic registration failure.
    ///
    /// @param context ignored runtime context
    @Override
    public void onLoad(PluginContext context) {
        throw new IllegalStateException("Expected packaged onLoad failure");
    }

    /// Activates the unreachable fixture.
    @Override
    public void onEnable() {
    }

    /// Deactivates the unreachable fixture.
    @Override
    public void onDisable() {
    }

    /// Reports that registration never completed.
    ///
    /// @return never returns normally
    @Override
    public PluginManifest getManifest() {
        throw new IllegalStateException("Plugin did not finish loading");
    }
}
