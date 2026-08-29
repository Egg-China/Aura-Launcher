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

import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/// Holds a package path, validated manifest, and exact identity selected before dependency traversal.
@NotNullByDefault
final class PluginPackageCandidate {
    /// Installed package path.
    final Path nplFile;

    /// Validated package manifest.
    final PluginManifest manifest;

    /// Exact package identity used for runtime policy and diagnostics.
    final PluginArtifactIdentity identity;

    /// Creates a package candidate.
    ///
    /// @param nplFile installed package path
    /// @param manifest validated manifest
    /// @param identity exact package identity
    PluginPackageCandidate(
            Path nplFile,
            PluginManifest manifest,
            PluginArtifactIdentity identity
    ) {
        this.nplFile = nplFile;
        this.manifest = manifest;
        this.identity = identity;
    }

    /// Verifies that the manifest captured during discovery belongs to the exact immutable package snapshot.
    ///
    /// @param pluginPackage verified package snapshot selected for lifecycle execution
    /// @throws IOException if the snapshot manifest is absent or differs from the discovered execution contract
    void verifySnapshotManifest(VerifiedPluginPackage pluginPackage) throws IOException {
        byte @Nullable @Unmodifiable [] verifiedManifestBytes = pluginPackage.readResourceBytes("plugin.json");
        if (verifiedManifestBytes == null) {
            throw new IOException("Verified plugin package has no root plugin.json: " + manifest.getId());
        }
        PluginManifest verifiedManifest = PluginManifest.fromJson(new InputStreamReader(
                new ByteArrayInputStream(verifiedManifestBytes),
                StandardCharsets.UTF_8
        ));
        if (!manifest.equals(verifiedManifest)
                || !identity.equals(PluginArtifactIdentity.of(verifiedManifest, identity.getSha256()))) {
            throw new IOException("Plugin manifest changed while the package was being verified: "
                    + manifest.getId());
        }
    }
}
