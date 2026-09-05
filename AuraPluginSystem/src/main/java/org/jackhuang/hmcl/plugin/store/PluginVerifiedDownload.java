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

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.trust.PluginInstallationTrustProof;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/// Immutable result of a fully validated Store download published into an isolated staging directory.
///
/// A concrete trust proof records installation evidence only. Its presence does not authorize execution; callers
/// must still use the Plugin Manager's current revalidation and revocation boundary before activating the package.
///
/// @param stagedPath absolute normalized path of the atomically published NPL
/// @param artifactIdentity exact package ID, version, and complete SHA-256
/// @param artifactSize actual complete package size in bytes
/// @param trustProof concrete official or certified proof, or `null` for community content
@NotNullByDefault
public record PluginVerifiedDownload(
        Path stagedPath,
        PluginArtifactIdentity artifactIdentity,
        long artifactSize,
        @Nullable PluginInstallationTrustProof trustProof
) {
    /// Validates the immutable verified-download result boundary.
    public PluginVerifiedDownload {
        Objects.requireNonNull(stagedPath, "stagedPath");
        Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        if (!stagedPath.isAbsolute() || !stagedPath.normalize().equals(stagedPath)) {
            throw new IllegalArgumentException("Verified download path must be absolute and normalized");
        }
        if (artifactSize <= 0) {
            throw new IllegalArgumentException("Verified download size must be positive");
        }
    }
}
