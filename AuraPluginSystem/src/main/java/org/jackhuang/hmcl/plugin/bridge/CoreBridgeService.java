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
package org.jackhuang.hmcl.plugin.bridge;

import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Registers stable payload-scoped launcher metadata and directory operations.
@NotNullByDefault
public final class CoreBridgeService {
    /// Registry receiving this service's frozen method handlers.
    private final BridgeServiceRegistry registry;

    /// Registers all stable Core handlers in one target registry.
    ///
    /// @param registry target Bridge service registry
    public CoreBridgeService(BridgeServiceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.registry.register(BridgeMethod.CORE_LAUNCHER_VERSION, this::launcherVersion);
        this.registry.register(BridgeMethod.CORE_PACKAGE_DIRECTORY, this::packageDirectory);
        this.registry.register(BridgeMethod.CORE_DATA_DIRECTORY, this::dataDirectory);
    }

    /// Returns the launcher version embedded in the running build.
    ///
    /// @param invocation verified payload invocation
    /// @param input validated null input
    /// @return launcher version string
    private BridgeValue launcherVersion(BridgeServiceRegistry.Invocation invocation, BridgeValue input) {
        return BridgeValue.string(Metadata.VERSION);
    }

    /// Returns the exact normalized payload package directory.
    ///
    /// @param invocation verified payload invocation
    /// @param input validated null input
    /// @return platform-native package path string
    private BridgeValue packageDirectory(BridgeServiceRegistry.Invocation invocation, BridgeValue input) {
        return BridgeValue.string(invocation.context().packagePath().toString());
    }

    /// Returns the exact normalized private payload data directory.
    ///
    /// @param invocation verified payload invocation
    /// @param input validated null input
    /// @return platform-native data path string
    private BridgeValue dataDirectory(BridgeServiceRegistry.Invocation invocation, BridgeValue input) {
        return BridgeValue.string(invocation.context().dataDirectory().toString());
    }
}
