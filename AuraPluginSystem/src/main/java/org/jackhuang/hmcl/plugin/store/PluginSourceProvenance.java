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

import org.jackhuang.hmcl.plugin.trust.PluginTrustLevel;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable security provenance for one selected plugin source.
@NotNullByDefault
public final class PluginSourceProvenance {
    /// Cryptographically derived trust level captured during dependency resolution.
    private final PluginTrustLevel trustLevel;

    /// Credential-safe configured source host captured during dependency resolution.
    private final String hostIdentity;

    /// Captures security provenance directly from an immutable source configuration.
    ///
    /// Mutable aliases and remote registry names intentionally do not participate in this value.
    ///
    /// @param source selected source configuration
    /// @return immutable official status and safe host identity
    public static PluginSourceProvenance from(PluginSource source) {
        Objects.requireNonNull(source, "source");
        return new PluginSourceProvenance(
                source.isOfficial() ? PluginTrustLevel.OFFICIAL : PluginTrustLevel.COMMUNITY,
                PluginSourceLabels.sourceUrlFallback(source)
        );
    }

    /// Captures security provenance from one exact source-bound store item.
    public static PluginSourceProvenance from(PluginStoreItem item) {
        Objects.requireNonNull(item, "item");
        return new PluginSourceProvenance(
                item.getTrust().level(),
                PluginSourceLabels.sourceUrlFallback(item.getSource())
        );
    }

    /// Captures security provenance from one exact source-bound package version.
    ///
    /// @param item source-bound item
    /// @param version exact selected version
    /// @return immutable version-specific provenance
    public static PluginSourceProvenance from(
            PluginStoreItem item,
            PluginStoreManifest.PluginVersionEntry version
    ) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(version, "version");
        return new PluginSourceProvenance(
                item.getTrust(version).level(),
                PluginSourceLabels.sourceUrlFallback(item.getSource())
        );
    }

    /// Creates one immutable source provenance snapshot.
    ///
    /// @param trustLevel verified trust level
    /// @param hostIdentity credential-safe configured source host
    private PluginSourceProvenance(PluginTrustLevel trustLevel, String hostIdentity) {
        this.trustLevel = Objects.requireNonNull(trustLevel, "trustLevel");
        this.hostIdentity = Objects.requireNonNull(hostIdentity, "hostIdentity");
    }

    /// Returns whether the source is the built-in official registry.
    ///
    /// @return official source status
    public boolean isOfficial() {
        return trustLevel == PluginTrustLevel.OFFICIAL;
    }

    /// Returns the exact trust level captured during resolution.
    public PluginTrustLevel getTrustLevel() {
        return trustLevel;
    }

    /// Returns the credential-safe configured source host.
    ///
    /// @return host identity without URL credentials, path, query, or fragment
    public String getHostIdentity() {
        return hostIdentity;
    }
}
