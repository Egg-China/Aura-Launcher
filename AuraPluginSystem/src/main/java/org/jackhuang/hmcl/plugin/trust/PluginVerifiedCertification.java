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
package org.jackhuang.hmcl.plugin.trust;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable values derived by re-verifying both signed certification envelopes for one installed NPL.
///
/// @param pluginId signed plugin ID
/// @param version signed package version
/// @param sha256 signed complete package SHA-256
/// @param size signed complete package size
/// @param repositoryId signed immutable GitHub repository ID
/// @param repository signed normalized GitHub repository identity
/// @param repositorySignerKeyId root-authorized repository-attestor key ID
/// @param artifactSignerKeyId root-authorized artifact-attestor key ID
/// @param repositoryVerificationId shared historical repository verification ID
@NotNullByDefault
public record PluginVerifiedCertification(
        String pluginId,
        String version,
        String sha256,
        long size,
        long repositoryId,
        String repository,
        String repositorySignerKeyId,
        String artifactSignerKeyId,
        String repositoryVerificationId
) {
    /// Rejects null components and non-positive numeric identities.
    public PluginVerifiedCertification {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(repositorySignerKeyId, "repositorySignerKeyId");
        Objects.requireNonNull(artifactSignerKeyId, "artifactSignerKeyId");
        Objects.requireNonNull(repositoryVerificationId, "repositoryVerificationId");
        if (size <= 0 || size > CanonicalJson.MAX_SAFE_INTEGER
                || repositoryId <= 0 || repositoryId > CanonicalJson.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("Certification size and repository ID must be positive safe integers");
        }
    }
}
