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

import org.jackhuang.hmcl.plugin.PluginVersion;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/// Selects one compatible runtime provider through a stable, fail-closed ranking policy.
@NotNullByDefault
public final class RuntimeProviderSelector {
    /// Deterministic provider order shared by registry snapshots and requirement selection.
    private static final Comparator<RuntimeProviderDescriptor> ORDER = Comparator
            .comparingInt(RuntimeProviderSelector::availabilityTier)
            .thenComparingInt(RuntimeProviderDescriptor::sourcePriority)
            .thenComparing((left, right) -> PluginVersion.compare(right.version(), left.version()))
            .thenComparing(RuntimeProviderDescriptor::providerId);

    /// Creates a stateless provider selector.
    public RuntimeProviderSelector() {
    }

    /// Selects the highest-ranked compatible provider, or the compatible pinned provider when requested.
    ///
    /// Explicit pins bypass ranking and never fall back to another provider.
    ///
    /// @param requirement dependent runtime requirement
    /// @param candidates provider candidates
    /// @return selected compatible descriptor, or empty when none can satisfy the requirement
    public Optional<RuntimeProviderDescriptor> select(
            RuntimeRequirement requirement,
            Collection<RuntimeProviderDescriptor> candidates) {
        @Nullable String pinnedProviderId = requirement.getPinnedProviderId();
        if (pinnedProviderId != null) {
            return candidates.stream()
                    .filter(candidate -> pinnedProviderId.equals(candidate.providerId()))
                    .filter(candidate -> isCompatible(candidate, requirement))
                    .findFirst();
        }
        return ordered(candidates).stream()
                .filter(candidate -> isCompatible(candidate, requirement))
                .findFirst();
    }

    /// Returns an immutable deterministic snapshot without applying a runtime requirement.
    ///
    /// @param candidates descriptors to order
    /// @return ordered immutable descriptor list
    public @Unmodifiable List<RuntimeProviderDescriptor> ordered(
            Collection<RuntimeProviderDescriptor> candidates) {
        return candidates.stream().sorted(ORDER).toList();
    }

    /// Returns whether a descriptor satisfies every runtime capability dimension.
    ///
    /// @param descriptor provider descriptor
    /// @param requirement dependent requirement
    /// @return whether the provider can execute the dependent payload
    public boolean isCompatible(RuntimeProviderDescriptor descriptor, RuntimeRequirement requirement) {
        Optional<RuntimeProviderDeclaration> capability = descriptor.capability(requirement.getRuntime());
        if (capability.isEmpty()) {
            return false;
        }
        RuntimeProviderDeclaration declaration = capability.orElseThrow();
        return declaration.getAbis().contains(requirement.getPluginAbi())
                && declaration.getBridgeAbi() == requirement.getBridgeAbi()
                && declaration.getExecutionModes().contains(requirement.getExecutionMode())
                && declaration.getFeatures().containsAll(requirement.getRequiredFeatures());
    }

    /// Assigns the installed/enabled availability tier used as the primary sort key.
    ///
    /// @param descriptor provider descriptor
    /// @return zero for enabled installed, one for disabled installed, or two for remote
    private static int availabilityTier(RuntimeProviderDescriptor descriptor) {
        if (!descriptor.installed()) {
            return 2;
        }
        return descriptor.enabled() ? 0 : 1;
    }
}
