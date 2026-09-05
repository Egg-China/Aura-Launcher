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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that official installation receipts remain exact, bounded, immutable, and independently authenticatable.
@NotNullByDefault
public final class PluginOfficialReceiptTest {
    /// Stable plugin ID used by every exact-artifact fixture.
    private static final String PLUGIN_ID = "dev.example.official-ui";

    /// Stable package version used by every exact-artifact fixture.
    private static final String VERSION = "1.2.3";

    /// Stable complete package SHA-256 used by every exact-artifact fixture.
    private static final String SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    /// Stable complete package size used by every exact-artifact fixture.
    private static final long SIZE = 42L;

    /// Exact selected desktop platform.
    private static final String PLATFORM = "windows-x64";

    /// Exact selected release URL.
    private static final String ARTIFACT_URL =
            "https://github.com/example/plugin/releases/download/v1.2.3/plugin-windows-x64.npl";

    /// Signed registry coordinate for the Store manifest.
    private static final String MANIFEST_URL = "https://store.example/plugins/official-ui.json";

    /// Signed source repository coordinate.
    private static final String REPOSITORY = "github.com/example/plugin";

    /// Deterministic verifier clock; official registry signatures do not require any other role.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T08:00:00Z"), ZoneOffset.UTC);

    /// Real official-registry signing key generated for each test.
    private KeyPair officialSigner;

    /// Root verifier containing only the official-repository role.
    private PluginTrustVerifier officialOnlyVerifier;

    /// Exact UTF-8 Store manifest fixture bytes.
    private byte[] manifestUtf8;

    /// Exact UTF-8 signed official-registry envelope fixture bytes.
    private byte[] registryEnvelopeUtf8;

    /// Creates fresh real signed fixtures so tests never rely on cached trust state.
    @BeforeEach
    public void setUp() throws Exception {
        officialSigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        officialOnlyVerifier = PluginTrustVerifier.fromRoot(
                officialOnlyRoot(officialSigner),
                CLOCK,
                Set.of(),
                Set.of()
        );
        manifestUtf8 = manifest(VERSION, PLATFORM, ARTIFACT_URL, SHA256, SIZE);
        registryEnvelopeUtf8 = registryEnvelope(manifestUtf8, false);
    }

    /// Accepts an exact artifact using a trust root that carries no role except official-repository.
    @Test
    public void verifiesExactArtifactWithOfficialOnlyRoot() {
        PluginOfficialReceipt receipt = receipt(
                registryEnvelopeUtf8,
                manifestUtf8,
                MANIFEST_URL,
                REPOSITORY,
                PLATFORM,
                ARTIFACT_URL,
                VERSION,
                SHA256,
                SIZE
        );

        assertDoesNotThrow(() -> receipt.verify(
                officialOnlyVerifier,
                new PluginArtifactIdentity(PLUGIN_ID, VERSION, SHA256),
                SIZE
        ));
    }

    /// Prevents callers from mutating either retained byte sequence through constructor inputs or accessors.
    @Test
    public void retainsDefensiveCopiesOfProofBytes() {
        byte[] suppliedRegistry = registryEnvelopeUtf8.clone();
        byte[] suppliedManifest = manifestUtf8.clone();
        PluginOfficialReceipt receipt = receipt(
                suppliedRegistry,
                suppliedManifest,
                MANIFEST_URL,
                REPOSITORY,
                PLATFORM,
                ARTIFACT_URL,
                VERSION,
                SHA256,
                SIZE
        );
        byte[] exposedRegistry = receipt.getOfficialRegistryEnvelopeUtf8();
        byte[] exposedManifest = receipt.getStoreManifestUtf8();

        suppliedRegistry[0] ^= 1;
        suppliedManifest[0] ^= 1;
        exposedRegistry[0] ^= 1;
        exposedManifest[0] ^= 1;

        assertNotSame(exposedRegistry, receipt.getOfficialRegistryEnvelopeUtf8());
        assertNotSame(exposedManifest, receipt.getStoreManifestUtf8());
        assertArrayEquals(registryEnvelopeUtf8, receipt.getOfficialRegistryEnvelopeUtf8());
        assertArrayEquals(manifestUtf8, receipt.getStoreManifestUtf8());
        assertDoesNotThrow(() -> receipt.verify(
                officialOnlyVerifier,
                new PluginArtifactIdentity(PLUGIN_ID, VERSION, SHA256),
                SIZE
        ));
    }

    /// Rejects empty, oversized, or malformed UTF-8 proof documents before retaining them.
    @Test
    public void rejectsUnboundedOrInvalidUtf8ProofDocuments() {
        byte[] oversizedRegistry = new byte[2 * 1024 * 1024 + 1];
        byte[] oversizedManifest = new byte[4 * 1024 * 1024 + 1];
        byte[] malformedUtf8 = {(byte) 0xc3, 0x28};

        assertThrows(IllegalArgumentException.class, () -> receipt(
                oversizedRegistry, manifestUtf8, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
        assertThrows(IllegalArgumentException.class, () -> receipt(
                registryEnvelopeUtf8, oversizedManifest, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
        assertThrows(IllegalArgumentException.class, () -> receipt(
                malformedUtf8, manifestUtf8, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
        assertThrows(IllegalArgumentException.class, () -> receipt(
                registryEnvelopeUtf8, malformedUtf8, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
        assertThrows(IllegalArgumentException.class, () -> receipt(
                new byte[0], manifestUtf8, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
    }

    /// Rejects a leading UTF-8 byte-order mark instead of accepting an alternate byte representation of JSON.
    @Test
    public void rejectsByteOrderMarkedUtf8() {
        byte[] byteOrderMarkedObject = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'};

        assertThrows(IllegalArgumentException.class, () -> receipt(
                byteOrderMarkedObject, manifestUtf8, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
    }

    /// Rejects an artifact size above the Store downloader's exact package ceiling.
    @Test
    public void rejectsArtifactAboveStorePackageLimit() {
        assertThrows(IllegalArgumentException.class, () -> receipt(
                registryEnvelopeUtf8, manifestUtf8, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, 512L * 1024L * 1024L + 1L));
    }

    /// Rejects a second trailing JSON value in either retained raw proof document.
    @Test
    public void rejectsTrailingJsonDataInEitherProofDocument() {
        byte[] trailingRegistry = (new String(registryEnvelopeUtf8, StandardCharsets.UTF_8) + "{}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] trailingManifest = (new String(manifestUtf8, StandardCharsets.UTF_8) + "{}")
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> receipt(
                trailingRegistry, manifestUtf8, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
        assertThrows(IllegalArgumentException.class, () -> receipt(
                registryEnvelopeUtf8, trailingManifest, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
    }

    /// Accepts both raw proof documents immediately below Gson's 256-container rejection boundary.
    @Test
    public void acceptsJsonBelowMaximumNestingBoundary() {
        byte[] acceptedDepth = nestedJsonDocument(255);

        assertDoesNotThrow(() -> receipt(
                acceptedDepth, acceptedDepth, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
    }

    /// Rejects either raw proof document at the 256-container boundary and beyond it.
    @Test
    public void rejectsJsonAtOrBeyondMaximumNestingBoundary() {
        byte[] boundaryDepth = nestedJsonDocument(256);
        byte[] excessiveDepth = nestedJsonDocument(257);

        assertThrows(IllegalArgumentException.class, () -> receipt(
                boundaryDepth, manifestUtf8, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
        assertThrows(IllegalArgumentException.class, () -> receipt(
                registryEnvelopeUtf8, boundaryDepth, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
        assertThrows(IllegalArgumentException.class, () -> receipt(
                excessiveDepth, manifestUtf8, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
        assertThrows(IllegalArgumentException.class, () -> receipt(
                registryEnvelopeUtf8, excessiveDepth, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE));
    }

    /// Rejects a duplicate field at the retained envelope root before Gson can keep only its last value.
    @Test
    public void rejectsDuplicateOfficialEnvelopeField() {
        String envelope = new String(registryEnvelopeUtf8, StandardCharsets.UTF_8);
        byte[] duplicate = envelope.replace(
                "\"signatures\":",
                "\"signatures\":[],\"signatures\":"
        ).getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> receipt(
                duplicate, manifestUtf8, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE
        ));
    }

    /// Rejects duplicate fields recursively inside a signed registry entry even when the kept value is valid.
    @Test
    public void rejectsDuplicateSignedRegistryEntryField() throws Exception {
        String signed = registryPayload(manifestUtf8, false).toString();
        String duplicateSigned = signed.replace(
                "\"repository\":\"" + REPOSITORY + "\"",
                "\"repository\":\"github.com/attacker/plugin\",\"repository\":\"" + REPOSITORY + "\""
        );
        byte[] duplicate = rawSignedEnvelope(duplicateSigned);

        assertThrows(IllegalArgumentException.class, () -> receipt(
                duplicate, manifestUtf8, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE
        ));
    }

    /// Rejects duplicate fields recursively inside a pinned manifest artifact before Store parsing collapses them.
    @Test
    public void rejectsDuplicateNestedManifestArtifactField() throws Exception {
        String manifest = new String(manifestUtf8, StandardCharsets.UTF_8);
        byte[] duplicateManifest = manifest.replace(
                "\"size\": 42",
                "\"size\": 41,\n                  \"size\": 42"
        ).getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> receipt(
                registryEnvelope(duplicateManifest, false),
                duplicateManifest,
                MANIFEST_URL,
                REPOSITORY,
                PLATFORM,
                ARTIFACT_URL,
                VERSION,
                SHA256,
                SIZE
        ));
    }

    /// Re-verifies the retained official signature instead of trusting a prior badge or decision.
    @Test
    public void rejectsMutatedOfficialRegistrySignature() {
        JsonObject mutated = JsonParser.parseString(new String(
                registryEnvelopeUtf8,
                StandardCharsets.UTF_8
        )).getAsJsonObject();
        mutated.getAsJsonObject("signed").addProperty("name", "Attacker Store");
        PluginOfficialReceipt receipt = receipt(
                utf8(mutated), manifestUtf8, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE
        );

        assertThrows(IllegalArgumentException.class, () -> receipt.verify(
                officialOnlyVerifier,
                new PluginArtifactIdentity(PLUGIN_ID, VERSION, SHA256),
                SIZE
        ));
    }

    /// Hashes the exact retained manifest bytes and rejects even semantically harmless byte changes.
    @Test
    public void rejectsChangedManifestBytes() {
        byte[] changedManifest = (new String(manifestUtf8, StandardCharsets.UTF_8) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        PluginOfficialReceipt receipt = receipt(
                registryEnvelopeUtf8, changedManifest, MANIFEST_URL, REPOSITORY, PLATFORM,
                ARTIFACT_URL, VERSION, SHA256, SIZE
        );

        assertThrows(IllegalArgumentException.class, () -> receipt.verify(
                officialOnlyVerifier,
                new PluginArtifactIdentity(PLUGIN_ID, VERSION, SHA256),
                SIZE
        ));
    }

    /// Rejects every stored coordinate when it differs from the newly verified registry, manifest, or installed NPL.
    @Test
    public void rejectsWrongStoredOrInstalledArtifactCoordinates() {
        PluginArtifactIdentity installed = new PluginArtifactIdentity(PLUGIN_ID, VERSION, SHA256);

        assertReceiptRejected(receipt(registryEnvelopeUtf8, manifestUtf8,
                MANIFEST_URL + "?mirror=1", REPOSITORY, PLATFORM, ARTIFACT_URL, VERSION, SHA256, SIZE),
                installed, SIZE);
        assertReceiptRejected(receipt(registryEnvelopeUtf8, manifestUtf8,
                MANIFEST_URL, "github.com/example/other", PLATFORM, ARTIFACT_URL, VERSION, SHA256, SIZE),
                installed, SIZE);
        assertReceiptRejected(receipt(registryEnvelopeUtf8, manifestUtf8,
                MANIFEST_URL, REPOSITORY, "linux-x64", ARTIFACT_URL, VERSION, SHA256, SIZE),
                installed, SIZE);
        assertReceiptRejected(receipt(registryEnvelopeUtf8, manifestUtf8,
                MANIFEST_URL, REPOSITORY, PLATFORM, ARTIFACT_URL + ".bak", VERSION, SHA256, SIZE),
                installed, SIZE);
        assertReceiptRejected(receipt(registryEnvelopeUtf8, manifestUtf8,
                MANIFEST_URL, REPOSITORY, PLATFORM, ARTIFACT_URL, "9.9.9", SHA256, SIZE),
                installed, SIZE);
        assertReceiptRejected(receipt(registryEnvelopeUtf8, manifestUtf8,
                MANIFEST_URL, REPOSITORY, PLATFORM, ARTIFACT_URL, VERSION,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", SIZE),
                installed, SIZE);
        assertReceiptRejected(receipt(registryEnvelopeUtf8, manifestUtf8,
                MANIFEST_URL, REPOSITORY, PLATFORM, ARTIFACT_URL, VERSION, SHA256, SIZE + 1),
                installed, SIZE);
        assertReceiptRejected(receipt(registryEnvelopeUtf8, manifestUtf8,
                MANIFEST_URL, REPOSITORY, PLATFORM, ARTIFACT_URL, VERSION, SHA256, SIZE),
                new PluginArtifactIdentity(PLUGIN_ID, "9.9.9", SHA256), SIZE);
        assertReceiptRejected(receipt(registryEnvelopeUtf8, manifestUtf8,
                MANIFEST_URL, REPOSITORY, PLATFORM, ARTIFACT_URL, VERSION, SHA256, SIZE),
                installed, SIZE + 1);
    }

    /// Rejects a missing registry entry and duplicate registry entries even when the enclosing signature is valid.
    @Test
    public void rejectsMissingOrDuplicateRegistryEntry() throws Exception {
        JsonObject missingSigned = registryPayload(manifestUtf8, false);
        missingSigned.getAsJsonArray("plugins").remove(0);
        byte[] missing = utf8(envelope(missingSigned, officialSigner));
        byte[] duplicate = registryEnvelope(manifestUtf8, true);

        assertReceiptRejected(receipt(missing, manifestUtf8, MANIFEST_URL, REPOSITORY,
                PLATFORM, ARTIFACT_URL, VERSION, SHA256, SIZE));
        assertReceiptRejected(receipt(duplicate, manifestUtf8, MANIFEST_URL, REPOSITORY,
                PLATFORM, ARTIFACT_URL, VERSION, SHA256, SIZE));
    }

    /// Rejects duplicate selected versions and duplicate selected-platform artifacts in otherwise pinned manifests.
    @Test
    public void rejectsAmbiguousManifestSelection() throws Exception {
        byte[] duplicateVersionManifest = manifestWithVersions("""
                %s,
                %s
                """.formatted(version(PLATFORM), version(PLATFORM)));
        byte[] duplicatePlatformManifest = manifestWithVersions(versionWithArtifacts("""
                %s,
                %s
                """.formatted(artifact(PLATFORM), artifact(PLATFORM))));

        assertReceiptRejected(receipt(
                registryEnvelope(duplicateVersionManifest, false),
                duplicateVersionManifest,
                MANIFEST_URL,
                REPOSITORY,
                PLATFORM,
                ARTIFACT_URL,
                VERSION,
                SHA256,
                SIZE
        ));
        assertReceiptRejected(receipt(
                registryEnvelope(duplicatePlatformManifest, false),
                duplicatePlatformManifest,
                MANIFEST_URL,
                REPOSITORY,
                PLATFORM,
                ARTIFACT_URL,
                VERSION,
                SHA256,
                SIZE
        ));
    }

    /// Compares manifest artifact URL, digest, and size independently against the stored exact artifact fields.
    @Test
    public void rejectsResignedRegistryForChangedManifestArtifactFields() throws Exception {
        byte[] changedUrl = manifest(VERSION, PLATFORM, ARTIFACT_URL + ".bak", SHA256, SIZE);
        byte[] changedHash = manifest(
                VERSION,
                PLATFORM,
                ARTIFACT_URL,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                SIZE
        );
        byte[] changedSize = manifest(VERSION, PLATFORM, ARTIFACT_URL, SHA256, SIZE + 1);

        assertReceiptRejected(receipt(registryEnvelope(changedUrl, false), changedUrl,
                MANIFEST_URL, REPOSITORY, PLATFORM, ARTIFACT_URL, VERSION, SHA256, SIZE));
        assertReceiptRejected(receipt(registryEnvelope(changedHash, false), changedHash,
                MANIFEST_URL, REPOSITORY, PLATFORM, ARTIFACT_URL, VERSION, SHA256, SIZE));
        assertReceiptRejected(receipt(registryEnvelope(changedSize, false), changedSize,
                MANIFEST_URL, REPOSITORY, PLATFORM, ARTIFACT_URL, VERSION, SHA256, SIZE));
    }

    /// Creates one receipt with explicit retained and derived fields.
    private static PluginOfficialReceipt receipt(
            byte[] registryUtf8,
            byte[] manifestUtf8,
            String manifestUrl,
            String repository,
            String platform,
            String artifactUrl,
            String version,
            String sha256,
            long size
    ) {
        return new PluginOfficialReceipt(
                registryUtf8,
                manifestUtf8,
                manifestUrl,
                repository,
                platform,
                artifactUrl,
                new PluginArtifactIdentity(PLUGIN_ID, version, sha256),
                size
        );
    }

    /// Asserts that one proof cannot authorize the default exact installed artifact.
    private void assertReceiptRejected(PluginOfficialReceipt receipt) {
        assertReceiptRejected(receipt, new PluginArtifactIdentity(PLUGIN_ID, VERSION, SHA256), SIZE);
    }

    /// Asserts that one proof cannot authorize the supplied installed artifact.
    private void assertReceiptRejected(
            PluginOfficialReceipt receipt,
            PluginArtifactIdentity installedIdentity,
            long installedSize
    ) {
        assertThrows(IllegalArgumentException.class,
                () -> receipt.verify(officialOnlyVerifier, installedIdentity, installedSize));
    }

    /// Creates one valid API-v5 Store manifest with one exact platform artifact.
    private static byte[] manifest(String version, String platform, String url, String sha256, long size) {
        return manifestWithVersions(version(platform, url, sha256, size));
    }

    /// Creates one complete Store manifest from serialized version entries.
    private static byte[] manifestWithVersions(String versions) {
        return ("""
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "repository": "%s",
                  "versions": [%s]
                }
                """.formatted(PLUGIN_ID, REPOSITORY, versions)).getBytes(StandardCharsets.UTF_8);
    }

    /// Creates one valid version entry for the stable selected artifact.
    private static String version(String platform) {
        return version(platform, ARTIFACT_URL, SHA256, SIZE);
    }

    /// Creates one API-v5 version entry with one exact platform artifact.
    private static String version(String platform, String url, String sha256, long size) {
        return versionWithArtifacts(artifact(platform, url, sha256, size));
    }

    /// Creates one API-v5 version entry around serialized artifact entries.
    private static String versionWithArtifacts(String artifacts) {
        return """
                {
                  "version": "%s",
                  "pluginApiVersion": 5,
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "platforms": ["%s"],
                  "pluginKind": "normal",
                  "artifacts": [%s],
                  "dependencies": []
                }
                """.formatted(VERSION, PLATFORM, artifacts);
    }

    /// Creates one serialized platform artifact using stable fields.
    private static String artifact(String platform) {
        return artifact(platform, ARTIFACT_URL, SHA256, SIZE);
    }

    /// Creates one serialized exact platform artifact.
    private static String artifact(String platform, String url, String sha256, long size) {
        return """
                {
                  "platform": "%s",
                  "packageUrl": "%s",
                  "sha256": "%s",
                  "size": %d
                }
                """.formatted(platform, url, sha256, size);
    }

    /// Creates and signs one official registry, optionally repeating its plugin entry.
    private byte[] registryEnvelope(byte[] exactManifestUtf8, boolean duplicateEntry) throws Exception {
        return utf8(envelope(registryPayload(exactManifestUtf8, duplicateEntry), officialSigner));
    }

    /// Signs the last-value Gson interpretation while retaining a raw signed object containing duplicate fields.
    private byte[] rawSignedEnvelope(String rawSigned) throws Exception {
        JsonObject collapsedSigned = JsonParser.parseString(rawSigned).getAsJsonObject();
        JsonObject signedEnvelope = envelope(collapsedSigned, officialSigner);
        return ("{\"signed\":" + rawSigned
                + ",\"signatures\":" + signedEnvelope.get("signatures") + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /// Creates the signed registry payload with an exact manifest-byte digest.
    private static JsonObject registryPayload(byte[] exactManifestUtf8, boolean duplicateEntry) throws Exception {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", PLUGIN_ID);
        entry.addProperty("manifestUrl", MANIFEST_URL);
        entry.addProperty("manifestSha256", sha256(exactManifestUtf8));
        entry.addProperty("repository", REPOSITORY);
        JsonArray plugins = new JsonArray();
        plugins.add(entry);
        if (duplicateEntry) {
            plugins.add(entry.deepCopy());
        }
        JsonObject signed = new JsonObject();
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("name", "Aura Launcher Plugin Store");
        signed.add("plugins", plugins);
        return signed;
    }

    /// Creates root metadata whose sole signing capability is official-repository.
    private static JsonObject officialOnlyRoot(KeyPair signer) throws Exception {
        String signerKeyId = keyId(signer);
        JsonObject key = new JsonObject();
        key.addProperty("keyType", "ed25519");
        key.addProperty("scheme", "ed25519");
        key.addProperty("publicKey", Base64.getEncoder().encodeToString(signer.getPublic().getEncoded()));
        JsonObject keys = new JsonObject();
        keys.add(signerKeyId, key);
        JsonArray roleKeyIds = new JsonArray();
        roleKeyIds.add(signerKeyId);
        JsonObject role = new JsonObject();
        role.add("keyIds", roleKeyIds);
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

    /// Creates a real Ed25519 envelope over canonical official-registry signature input.
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
    private static String keyId(KeyPair signer) throws Exception {
        return "ed25519:" + sha256(signer.getPublic().getEncoded());
    }

    /// Computes one lower-case SHA-256 hexadecimal digest independently of receipt code.
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /// Serializes a fixture JSON object to UTF-8 bytes.
    private static byte[] utf8(JsonObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    /// Creates one object whose root and nested arrays have the requested total container depth.
    ///
    /// @param containerDepth total object-plus-array container count
    /// @return strict UTF-8 JSON document
    private static byte[] nestedJsonDocument(int containerDepth) {
        if (containerDepth < 1) {
            throw new IllegalArgumentException("Fixture depth must be positive");
        }
        StringBuilder json = new StringBuilder(containerDepth * 2 + 16);
        json.append("{\"value\":");
        for (int depth = 1; depth < containerDepth; depth++) {
            json.append('[');
        }
        json.append("null");
        for (int depth = 1; depth < containerDepth; depth++) {
            json.append(']');
        }
        json.append('}');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
}
