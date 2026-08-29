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

/// Immutable trust decision and credential-safe diagnostic metadata.
@NotNullByDefault
public record PluginTrustResult(
        PluginTrustLevel level,
        String detail,
        @Nullable String keyId,
        @Nullable String certificateSerial,
        @Nullable PluginCertificationReceipt certificationReceipt
) {
    /// Validates one trust result at construction.
    public PluginTrustResult {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(detail, "detail");
    }

    /// Creates an unsigned community decision.
    public static PluginTrustResult community() {
        return new PluginTrustResult(PluginTrustLevel.COMMUNITY, "unsigned community manifest", null, null, null);
    }

    /// Creates a rejected signing decision.
    public static PluginTrustResult rejected(String detail) {
        return new PluginTrustResult(PluginTrustLevel.REJECTED, detail, null, null, null);
    }

    /// Creates an official repository decision.
    public static PluginTrustResult official(String keyId) {
        return new PluginTrustResult(PluginTrustLevel.OFFICIAL, "official repository signature", keyId, null, null);
    }

    /// Creates a certified developer decision.
    public static PluginTrustResult certified(String keyId, String serial) {
        return new PluginTrustResult(
                PluginTrustLevel.CERTIFIED,
                "certified developer signature",
                keyId,
                serial,
                null
        );
    }

    /// Creates a community-review certification delegated by the signed official plugin registry.
    ///
    /// The registry reference itself carries the trust decision, so this result has no release signer,
    /// verification serial, or installation receipt.
    public static PluginTrustResult certifiedByOfficialReference() {
        return new PluginTrustResult(
                PluginTrustLevel.CERTIFIED,
                "referenced by official plugin registry",
                null,
                null,
                null
        );
    }

    /// Attaches the two proof envelopes required to persist this certified decision safely.
    ///
    /// @param receipt exact proof-backed installation receipt
    /// @return certified decision carrying the receipt
    public PluginTrustResult withCertificationReceipt(PluginCertificationReceipt receipt) {
        if (level != PluginTrustLevel.CERTIFIED
                || keyId == null
                || certificateSerial == null
                || !keyId.equals(receipt.artifactSignerKeyId())
                || !certificateSerial.equals(receipt.repositoryVerificationId())) {
            throw new IllegalStateException("Only the matching certified decision can carry this receipt");
        }
        return new PluginTrustResult(level, detail, keyId, certificateSerial, receipt);
    }

    /// Returns whether installation may continue to permission review.
    public boolean canInstall() {
        return level.isInstallable();
    }

    /// Returns whether installation requires a source warning.
    public boolean requiresSourceWarning() {
        return level.requiresSourceWarning();
    }
}
