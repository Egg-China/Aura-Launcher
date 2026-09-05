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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.trust.CanonicalJson;
import org.jackhuang.hmcl.plugin.trust.PluginInstallationTrustProof;
import org.jackhuang.hmcl.plugin.trust.PluginOfficialReceipt;
import org.jackhuang.hmcl.plugin.trust.PluginTrustResult;
import org.jackhuang.hmcl.plugin.trust.PluginTrustVerifier;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that proof-aware Store downloads bind staged package bytes to one captured source generation.
@NotNullByDefault
public final class PluginVerifiedDownloadTest {
    /// Rejects a version retained from a replaced source generation before requesting either package artifact.
    @Test
    public void rejectsStaleCrossSourceVersionBeforeDownload(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.aura.test.stale-download";
        String versionText = "1.0.0";
        byte @Unmodifiable [] firstPackage = createPluginPackage(pluginId, versionText, "[]");
        byte @Unmodifiable [] secondPackage = createPluginPackage(pluginId, "2.0.0", "[]");
        AtomicInteger packageRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String firstManifestUrl = baseUrl + "/first-manifest.json";
        String secondManifestUrl = baseUrl + "/second-manifest.json";
        byte @Unmodifiable [] firstManifestBytes = communityManifest(
                pluginId,
                versionText,
                baseUrl + "/first.npl",
                firstPackage
        );
        byte @Unmodifiable [] secondManifestBytes = communityManifest(
                pluginId,
                "2.0.0",
                baseUrl + "/second.npl",
                secondPackage
        );
        server.createContext("/first-registry.json", exchange -> respond(exchange, registry(
                pluginId,
                firstManifestUrl
        )));
        server.createContext("/second-registry.json", exchange -> respond(exchange, registry(
                pluginId,
                secondManifestUrl
        )));
        server.createContext("/first-manifest.json", exchange -> respond(exchange, firstManifestBytes));
        server.createContext("/second-manifest.json", exchange -> respond(exchange, secondManifestBytes));
        server.createContext("/first.npl", exchange -> {
            packageRequests.incrementAndGet();
            respond(exchange, firstPackage);
        });
        server.createContext("/second.npl", exchange -> {
            packageRequests.incrementAndGet();
            respond(exchange, secondPackage);
        });
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(new PluginSource(
                    "first",
                    baseUrl + "/first-registry.json",
                    null,
                    true,
                    false
            ));
            PluginStoreManifest firstManifest = manager.getPluginManifest(pluginId, firstManifestUrl);
            PluginStoreManifest.PluginVersionEntry staleVersion = Objects.requireNonNull(
                    firstManifest.getVersion(versionText)
            );
            manager.loadSource(new PluginSource(
                    "second",
                    baseUrl + "/second-registry.json",
                    null,
                    true,
                    false
            ));
            manager.getPluginManifest(pluginId, secondManifestUrl);

            assertThrows(IOException.class, () -> manager.downloadPluginToStagingWithProof(
                    pluginId,
                    staleVersion,
                    temporaryDirectory.resolve("staging")
            ));
            assertEquals(0, packageRequests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Rejects malformed UTF-8 registry and manifest bytes instead of parsing replacement characters.
    @Test
    public void rejectsInvalidUtf8ProofBearingDocuments() throws Exception {
        String pluginId = "dev.aura.test.invalid-utf8";
        byte @Unmodifiable [] malformedUtf8 = {(byte) 0xc3, 0x28};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String manifestUrl = baseUrl + "/manifest.json";
        server.createContext("/bad-registry.json", exchange -> respond(exchange, malformedUtf8));
        server.createContext("/registry.json", exchange -> respond(exchange, registry(pluginId, manifestUrl)));
        server.createContext("/manifest.json", exchange -> respond(exchange, malformedUtf8));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            assertThrows(IOException.class, () -> manager.loadSource(new PluginSource(
                    "bad-registry",
                    baseUrl + "/bad-registry.json",
                    null,
                    true,
                    false
            )));
            manager.loadSource(new PluginSource(
                    "bad-manifest",
                    baseUrl + "/registry.json",
                    null,
                    true,
                    false
            ));
            assertThrows(IOException.class, () -> manager.getPluginManifest(pluginId, manifestUrl));
        } finally {
            server.stop(0);
        }
    }

    /// Keeps a complete official receipt on its captured generation through a concurrent source and badge mutation.
    @Test
    public void returnsExactReverifiedOfficialProof(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.aura.test.official-download";
        String versionText = "2.0.0";
        String repository = "github.com/aura/official-download";
        String platform = PluginPlatformTarget.current().getId();
        byte @Unmodifiable [] packageBytes = createPluginPackage(
                pluginId,
                versionText,
                "[\"" + platform + "\"]"
        );
        String packageSha256 = sha256(packageBytes);
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PluginTrustVerifier verifier = PluginTrustVerifier.fromRoot(
                officialOnlyRoot(signer),
                Clock.systemUTC(),
                Set.of(),
                Set.of()
        );
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String packageUrl = baseUrl + "/official.npl";
        String manifestUrl = baseUrl + "/official-manifest.json";
        byte @Unmodifiable [] manifestBytes = ("""

                  {
                    "schemaVersion": 2,
                    "id": "%s",
                    "repository": "%s",
                    "versions": [{
                      "version": "%s",
                      "pluginApiVersion": 5,
                      "permissions": [],
                      "requiredPermissions": [],
                      "launcherVersion": "*",
                      "runtime": "java",
                      "abi": 1,
                      "platforms": ["%s"],
                      "pluginKind": "normal",
                      "artifacts": [{
                        "platform": "%s",
                        "packageUrl": "%s",
                        "sha256": "%s",
                        "size": %d
                      }],
                      "dependencies": []
                    }]
                  }

                """.formatted(
                pluginId,
                repository,
                versionText,
                platform,
                platform,
                packageUrl,
                packageSha256,
                packageBytes.length
        )).getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] registryBytes = signedRegistry(
                signer,
                pluginId,
                manifestUrl,
                repository,
                sha256(manifestBytes)
        );
        CountDownLatch packageRequested = new CountDownLatch(1);
        CountDownLatch releasePackage = new CountDownLatch(1);
        server.createContext("/registry.json", exchange -> respond(exchange, registryBytes));
        server.createContext("/replacement-registry.json", exchange -> respond(exchange, ("""
                {"schemaVersion":1,"name":"Replacement","plugins":[]}
                """).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/official-manifest.json", exchange -> respond(exchange, manifestBytes));
        server.createContext("/official.npl", exchange -> {
            packageRequested.countDown();
            await(releasePackage, "package release");
            respond(exchange, packageBytes);
        });
        ExecutorService serverExecutor = Executors.newFixedThreadPool(2);
        server.setExecutor(serverExecutor);
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            PluginStoreManager manager = new PluginStoreManager(verifier);
            manager.loadSource(new PluginSource(
                    PluginSource.OFFICIAL_ID,
                    baseUrl + "/registry.json",
                    null,
                    true,
                    true
            ));
            PluginStoreManifest manifest = manager.getPluginManifest(pluginId, manifestUrl);
            PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(manifest.getVersion(versionText));

            Future<PluginVerifiedDownload> pending = executor.submit(() ->
                    manager.downloadPluginToStagingWithProof(
                            pluginId,
                            version,
                            temporaryDirectory.resolve("official-staging")
                    ));
            await(packageRequested, "package request");
            version.setTrust(PluginTrustResult.community());
            manager.loadSource(new PluginSource(
                    "replacement",
                    baseUrl + "/replacement-registry.json",
                    null,
                    true,
                    false
            ));
            releasePackage.countDown();
            PluginVerifiedDownload result = pending.get(10, TimeUnit.SECONDS);

            assertNotNull(result.trustProof());
            PluginInstallationTrustProof proof = Objects.requireNonNull(result.trustProof());
            assertEquals(PluginInstallationTrustProof.Kind.OFFICIAL, proof.kind());
            assertNotNull(proof.officialReceipt());
            PluginOfficialReceipt receipt = Objects.requireNonNull(proof.officialReceipt());
            assertArrayEquals(registryBytes, receipt.getOfficialRegistryEnvelopeUtf8());
            assertArrayEquals(manifestBytes, receipt.getStoreManifestUtf8());
            receipt.verify(verifier, result.artifactIdentity(), result.artifactSize());
        } finally {
            releasePackage.countDown();
            executor.shutdownNow();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    /// Returns exact package identity and size without inventing authority for a community source.
    @Test
    public void returnsCommunityDownloadWithoutProof(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.aura.test.community-download";
        String versionText = "1.0.0";
        byte @Unmodifiable [] packageBytes = createPluginPackage(pluginId, versionText, "[]");
        String packageSha256 = sha256(packageBytes);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String packageUrl = baseUrl + "/plugin.npl";
        String manifestUrl = baseUrl + "/manifest.json";
        server.createContext("/registry.json", exchange -> respond(exchange, ("""
                {"schemaVersion":1,"name":"Community","plugins":[{
                  "id":"%s","name":"Community Plugin","manifestUrl":"%s"
                }]}
                """.formatted(pluginId, manifestUrl)).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/manifest.json", exchange -> respond(exchange, ("""
                {
                  "schemaVersion":2,
                  "id":"%s",
                  "versions":[{
                    "version":"%s",
                    "packageUrl":"%s",
                    "sha256":"%s",
                    "pluginApiVersion":5,
                    "permissions":[],
                    "requiredPermissions":[],
                    "launcherVersion":"*",
                    "runtime":"java",
                    "abi":1,
                    "platforms":[],
                    "dependencies":[],
                    "size":%d
                  }]
                }
                """.formatted(pluginId, versionText, packageUrl, packageSha256, packageBytes.length))
                .getBytes(StandardCharsets.UTF_8)));
        server.createContext("/plugin.npl", exchange -> respond(exchange, packageBytes));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(new PluginSource("community", baseUrl + "/registry.json", null, true, false));
            PluginStoreManifest manifest = manager.getPluginManifest(pluginId, manifestUrl);
            PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(manifest.getVersion(versionText));
            version.setTrust(PluginTrustResult.official("caller-controlled-badge"));

            PluginVerifiedDownload result = manager.downloadPluginToStagingWithProof(
                    pluginId,
                    version,
                    temporaryDirectory.resolve("staging")
            );

            assertEquals(result.stagedPath().toAbsolutePath().normalize(), result.stagedPath());
            assertEquals(pluginId, result.artifactIdentity().getPluginId());
            assertEquals(versionText, result.artifactIdentity().getVersion());
            assertEquals(packageSha256, result.artifactIdentity().getSha256());
            assertEquals(packageBytes.length, result.artifactSize());
            assertNull(result.trustProof());
            assertArrayEquals(packageBytes, Files.readAllBytes(result.stagedPath()));
        } finally {
            server.stop(0);
        }
    }

    /// Awaits one deterministic concurrency gate.
    ///
    /// @param latch gate to await
    /// @param description diagnostic gate description
    /// @throws IOException if waiting is interrupted or times out
    private static void await(CountDownLatch latch, String description) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + description, exception);
        }
    }

    /// Creates the smallest valid schema-v5 NPL package for one exact identity.
    ///
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param platformsJson exact schema-v5 platform declaration
    /// @return complete NPL bytes
    /// @throws IOException if ZIP generation fails
    private static byte @Unmodifiable [] createPluginPackage(
            String pluginId,
            String version,
            String platformsJson
    ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("plugin.json"));
            zip.write(("""
                    {
                      "schemaVersion":5,
                      "id":"%s",
                      "name":"Verified Download Test",
                      "version":"%s",
                      "type":"java",
                      "entrypoint":"dev.aura.test.Plugin",
                      "permissions":[],
                      "requiredPermissions":[],
                      "launcherVersion":"*",
                      "runtime":"java",
                      "abi":1,
                      "platforms":%s,
                      "dependencies":[]
                    }
                    """.formatted(pluginId, version, platformsJson)).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /// Creates one minimal community registry response.
    ///
    /// @param pluginId registry plugin ID
    /// @param manifestUrl manifest endpoint
    /// @return exact registry bytes
    private static byte @Unmodifiable [] registry(String pluginId, String manifestUrl) {
        return ("""
                {"schemaVersion":1,"name":"Source","plugins":[{
                  "id":"%s","name":"Plugin","manifestUrl":"%s"
                }]}
                """.formatted(pluginId, manifestUrl)).getBytes(StandardCharsets.UTF_8);
    }

    /// Creates one minimal schema-v5 community manifest response.
    ///
    /// @param pluginId manifest plugin ID
    /// @param version package version
    /// @param packageUrl package endpoint
    /// @param packageBytes exact NPL bytes
    /// @return exact manifest bytes
    /// @throws Exception if SHA-256 is unavailable
    private static byte @Unmodifiable [] communityManifest(
            String pluginId,
            String version,
            String packageUrl,
            byte @Unmodifiable [] packageBytes
    ) throws Exception {
        return ("""
                {
                  "schemaVersion":2,
                  "id":"%s",
                  "versions":[{
                    "version":"%s",
                    "packageUrl":"%s",
                    "sha256":"%s",
                    "pluginApiVersion":5,
                    "permissions":[],
                    "requiredPermissions":[],
                    "launcherVersion":"*",
                    "runtime":"java",
                    "abi":1,
                    "platforms":[],
                    "dependencies":[],
                    "size":%d
                  }]
                }
                """.formatted(pluginId, version, packageUrl, sha256(packageBytes), packageBytes.length))
                .getBytes(StandardCharsets.UTF_8);
    }

    /// Creates root metadata whose sole authority is the official Store registry role.
    ///
    /// @param signer official-registry signer
    /// @return unsigned embedded root metadata for the test verifier
    /// @throws Exception if the signer key ID cannot be computed
    private static JsonObject officialOnlyRoot(KeyPair signer) throws Exception {
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

    /// Creates one real signed official-registry envelope with an exact manifest-byte pin.
    ///
    /// @param signer official-registry signer
    /// @param pluginId signed plugin ID
    /// @param manifestUrl signed manifest URL
    /// @param repository signed repository identity
    /// @param manifestSha256 digest of exact manifest response bytes
    /// @return whitespace-bearing exact envelope bytes
    /// @throws Exception if signing fails
    private static byte @Unmodifiable [] signedRegistry(
            KeyPair signer,
            String pluginId,
            String manifestUrl,
            String repository,
            String manifestSha256
    ) throws Exception {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", pluginId);
        entry.addProperty("name", "Official Download");
        entry.addProperty("manifestUrl", manifestUrl);
        entry.addProperty("manifestSha256", manifestSha256);
        entry.addProperty("repository", repository);
        JsonArray plugins = new JsonArray();
        plugins.add(entry);
        JsonObject payload = new JsonObject();
        payload.addProperty("schemaVersion", 1);
        payload.addProperty("name", "Official Download Store");
        payload.add("plugins", plugins);

        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(CanonicalJson.signatureInput(PluginTrustVerifier.OFFICIAL_REGISTRY_DOMAIN, payload));
        JsonObject signatureEntry = new JsonObject();
        signatureEntry.addProperty("keyId", keyId(signer));
        signatureEntry.addProperty("signature", Base64.getEncoder().encodeToString(signature.sign()));
        JsonArray signatures = new JsonArray();
        signatures.add(signatureEntry);
        JsonObject envelope = new JsonObject();
        envelope.add("signed", payload);
        envelope.add("signatures", signatures);
        return ("\n  " + JsonUtils.GSON.toJson(envelope) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /// Computes the trust-root key identifier for one Ed25519 public key.
    ///
    /// @param signer key pair
    /// @return canonical key identifier
    /// @throws Exception if SHA-256 is unavailable
    private static String keyId(KeyPair signer) throws Exception {
        return "ed25519:" + sha256(signer.getPublic().getEncoded());
    }

    /// Writes one exact bounded loopback HTTP response.
    ///
    /// @param exchange incoming request
    /// @param body exact response body
    /// @throws IOException if the response cannot be written
    private static void respond(HttpExchange exchange, byte @Unmodifiable [] body) throws IOException {
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /// Computes the lower-case SHA-256 used by Store artifact metadata.
    ///
    /// @param bytes package bytes
    /// @return lower-case digest
    /// @throws Exception if SHA-256 is unavailable
    private static String sha256(byte @Unmodifiable [] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
