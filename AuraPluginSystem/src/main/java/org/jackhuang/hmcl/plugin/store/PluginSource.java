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

import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Immutable persisted configuration for one plugin registry source.
@NotNullByDefault
public final class PluginSource {
    /// Stable identifier reserved for Aura's built-in source slot.
    public static final String OFFICIAL_ID = "official";

    /// Stable identifier reserved for the built-in GitHub Topic source.
    public static final String HMCLCE_TOPIC_ID = "github-topic-hmclce";

    /// Stable local source identifier.
    private final String id;

    /// Persisted registry URL.
    private final String url;

    /// Optional normalized local display name.
    private final @Nullable String alias;

    /// Whether the source participates in catalog aggregation.
    private final boolean enabled;

    /// Whether this source is the built-in official registry.
    private final boolean official;

    /// Creates an immutable source configuration.
    ///
    /// @param id stable source identifier
    /// @param url persisted registry URL
    /// @param alias optional local display name
    /// @param enabled whether the source participates in aggregation
    /// @param official whether the source is the built-in official registry
    public PluginSource(String id, String url, @Nullable String alias, boolean enabled, boolean official) {
        this.id = Objects.requireNonNull(id, "id");
        this.url = Objects.requireNonNull(url, "url");
        this.alias = normalizeAlias(alias);
        this.enabled = enabled;
        this.official = official;
    }

    /// Returns the stable local source identifier.
    ///
    /// @return source identifier
    public String getId() {
        return id;
    }

    /// Returns the persisted registry URL.
    ///
    /// @return registry URL
    public String getUrl() {
        return url;
    }

    /// Returns the optional normalized local display name.
    ///
    /// @return local display name, or `null` when none is configured
    public @Nullable String getAlias() {
        return alias;
    }

    /// Returns whether the source participates in catalog aggregation.
    ///
    /// @return whether the source is enabled
    public boolean isEnabled() {
        return enabled;
    }

    /// Returns whether the source is the built-in official registry.
    ///
    /// @return whether the source is official
    public boolean isOfficial() {
        return official;
    }

    /// Returns whether this source performs built-in GitHub Topic discovery.
    public boolean isGitHubTopic() {
        return HMCLCE_TOPIC_ID.equals(id);
    }

    /// Returns whether this source has a launcher-defined immutable security identity.
    public boolean isBuiltIn() {
        return official || isGitHubTopic();
    }

    /// Returns a copy with a replacement URL and alias.
    ///
    /// @param url replacement registry URL
    /// @param alias replacement optional local display name
    /// @return independently immutable source copy
    public PluginSource withConfiguration(String url, @Nullable String alias) {
        return new PluginSource(id, url, alias, enabled, official);
    }

    /// Returns a copy with replacement enablement.
    ///
    /// @param enabled replacement enablement
    /// @return independently immutable source copy
    public PluginSource withEnabled(boolean enabled) {
        return new PluginSource(id, url, alias, enabled, official);
    }

    /// Converts blank aliases to `null` and trims user-entered names.
    ///
    /// @param alias optional local display name
    /// @return normalized local display name, or `null` if blank
    private static @Nullable String normalizeAlias(@Nullable String alias) {
        return alias == null || StringUtils.isBlank(alias) ? null : alias.trim();
    }
}
