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
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Immutable tagged wrapper for the concrete proof retained with one privileged plugin installation.
///
/// Official receipts are independently reverified through [PluginOfficialReceipt]. Certified receipts remain
/// exposed unchanged so integration can use the existing authenticated [PluginRuntimeTrustGuard] revalidation and
/// revocation contract. This wrapper never treats a cached trust badge as runtime authority.
@NotNullByDefault
public final class PluginInstallationTrustProof {
    /// Concrete installation-proof variants.
    @NotNullByDefault
    public enum Kind {
        /// Full signed official-registry and exact-manifest proof.
        OFFICIAL,

        /// Existing dual-attestation certification proof.
        CERTIFIED
    }

    /// Exact concrete proof variant.
    private final Kind kind;

    /// Official proof present only for [Kind#OFFICIAL].
    private final @Nullable PluginOfficialReceipt officialReceipt;

    /// Certification proof present only for [Kind#CERTIFIED].
    private final @Nullable PluginCertificationReceipt certificationReceipt;

    /// Creates one internally consistent tagged proof.
    ///
    /// @param kind concrete proof variant
    /// @param officialReceipt official receipt or `null`
    /// @param certificationReceipt certification receipt or `null`
    private PluginInstallationTrustProof(
            Kind kind,
            @Nullable PluginOfficialReceipt officialReceipt,
            @Nullable PluginCertificationReceipt certificationReceipt
    ) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.officialReceipt = officialReceipt;
        this.certificationReceipt = certificationReceipt;
    }

    /// Reconstructs an official structural proof from its complete durable receipt.
    ///
    /// Loading this tag does not authorize execution. Call [PluginOfficialReceipt#verify(PluginTrustVerifier,
    /// org.jackhuang.hmcl.plugin.PluginArtifactIdentity, long)] against the current installed artifact first.
    ///
    /// @param receipt complete durable official receipt
    /// @return official tagged proof
    public static PluginInstallationTrustProof official(PluginOfficialReceipt receipt) {
        return new PluginInstallationTrustProof(
                Kind.OFFICIAL,
                Objects.requireNonNull(receipt, "receipt"),
                null
        );
    }

    /// Reconstructs a certified structural proof from its complete durable receipt.
    ///
    /// Loading this tag does not establish current certification. Integration must submit the receipt to the existing
    /// authenticated certification and revocation guard before authorizing execution.
    ///
    /// @param receipt complete durable certification receipt
    /// @return certified tagged proof
    public static PluginInstallationTrustProof certified(PluginCertificationReceipt receipt) {
        return new PluginInstallationTrustProof(
                Kind.CERTIFIED,
                null,
                Objects.requireNonNull(receipt, "receipt")
        );
    }

    /// Converts one install-time trust decision to a durable concrete proof without persisting the decision itself.
    ///
    /// An official decision requires the supplied complete official receipt. A certified decision requires its own
    /// matching [PluginCertificationReceipt]. Community, rejected, and official-reference badge-only decisions fail
    /// closed.
    ///
    /// @param decision transient install-time trust decision
    /// @param officialReceipt complete official receipt for an official decision, otherwise `null`
    /// @return immutable concrete installation proof
    /// @throws IllegalArgumentException if the decision has no matching concrete receipt
    public static PluginInstallationTrustProof fromInstallDecision(
            PluginTrustResult decision,
            @Nullable PluginOfficialReceipt officialReceipt
    ) {
        Objects.requireNonNull(decision, "decision");
        if (decision.level() == PluginTrustLevel.OFFICIAL) {
            if (officialReceipt == null || decision.certificationReceipt() != null) {
                throw new IllegalArgumentException("Official installation requires a complete official receipt");
            }
            return official(officialReceipt);
        }
        if (decision.level() == PluginTrustLevel.CERTIFIED) {
            @Nullable PluginCertificationReceipt certificationReceipt = decision.certificationReceipt();
            if (officialReceipt != null || certificationReceipt == null
                    || !Objects.equals(decision.keyId(), certificationReceipt.artifactSignerKeyId())
                    || !Objects.equals(decision.certificateSerial(),
                    certificationReceipt.repositoryVerificationId())) {
                throw new IllegalArgumentException(
                        "Certified installation requires its matching certification receipt"
                );
            }
            return certified(certificationReceipt);
        }
        throw new IllegalArgumentException("Install decision has no authoritative installation proof");
    }

    /// Returns the concrete proof variant.
    ///
    /// @return official or certified kind
    public Kind kind() {
        return kind;
    }

    /// Returns the official receipt only for an official proof.
    ///
    /// @return official receipt or `null`
    public @Nullable PluginOfficialReceipt officialReceipt() {
        return officialReceipt;
    }

    /// Returns the certification receipt only for a certified proof.
    ///
    /// Callers granting current authority must pass this receipt through the existing authenticated certification
    /// and revocation guard rather than treating its presence as current certification.
    ///
    /// @return certification receipt or `null`
    public @Nullable PluginCertificationReceipt certificationReceipt() {
        return certificationReceipt;
    }
}
