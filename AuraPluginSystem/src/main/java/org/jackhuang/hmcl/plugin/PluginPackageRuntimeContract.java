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

import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Immutable Store expectation for every package field that defines a schema-v5 runtime contract.
@NotNullByDefault
public record PluginPackageRuntimeContract(
        String version,
        int schemaVersion,
        String runtime,
        int abi,
        @Unmodifiable List<String> platforms,
        PluginKind pluginKind,
        PluginExecutionMode executionMode,
        @Nullable String runtimeProvider,
        @Unmodifiable List<RuntimeProviderDeclaration> providesRuntimes
) {
    /// Creates a validated defensive snapshot independent of mutable Store metadata.
    public PluginPackageRuntimeContract {
        version = Objects.requireNonNull(version);
        if (version.isBlank()) {
            throw new IllegalArgumentException("Package runtime contract version cannot be blank");
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("Package runtime contract schema version must be positive");
        }
        runtime = PluginRuntimeTypes.requireValid(runtime);
        if (abi <= 0) {
            throw new IllegalArgumentException("Package runtime contract ABI must be positive");
        }
        platforms = List.copyOf(platforms);
        pluginKind = Objects.requireNonNull(pluginKind);
        executionMode = Objects.requireNonNull(executionMode);
        providesRuntimes = List.copyOf(providesRuntimes);
    }

    /// Captures the exact runtime contract parsed from a downloaded package manifest.
    ///
    /// @param manifest validated downloaded package manifest
    /// @return immutable package runtime contract
    public static PluginPackageRuntimeContract fromManifest(PluginManifest manifest) {
        return new PluginPackageRuntimeContract(
                manifest.getVersion(),
                manifest.getSchemaVersion(),
                manifest.getRuntime(),
                manifest.getAbi(),
                manifest.getPlatforms(),
                manifest.getPluginKind(),
                manifest.getExecutionMode(),
                manifest.getRuntimeProvider(),
                manifest.getProvidesRuntimes()
        );
    }
}
