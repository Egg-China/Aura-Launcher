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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/// Parsed role keys and thresholds from the embedded Aura Launcher plugin trust root.
@NotNullByDefault
final class PluginTrustRoot {
    /// Online roles whose single-signature envelopes require isolated rotation key sets.
    private static final @Unmodifiable Set<String> ONLINE_ROLES = Set.of(
            "official-repository",
            "repository-attestor",
            "artifact-attestor",
            "trust-status"
    );

    /// Trusted public keys by key ID.
    private final @Unmodifiable Map<String, PublicKey> keys;

    /// Trusted role policies by role name.
    private final @Unmodifiable Map<String, Role> roles;

    /// Fixed online status endpoint supplied only by root metadata.
    private final String statusUrl;

    /// Parses one embedded root envelope.
    PluginTrustRoot(JsonObject document) {
        JsonObject signed = object(document, "signed");
        if (!"root".equals(string(signed, "_type")) || integer(signed, "schemaVersion") != 1) {
            throw new IllegalArgumentException("Unsupported plugin trust root");
        }
        keys = Map.copyOf(parseKeys(object(signed, "keys")));
        roles = Map.copyOf(parseRoles(object(signed, "roles"), keys.keySet()));
        statusUrl = java.util.Objects.requireNonNullElse(optionalString(signed, "statusUrl"), "");
    }

    /// Returns the root-controlled online status URL, or an empty string for development roots.
    String getStatusUrl() {
        return statusUrl;
    }

    /// Verifies one signed payload against the selected role threshold.
    ///
    /// @return one verified key ID, or `null` when the threshold is not met
    @Nullable String verifyRole(String roleName, String domain, JsonObject payload, JsonArray signatures) {
        @Nullable Role role = roles.get(roleName);
        if (role == null) {
            return null;
        }
        byte[] input = CanonicalJson.signatureInput(domain, payload);
        Set<String> verified = new HashSet<>();
        for (JsonElement signatureElement : signatures) {
            if (!signatureElement.isJsonObject()) {
                continue;
            }
            JsonObject signatureObject = signatureElement.getAsJsonObject();
            @Nullable String keyId = optionalString(signatureObject, "keyId");
            @Nullable String encodedSignature = optionalString(signatureObject, "signature");
            if (keyId == null || encodedSignature == null || !role.keyIds().contains(keyId) || verified.contains(keyId)) {
                continue;
            }
            PublicKey key = keys.get(keyId);
            if (key != null && verify(key, input, encodedSignature)) {
                verified.add(keyId);
                if (verified.size() >= role.threshold()) {
                    return keyId;
                }
            }
        }
        return null;
    }

    /// Parses and validates all root public keys.
    private static Map<String, PublicKey> parseKeys(JsonObject declarations) {
        Map<String, PublicKey> parsed = new HashMap<>();
        declarations.entrySet().forEach(entry -> {
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException("Root key declaration must be an object");
            }
            JsonObject declaration = entry.getValue().getAsJsonObject();
            if (!"ed25519".equals(string(declaration, "keyType"))
                    || !"ed25519".equals(string(declaration, "scheme"))) {
                throw new IllegalArgumentException("Unsupported root key algorithm");
            }
            try {
                byte[] encoded = Base64.getDecoder().decode(string(declaration, "publicKey"));
                PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
                String computed = "ed25519:" + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(encoded)
                );
                if (!computed.equals(entry.getKey())) {
                    throw new IllegalArgumentException("Root key ID does not match its public key");
                }
                parsed.put(entry.getKey(), key);
            } catch (Exception exception) {
                if (exception instanceof IllegalArgumentException illegalArgumentException) {
                    throw illegalArgumentException;
                }
                throw new IllegalArgumentException("Invalid Ed25519 root key", exception);
            }
        });
        return parsed;
    }

    /// Parses role thresholds and verifies that every referenced key exists.
    private static Map<String, Role> parseRoles(JsonObject declarations, Set<String> keyIds) {
        Map<String, Role> parsed = new HashMap<>();
        Set<String> assignedOnlineKeyIds = new HashSet<>();
        declarations.entrySet().forEach(entry -> {
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException("Root role declaration must be an object");
            }
            JsonObject declaration = entry.getValue().getAsJsonObject();
            JsonArray roleKeys = array(declaration, "keyIds");
            Set<String> selected = new HashSet<>();
            for (JsonElement value : roleKeys) {
                if (!value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isString()
                        || !selected.add(value.getAsString())) {
                    throw new IllegalArgumentException("Root role contains an invalid duplicate key ID");
                }
            }
            int threshold = integer(declaration, "threshold");
            if (threshold < 1 || threshold > selected.size() || !keyIds.containsAll(selected)) {
                throw new IllegalArgumentException("Invalid root role threshold or key reference");
            }
            if (ONLINE_ROLES.contains(entry.getKey())) {
                if (threshold != 1) {
                    throw new IllegalArgumentException("Online root roles must use threshold one");
                }
                if (selected.stream().anyMatch(assignedOnlineKeyIds::contains)) {
                    throw new IllegalArgumentException("Online root roles must not reuse signing keys");
                }
                assignedOnlineKeyIds.addAll(selected);
            }
            parsed.put(entry.getKey(), new Role(Set.copyOf(selected), threshold));
        });
        return parsed;
    }

    /// Verifies one Base64 Ed25519 signature.
    static boolean verify(PublicKey key, byte @Unmodifiable [] input, String encodedSignature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(input);
            return verifier.verify(Base64.getDecoder().decode(encodedSignature));
        } catch (Exception exception) {
            return false;
        }
    }

    /// Reads one required object field.
    static JsonObject object(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Required object field is missing: " + name);
        }
        return value.getAsJsonObject();
    }

    /// Reads one required array field.
    static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Required array field is missing: " + name);
        }
        return value.getAsJsonArray();
    }

    /// Reads one required string field.
    static String string(JsonObject object, String name) {
        @Nullable String value = optionalString(object, name);
        if (value == null) {
            throw new IllegalArgumentException("Required string field is missing: " + name);
        }
        return value;
    }

    /// Reads one optional string field.
    static @Nullable String optionalString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    /// Reads one required integer field.
    static int integer(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Required integer field is missing: " + name);
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Required integer field is not exact: " + name, exception);
        }
    }

    /// Immutable threshold role.
    private record Role(@Unmodifiable Set<String> keyIds, int threshold) {
    }
}
