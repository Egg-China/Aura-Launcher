/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
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
