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

import java.util.Objects;

/// Resolves opaque launch-scoped secret slots when the plugin holds the `account` permission.
@FunctionalInterface
@NotNullByDefault
public interface PluginSecretAccess {
    /// Resolves one opaque slot without exposing other slot names.
    ///
    /// @param slot canonical secret slot
    /// @return copied secret value
    /// @throws PluginPermissionException if account access is unavailable
    /// @throws IllegalArgumentException if the granted accessor does not contain the slot
    String resolve(String slot);

    /// Creates an accessor that uniformly denies every slot lookup.
    ///
    /// @param pluginId plugin receiving the accessor
    /// @return permission-denied accessor
    static PluginSecretAccess denied(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        if (pluginId.isBlank()) {
            throw new IllegalArgumentException("Plugin ID must not be blank");
        }
        return slot -> {
            Objects.requireNonNull(slot, "slot");
            throw new PluginPermissionException(pluginId, PluginPermission.ACCOUNT);
        };
    }
}
