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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies backward-readable, bounded, durable storage for concrete installation trust proofs.
@NotNullByDefault
public final class PluginInstallationTrustProofStoreTest {
    /// Stable official fixture plugin ID.
    private static final String OFFICIAL_ID = "dev.example.official-ui";

    /// Stable certified fixture plugin ID.
    private static final String CERTIFIED_ID = "dev.example.certified-ui";

    /// Stable replacement fixture plugin ID.
    private static final String REPLACEMENT_ID = "dev.example.replacement-ui";

    /// Stable exact package digest.
    private static final String SHA256 = "a".repeat(64);

    /// Stable structurally valid signing key identifier.
    private static final String KEY_ID = "ed25519:" + "b".repeat(64);

    /// Exact platform selected by the signed official fixture.
    private static final String PLATFORM = "windows-x64";

    /// Exact official artifact URL selected by the signed manifest fixture.
    private static final String ARTIFACT_URL = "https://store.example/official-ui.npl";

    /// Exact manifest URL pinned by the signed official registry fixture.
    private static final String MANIFEST_URL = "https://store.example/official-ui.json";

    /// Exact normalized repository shared by the signed registry and manifest.
    private static final String OFFICIAL_REPOSITORY = "github.com/example/official-ui";

    /// Reads schema 1 through the proof API and migrates it without losing unrelated official state.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if fixture persistence fails
    @Test
    public void readsSchemaOneAndLegacyReplacementPreservesOfficialProof(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        PluginCertificationReceipt legacy = certificationReceipt(CERTIFIED_ID, "1.0.0");
        Files.writeString(receiptFile(temporaryDirectory), schemaOne(legacy), StandardCharsets.UTF_8);
        PluginCertificationReceiptStore store = new PluginCertificationReceiptStore(temporaryDirectory);

        assertEquals(Map.of(CERTIFIED_ID, legacy), store.readAll());
        assertEquals(
                PluginInstallationTrustProof.Kind.CERTIFIED,
                store.readAllProofs().get(CERTIFIED_ID).kind()
        );

        store.replaceInstallationProofs(
                Set.of(OFFICIAL_ID),
                Map.of(OFFICIAL_ID, PluginInstallationTrustProof.official(officialReceipt(OFFICIAL_ID, "2.0.0")))
        );
        PluginCertificationReceipt replacement = certificationReceipt(REPLACEMENT_ID, "3.0.0");
        store.replaceInstallations(Set.of(REPLACEMENT_ID), Map.of(REPLACEMENT_ID, replacement));

        @Unmodifiable Map<String, PluginInstallationTrustProof> proofs = store.readAllProofs();
        assertEquals(Set.of(CERTIFIED_ID, OFFICIAL_ID, REPLACEMENT_ID), proofs.keySet());
        assertEquals(PluginInstallationTrustProof.Kind.OFFICIAL, proofs.get(OFFICIAL_ID).kind());
        assertEquals(Map.of(CERTIFIED_ID, legacy, REPLACEMENT_ID, replacement), store.readAll());
        assertTrue(Files.readString(receiptFile(temporaryDirectory)).contains("\"schemaVersion\": 2"));
    }

    /// Round-trips every official byte and coordinate beside a complete certified envelope and returns immutable maps.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if fixture persistence fails
    @Test
    public void roundTripsMixedSchemaTwoProofsLosslesslyAndImmutably(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        OfficialFixture officialFixture = signedOfficialFixture();
        PluginOfficialReceipt expectedOfficial = officialFixture.receipt();
        PluginCertificationReceipt expectedCertified = certificationReceipt(CERTIFIED_ID, "1.0.0");
        PluginCertificationReceiptStore store = new PluginCertificationReceiptStore(temporaryDirectory);
        store.replaceInstallationProofs(
                Set.of(OFFICIAL_ID, CERTIFIED_ID),
                Map.of(
                        OFFICIAL_ID, PluginInstallationTrustProof.official(expectedOfficial),
                        CERTIFIED_ID, PluginInstallationTrustProof.certified(expectedCertified)
                )
        );

        @Unmodifiable Map<String, PluginInstallationTrustProof> actual = store.readAllProofs();
        PluginInstallationTrustProof actualOfficialProof = Objects.requireNonNull(actual.get(OFFICIAL_ID));
        PluginOfficialReceipt actualOfficial = Objects.requireNonNull(actualOfficialProof.officialReceipt());
        assertArrayEquals(
                expectedOfficial.getOfficialRegistryEnvelopeUtf8(),
                actualOfficial.getOfficialRegistryEnvelopeUtf8()
        );
        assertArrayEquals(expectedOfficial.getStoreManifestUtf8(), actualOfficial.getStoreManifestUtf8());
        assertEquals(expectedOfficial.getManifestUrl(), actualOfficial.getManifestUrl());
        assertEquals(expectedOfficial.getRepository(), actualOfficial.getRepository());
        assertEquals(expectedOfficial.getSelectedPlatform(), actualOfficial.getSelectedPlatform());
        assertEquals(expectedOfficial.getArtifactUrl(), actualOfficial.getArtifactUrl());
        assertEquals(expectedOfficial.getArtifactIdentity(), actualOfficial.getArtifactIdentity());
        assertEquals(expectedOfficial.getArtifactSize(), actualOfficial.getArtifactSize());
        assertEquals(expectedCertified, actual.get(CERTIFIED_ID).certificationReceipt());
        assertThrows(UnsupportedOperationException.class, () -> actual.remove(OFFICIAL_ID));
        assertThrows(UnsupportedOperationException.class, () -> store.readAll().remove(CERTIFIED_ID));
        assertDoesNotThrow(() -> actualOfficial.verify(
                officialFixture.verifier(),
                new PluginArtifactIdentity(OFFICIAL_ID, "2.0.0", SHA256),
                42L
        ));

        JsonObject tamperedEnvelope = JsonParser.parseString(new String(
                actualOfficial.getOfficialRegistryEnvelopeUtf8(),
                StandardCharsets.UTF_8
        )).getAsJsonObject();
        tamperedEnvelope.getAsJsonObject("signed").addProperty("name", "Tampered Store");
        PluginOfficialReceipt tampered = new PluginOfficialReceipt(
                tamperedEnvelope.toString().getBytes(StandardCharsets.UTF_8),
                actualOfficial.getStoreManifestUtf8(),
                actualOfficial.getManifestUrl(),
                actualOfficial.getRepository(),
                actualOfficial.getSelectedPlatform(),
                actualOfficial.getArtifactUrl(),
                actualOfficial.getArtifactIdentity(),
                actualOfficial.getArtifactSize()
        );
        assertThrows(IllegalArgumentException.class, () -> tampered.verify(
                officialFixture.verifier(),
                new PluginArtifactIdentity(OFFICIAL_ID, "2.0.0", SHA256),
                42L
        ));

        String serialized = Files.readString(receiptFile(temporaryDirectory));
        assertTrue(serialized.indexOf(CERTIFIED_ID) < serialized.indexOf(OFFICIAL_ID));
    }

    /// Replaces proof kinds in both directions, clears stale proofless replacements, and removes only one target.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if fixture persistence fails
    @Test
    public void replacementAndRemovalOperateAcrossBothProofKinds(@TempDir Path temporaryDirectory) throws Exception {
        PluginCertificationReceiptStore store = new PluginCertificationReceiptStore(temporaryDirectory);
        store.replaceInstallationProofs(
                Set.of(OFFICIAL_ID, CERTIFIED_ID, REPLACEMENT_ID),
                Map.of(
                        OFFICIAL_ID, PluginInstallationTrustProof.official(officialReceipt(OFFICIAL_ID, "1.0.0")),
                        CERTIFIED_ID, PluginInstallationTrustProof.certified(
                                certificationReceipt(CERTIFIED_ID, "1.0.0")
                        ),
                        REPLACEMENT_ID, PluginInstallationTrustProof.official(
                                officialReceipt(REPLACEMENT_ID, "1.0.0")
                        )
                )
        );

        store.replaceInstallations(
                Set.of(OFFICIAL_ID),
                Map.of(OFFICIAL_ID, certificationReceipt(OFFICIAL_ID, "2.0.0"))
        );
        store.replaceInstallationProofs(
                Set.of(CERTIFIED_ID),
                Map.of(CERTIFIED_ID, PluginInstallationTrustProof.official(
                        officialReceipt(CERTIFIED_ID, "2.0.0")
                ))
        );
        store.replaceInstallationProofs(Set.of(REPLACEMENT_ID), Map.of());
        store.removePlugin(OFFICIAL_ID);

        @Unmodifiable Map<String, PluginInstallationTrustProof> actual = store.readAllProofs();
        assertEquals(Set.of(CERTIFIED_ID), actual.keySet());
        assertEquals(PluginInstallationTrustProof.Kind.OFFICIAL, actual.get(CERTIFIED_ID).kind());
        assertTrue(store.readAll().isEmpty());
    }

    /// Rejects invalid mutation keys and batches before changing a recoverable prior document.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if fixture persistence fails
    @Test
    public void rejectsInvalidMutationInputsBeforeWriting(@TempDir Path temporaryDirectory) throws Exception {
        PluginCertificationReceiptStore store = seededStore(temporaryDirectory);
        byte @Unmodifiable [] original = Files.readAllBytes(receiptFile(temporaryDirectory));

        assertThrows(IllegalArgumentException.class, () -> store.replaceInstallationProofs(
                Set.of(OFFICIAL_ID),
                Map.of(CERTIFIED_ID, PluginInstallationTrustProof.official(
                        officialReceipt(CERTIFIED_ID, "2.0.0")
                ))
        ));
        assertThrows(IllegalArgumentException.class, () -> store.replaceInstallationProofs(
                Set.of(OFFICIAL_ID),
                Map.of(OFFICIAL_ID, PluginInstallationTrustProof.official(
                        officialReceipt(CERTIFIED_ID, "2.0.0")
                ))
        ));
        assertThrows(IllegalArgumentException.class, () -> store.replaceInstallations(
                Set.of(OFFICIAL_ID),
                Map.of(OFFICIAL_ID, certificationReceipt(CERTIFIED_ID, "2.0.0"))
        ));

        assertArrayEquals(original, Files.readAllBytes(receiptFile(temporaryDirectory)));
    }

    /// Uses immutable snapshots when caller-owned batch collections mutate during method execution.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if fixture persistence fails
    @Test
    public void snapshotsCallerCollectionsBeforeValidationAndUse(@TempDir Path temporaryDirectory)
            throws Exception {
        PluginCertificationReceiptStore proofStore = seededStore(temporaryDirectory.resolve("proof"));
        ConcurrentHashMap<String, PluginInstallationTrustProof> mutableProofs = new ConcurrentHashMap<>();
        mutableProofs.put(CERTIFIED_ID, PluginInstallationTrustProof.certified(
                certificationReceipt(CERTIFIED_ID, "1.0.0")
        ));
        Map<String, PluginInstallationTrustProof> proofView = new SnapshotKeyMap<>(mutableProofs);
        Set<String> proofBatch = new MutatingIterationSet(
                Set.of(CERTIFIED_ID),
                () -> mutableProofs.put(REPLACEMENT_ID, PluginInstallationTrustProof.certified(
                        certificationReceipt(REPLACEMENT_ID, "1.0.0")
                ))
        );

        proofStore.replaceInstallationProofs(proofBatch, proofView);

        assertTrue(mutableProofs.containsKey(REPLACEMENT_ID));
        assertEquals(
                Set.of(OFFICIAL_ID, CERTIFIED_ID),
                proofStore.readAllProofs().keySet()
        );

        Path legacyHome = temporaryDirectory.resolve("legacy");
        PluginCertificationReceiptStore legacyStore = seededStore(legacyHome);
        ConcurrentHashMap<String, PluginCertificationReceipt> mutableReceipts = new ConcurrentHashMap<>();
        mutableReceipts.put(CERTIFIED_ID, certificationReceipt(CERTIFIED_ID, "1.0.0"));
        Map<String, PluginCertificationReceipt> receiptView = new SnapshotKeyMap<>(mutableReceipts);
        Set<String> receiptBatch = new MutatingIterationSet(
                Set.of(CERTIFIED_ID),
                () -> mutableReceipts.put(REPLACEMENT_ID, certificationReceipt(REPLACEMENT_ID, "1.0.0"))
        );

        legacyStore.replaceInstallations(receiptBatch, receiptView);

        assertTrue(mutableReceipts.containsKey(REPLACEMENT_ID));
        assertEquals(Set.of(CERTIFIED_ID), legacyStore.readAll().keySet());
        assertEquals(Set.of(OFFICIAL_ID, CERTIFIED_ID), legacyStore.readAllProofs().keySet());
    }

    /// Cleans the actual task-created temporary file and preserves prior bytes after a post-write move failure.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if fixture persistence fails
    @Test
    public void postTempMoveFailureCleansCreatedFileAndPreservesDocument(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        PluginCertificationReceiptStore seeded = seededStore(temporaryDirectory);
        byte @Unmodifiable [] original = Files.readAllBytes(receiptFile(temporaryDirectory));
        AtomicReference<@Nullable Path> createdTemporaryFile = new AtomicReference<>();
        PluginCertificationReceiptStore failingStore = new PluginCertificationReceiptStore(
                temporaryDirectory,
                (temporaryFile, targetFile) -> {
                    assertTrue(Files.isRegularFile(temporaryFile, java.nio.file.LinkOption.NOFOLLOW_LINKS));
                    assertEquals(receiptFile(temporaryDirectory), targetFile);
                    createdTemporaryFile.set(temporaryFile);
                    throw new IOException("injected atomic move failure");
                }
        );

        assertThrows(IOException.class, () -> failingStore.replaceInstallationProofs(
                Set.of(CERTIFIED_ID),
                Map.of(CERTIFIED_ID, PluginInstallationTrustProof.certified(
                        certificationReceipt(CERTIFIED_ID, "1.0.0")
                ))
        ));

        Path temporaryFile = Objects.requireNonNull(createdTemporaryFile.get());
        assertArrayEquals(original, Files.readAllBytes(receiptFile(temporaryDirectory)));
        assertFalse(Files.exists(temporaryFile, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals(Set.of(OFFICIAL_ID), seeded.readAllProofs().keySet());
    }

    /// Leaves a predictable pre-existing temporary symlink and its target untouched where symlinks are supported.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if fixture persistence fails
    @Test
    public void predictableTemporarySymlinkIsNeverFollowed(@TempDir Path temporaryDirectory) throws Exception {
        PluginCertificationReceiptStore store = seededStore(temporaryDirectory);
        Path symlinkTarget = temporaryDirectory.resolve("symlink-target.txt");
        byte @Unmodifiable [] targetBytes = "symlink sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(symlinkTarget, targetBytes);
        Path predictableTemp = receiptFile(temporaryDirectory)
                .resolveSibling(PluginCertificationReceiptStore.FILE_NAME + ".tmp");
        try {
            Files.createSymbolicLink(predictableTemp, symlinkTarget.getFileName());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable: " + exception.getClass().getSimpleName());
            return;
        }

        store.replaceInstallationProofs(
                Set.of(CERTIFIED_ID),
                Map.of(CERTIFIED_ID, PluginInstallationTrustProof.certified(
                        certificationReceipt(CERTIFIED_ID, "1.0.0")
                ))
        );

        assertTrue(Files.isSymbolicLink(predictableTemp));
        assertArrayEquals(targetBytes, Files.readAllBytes(symlinkTarget));
    }

    /// Fails closed for unknown, inconsistent, duplicated, mistyped, trailing, non-UTF-8, or over-deep state.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if fixture persistence fails
    @Test
    public void rejectsMalformedSchemaTwoDocuments(@TempDir Path temporaryDirectory) throws Exception {
        String certified = certifiedPayload(certificationReceipt(CERTIFIED_ID, "1.0.0"));
        String official = officialPayload(officialReceipt(OFFICIAL_ID, "1.0.0"));
        String @Unmodifiable [] invalidDocuments = {
                "{\"schemaVersion\":2,\"schemaVersion\":2,\"proofs\":[]}",
                "{\"schemaVersion\":2,\"proofs\":[{\"kind\":\"UNKNOWN\",\"certified\":"
                        + certified + "}]}",
                "{\"schemaVersion\":2,\"proofs\":[{\"kind\":\"OFFICIAL\",\"certified\":"
                        + certified + "}]}",
                "{\"schemaVersion\":2,\"proofs\":[{\"kind\":\"CERTIFIED\",\"certified\":"
                        + certified + ",\"official\":" + official + "}]}",
                "{\"schemaVersion\":2,\"proofs\":[{\"kind\":7,\"certified\":" + certified + "}]}",
                "{\"schemaVersion\":2,\"proofs\":[{\"kind\":\"CERTIFIED\",\"certified\":"
                        + certified + "},{\"kind\":\"CERTIFIED\",\"certified\":" + certified + "}]}",
                "{\"schemaVersion\":2,\"proofs\":[],\"unexpected\":true}",
                "{\"schemaVersion\":2,\"proofs\":[]} true",
                nestedDocument(257)
        };

        for (String invalid : invalidDocuments) {
            Files.writeString(receiptFile(temporaryDirectory), invalid, StandardCharsets.UTF_8);
            assertThrows(IOException.class,
                    () -> new PluginCertificationReceiptStore(temporaryDirectory).readAllProofs(), invalid);
        }
        Files.write(receiptFile(temporaryDirectory), new byte[]{'{', '}', (byte) 0xc3, 0x28});
        assertThrows(IOException.class,
                () -> new PluginCertificationReceiptStore(temporaryDirectory).readAllProofs());
    }

    /// Bounds reads and writes while preserving prior bytes and avoiding a predictable temporary pathname.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if fixture persistence fails
    @Test
    public void boundsIoAndPreservesRecoverableStateAndPredictableTemp(@TempDir Path temporaryDirectory)
            throws Exception {
        PluginCertificationReceiptStore store = seededStore(temporaryDirectory);
        Path receiptFile = receiptFile(temporaryDirectory);
        Path predictableTemp = receiptFile.resolveSibling(receiptFile.getFileName() + ".tmp");
        Files.writeString(predictableTemp, "sentinel", StandardCharsets.UTF_8);
        store.replaceInstallationProofs(
                Set.of(CERTIFIED_ID),
                Map.of(CERTIFIED_ID, PluginInstallationTrustProof.certified(
                        certificationReceipt(CERTIFIED_ID, "1.0.0")
                ))
        );
        byte @Unmodifiable [] original = Files.readAllBytes(receiptFile);
        assertEquals("sentinel", Files.readString(predictableTemp));
        assertEquals(1L, countTemporaryFiles(temporaryDirectory));

        String largeObject = "{\"padding\":\"" + "x".repeat(2 * 1024 * 1024 - 32) + "\"}";
        PluginOfficialReceipt oversizedState = new PluginOfficialReceipt(
                largeObject.getBytes(StandardCharsets.UTF_8),
                largeObject.getBytes(StandardCharsets.UTF_8),
                "https://store.example/large.json",
                "github.com/example/large",
                "windows-x64",
                "https://store.example/large.npl",
                new PluginArtifactIdentity("dev.example.large", "1.0.0", SHA256),
                1L
        );
        assertThrows(IOException.class, () -> store.replaceInstallationProofs(
                Set.of("dev.example.large"),
                Map.of("dev.example.large", PluginInstallationTrustProof.official(oversizedState))
        ));
        assertArrayEquals(original, Files.readAllBytes(receiptFile));
        assertEquals("sentinel", Files.readString(predictableTemp));
        assertEquals(1L, countTemporaryFiles(temporaryDirectory));

        byte[] oversizedDocument = new byte[4 * 1024 * 1024 + 1];
        Arrays.fill(oversizedDocument, (byte) ' ');
        Files.write(receiptFile, oversizedDocument);
        assertThrows(IOException.class, store::readAllProofs);
    }

    /// Creates one store containing a deterministic official proof.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @return populated store
    /// @throws IOException if fixture persistence fails
    private static PluginCertificationReceiptStore seededStore(Path temporaryDirectory) throws IOException {
        PluginCertificationReceiptStore store = new PluginCertificationReceiptStore(temporaryDirectory);
        store.replaceInstallationProofs(
                Set.of(OFFICIAL_ID),
                Map.of(OFFICIAL_ID, PluginInstallationTrustProof.official(officialReceipt(OFFICIAL_ID, "1.0.0")))
        );
        return store;
    }

    /// Returns the stable receipt document path.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @return receipt path
    private static Path receiptFile(Path temporaryDirectory) {
        return temporaryDirectory.resolve(PluginCertificationReceiptStore.FILE_NAME);
    }

    /// Counts temporary receipt paths without leaking a directory stream on Windows.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @return number of temporary-named paths
    /// @throws IOException if the directory cannot be listed
    private static long countTemporaryFiles(Path temporaryDirectory) throws IOException {
        try (var paths = Files.list(temporaryDirectory)) {
            return paths.filter(path -> path.getFileName().toString().contains(".tmp")).count();
        }
    }

    /// Creates a complete structural official receipt with deliberately non-normalized JSON bytes.
    ///
    /// @param pluginId exact plugin ID
    /// @param version exact version
    /// @return official receipt
    private static PluginOfficialReceipt officialReceipt(String pluginId, String version) {
        byte @Unmodifiable [] registry = "{\n  \"signed\": {}, \"signatures\": []\n}"
                .getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] manifest = "{\n  \"schemaVersion\": 2, \"versions\": []\n}"
                .getBytes(StandardCharsets.UTF_8);
        return new PluginOfficialReceipt(
                registry,
                manifest,
                "https://store.example/" + pluginId + ".json",
                "github.com/example/" + pluginId.substring(pluginId.lastIndexOf('.') + 1),
                "windows-x64",
                "https://store.example/" + pluginId + ".npl",
                new PluginArtifactIdentity(pluginId, version, SHA256),
                42L
        );
    }

    /// Creates one complete structural certification receipt.
    ///
    /// @param pluginId exact plugin ID
    /// @param version exact package version
    /// @return certification receipt
    private static PluginCertificationReceipt certificationReceipt(String pluginId, String version) {
        return new PluginCertificationReceipt(
                pluginId,
                version,
                SHA256,
                42L,
                17L,
                "github.com/example/certified-ui",
                KEY_ID,
                KEY_ID,
                "verification-17",
                "{\"signed\":{},\"signatures\":[]}",
                "{\"signed\":{},\"signatures\":[]}"
        );
    }

    /// Creates a complete official receipt backed by a real Ed25519 registry signature and its original root.
    ///
    /// @return signed official fixture
    /// @throws Exception if key generation, hashing, or signing fails
    private static OfficialFixture signedOfficialFixture() throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte @Unmodifiable [] manifest = ("""
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "repository": "%s",
                  "versions": [{
                    "version": "2.0.0",
                    "pluginApiVersion": 5,
                    "permissions": [],
                    "requiredPermissions": [],
                    "launcherVersion": "*",
                    "runtime": "java",
                    "abi": 2,
                    "platforms": ["%s"],
                    "pluginKind": "normal",
                    "artifacts": [{
                      "platform": "%s",
                      "packageUrl": "%s",
                      "sha256": "%s",
                      "size": 42
                    }],
                    "dependencies": []
                  }]
                }
                """.formatted(
                OFFICIAL_ID, OFFICIAL_REPOSITORY, PLATFORM, PLATFORM, ARTIFACT_URL, SHA256
        )).getBytes(StandardCharsets.UTF_8);
        JsonObject registryEntry = new JsonObject();
        registryEntry.addProperty("id", OFFICIAL_ID);
        registryEntry.addProperty("manifestUrl", MANIFEST_URL);
        registryEntry.addProperty("manifestSha256", sha256(manifest));
        registryEntry.addProperty("repository", OFFICIAL_REPOSITORY);
        JsonArray plugins = new JsonArray();
        plugins.add(registryEntry);
        JsonObject signedRegistry = new JsonObject();
        signedRegistry.addProperty("schemaVersion", 1);
        signedRegistry.addProperty("name", "Aura Launcher Plugin Store");
        signedRegistry.add("plugins", plugins);
        byte @Unmodifiable [] registryEnvelope =
                envelope(signedRegistry, signer).toString().getBytes(StandardCharsets.UTF_8);
        PluginTrustVerifier verifier = PluginTrustVerifier.fromRoot(
                officialRoot(signer),
                Clock.fixed(Instant.parse("2026-08-14T08:00:00Z"), ZoneOffset.UTC),
                Set.of(),
                Set.of()
        );
        PluginOfficialReceipt receipt = new PluginOfficialReceipt(
                registryEnvelope,
                manifest,
                MANIFEST_URL,
                OFFICIAL_REPOSITORY,
                PLATFORM,
                ARTIFACT_URL,
                new PluginArtifactIdentity(OFFICIAL_ID, "2.0.0", SHA256),
                42L
        );
        return new OfficialFixture(verifier, receipt);
    }

    /// Creates a root containing only the generated official-repository signing capability.
    ///
    /// @param signer official registry signer
    /// @return root envelope accepted by the production verifier
    /// @throws Exception if the key identifier cannot be hashed
    private static JsonObject officialRoot(KeyPair signer) throws Exception {
        String signerKeyId = keyId(signer);
        JsonObject key = new JsonObject();
        key.addProperty("keyType", "ed25519");
        key.addProperty("scheme", "ed25519");
        key.addProperty("publicKey", Base64.getEncoder().encodeToString(signer.getPublic().getEncoded()));
        JsonObject keys = new JsonObject();
        keys.add(signerKeyId, key);
        JsonArray keyIds = new JsonArray();
        keyIds.add(signerKeyId);
        JsonObject role = new JsonObject();
        role.add("keyIds", keyIds);
        role.addProperty("threshold", 1);
        JsonObject roles = new JsonObject();
        roles.add("official-repository", role);
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "root");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("version", 1);
        signed.addProperty("expires", "2036-01-01T00:00:00Z");
        signed.addProperty("statusUrl", "");
        signed.add("keys", keys);
        signed.add("roles", roles);
        JsonObject root = new JsonObject();
        root.add("signed", signed);
        root.add("signatures", new JsonArray());
        return root;
    }

    /// Signs one official registry payload using the production canonical signature input.
    ///
    /// @param signed signed registry object
    /// @param signer official registry signer
    /// @return signed envelope
    /// @throws Exception if signing fails
    private static JsonObject envelope(JsonObject signed, KeyPair signer) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(CanonicalJson.signatureInput(PluginTrustVerifier.OFFICIAL_REGISTRY_DOMAIN, signed));
        JsonObject signatureEntry = new JsonObject();
        signatureEntry.addProperty("keyId", keyId(signer));
        signatureEntry.addProperty("signature", Base64.getEncoder().encodeToString(signature.sign()));
        JsonArray signatures = new JsonArray();
        signatures.add(signatureEntry);
        JsonObject envelope = new JsonObject();
        envelope.add("signed", signed);
        envelope.add("signatures", signatures);
        return envelope;
    }

    /// Computes the trust-root key identifier for one generated signer.
    ///
    /// @param signer generated signing pair
    /// @return canonical Ed25519 key ID
    /// @throws Exception if SHA-256 is unavailable
    private static String keyId(KeyPair signer) throws Exception {
        return "ed25519:" + sha256(signer.getPublic().getEncoded());
    }

    /// Computes a lower-case SHA-256 digest independently of receipt persistence.
    ///
    /// @param value exact bytes
    /// @return hexadecimal digest
    /// @throws Exception if SHA-256 is unavailable
    private static String sha256(byte @Unmodifiable [] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    /// Serializes one exact schema-1 receipt fixture independently of store code.
    ///
    /// @param receipt certification receipt
    /// @return legacy JSON document
    private static String schemaOne(PluginCertificationReceipt receipt) {
        return """
                {
                  "schemaVersion": 1,
                  "receipts": [%s]
                }
                """.formatted(certifiedPayload(receipt));
    }

    /// Serializes one certified payload independently of store code.
    ///
    /// @param receipt certification receipt
    /// @return compact payload JSON
    private static String certifiedPayload(PluginCertificationReceipt receipt) {
        return """
                {"pluginId":"%s","version":"%s","sha256":"%s","size":%d,"repositoryId":%d,
                "repository":"%s","repositorySignerKeyId":"%s","artifactSignerKeyId":"%s",
                "repositoryVerificationId":"%s","artifactAttestationJson":"%s",
                "repositoryAttestationJson":"%s"}
                """.formatted(
                receipt.pluginId(), receipt.version(), receipt.sha256(), receipt.size(), receipt.repositoryId(),
                receipt.repository(), receipt.repositorySignerKeyId(), receipt.artifactSignerKeyId(),
                receipt.repositoryVerificationId(), escape(receipt.artifactAttestationJson()),
                escape(receipt.repositoryAttestationJson())
        );
    }

    /// Serializes one official payload sufficient for malformed tag tests.
    ///
    /// @param receipt official receipt
    /// @return compact payload JSON
    private static String officialPayload(PluginOfficialReceipt receipt) {
        PluginArtifactIdentity identity = receipt.getArtifactIdentity();
        return """
                {"officialRegistryEnvelopeBase64":"e30=","storeManifestBase64":"e30=",
                "manifestUrl":"%s","repository":"%s","selectedPlatform":"%s","artifactUrl":"%s",
                "pluginId":"%s","version":"%s","sha256":"%s","artifactSize":%d}
                """.formatted(
                receipt.getManifestUrl(), receipt.getRepository(), receipt.getSelectedPlatform(),
                receipt.getArtifactUrl(), identity.getPluginId(), identity.getVersion(), identity.getSha256(),
                receipt.getArtifactSize()
        );
    }

    /// Escapes JSON envelope text for embedding in one independently constructed fixture.
    ///
    /// @param value raw envelope JSON
    /// @return JSON string content
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /// Creates an otherwise unsupported document at the requested container depth.
    ///
    /// @param depth total nested object depth
    /// @return JSON text
    private static String nestedDocument(int depth) {
        return "{\"x\":".repeat(depth) + "null" + "}".repeat(depth);
    }

    /// Caller-owned map whose key view is a stable snapshot while entry reads use the concurrent backing map.
    ///
    /// @param <V> proof or receipt value type
    @NotNullByDefault
    private static final class SnapshotKeyMap<V> extends AbstractMap<String, V> {
        /// Concurrent caller-owned state mutated while the store call is in progress.
        private final ConcurrentHashMap<String, V> backing;

        /// Creates one deterministic concurrent caller view.
        ///
        /// @param backing concurrent caller-owned state
        private SnapshotKeyMap(ConcurrentHashMap<String, V> backing) {
            this.backing = backing;
        }

        /// Returns the live entry view used by map copying and merging.
        ///
        /// @return live concurrent entries
        @Override
        public Set<Entry<String, V>> entrySet() {
            return backing.entrySet();
        }

        /// Returns keys captured before the paired batch set triggers its mutation.
        ///
        /// @return immutable key snapshot
        @Override
        public @Unmodifiable Set<String> keySet() {
            return Set.copyOf(backing.keySet());
        }
    }

    /// Batch set that runs one deterministic caller mutation when store code first iterates the set.
    @NotNullByDefault
    private static final class MutatingIterationSet extends AbstractSet<String> {
        /// Immutable declared replacement batch.
        private final @Unmodifiable Set<String> values;

        /// Caller mutation triggered between map capture and later validation/use.
        private final Runnable mutation;

        /// Whether the one-shot mutation has already run.
        private boolean mutated;

        /// Creates one deterministic mutation boundary.
        ///
        /// @param values immutable declared batch
        /// @param mutation caller-side collection mutation
        private MutatingIterationSet(@Unmodifiable Set<String> values, Runnable mutation) {
            this.values = values;
            this.mutation = mutation;
        }

        /// Triggers the caller mutation once, then iterates only the declared batch snapshot.
        ///
        /// @return iterator over the original batch
        @Override
        public Iterator<String> iterator() {
            if (!mutated) {
                mutated = true;
                mutation.run();
            }
            return values.iterator();
        }

        /// Returns the number of declared batch IDs.
        ///
        /// @return declared batch size
        @Override
        public int size() {
            return values.size();
        }
    }

    /// Real signed official receipt paired with the root that must revalidate it after reload.
    ///
    /// @param verifier original official-only root verifier
    /// @param receipt exact signed official receipt
    @NotNullByDefault
    private record OfficialFixture(PluginTrustVerifier verifier, PluginOfficialReceipt receipt) {
    }
}
