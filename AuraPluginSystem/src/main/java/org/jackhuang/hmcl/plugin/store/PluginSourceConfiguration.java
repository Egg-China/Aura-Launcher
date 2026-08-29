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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Immutable ordered plugin-source configuration paired with its monotonic repository revision.
@NotNullByDefault
public final class PluginSourceConfiguration {
    /// Monotonic revision advanced after every successful source mutation.
    private final long revision;

    /// Exact ordered source configuration at this revision.
    private final @Unmodifiable List<PluginSource> sources;

    /// Creates an immutable revision-bearing source configuration.
    ///
    /// @param revision non-negative source configuration revision
    /// @param sources exact ordered source configuration
    public PluginSourceConfiguration(long revision, @Unmodifiable List<PluginSource> sources) {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        this.revision = revision;
        this.sources = List.copyOf(sources);
    }

    /// Returns the monotonic source configuration revision.
    ///
    /// @return source configuration revision
    public long getRevision() {
        return revision;
    }

    /// Returns the exact ordered source configuration captured with this revision.
    ///
    /// @return immutable ordered source configuration
    public @Unmodifiable List<PluginSource> getSources() {
        return sources;
    }
}
