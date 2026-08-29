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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Captures and revalidates exact installed state around plugin installation confirmation and publication.
@NotNullByDefault
final class PluginInstallationStateGuard {
    /// Exact installed and loaded artifact resolver.
    private final PluginArtifactResolver artifactResolver;

    /// Creates one installation state guard.
    ///
    /// @param artifactResolver current artifact resolver
    PluginInstallationStateGuard(PluginArtifactResolver artifactResolver) {
        this.artifactResolver = artifactResolver;
    }

    /// Resolves exact current identities for every manifest in one locked planning snapshot.
    ///
    /// @param manifests immutable manifests selected for planning
    /// @return immutable exact identities with the same plugin ID key set
    /// @throws IOException if an artifact is absent, ambiguous, unreadable, or differs from its manifest snapshot
    @Unmodifiable Map<String, PluginArtifactIdentity> resolvePlanningArtifactIdentities(
            @Unmodifiable Map<String, PluginManifest> manifests
    ) throws IOException {
        Map<String, PluginArtifactIdentity> identities = new LinkedHashMap<>();
        for (Map.Entry<String, PluginManifest> entry : manifests.entrySet()) {
            @Nullable PluginPermissionService.ResolvedArtifact resolved =
                    artifactResolver.findCurrentPermissionArtifact(entry.getKey());
            if (resolved == null || !entry.getValue().equals(resolved.getManifest())) {
                throw new IOException("Plugin artifact does not match its installation-planning manifest: "
                        + entry.getKey());
            }
            identities.put(
                    entry.getKey(),
                    PluginArtifactIdentity.of(resolved.getManifest(), resolved.getArtifact().getSha256())
            );
        }
        return Map.copyOf(identities);
    }

    /// Captures inspection-time prior artifact expectations for local or compatibility installation callers.
    ///
    /// @param inspections immutable inspected packages
    /// @return immutable prior expectations indexed by replacement plugin ID
    static @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifactsFromInspections(
            @Unmodifiable List<LocalPluginInspection> inspections
    ) {
        Map<String, Optional<PluginArtifactIdentity>> expectations = new LinkedHashMap<>();
        for (LocalPluginInspection inspection : inspections) {
            expectations.put(
                    inspection.manifest.getId(),
                    Optional.ofNullable(inspection.priorArtifactIdentity)
            );
        }
        return Map.copyOf(expectations);
    }

    /// Revalidates the exact old state of every replacement before any transaction file is written.
    ///
    /// @param replacementIds exact plugin IDs replaced by the batch
    /// @param expectedPriorArtifacts confirmed absence or exact old artifact for every replacement
    /// @throws IOException if an install target appeared or an update target changed after confirmation
    void validateReplacementPriorArtifacts(
            @Unmodifiable Set<String> replacementIds,
            @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts
    ) throws IOException {
        if (!replacementIds.equals(expectedPriorArtifacts.keySet())) {
            throw new IllegalArgumentException("Every replacement must have exactly one prior artifact expectation");
        }
        for (String pluginId : replacementIds) {
            Optional<PluginArtifactIdentity> expected = Objects.requireNonNull(
                    expectedPriorArtifacts.get(pluginId)
            );
            @Nullable PluginArtifactIdentity current = artifactResolver.findCurrentArtifactIdentity(pluginId);
            if (expected.isEmpty()) {
                if (current != null) {
                    throw new IOException("Plugin " + pluginId
                            + " was installed after confirmation; refusing to overwrite " + current);
                }
                continue;
            }
            PluginArtifactIdentity expectedIdentity = expected.get();
            if (!expectedIdentity.getPluginId().equals(pluginId)) {
                throw new IllegalArgumentException("Prior artifact expectation key does not match its identity: "
                        + pluginId);
            }
            if (!expectedIdentity.equals(current)) {
                throw new IOException("Plugin " + pluginId + " changed after confirmation: expected "
                        + expectedIdentity + " but found " + (current == null ? "no artifact" : current));
            }
        }
    }
}
