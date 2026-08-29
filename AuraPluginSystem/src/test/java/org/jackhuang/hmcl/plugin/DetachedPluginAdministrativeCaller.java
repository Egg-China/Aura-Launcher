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

import java.io.IOException;
import java.util.Set;

/// Test helper loaded through a detached ordinary `URLClassLoader` to attempt self-authorization.
@NotNullByDefault
public final class DetachedPluginAdministrativeCaller implements Runnable {
    /// Manager targeted by the detached plugin-like caller.
    private final PluginManager manager;

    /// Installed plugin ID whose denied permission must remain denied.
    private final String pluginId;

    /// Creates a detached administrative caller.
    ///
    /// @param manager isolated plugin manager
    /// @param pluginId installed plugin ID
    public DetachedPluginAdministrativeCaller(PluginManager manager, String pluginId) {
        this.manager = manager;
        this.pluginId = pluginId;
    }

    /// Attempts to grant filesystem access without launcher confirmation.
    @Override
    public void run() {
        try {
            manager.setGrantedPermissions(pluginId, Set.of(PluginPermission.FILESYSTEM));
        } catch (IOException exception) {
            throw new IllegalStateException("Ucepected permission persistence failure", exception);
        }
    }
}
