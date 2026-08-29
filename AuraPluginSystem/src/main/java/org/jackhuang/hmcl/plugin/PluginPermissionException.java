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

/// Reports an attempt to use an official launcher capability that the user has not granted.
@NotNullByDefault
public final class PluginPermissionException extends SecurityException {
    /// Plugin ID whose capability request was denied.
    private final String pluginId;

    /// Capability required by the rejected operation.
    private final PluginPermission permission;

    /// Reason the capability is unavailable to the plugin.
    private final Reason reason;

    /// Creates a permission-denied exception for one plugin capability.
    ///
    /// @param pluginId plugin requesting the capability
    /// @param permission capability that is not currently granted
    public PluginPermissionException(String pluginId, PluginPermission permission) {
        this(pluginId, permission, Reason.USER_DENIED);
    }

    /// Creates a permission-denied exception with an explicit denial reason.
    ///
    /// @param pluginId plugin requesting the capability
    /// @param permission capability that is unavailable
    /// @param reason whether the developer omitted the request or the user denied it
    public PluginPermissionException(String pluginId, PluginPermission permission, Reason reason) {
        super(reason == Reason.NOT_DECLARED
                ? "Plugin " + pluginId + " did not declare permission " + permission.getId()
                : "Plugin " + pluginId + " is not granted permission " + permission.getId());
        this.pluginId = pluginId;
        this.permission = permission;
        this.reason = reason;
    }

    /// Returns the plugin whose operation was rejected.
    ///
    /// @return requesting plugin ID
    public String getPluginId() {
        return pluginId;
    }

    /// Returns the capability required by the rejected operation.
    ///
    /// @return missing permission
    public PluginPermission getPermission() {
        return permission;
    }

    /// Returns why the capability is unavailable.
    ///
    /// @return denial reason
    public Reason getReason() {
        return reason;
    }

    /// Distinguishes missing developer declarations from user-denied requests.
    @NotNullByDefault
    public enum Reason {
        /// The package manifest did not request the capability, so the user cannot grant it.
        NOT_DECLARED,

        /// The package requested the capability, but the user has not granted it.
        USER_DENIED
    }
}
