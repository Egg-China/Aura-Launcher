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
package org.jackhuang.hmcl.plugin.runtime;

import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;
import java.util.Set;

/// Immutable runtime-provider selection contract derived from a schema-v5 plugin manifest.
@NotNullByDefault
public final class RuntimeRequirement {
    /// Canonical runtime identifier required by the plugin.
    private final String runtime;

    /// Plugin ABI generation required by the plugin.
    private final int pluginAbi;

    /// Launcher-to-provider bridge ABI generation required by the plugin.
    private final int bridgeAbi;

    /// Requested execution boundary.
    private final PluginExecutionMode executionMode;

    /// Immutable runtime features required by the plugin.
    private final @Unmodifiable Set<RuntimeFeature> requiredFeatures;

    /// Optional pinned runtime-provider plugin ID.
    private final @Nullable String pinnedProviderId;

    /// Creates an immutable runtime requirement.
    ///
    /// @param runtime canonical runtime identifier
    /// @param pluginAbi required plugin ABI generation
    /// @param bridgeAbi required bridge ABI generation
    /// @param executionMode requested execution boundary
    /// @param requiredFeatures required provider capabilities
    /// @param pinnedProviderId optional provider plugin ID
    /// @throws IllegalArgumentException when any contract value is unsupported or incompatible
    public RuntimeRequirement(
            String runtime,
            int pluginAbi,
            int bridgeAbi,
            PluginExecutionMode executionMode,
            Set<RuntimeFeature> requiredFeatures,
            @Nullable String pinnedProviderId) {
        String canonicalRuntime = PluginRuntimeTypes.requireValid(runtime);
        if (!canonicalRuntime.equals(runtime)) {
            throw new IllegalArgumentException("Runtime requirement identifier must be canonical: " + runtime);
        }
        this.runtime = canonicalRuntime;
        this.pluginAbi = PluginAbi.requireValid(pluginAbi);
        if (bridgeAbi != 1) {
            throw new IllegalArgumentException("Unsupported runtime bridge ABI: " + bridgeAbi);
        }
        this.bridgeAbi = bridgeAbi;
        this.executionMode = Objects.requireNonNull(executionMode, "Runtime execution mode cannot be null");
        this.requiredFeatures = Set.copyOf(requiredFeatures);
        if (this.executionMode == PluginExecutionMode.ISOLATED
                && this.requiredFeatures.contains(RuntimeFeature.RAW_JVM)) {
            throw new IllegalArgumentException("Isolated runtime requirements cannot require raw-jvm");
        }
        if (pinnedProviderId != null && !PluginManifest.isValidId(pinnedProviderId)) {
            throw new IllegalArgumentException("Invalid runtime provider plugin ID: " + pinnedProviderId);
        }
        this.pinnedProviderId = pinnedProviderId;
    }

    /// Returns the canonical required runtime identifier.
    ///
    /// @return canonical runtime identifier
    public String getRuntime() {
        return runtime;
    }

    /// Returns the required plugin ABI generation.
    ///
    /// @return plugin ABI generation
    public int getPluginAbi() {
        return pluginAbi;
    }

    /// Returns the required bridge ABI generation.
    ///
    /// @return bridge ABI generation
    public int getBridgeAbi() {
        return bridgeAbi;
    }

    /// Returns the requested execution boundary.
    ///
    /// @return execution mode
    public PluginExecutionMode getExecutionMode() {
        return executionMode;
    }

    /// Returns the immutable required runtime features.
    ///
    /// @return required runtime features
    public @Unmodifiable Set<RuntimeFeature> getRequiredFeatures() {
        return requiredFeatures;
    }

    /// Returns the optional pinned runtime-provider plugin ID.
    ///
    /// @return pinned provider ID, or `null` when provider selection is unpinned
    public @Nullable String getPinnedProviderId() {
        return pinnedProviderId;
    }

    /// Compares every provider-selection contract field.
    ///
    /// @param other comparison target
    /// @return whether both requirements are equivalent
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof RuntimeRequirement requirement
                && pluginAbi == requirement.pluginAbi
                && bridgeAbi == requirement.bridgeAbi
                && runtime.equals(requirement.runtime)
                && executionMode == requirement.executionMode
                && requiredFeatures.equals(requirement.requiredFeatures)
                && Objects.equals(pinnedProviderId, requirement.pinnedProviderId);
    }

    /// Returns a hash derived from every provider-selection contract field.
    ///
    /// @return requirement hash
    @Override
    public int hashCode() {
        return Objects.hash(runtime, pluginAbi, bridgeAbi, executionMode, requiredFeatures, pinnedProviderId);
    }
}
