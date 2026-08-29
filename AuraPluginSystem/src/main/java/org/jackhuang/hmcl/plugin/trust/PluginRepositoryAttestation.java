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
import org.jetbrains.annotations.Unmodifiable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// Immutable, role-verified weekly approval for one exact GitHub repository identity.
///
/// @param repository normalized GitHub repository identity
/// @param repositoryId immutable GitHub repository numeric ID
/// @param defaultBranch verified default branch
/// @param topics verified repository topics
/// @param pluginIds plugin IDs authorized by this repository
/// @param status approval state recorded by the repository verifier
/// @param checkedAt verification instant
/// @param validUntil exclusive proof expiry
/// @param policyVersion applied approval policy
/// @param sourceCommit source commit inspected by the verifier
/// @param verificationId stable audit reference shared with artifact proofs and online status
/// @param keyId root-authorized signing key that verified this proof
@NotNullByDefault
public record PluginRepositoryAttestation(
        String repository,
        long repositoryId,
        String defaultBranch,
        @Unmodifiable List<String> topics,
        @Unmodifiable List<String> pluginIds,
        String status,
        Instant checkedAt,
        Instant validUntil,
        String policyVersion,
        String sourceCommit,
        String verificationId,
        String keyId
) {
    /// Defensively copies collection values and rejects null components.
    public PluginRepositoryAttestation {
        Objects.requireNonNull(repository, "repository");
        if (repositoryId <= 0 || repositoryId > CanonicalJson.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("Repository ID must be a positive safe integer");
        }
        Objects.requireNonNull(defaultBranch, "defaultBranch");
        topics = List.copyOf(topics);
        pluginIds = List.copyOf(pluginIds);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(checkedAt, "checkedAt");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(sourceCommit, "sourceCommit");
        Objects.requireNonNull(verificationId, "verificationId");
        Objects.requireNonNull(keyId, "keyId");
    }

    /// Returns whether this repository proof remains approved and unexpired at the supplied instant.
    ///
    /// @param now validation instant
    /// @return whether artifact certification may use this proof
    public boolean isApprovedAt(Instant now) {
        return "approved".equals(status) && !now.isBefore(checkedAt) && now.isBefore(validUntil);
    }
}
