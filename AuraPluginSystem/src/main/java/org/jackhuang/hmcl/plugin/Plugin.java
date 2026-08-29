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

/// Defines the lifecycle implemented by Java and Kotlin HMCL plugins.
@NotNullByDefault
public interface Plugin {
    /// Receives immutable package metadata and launcher services after the plugin class is created.
    ///
    /// @param context plugin context
    void onLoad(PluginContext context);

    /// Activates the plugin after all declared dependencies are loaded and enabled.
    void onEnable();

    /// Deactivates runtime registrations owned by the plugin.
    void onDisable();

    /// Releases resources immediately before the plugin class loader is closed.
    default void onUnload() {
    }

    /// Handles one manifest-declared Hook event.
    ///
    /// @param event immutable event envelope
    /// @return transactional Hook result
    default PluginHookResult onHook(PluginHookEvent event) {
        return PluginHookResult.unchanged();
    }

    /// Returns the plugin manifest associated with this instance.
    ///
    /// The manager uses the package manifest as the authoritative value and exposes this method for compatibility.
    ///
    /// @return plugin manifest
    PluginManifest getManifest();
}
