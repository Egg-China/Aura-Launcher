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
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/// Captures one eligible Hook endpoint and its dispatch-time authorization and dependency state.
@NotNullByDefault
final class PluginHookSubscriber implements AutoCloseable {
    /// Canonical plugin ID.
    private final String pluginId;

    /// Declared plugin dependencies copied at snapshot time.
    private final @Unmodifiable Set<String> dependencyIds;

    /// Effective exact-artifact permissions copied at snapshot time.
    private final @Unmodifiable Set<PluginPermission> permissions;

    /// Runtime-neutral callback endpoint.
    private final PluginHookEndpoint endpoint;

    /// Releases the container callback lease.
    private final Runnable releaseLease;

    /// Whether this snapshot entry already released its lease.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates one immutable subscriber snapshot entry.
    ///
    /// @param pluginId canonical plugin ID
    /// @param dependencyIds declared plugin dependency IDs
    /// @param permissions exact-artifact permissions effective at snapshot time
    /// @param endpoint runtime-neutral callback endpoint
    /// @param releaseLease callback lease release action
    PluginHookSubscriber(
            String pluginId,
            Set<String> dependencyIds,
            Set<PluginPermission> permissions,
            PluginHookEndpoint endpoint,
            Runnable releaseLease
    ) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.dependencyIds = Set.copyOf(dependencyIds);
        this.permissions = Set.copyOf(permissions);
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.releaseLease = Objects.requireNonNull(releaseLease, "releaseLease");
    }

    /// Returns the canonical plugin ID.
    ///
    /// @return plugin ID
    String pluginId() {
        return pluginId;
    }

    /// Returns the immutable declared dependency ID snapshot.
    ///
    /// @return dependency IDs
    @Unmodifiable Set<String> dependencyIds() {
        return dependencyIds;
    }

    /// Returns the immutable effective permission snapshot.
    ///
    /// @return exact-artifact permissions
    @Unmodifiable Set<PluginPermission> permissions() {
        return permissions;
    }

    /// Returns the runtime-neutral endpoint.
    ///
    /// @return callback endpoint
    PluginHookEndpoint endpoint() {
        return endpoint;
    }

    /// Releases the callback lease exactly once.
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            releaseLease.run();
        }
    }
}
