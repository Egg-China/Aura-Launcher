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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that every in-memory protocol identity preserves the Node.js safe-integer contract.
@NotNullByDefault
public final class PluginProtocolNumberTest {
    /// Rejects oversized repository IDs in weekly proofs and online repository state.
    @Test
    public void rejectsUnsafeRepositoryIds() {
        long unsafe = CanonicalJson.MAX_SAFE_INTEGER + 1;
        assertThrows(IllegalArgumentException.class, () -> new PluginRepositoryAttestation(
                "github.com/example/plugin",
                unsafe,
                "main",
                List.of("HMCLCE"),
                List.of("dev.example.plugin"),
                "approved",
                Instant.parse("2026-08-14T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z"),
                "2026-08",
                "0123456789abcdef0123456789abcdef01234567",
                "verification-1",
                "ed25519:" + "1".repeat(64)
        ));
        assertThrows(IllegalArgumentException.class, () -> new PluginTrustStatusSnapshot.RepositoryStatus(
                unsafe,
                "github.com/example/plugin",
                "verification-1",
                "approved",
                Instant.parse("2026-08-14T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z"),
                List.of("dev.example.plugin")
        ));
    }

    /// Rejects oversized status versions independently of the signed JSON parser.
    @Test
    public void rejectsUnsafeStatusVersion() {
        assertThrows(IllegalArgumentException.class, () -> new PluginTrustStatusSnapshot(
                CanonicalJson.MAX_SAFE_INTEGER + 1,
                Instant.parse("2026-08-14T00:00:00Z"),
                Instant.parse("2026-08-15T00:00:00Z"),
                "2026-08",
                List.of(),
                List.of(),
                Set.of(),
                "ed25519:" + "1".repeat(64)
        ));
    }

    /// Rejects oversized package sizes and repository IDs in verified and persisted certification identities.
    @Test
    public void rejectsUnsafeCertificationNumbers() {
        long unsafe = CanonicalJson.MAX_SAFE_INTEGER + 1;
        String sha256 = "a".repeat(64);
        String repositoryKey = "ed25519:" + "1".repeat(64);
        String artifactKey = "ed25519:" + "2".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> new PluginVerifiedCertification(
                "dev.example.plugin",
                "1.0.0",
                sha256,
                unsafe,
                1701,
                "github.com/example/plugin",
                repositoryKey,
                artifactKey,
                "verification-1"
        ));
        assertThrows(IllegalArgumentException.class, () -> new PluginCertificationReceipt(
                "dev.example.plugin",
                "1.0.0",
                sha256,
                42,
                unsafe,
                "github.com/example/plugin",
                repositoryKey,
                artifactKey,
                "verification-1",
                "{\"signed\":{},\"signatures\":[]}",
                "{\"signed\":{},\"signatures\":[]}"
        ));
    }
}
