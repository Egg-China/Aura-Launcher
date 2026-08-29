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
package org.jackhuang.hmcl.plugin.store;

import org.jackhuang.hmcl.util.function.ExceptionalRunnable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/// Persists ordered registry sources and local plugin favorites as one transactional preference state.
@NotNullByDefault
public interface PluginSourceRepository {
    /// Returns sources in ascending catalog-priority order.
    ///
    /// @return immutable source snapshot
    @Unmodifiable List<PluginSource> getSources();

    /// Returns sources and their monotonic configuration revision as one atomic snapshot.
    ///
    /// @return immutable revision-bearing source configuration
    PluginSourceConfiguration getSourceConfiguration();

    /// Adds an enabled custom registry source.
    ///
    /// @param url registry URL
    /// @param alias optional local display name
    /// @return persisted source
    /// @throws IOException if validation or persistence fails
    PluginSource addSource(String url, @Nullable String alias) throws IOException;

    /// Replaces the URL and alias of one custom source while retaining its ID and priority.
    ///
    /// @param sourceId stable source identifier
    /// @param url replacement registry URL
    /// @param alias replacement optional local display name
    /// @return persisted source
    /// @throws IOException if validation or persistence fails
    PluginSource updateSource(String sourceId, String url, @Nullable String alias) throws IOException;

    /// Replaces the local alias of one source.
    ///
    /// @param sourceId stable source identifier
    /// @param alias replacement optional local display name
    /// @return persisted source
    /// @throws IOException if validation or persistence fails
    PluginSource updateAlias(String sourceId, @Nullable String alias) throws IOException;

    /// Removes one custom source.
    ///
    /// @param sourceId stable source identifier
    /// @throws IOException if persistence fails
    void removeSource(String sourceId) throws IOException;

    /// Changes whether one source participates in aggregation.
    ///
    /// @param sourceId stable source identifier
    /// @param enabled desired enablement
    /// @return persisted source
    /// @throws IOException if validation or persistence fails
    PluginSource setEnabled(String sourceId, boolean enabled) throws IOException;

    /// Replaces source priority with an exact permutation of current source IDs.
    ///
    /// @param sourceIds every current source ID exactly once in desired order
    /// @return immutable persisted source snapshot
    /// @throws IOException if validation or persistence fails
    @Unmodifiable List<PluginSource> reorder(@Unmodifiable List<String> sourceIds) throws IOException;

    /// Runs publication only while this repository still holds the expected revision and exact source values.
    ///
    /// Implementations must hold the same lock used by source mutations across both comparison and action.
    ///
    /// @param expectedConfiguration revision-bearing source configuration that selected the operation
    /// @param action publication action that must not race a source mutation
    /// @throws IOException if the expected configuration is stale or publication fails
    void executeIfSourcesMatch(
            PluginSourceConfiguration expectedConfiguration,
            ExceptionalRunnable<IOException> action
    ) throws IOException;

    /// Returns whether one plugin is a local favorite.
    ///
    /// @param pluginId plugin identifier
    /// @return favorite state
    boolean isFavorite(String pluginId);

    /// Updates one local favorite without exposing persistence failures to legacy callers.
    ///
    /// @param pluginId plugin identifier
    /// @param favorite desired favorite state
    void setFavorite(String pluginId, boolean favorite);

    /// Returns an immutable snapshot of favorite plugin IDs.
    ///
    /// @return favorite plugin IDs
    @Unmodifiable Set<String> getFavoritePluginIds();
}
