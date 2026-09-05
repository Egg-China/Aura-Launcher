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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.store.PluginStoreArtifact;
import org.jackhuang.hmcl.plugin.store.PluginStoreManifest;
import org.jackhuang.hmcl.plugin.store.PluginStoreRegistry;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/// Durable official-repository receipt bound to one exact selected platform artifact.
///
/// The retained coordinates are derived lookup fields only. [#verify(PluginTrustVerifier, PluginArtifactIdentity,
/// long)] re-verifies the original official-registry envelope, hashes the exact retained Store-manifest UTF-8,
/// parses that manifest through the normal Store contract, and compares every coordinate before authority is granted.
@NotNullByDefault
public final class PluginOfficialReceipt {
    /// Maximum accepted official-registry response size, matching Store downloads.
    private static final int MAX_REGISTRY_BYTES = 2 * 1024 * 1024;

    /// Maximum accepted Store-manifest response size, matching Store downloads.
    private static final int MAX_STORE_MANIFEST_BYTES = 4 * 1024 * 1024;

    /// Maximum package size accepted by Store downloads.
    private static final long MAX_PACKAGE_BYTES = 512L * 1024L * 1024L;

    /// Maximum recursive JSON nesting accepted during duplicate-field validation.
    private static final int MAX_JSON_DEPTH = 256;

    /// Complete signed official-registry envelope as exact UTF-8 bytes.
    private final byte @Unmodifiable [] officialRegistryEnvelopeUtf8;

    /// Exact UTF-8 bytes pinned by the signed registry entry.
    private final byte @Unmodifiable [] storeManifestUtf8;

    /// Exact manifest URL stored in the signed registry entry.
    private final String manifestUrl;

    /// Normalized repository identity shared by the registry entry and Store manifest.
    private final String repository;

    /// Canonical exact selected platform identifier.
    private final String selectedPlatform;

    /// Exact selected platform artifact URL.
    private final String artifactUrl;

    /// Exact installed package ID, version, and complete SHA-256.
    private final PluginArtifactIdentity artifactIdentity;

    /// Exact complete installed package size.
    private final long artifactSize;

    /// Retains one structurally bounded proof and its exact derived artifact coordinates.
    ///
    /// This constructor validates only immutable representation constraints. Call [#verify(PluginTrustVerifier,
    /// PluginArtifactIdentity, long)] before using the receipt as runtime authority.
    ///
    /// @param officialRegistryEnvelopeUtf8 complete signed official-registry envelope bytes
    /// @param storeManifestUtf8 exact Store-manifest bytes pinned by the registry
    /// @param manifestUrl exact signed registry manifest URL
    /// @param repository signed registry and Store-manifest repository identity
    /// @param selectedPlatform canonical exact selected platform
    /// @param artifactUrl exact selected artifact URL
    /// @param artifactIdentity exact installed package identity
    /// @param artifactSize exact installed package size
    public PluginOfficialReceipt(
            byte[] officialRegistryEnvelopeUtf8,
            byte[] storeManifestUtf8,
            String manifestUrl,
            String repository,
            String selectedPlatform,
            String artifactUrl,
            PluginArtifactIdentity artifactIdentity,
            long artifactSize
    ) {
        this.officialRegistryEnvelopeUtf8 = requireProofBytes(
                officialRegistryEnvelopeUtf8,
                MAX_REGISTRY_BYTES,
                "official registry envelope"
        );
        this.storeManifestUtf8 = requireProofBytes(
                storeManifestUtf8,
                MAX_STORE_MANIFEST_BYTES,
                "Store manifest"
        );
        this.manifestUrl = requireNonBlank(manifestUrl, "manifest URL");
        this.repository = PluginTrustVerifier.normalizeRepository(repository);
        PluginPlatformTarget platform = PluginPlatformTarget.parse(selectedPlatform);
        if (!platform.getId().equals(selectedPlatform)) {
            throw new IllegalArgumentException("Official receipt platform must be canonical");
        }
        this.selectedPlatform = selectedPlatform;
        this.artifactUrl = requireNonBlank(artifactUrl, "artifact URL");
        this.artifactIdentity = Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        if (artifactSize <= 0 || artifactSize > MAX_PACKAGE_BYTES) {
            throw new IllegalArgumentException("Official receipt artifact size exceeds Store download limits");
        }
        this.artifactSize = artifactSize;
    }

    /// Returns a defensive copy of the complete signed official-registry envelope bytes.
    ///
    /// @return immutable-by-contract UTF-8 envelope bytes
    public byte @Unmodifiable [] getOfficialRegistryEnvelopeUtf8() {
        return officialRegistryEnvelopeUtf8.clone();
    }

    /// Returns a defensive copy of the exact Store-manifest bytes.
    ///
    /// @return immutable-by-contract UTF-8 manifest bytes
    public byte @Unmodifiable [] getStoreManifestUtf8() {
        return storeManifestUtf8.clone();
    }

    /// Returns the exact manifest URL from the signed registry entry.
    ///
    /// @return manifest URL
    public String getManifestUrl() {
        return manifestUrl;
    }

    /// Returns the normalized signed repository identity.
    ///
    /// @return GitHub owner/repository identity
    public String getRepository() {
        return repository;
    }

    /// Returns the canonical exact selected platform.
    ///
    /// @return platform identifier
    public String getSelectedPlatform() {
        return selectedPlatform;
    }

    /// Returns the exact selected release artifact URL.
    ///
    /// @return artifact URL
    public String getArtifactUrl() {
        return artifactUrl;
    }

    /// Returns the exact stored package identity.
    ///
    /// @return immutable package ID, version, and SHA-256
    public PluginArtifactIdentity getArtifactIdentity() {
        return artifactIdentity;
    }

    /// Returns the exact stored package size.
    ///
    /// @return package size in bytes
    public long getArtifactSize() {
        return artifactSize;
    }

    /// Re-verifies the complete official proof and binds it to current installed package bytes.
    ///
    /// This method accepts no cached Store trust result. The supplied verifier must authenticate the retained envelope
    /// with its official-repository root role on every call.
    ///
    /// @param verifier current embedded-root verifier
    /// @param installedIdentity exact current installed package identity
    /// @param installedSize exact current installed package size
    /// @throws IllegalArgumentException if any signature, byte digest, document, coordinate, or artifact field differs
    public void verify(
            PluginTrustVerifier verifier,
            PluginArtifactIdentity installedIdentity,
            long installedSize
    ) {
        Objects.requireNonNull(verifier, "verifier");
        Objects.requireNonNull(installedIdentity, "installedIdentity");
        if (!artifactIdentity.equals(installedIdentity) || artifactSize != installedSize) {
            throw new IllegalArgumentException("Official receipt does not match the installed NPL identity");
        }

        try {
            PluginDocumentVerification registryVerification = verifier.verifyOfficialRegistry(
                    parseObject(officialRegistryEnvelopeUtf8, "official registry envelope")
            );
            if (registryVerification.trust().level() != PluginTrustLevel.OFFICIAL) {
                throw new IllegalArgumentException("Official registry signature verification failed");
            }

            @Nullable PluginStoreRegistry registry = JsonUtils.GSON.fromJson(
                    registryVerification.signed(),
                    PluginStoreRegistry.class
            );
            if (registry == null) {
                throw new IllegalArgumentException("Official registry payload is empty");
            }
            registry.validate();
            @Nullable PluginStoreRegistry.PluginStoreEntry entry =
                    registry.findPlugin(artifactIdentity.getPluginId());
            if (entry == null) {
                throw new IllegalArgumentException("Official registry has no matching plugin entry");
            }
            requireEqual(manifestUrl, entry.getManifestUrl(), "manifest URL");
            requireEqual(repository, PluginTrustVerifier.normalizeRepository(entry.getRepository()), "repository");
            requireEqual(sha256(storeManifestUtf8), entry.getManifestSha256(), "manifest SHA-256");

            PluginStoreManifest manifest = PluginStoreManifest.fromJson(
                    parseObject(storeManifestUtf8, "Store manifest"),
                    artifactIdentity.getPluginId()
            );
            requireEqual(repository, PluginTrustVerifier.normalizeRepository(manifest.getRepository()), "repository");
            @Nullable PluginStoreManifest.PluginVersionEntry version =
                    manifest.getVersion(artifactIdentity.getVersion());
            if (version == null) {
                throw new IllegalArgumentException("Store manifest has no matching plugin version");
            }
            PluginStoreArtifact artifact = exactArtifact(version, selectedPlatform);
            requireEqual(artifactUrl, artifact.packageUrl(), "artifact URL");
            requireEqual(artifactIdentity.getSha256(), artifact.sha256(), "artifact SHA-256");
            if (artifactSize != artifact.size()) {
                throw new IllegalArgumentException("Official receipt artifact size does not match the Store manifest");
            }
        } catch (IOException | JsonParseException exception) {
            throw new IllegalArgumentException("Official installation receipt document is invalid", exception);
        }
    }

    /// Selects exactly one platform artifact without compatibility fallbacks.
    ///
    /// @param version validated exact Store version
    /// @param platform canonical selected platform
    /// @return exact matching artifact
    private static PluginStoreArtifact exactArtifact(
            PluginStoreManifest.PluginVersionEntry version,
            String platform
    ) {
        @Nullable PluginStoreArtifact selected = null;
        for (PluginStoreArtifact artifact : version.getArtifacts()) {
            if (!artifact.platform().getId().equals(platform)) {
                continue;
            }
            if (selected != null) {
                throw new IllegalArgumentException("Store manifest has duplicate selected-platform artifacts");
            }
            selected = artifact;
        }
        if (selected == null) {
            throw new IllegalArgumentException("Store manifest has no exact selected-platform artifact");
        }
        return selected;
    }

    /// Copies and validates one bounded non-empty UTF-8 JSON object byte sequence.
    ///
    /// @param value untrusted source bytes
    /// @param maximumBytes maximum permitted length
    /// @param description diagnostic document name
    /// @return defensive copy
    private static byte @Unmodifiable [] requireProofBytes(
            byte[] value,
            int maximumBytes,
            String description
    ) {
        Objects.requireNonNull(value, description);
        if (value.length == 0 || value.length > maximumBytes) {
            throw new IllegalArgumentException(description + " has an invalid byte length");
        }
        byte[] copy = value.clone();
        parseObject(copy, description);
        return copy;
    }

    /// Strictly decodes and parses one UTF-8 JSON object without exposing the mutable parse tree.
    ///
    /// @param value exact UTF-8 bytes
    /// @param description diagnostic document name
    /// @return newly parsed object
    private static JsonObject parseObject(byte[] value, String description) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
            if (decoded.startsWith("\ufeff")
                    || !Arrays.equals(value, decoded.getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException(description + " is not canonical UTF-8");
            }
            requireUniqueObjectFields(decoded, description);
            JsonElement parsed = JsonParser.parseString(decoded);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException(description + " is not a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (CharacterCodingException | JsonParseException exception) {
            throw new IllegalArgumentException(description + " is not valid UTF-8 JSON", exception);
        }
    }

    /// Strictly traverses one JSON document and rejects duplicate object fields at every nesting depth.
    ///
    /// @param decoded canonical UTF-8 JSON text
    /// @param description diagnostic document name
    private static void requireUniqueObjectFields(String decoded, String description) {
        try (JsonReader reader = new JsonReader(new StringReader(decoded))) {
            reader.setStrictness(Strictness.STRICT);
            scanValue(reader, 0, description);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException(description + " has trailing JSON data");
            }
        } catch (IOException | IllegalStateException exception) {
            throw new IllegalArgumentException(description + " is not strict JSON", exception);
        }
    }

    /// Scans one strict JSON value while maintaining a separate duplicate-name set for each object.
    ///
    /// @param reader strict streaming reader
    /// @param depth current container nesting depth
    /// @param description diagnostic document name
    /// @throws IOException if token reading fails
    private static void scanValue(JsonReader reader, int depth, String description) throws IOException {
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT -> {
                requireDepth(depth, description);
                Set<String> names = new HashSet<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) {
                        throw new IllegalArgumentException(description + " contains duplicate field: " + name);
                    }
                    scanValue(reader, depth + 1, description);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                requireDepth(depth, description);
                reader.beginArray();
                while (reader.hasNext()) {
                    scanValue(reader, depth + 1, description);
                }
                reader.endArray();
            }
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw new IllegalArgumentException(description + " contains an invalid JSON token");
        }
    }

    /// Bounds recursive validation before adversarial nesting can exhaust the Java stack.
    ///
    /// @param depth current container nesting depth
    /// @param description diagnostic document name
    private static void requireDepth(int depth, String description) {
        if (depth >= MAX_JSON_DEPTH) {
            throw new IllegalArgumentException(description + " exceeds the maximum JSON nesting depth");
        }
    }

    /// Computes the lower-case SHA-256 digest of exact retained bytes.
    ///
    /// @param value bytes to hash
    /// @return lower-case hexadecimal digest
    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Required SHA-256 implementation is unavailable", exception);
        }
    }

    /// Requires a non-blank exact coordinate without normalizing it.
    ///
    /// @param value coordinate value
    /// @param description diagnostic field name
    /// @return unchanged value
    private static String requireNonBlank(String value, String description) {
        Objects.requireNonNull(value, description);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Official receipt " + description + " cannot be blank");
        }
        return value;
    }

    /// Requires an exact equality match for one derived signed field.
    ///
    /// @param expected retained receipt value
    /// @param actual newly verified value
    /// @param description diagnostic field name
    private static void requireEqual(String expected, String actual, String description) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Official receipt " + description + " does not match signed data");
        }
    }
}
