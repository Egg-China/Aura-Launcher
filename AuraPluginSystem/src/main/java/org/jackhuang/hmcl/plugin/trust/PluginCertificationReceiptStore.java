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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Persists exact structural installation proofs while retaining the certified-only API for legacy callers.
///
/// A loaded receipt is never runtime authority. Callers must cryptographically revalidate official receipt bytes
/// against the current installed artifact, and must revalidate certified receipts plus authenticated revocation state,
/// before authorizing plugin execution.
@NotNullByDefault
public final class PluginCertificationReceiptStore {
    /// Legacy certified-only document schema.
    private static final int LEGACY_SCHEMA_VERSION = 1;

    /// Current tagged installation-proof document schema.
    private static final int PROOF_SCHEMA_VERSION = 2;

    /// Maximum accepted or emitted receipt document size.
    private static final int MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;

    /// Maximum recursive JSON container nesting accepted from disk.
    private static final int MAX_JSON_DEPTH = 256;

    /// Stable launcher-local file name also captured by the package transaction journal.
    public static final String FILE_NAME = "plugin-certification-receipts.json";

    /// Deterministic JSON formatter for the private launcher-local document.
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /// Exact fields in a schema-1 document root.
    private static final @Unmodifiable Set<String> LEGACY_ROOT_FIELDS = Set.of("schemaVersion", "receipts");

    /// Exact fields in a schema-2 document root.
    private static final @Unmodifiable Set<String> PROOF_ROOT_FIELDS = Set.of("schemaVersion", "proofs");

    /// Exact fields in a tagged official entry.
    private static final @Unmodifiable Set<String> OFFICIAL_ENTRY_FIELDS = Set.of("kind", "official");

    /// Exact fields in a tagged certified entry.
    private static final @Unmodifiable Set<String> CERTIFIED_ENTRY_FIELDS = Set.of("kind", "certified");

    /// Exact fields in one durable certification receipt.
    private static final @Unmodifiable Set<String> CERTIFIED_FIELDS = Set.of(
            "pluginId", "version", "sha256", "size", "repositoryId", "repository",
            "repositorySignerKeyId", "artifactSignerKeyId", "repositoryVerificationId",
            "artifactAttestationJson", "repositoryAttestationJson"
    );

    /// Exact fields in one durable official receipt.
    private static final @Unmodifiable Set<String> OFFICIAL_FIELDS = Set.of(
            "officialRegistryEnvelopeBase64", "storeManifestBase64", "manifestUrl", "repository",
            "selectedPlatform", "artifactUrl", "pluginId", "version", "sha256", "artifactSize"
    );

    /// Exact receipt document path.
    private final Path receiptFile;

    /// Atomic replacement operation, injectable within this package for deterministic I/O failure verification.
    private final AtomicReceiptMover atomicReceiptMover;

    /// Creates a store rooted at one launcher-local home.
    ///
    /// @param localHome launcher-local home
    public PluginCertificationReceiptStore(Path localHome) {
        this(localHome, PluginCertificationReceiptStore::moveAtomically);
    }

    /// Creates a store with an explicit atomic replacement operation.
    ///
    /// @param localHome launcher-local home
    /// @param atomicReceiptMover atomic same-directory replacement operation
    PluginCertificationReceiptStore(Path localHome, AtomicReceiptMover atomicReceiptMover) {
        receiptFile = localHome.resolve(FILE_NAME).toAbsolutePath().normalize();
        this.atomicReceiptMover = Objects.requireNonNull(atomicReceiptMover, "atomicReceiptMover");
    }

    /// Performs the production atomic replacement without a non-atomic fallback.
    ///
    /// @param temporaryFile uniquely created complete document
    /// @param targetFile stable receipt document path
    /// @throws IOException if the filesystem cannot atomically replace the target
    private static void moveAtomically(Path temporaryFile, Path targetFile) throws IOException {
        try {
            Files.move(
                    temporaryFile,
                    targetFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic plugin receipt replacement is unsupported", exception);
        }
    }

    /// Reads the certified subset for existing callers without treating stored proof presence as authority.
    ///
    /// @return immutable certification receipts indexed by plugin ID
    /// @throws IOException if the document is unsafe, oversized, malformed, or internally inconsistent
    public synchronized @Unmodifiable Map<String, PluginCertificationReceipt> readAll() throws IOException {
        Map<String, PluginCertificationReceipt> certified = new LinkedHashMap<>();
        for (Map.Entry<String, PluginInstallationTrustProof> entry : readAllProofs().entrySet()) {
            if (entry.getValue().kind() == PluginInstallationTrustProof.Kind.CERTIFIED) {
                certified.put(entry.getKey(), Objects.requireNonNull(entry.getValue().certificationReceipt()));
            }
        }
        return Map.copyOf(certified);
    }

    /// Reads every exact structural installation proof from either supported schema.
    ///
    /// This method performs structural reconstruction only. Callers must cryptographically revalidate official proof
    /// bytes and perform authenticated certification and revocation checks before granting runtime authority.
    ///
    /// @return immutable proofs indexed by their exact receipt plugin IDs
    /// @throws IOException if the document is unsafe, oversized, malformed, or internally inconsistent
    public synchronized @Unmodifiable Map<String, PluginInstallationTrustProof> readAllProofs()
            throws IOException {
        if (!Files.exists(receiptFile, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        byte @Unmodifiable [] bytes = readBoundedDocument();
        JsonObject document = parseDocument(bytes);
        int schemaVersion;
        try {
            schemaVersion = requireInt(document, "schemaVersion", "receipt document");
            return switch (schemaVersion) {
                case LEGACY_SCHEMA_VERSION -> readLegacyDocument(document);
                case PROOF_SCHEMA_VERSION -> readProofDocument(document);
                default -> throw new IOException("Plugin receipt document has an unsupported schema");
            };
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException("Plugin receipt document is structurally invalid", exception);
        }
    }

    /// Replaces certified receipts for a complete installation batch while preserving unrelated proof kinds.
    ///
    /// Every replaced plugin ID without a new receipt loses any old official or certified proof.
    ///
    /// @param replacedPluginIds every plugin ID whose NPL is being replaced
    /// @param newReceipts certified replacement receipts indexed by plugin ID
    /// @throws IOException if current state is invalid or replacement cannot be persisted atomically
    public synchronized void replaceInstallations(
            Set<String> replacedPluginIds,
            Map<String, PluginCertificationReceipt> newReceipts
    ) throws IOException {
        Objects.requireNonNull(replacedPluginIds, "replacedPluginIds");
        Objects.requireNonNull(newReceipts, "newReceipts");
        @Unmodifiable Map<String, PluginCertificationReceipt> receiptSnapshot = Map.copyOf(newReceipts);
        @Unmodifiable Set<String> replacedPluginIdSnapshot = Set.copyOf(replacedPluginIds);
        if (!replacedPluginIdSnapshot.containsAll(receiptSnapshot.keySet())) {
            throw new IllegalArgumentException("Certification receipts contain a plugin outside the install batch");
        }
        Map<String, PluginInstallationTrustProof> proofs = new LinkedHashMap<>();
        for (Map.Entry<String, PluginCertificationReceipt> entry : receiptSnapshot.entrySet()) {
            PluginCertificationReceipt receipt = Objects.requireNonNull(entry.getValue(), "certification receipt");
            if (!entry.getKey().equals(receipt.pluginId())) {
                throw new IllegalArgumentException("Certification receipt map key does not match its plugin ID");
            }
            proofs.put(entry.getKey(), PluginInstallationTrustProof.certified(receipt));
        }
        replaceInstallationProofs(replacedPluginIdSnapshot, proofs);
    }

    /// Replaces concrete proofs for a complete installation batch while retaining unrelated installed identities.
    ///
    /// Every replaced plugin ID without a new proof loses any prior proof kind. Stored proofs remain structural data;
    /// runtime consumers must revalidate signatures, installed bytes, and authenticated revocation state.
    ///
    /// @param replacedPluginIds every plugin ID whose NPL is being replaced
    /// @param newProofs replacement structural proofs indexed by plugin ID
    /// @throws IOException if current state is invalid or replacement cannot be persisted atomically
    public synchronized void replaceInstallationProofs(
            Set<String> replacedPluginIds,
            Map<String, PluginInstallationTrustProof> newProofs
    ) throws IOException {
        Objects.requireNonNull(replacedPluginIds, "replacedPluginIds");
        Objects.requireNonNull(newProofs, "newProofs");
        @Unmodifiable Map<String, PluginInstallationTrustProof> proofSnapshot = Map.copyOf(newProofs);
        @Unmodifiable Set<String> replacedPluginIdSnapshot = Set.copyOf(replacedPluginIds);
        validateReplacement(replacedPluginIdSnapshot, proofSnapshot);
        Map<String, PluginInstallationTrustProof> replacement = new LinkedHashMap<>(readAllProofs());
        replacedPluginIdSnapshot.forEach(replacement::remove);
        replacement.putAll(proofSnapshot);
        write(replacement);
    }

    /// Removes one uninstalled plugin's proof of either kind while retaining every unrelated proof.
    ///
    /// @param pluginId removed plugin ID
    /// @throws IOException if current state is invalid or replacement cannot be persisted
    public synchronized void removePlugin(String pluginId) throws IOException {
        Objects.requireNonNull(pluginId, "pluginId");
        Map<String, PluginInstallationTrustProof> replacement = new LinkedHashMap<>(readAllProofs());
        if (replacement.remove(pluginId) != null) {
            write(replacement);
        }
    }

    /// Validates every replacement before any state is read or written.
    ///
    /// @param replacedPluginIds complete replacement batch
    /// @param newProofs concrete replacement proofs
    private static void validateReplacement(
            Set<String> replacedPluginIds,
            Map<String, PluginInstallationTrustProof> newProofs
    ) {
        Objects.requireNonNull(replacedPluginIds, "replacedPluginIds");
        Objects.requireNonNull(newProofs, "newProofs");
        if (!replacedPluginIds.containsAll(newProofs.keySet())) {
            throw new IllegalArgumentException("Installation proofs contain a plugin outside the install batch");
        }
        for (Map.Entry<String, PluginInstallationTrustProof> entry : newProofs.entrySet()) {
            PluginInstallationTrustProof proof = Objects.requireNonNull(entry.getValue(), "installation proof");
            if (!entry.getKey().equals(pluginId(proof))) {
                throw new IllegalArgumentException("Installation proof map key does not match its plugin ID");
            }
        }
    }

    /// Returns the exact receipt plugin ID while checking that the proof tag has one matching payload.
    ///
    /// @param proof structural proof
    /// @return exact plugin ID
    private static String pluginId(PluginInstallationTrustProof proof) {
        return switch (proof.kind()) {
            case OFFICIAL -> {
                PluginOfficialReceipt receipt = Objects.requireNonNull(proof.officialReceipt());
                if (proof.certificationReceipt() != null) {
                    throw new IllegalArgumentException("Official proof contains a certification receipt");
                }
                yield receipt.getArtifactIdentity().getPluginId();
            }
            case CERTIFIED -> {
                PluginCertificationReceipt receipt = Objects.requireNonNull(proof.certificationReceipt());
                if (proof.officialReceipt() != null) {
                    throw new IllegalArgumentException("Certified proof contains an official receipt");
                }
                yield receipt.pluginId();
            }
        };
    }

    /// Reads a regular non-symlink document with a hard byte ceiling even if it grows concurrently.
    ///
    /// @return exact document bytes
    /// @throws IOException if the file is unsafe, oversized, or unreadable
    private byte @Unmodifiable [] readBoundedDocument() throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                receiptFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("Plugin receipt document is not a regular file");
        }
        if (attributes.size() > MAX_DOCUMENT_BYTES) {
            throw new IOException("Plugin receipt document is too large");
        }
        @Unmodifiable Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (var channel = Files.newByteChannel(receiptFile, options);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) attributes.size())) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int total = 0;
            int count;
            while ((count = channel.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                total += count;
                if (total > MAX_DOCUMENT_BYTES) {
                    throw new IOException("Plugin receipt document is too large");
                }
                output.write(buffer.array(), 0, count);
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    /// Strictly decodes one canonical UTF-8 JSON object and rejects duplicates, trailing data, and excessive depth.
    ///
    /// @param bytes exact persisted bytes
    /// @return parsed root object
    /// @throws IOException if decoding or parsing fails
    private static JsonObject parseDocument(byte @Unmodifiable [] bytes) throws IOException {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (decoded.startsWith("\ufeff")
                    || !java.util.Arrays.equals(bytes, decoded.getBytes(StandardCharsets.UTF_8))) {
                throw new IOException("Plugin receipt document is not canonical UTF-8");
            }
            requireUniqueObjectFields(decoded);
            JsonElement parsed = JsonParser.parseString(decoded);
            if (!parsed.isJsonObject()) {
                throw new IOException("Plugin receipt document root is not an object");
            }
            return parsed.getAsJsonObject();
        } catch (CharacterCodingException | JsonParseException exception) {
            throw new IOException("Plugin receipt document is malformed", exception);
        }
    }

    /// Strictly traverses one JSON document and rejects duplicate object fields at every depth.
    ///
    /// @param decoded canonical UTF-8 document
    /// @throws IOException if strict traversal fails
    private static void requireUniqueObjectFields(String decoded) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(decoded))) {
            reader.setStrictness(Strictness.STRICT);
            scanValue(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Plugin receipt document has trailing JSON data");
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new IOException("Plugin receipt document is not strict JSON", exception);
        }
    }

    /// Scans one strict JSON value with a separate duplicate-name set for every object.
    ///
    /// @param reader strict streaming reader
    /// @param depth current container depth
    /// @throws IOException if token reading or validation fails
    private static void scanValue(JsonReader reader, int depth) throws IOException {
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT -> {
                requireDepth(depth);
                Set<String> names = new HashSet<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) {
                        throw new IOException("Plugin receipt document contains duplicate field: " + name);
                    }
                    scanValue(reader, depth + 1);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                requireDepth(depth);
                reader.beginArray();
                while (reader.hasNext()) {
                    scanValue(reader, depth + 1);
                }
                reader.endArray();
            }
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw new IOException("Plugin receipt document contains an invalid JSON token");
        }
    }

    /// Bounds recursive validation before adversarial nesting can exhaust the Java stack.
    ///
    /// @param depth current container depth
    /// @throws IOException if the nesting limit is exceeded
    private static void requireDepth(int depth) throws IOException {
        if (depth >= MAX_JSON_DEPTH) {
            throw new IOException("Plugin receipt document exceeds the maximum JSON nesting depth");
        }
    }

    /// Reads a legacy certified-only document into tagged structural proofs.
    ///
    /// @param document validated root object
    /// @return immutable tagged proofs
    private static @Unmodifiable Map<String, PluginInstallationTrustProof> readLegacyDocument(JsonObject document) {
        requireFields(document, LEGACY_ROOT_FIELDS, "schema-1 receipt document");
        Map<String, PluginInstallationTrustProof> proofs = new LinkedHashMap<>();
        for (JsonElement element : requireArray(document, "receipts", "schema-1 receipt document")) {
            PluginCertificationReceipt receipt = readCertified(requireObject(element, "certification receipt"));
            if (proofs.putIfAbsent(receipt.pluginId(), PluginInstallationTrustProof.certified(receipt)) != null) {
                throw new IllegalArgumentException("Receipt document contains a duplicate plugin ID");
            }
        }
        return Map.copyOf(proofs);
    }

    /// Reads a schema-2 tagged proof document through validated public receipt constructors.
    ///
    /// @param document validated root object
    /// @return immutable tagged proofs
    private static @Unmodifiable Map<String, PluginInstallationTrustProof> readProofDocument(JsonObject document) {
        requireFields(document, PROOF_ROOT_FIELDS, "schema-2 proof document");
        Map<String, PluginInstallationTrustProof> proofs = new LinkedHashMap<>();
        for (JsonElement element : requireArray(document, "proofs", "schema-2 proof document")) {
            JsonObject entry = requireObject(element, "installation proof");
            String kind = requireString(entry, "kind", "installation proof");
            PluginInstallationTrustProof proof;
            switch (kind) {
                case "OFFICIAL" -> {
                    requireFields(entry, OFFICIAL_ENTRY_FIELDS, "official proof entry");
                    proof = PluginInstallationTrustProof.official(readOfficial(
                            requireObject(entry.get("official"), "official receipt")
                    ));
                }
                case "CERTIFIED" -> {
                    requireFields(entry, CERTIFIED_ENTRY_FIELDS, "certified proof entry");
                    proof = PluginInstallationTrustProof.certified(readCertified(
                            requireObject(entry.get("certified"), "certification receipt")
                    ));
                }
                default -> throw new IllegalArgumentException("Installation proof kind is unsupported");
            }
            if (proofs.putIfAbsent(pluginId(proof), proof) != null) {
                throw new IllegalArgumentException("Proof document contains a duplicate plugin ID");
            }
        }
        return Map.copyOf(proofs);
    }

    /// Reconstructs one full certification receipt from explicitly typed fields.
    ///
    /// @param value serialized receipt object
    /// @return validated certification receipt
    private static PluginCertificationReceipt readCertified(JsonObject value) {
        requireFields(value, CERTIFIED_FIELDS, "certification receipt");
        return new PluginCertificationReceipt(
                requireString(value, "pluginId", "certification receipt"),
                requireString(value, "version", "certification receipt"),
                requireString(value, "sha256", "certification receipt"),
                requireLong(value, "size", "certification receipt"),
                requireLong(value, "repositoryId", "certification receipt"),
                requireString(value, "repository", "certification receipt"),
                requireString(value, "repositorySignerKeyId", "certification receipt"),
                requireString(value, "artifactSignerKeyId", "certification receipt"),
                requireString(value, "repositoryVerificationId", "certification receipt"),
                requireString(value, "artifactAttestationJson", "certification receipt"),
                requireString(value, "repositoryAttestationJson", "certification receipt")
        );
    }

    /// Reconstructs one full official receipt from bounded Base64 and explicitly typed coordinates.
    ///
    /// @param value serialized receipt object
    /// @return validated official receipt
    private static PluginOfficialReceipt readOfficial(JsonObject value) {
        requireFields(value, OFFICIAL_FIELDS, "official receipt");
        String pluginId = requireString(value, "pluginId", "official receipt");
        String version = requireString(value, "version", "official receipt");
        String sha256 = requireString(value, "sha256", "official receipt");
        return new PluginOfficialReceipt(
                decodeBase64(value, "officialRegistryEnvelopeBase64", "official receipt"),
                decodeBase64(value, "storeManifestBase64", "official receipt"),
                requireString(value, "manifestUrl", "official receipt"),
                requireString(value, "repository", "official receipt"),
                requireString(value, "selectedPlatform", "official receipt"),
                requireString(value, "artifactUrl", "official receipt"),
                new PluginArtifactIdentity(pluginId, version, sha256),
                requireLong(value, "artifactSize", "official receipt")
        );
    }

    /// Writes one complete deterministic schema-2 document through a unique same-directory temporary file.
    ///
    /// @param proofs complete proof state
    /// @throws IOException if serialization or replacement fails
    private void write(Map<String, PluginInstallationTrustProof> proofs) throws IOException {
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", PROOF_SCHEMA_VERSION);
        JsonArray entries = new JsonArray();
        proofs.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entries.add(writeProof(entry.getKey(), entry.getValue())));
        document.add("proofs", entries);
        byte @Unmodifiable [] bytes = GSON.toJson(document).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("Plugin receipt document is too large");
        }
        Path parent = Objects.requireNonNull(receiptFile.getParent());
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, receiptFile.getFileName() + ".", ".tmp");
        try {
            Files.write(temporaryFile, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            atomicReceiptMover.move(temporaryFile, receiptFile);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Serializes one validated tagged proof and checks its external map key.
    ///
    /// @param mapKey expected plugin ID
    /// @param proof concrete proof
    /// @return deterministic tagged JSON entry
    private static JsonObject writeProof(String mapKey, PluginInstallationTrustProof proof) {
        if (!mapKey.equals(pluginId(proof))) {
            throw new IllegalArgumentException("Installation proof map key does not match its plugin ID");
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("kind", proof.kind().name());
        switch (proof.kind()) {
            case OFFICIAL -> entry.add("official", writeOfficial(Objects.requireNonNull(proof.officialReceipt())));
            case CERTIFIED -> entry.add("certified",
                    writeCertified(Objects.requireNonNull(proof.certificationReceipt())));
        }
        return entry;
    }

    /// Serializes every certification receipt field without reflective private-state population.
    ///
    /// @param receipt certification receipt
    /// @return deterministic payload object
    private static JsonObject writeCertified(PluginCertificationReceipt receipt) {
        JsonObject value = new JsonObject();
        value.addProperty("pluginId", receipt.pluginId());
        value.addProperty("version", receipt.version());
        value.addProperty("sha256", receipt.sha256());
        value.addProperty("size", receipt.size());
        value.addProperty("repositoryId", receipt.repositoryId());
        value.addProperty("repository", receipt.repository());
        value.addProperty("repositorySignerKeyId", receipt.repositorySignerKeyId());
        value.addProperty("artifactSignerKeyId", receipt.artifactSignerKeyId());
        value.addProperty("repositoryVerificationId", receipt.repositoryVerificationId());
        value.addProperty("artifactAttestationJson", receipt.artifactAttestationJson());
        value.addProperty("repositoryAttestationJson", receipt.repositoryAttestationJson());
        return value;
    }

    /// Serializes exact official proof bytes and every derived receipt coordinate.
    ///
    /// @param receipt official receipt
    /// @return deterministic payload object
    private static JsonObject writeOfficial(PluginOfficialReceipt receipt) {
        PluginArtifactIdentity identity = receipt.getArtifactIdentity();
        JsonObject value = new JsonObject();
        value.addProperty("officialRegistryEnvelopeBase64",
                Base64.getEncoder().encodeToString(receipt.getOfficialRegistryEnvelopeUtf8()));
        value.addProperty("storeManifestBase64",
                Base64.getEncoder().encodeToString(receipt.getStoreManifestUtf8()));
        value.addProperty("manifestUrl", receipt.getManifestUrl());
        value.addProperty("repository", receipt.getRepository());
        value.addProperty("selectedPlatform", receipt.getSelectedPlatform());
        value.addProperty("artifactUrl", receipt.getArtifactUrl());
        value.addProperty("pluginId", identity.getPluginId());
        value.addProperty("version", identity.getVersion());
        value.addProperty("sha256", identity.getSha256());
        value.addProperty("artifactSize", receipt.getArtifactSize());
        return value;
    }

    /// Requires an object to contain exactly the known field set.
    ///
    /// @param value untrusted object
    /// @param expectedFields exact allowed and required field names
    /// @param description diagnostic object name
    private static void requireFields(JsonObject value, Set<String> expectedFields, String description) {
        if (!value.keySet().equals(expectedFields)) {
            throw new IllegalArgumentException(description + " has unknown or missing fields");
        }
    }

    /// Requires one named JSON array.
    ///
    /// @param value containing object
    /// @param field field name
    /// @param description diagnostic object name
    /// @return required array
    private static JsonArray requireArray(JsonObject value, String field, String description) {
        @Nullable JsonElement element = value.get(field);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException(description + " field " + field + " is not an array");
        }
        return element.getAsJsonArray();
    }

    /// Requires one JSON object value.
    ///
    /// @param value untrusted element
    /// @param description diagnostic object name
    /// @return required object
    private static JsonObject requireObject(@Nullable JsonElement value, String description) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(description + " is not an object");
        }
        return value.getAsJsonObject();
    }

    /// Requires one named JSON string without accepting numeric or Boolean coercion.
    ///
    /// @param value containing object
    /// @param field field name
    /// @param description diagnostic object name
    /// @return exact string value
    private static String requireString(JsonObject value, String field, String description) {
        @Nullable JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(description + " field " + field + " is not a string");
        }
        return element.getAsString();
    }

    /// Requires one named exact integer.
    ///
    /// @param value containing object
    /// @param field field name
    /// @param description diagnostic object name
    /// @return exact integer value
    private static int requireInt(JsonObject value, String field, String description) {
        long result = requireLong(value, field, description);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(description + " field " + field + " exceeds integer range");
        }
        return (int) result;
    }

    /// Requires one named exact long without accepting strings, fractions, or exponent overflow.
    ///
    /// @param value containing object
    /// @param field field name
    /// @param description diagnostic object name
    /// @return exact long value
    private static long requireLong(JsonObject value, String field, String description) {
        @Nullable JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(description + " field " + field + " is not an integer");
        }
        try {
            return new BigDecimal(element.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(description + " field " + field + " is not an exact integer", exception);
        }
    }

    /// Decodes one strict standard Base64 field before public receipt construction applies byte bounds.
    ///
    /// @param value containing object
    /// @param field field name
    /// @param description diagnostic object name
    /// @return decoded bytes
    private static byte @Unmodifiable [] decodeBase64(JsonObject value, String field, String description) {
        try {
            return Base64.getDecoder().decode(requireString(value, field, description));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(description + " field " + field + " is not valid Base64", exception);
        }
    }

    /// Moves a completely written temporary receipt over the stable document atomically.
    @FunctionalInterface
    @NotNullByDefault
    interface AtomicReceiptMover {
        /// Replaces the stable receipt with one complete temporary document.
        ///
        /// @param temporaryFile task-created temporary document
        /// @param targetFile stable receipt document
        /// @throws IOException if replacement fails
        void move(Path temporaryFile, Path targetFile) throws IOException;
    }
}
