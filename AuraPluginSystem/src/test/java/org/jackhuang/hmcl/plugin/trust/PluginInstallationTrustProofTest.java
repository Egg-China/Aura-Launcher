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

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that installation trust is represented only by one concrete durable proof kind.
@NotNullByDefault
public final class PluginInstallationTrustProofTest {
    /// Stable exact package digest for structurally valid proof fixtures.
    private static final String SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    /// Stable structurally valid Ed25519 key identifier.
    private static final String KEY_ID = "ed25519:"
            + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    /// Wraps a complete official receipt without exposing any mutable trust result as authority.
    @Test
    public void createsOfficialProofOnlyWithOfficialReceipt() {
        PluginOfficialReceipt receipt = officialReceipt();
        PluginInstallationTrustProof proof = PluginInstallationTrustProof.fromInstallDecision(
                PluginTrustResult.official(KEY_ID),
                receipt
        );

        assertEquals(PluginInstallationTrustProof.Kind.OFFICIAL, proof.kind());
        assertSame(receipt, proof.officialReceipt());
        assertNull(proof.certificationReceipt());
    }

    /// Preserves the original certification receipt for the existing authenticated revocation guard.
    @Test
    public void createsCertifiedProofOnlyWithCertificationReceipt() {
        PluginCertificationReceipt receipt = certificationReceipt();
        PluginTrustResult decision = PluginTrustResult.certified(KEY_ID, "verification-17")
                .withCertificationReceipt(receipt);
        PluginInstallationTrustProof proof = PluginInstallationTrustProof.fromInstallDecision(decision, null);

        assertEquals(PluginInstallationTrustProof.Kind.CERTIFIED, proof.kind());
        assertNull(proof.officialReceipt());
        assertSame(receipt, proof.certificationReceipt());
    }

    /// Reconstructs either structural tag directly from its durable receipt without fabricating a trust decision.
    @Test
    public void reconstructsStructuralProofFromDurableReceipt() {
        PluginOfficialReceipt officialReceipt = officialReceipt();
        PluginCertificationReceipt certificationReceipt = certificationReceipt();

        PluginInstallationTrustProof official = PluginInstallationTrustProof.official(officialReceipt);
        PluginInstallationTrustProof certified = PluginInstallationTrustProof.certified(certificationReceipt);

        assertEquals(PluginInstallationTrustProof.Kind.OFFICIAL, official.kind());
        assertSame(officialReceipt, official.officialReceipt());
        assertNull(official.certificationReceipt());
        assertEquals(PluginInstallationTrustProof.Kind.CERTIFIED, certified.kind());
        assertNull(certified.officialReceipt());
        assertSame(certificationReceipt, certified.certificationReceipt());
    }

    /// Refuses the official-reference certification badge because it carries no exact signed receipt.
    @Test
    public void rejectsBadgeOnlyCertification() {
        assertThrows(IllegalArgumentException.class, () -> PluginInstallationTrustProof.fromInstallDecision(
                PluginTrustResult.certifiedByOfficialReference(),
                null
        ));
    }

    /// Refuses official and certified decisions when their required concrete receipt is absent.
    @Test
    public void rejectsMissingConcreteReceipt() {
        assertThrows(IllegalArgumentException.class, () -> PluginInstallationTrustProof.fromInstallDecision(
                PluginTrustResult.official(KEY_ID),
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> PluginInstallationTrustProof.fromInstallDecision(
                PluginTrustResult.certified(KEY_ID, "verification-17"),
                null
        ));
    }

    /// Refuses community and rejected decisions because neither can authorize privileged UI execution.
    @Test
    public void rejectsNonAuthoritativeInstallDecision() {
        assertThrows(IllegalArgumentException.class, () -> PluginInstallationTrustProof.fromInstallDecision(
                PluginTrustResult.community(),
                officialReceipt()
        ));
        assertThrows(IllegalArgumentException.class, () -> PluginInstallationTrustProof.fromInstallDecision(
                PluginTrustResult.rejected("fixture rejection"),
                officialReceipt()
        ));
    }

    /// Creates a structurally valid official receipt; signature verification belongs to its own focused suite.
    private static PluginOfficialReceipt officialReceipt() {
        return new PluginOfficialReceipt(
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "https://store.example/plugin.json",
                "github.com/example/plugin",
                "windows-x64",
                "https://store.example/plugin.npl",
                new PluginArtifactIdentity("dev.example.plugin", "1.0.0", SHA256),
                1L
        );
    }

    /// Creates a structurally valid certification receipt with complete placeholder JSON objects.
    private static PluginCertificationReceipt certificationReceipt() {
        return new PluginCertificationReceipt(
                "dev.example.plugin",
                "1.0.0",
                SHA256,
                1L,
                17L,
                "github.com/example/plugin",
                KEY_ID,
                KEY_ID,
                "verification-17",
                "{}",
                "{}"
        );
    }
}
