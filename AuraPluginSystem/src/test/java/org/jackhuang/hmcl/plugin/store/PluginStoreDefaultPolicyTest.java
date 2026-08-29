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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.plugin.trust.PluginTrustVerifier;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that only validated trust capabilities enable the built-in official Store by default.
@NotNullByDefault
public final class PluginStoreDefaultPolicyTest {
    /// Derives automatic official-source enablement from a validated role while preserving explicit overrides.
    ///
    /// @throws GeneralSecurityException if the platform cannot create the policy fixture
    @Test
    public void derivesDefaultRegistryEnablementFromValidatedTrustRoot() throws GeneralSecurityException {
        PluginTrustVerifier developmentVerifier = policyVerifier(false);
        PluginTrustVerifier officialVerifier = policyVerifier(true);

        assertFalse(PluginStoreManager.defaultRegistryEnabled(null, developmentVerifier));
        assertTrue(PluginStoreManager.defaultRegistryEnabled(null, officialVerifier));
        assertFalse(PluginStoreManager.defaultRegistryEnabled("false", officialVerifier));
        assertTrue(PluginStoreManager.defaultRegistryEnabled("true", developmentVerifier));
    }

    /// Creates a valid policy fixture with or without the official repository role.
    ///
    /// @param official whether the root should authorize official registry signatures
    /// @return verifier backed by the requested purpose-scoped root
    /// @throws GeneralSecurityException if the platform cannot generate or hash an Ed25519 fixture key
    private static PluginTrustVerifier policyVerifier(boolean official) throws GeneralSecurityException {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "root");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("version", 1);
        signed.addProperty("expires", "2036-01-01T00:00:00Z");
        signed.addProperty("statusUrl", "");

        JsonObject keys = new JsonObject();
        JsonObject roles = new JsonObject();
        if (official) {
            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte @Unmodifiable [] encodedPublicKey = keyPair.getPublic().getEncoded();
            String keyId = "ed25519:" + sha256(encodedPublicKey);
            JsonObject key = new JsonObject();
            key.addProperty("keyType", "ed25519");
            key.addProperty("scheme", "ed25519");
            key.addProperty("publicKey", Base64.getEncoder().encodeToString(encodedPublicKey));
            keys.add(keyId, key);
            JsonObject role = new JsonObject();
            JsonArray keyIds = new JsonArray();
            keyIds.add(keyId);
            role.add("keyIds", keyIds);
            role.addProperty("threshold", 1);
            roles.add("official-repository", role);
        }
        signed.add("keys", keys);
        signed.add("roles", roles);

        JsonObject root = new JsonObject();
        root.add("signed", signed);
        root.add("signatures", new JsonArray());
        return PluginTrustVerifier.fromRoot(
                root,
                Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC),
                Set.of(),
                Set.of()
        );
    }

    /// Returns the lower-case SHA-256 digest used by Ed25519 trust-root key IDs.
    ///
    /// @param bytes encoded public key
    /// @return lower-case SHA-256 digest
    /// @throws GeneralSecurityException if SHA-256 is unavailable
    private static String sha256(byte @Unmodifiable [] bytes) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
