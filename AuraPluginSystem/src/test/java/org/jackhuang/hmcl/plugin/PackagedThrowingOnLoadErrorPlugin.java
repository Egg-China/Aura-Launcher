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

/// Package-owned lifecycle fixture that throws an [AssertionError] during registration.
@NotNullByDefault
public final class PackagedThrowingOnLoadErrorPlugin implements Plugin {
    /// Creates the registration-error fixture.
    public PackagedThrowingOnLoadErrorPlugin() {
    }

    /// Throws an error to verify discovery isolates plugin failures from later candidates.
    ///
    /// @param context ignored plugin runtime context
    @Override
    public void onLoad(PluginContext context) {
        throw new AssertionError("Expected onLoad error");
    }

    /// Performs no activation because registration always fails.
    @Override
    public void onEnable() {
    }

    /// Performs no deactivation because registration always fails.
    @Override
    public void onDisable() {
    }

    /// Cannot return a manifest because registration always fails first.
    ///
    /// @return never returns normally
    @Override
    public PluginManifest getManifest() {
        throw new IllegalStateException("Plugin was not loaded");
    }
}
