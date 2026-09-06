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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginVersion;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluator;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityResult;
import org.jackhuang.hmcl.plugin.trust.PluginCertificationReceipt;
import org.jackhuang.hmcl.plugin.trust.PluginDocumentVerification;
import org.jackhuang.hmcl.plugin.trust.PluginInstallationTrustProof;
import org.jackhuang.hmcl.plugin.trust.PluginOfficialReceipt;
import org.jackhuang.hmcl.plugin.trust.PluginRepositoryAttestation;
import org.jackhuang.hmcl.plugin.trust.PluginRepositoryAttestationDocument;
import org.jackhuang.hmcl.plugin.trust.PluginTrustLevel;
import org.jackhuang.hmcl.plugin.trust.PluginTrustResult;
import org.jackhuang.hmcl.plugin.trust.PluginTrustStatusCache;
import org.jackhuang.hmcl.plugin.trust.PluginTrustStatusSnapshot;
import org.jackhuang.hmcl.plugin.trust.PluginTrustVerifier;
import org.jackhuang.hmcl.plugin.trust.PluginVerifiedCertification;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Resolves validated remote registries, checks compatibility, and downloads verified plugin packages atomically.
@NotNullByDefault
public final class PluginStoreManager {
    /// Reserved Aura Launcher plugin registry endpoint.
    public static final String DEFAULT_REGISTRY_URL =
            System.getProperty("aura.plugin_store.registry",
                    "https://raw.githubusercontent.com/Egg-China/Aura-Launcher-Plugin-Store/main/plugins.json");

    /// Validated trust verifier shared by default Store clients and enablement policy.
    private static final PluginTrustVerifier DEFAULT_TRUST_VERIFIER = loadDefaultTrustVerifier();

    /// Whether the verified default registry should be loaded automatically.
    public static final boolean DEFAULT_REGISTRY_ENABLED = defaultRegistryEnabled(
            System.getProperty("aura.plugin_store.enabled"),
            DEFAULT_TRUST_VERIFIER
    );

    /// GitHub repository search endpoint used by the disabled Aura Topic source.
    public static final String GITHUB_TOPIC_API_URL = System.getProperty(
            "aura.plugin_store.github_api", "https://api.github.com/search/repositories"
    );

    /// GitHub raw-content base used by the disabled Aura Topic source.
    public static final String GITHUB_RAW_BASE_URL = System.getProperty(
            "aura.plugin_store.github_raw", "https://raw.githubusercontent.com"
    );

    /// Hard upper bound for any downloaded plugin package.
    private static final long MAX_PACKAGE_BYTES = 512L * 1024L * 1024L;

    /// Maximum UTF-8 bytes accepted for the top-level registry document.
    private static final int MAX_REGISTRY_BYTES = 2 * 1024 * 1024;

    /// Maximum UTF-8 bytes accepted for one plugin repository manifest.
    private static final int MAX_STORE_MANIFEST_BYTES = 4 * 1024 * 1024;

    /// Maximum plugin manifest bytes inspected before an atomic package replacement.
    private static final int MAX_PLUGIN_MANIFEST_BYTES = 1024 * 1024;

    /// Maximum README bytes retained and rendered by the store.
    private static final int MAX_README_BYTES = 2 * 1024 * 1024;

    /// Maximum redirects followed for one store-owned HTTP request.
    private static final int MAX_REDIRECTS = 20;

    /// Extracts the first Java feature number from registry text.
    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("(\\d+)");

    /// Atomically published source, registry, and source-owned request caches.
    private volatile @Nullable SourceContext context;

    /// Verifies signed official registries while ordinary manifests remain community content.
    private final PluginTrustVerifier trustVerifier;

    /// Legacy online status cache retained only for explicit compatibility fixtures.
    private final @Nullable PluginTrustStatusCache trustStatusCache;

    /// Raw-content base used for GitHub Topic repository manifests.
    private final String githubRawBaseUrl;

    /// Shared launcher, platform, runtime, and ABI compatibility evaluator.
    private final PluginCompatibilityEvaluator compatibilityEvaluator;

    /// Opens already validated package URLs, replaceable only by package-local transport tests.
    private final PackageConnectionFactory packageConnectionFactory;

    /// README cache retained only for the historical explicit-manifest API before a source has loaded.
    private final Map<String, String> unloadedReadmeCache = new ConcurrentHashMap<>();

    /// Package-local transport seam for immutable HTTPS artifact identities without live network access.
    @FunctionalInterface
    @NotNullByDefault
    interface PackageConnectionFactory {
        /// Opens one package connection after Store URL policy validation.
        ///
        /// @param url validated exact package URL
        /// @param purpose diagnostic request purpose
        /// @return connected package response
        /// @throws IOException if the response cannot be opened
        HttpURLConnection open(String url, String purpose) throws IOException;
    }

    /// Captures one source generation so readers cannot combine registry state or cache results across replacements.
    @NotNullByDefault
    static final class SourceContext {
        /// Immutable source configuration validated for this generation.
        private final PluginSource source;

        /// Registry validated for this source generation.
        private final PluginStoreRegistry registry;

        /// Validated manifests resolved only for this source generation.
        private final Map<String, CachedManifest> manifestCache = new ConcurrentHashMap<>();

        /// Exact bounded official-registry envelope bytes, absent only for generated Topic sources.
        private final byte @Nullable @Unmodifiable [] registryEnvelopeUtf8;

        /// Raw manifests prefetched by GitHub Topic discovery.
        private final Map<String, String> prefetchedManifestContents = new ConcurrentHashMap<>();

        /// Externally established GitHub identities by manifest URL.
        private final Map<String, String> repositoryIdentities = new ConcurrentHashMap<>();

        /// Topic repositories excluded before they could become registry entries.
        private final int skippedRepositoryCount;

        /// Historical repository proofs cached by artifact-signed verification ID for this source generation.
        private final Map<String, RepositoryAttestationResolution> repositoryAttestations = new ConcurrentHashMap<>();

        /// Trust result for the registry document itself.
        private final PluginTrustResult registryTrust;

        /// Bounded README text resolved only for this source generation.
        private final Map<String, String> readmeCache = new ConcurrentHashMap<>();

        /// Creates a source context after the registry has completed validation.
        ///
        /// @param source immutable source configuration
        /// @param registry validated registry
        /// @param registryTrust trust result for the registry document
        /// @param prefetchedManifestContents raw manifests prefetched during Topic discovery
        /// @param repositoryIdentities externally established repository identities by manifest URL
        /// @param skippedRepositoryCount Topic repositories excluded before registry publication
        private SourceContext(
                PluginSource source,
                PluginStoreRegistry registry,
                PluginTrustResult registryTrust,
                byte @Nullable @Unmodifiable [] registryEnvelopeUtf8,
                Map<String, String> prefetchedManifestContents,
                Map<String, String> repositoryIdentities,
                int skippedRepositoryCount
        ) {
            if (skippedRepositoryCount < 0) {
                throw new IllegalArgumentException("skippedRepositoryCount must not be negative");
            }
            this.source = source;
            this.registry = registry;
            this.registryTrust = registryTrust;
            this.registryEnvelopeUtf8 = registryEnvelopeUtf8 == null ? null : registryEnvelopeUtf8.clone();
            this.prefetchedManifestContents.putAll(prefetchedManifestContents);
            this.repositoryIdentities.putAll(repositoryIdentities);
            this.skippedRepositoryCount = skippedRepositoryCount;
        }
    }

    /// Atomically couples one parsed Store manifest to the exact UTF-8 bytes and source-derived trust used to parse it.
    @NotNullByDefault
    private static final class CachedManifest {
        /// Validated parsed manifest.
        private final PluginStoreManifest manifest;

        /// Exact bounded UTF-8 bytes used for parsing and official pin verification.
        private final byte @Unmodifiable [] manifestUtf8;

        /// Source-derived trust captured before cache publication.
        private final PluginTrustResult trust;

        /// Creates one immutable cache entry and defensively copies its proof-bearing bytes.
        ///
        /// @param manifest validated parsed manifest
        /// @param manifestUtf8 exact UTF-8 source bytes
        /// @param trust source-derived manifest trust
        private CachedManifest(
                PluginStoreManifest manifest,
                byte @Unmodifiable [] manifestUtf8,
                PluginTrustResult trust
        ) {
            this.manifest = Objects.requireNonNull(manifest, "manifest");
            this.manifestUtf8 = manifestUtf8.clone();
            this.trust = Objects.requireNonNull(trust, "trust");
        }

        /// Returns the validated parsed manifest.
        ///
        /// @return parsed manifest
        private PluginStoreManifest manifest() {
            return manifest;
        }

        /// Returns a defensive copy of the exact source bytes.
        ///
        /// @return exact UTF-8 manifest bytes
        private byte @Unmodifiable [] manifestUtf8() {
            return manifestUtf8.clone();
        }

        /// Returns the source-derived manifest trust.
        ///
        /// @return captured trust decision
        private PluginTrustResult trust() {
            return trust;
        }
    }

    /// Source-generation-bound inputs used to derive and reverify one concrete installation proof.
    @NotNullByDefault
    private final class DownloadProofContext {
        /// Captured source generation that owns every proof input.
        private final SourceContext sourceContext;

        /// Exact registry entry selected from the captured generation.
        private final PluginStoreRegistry.PluginStoreEntry entry;

        /// Atomically published parsed manifest and exact source bytes.
        private final CachedManifest cachedManifest;

        /// Source- or verifier-derived trust decision, never a mutable version badge.
        private final PluginTrustResult trustDecision;

        /// Creates proof inputs already bound to one captured source generation.
        ///
        /// @param sourceContext captured source generation
        /// @param entry exact captured registry entry
        /// @param cachedManifest exact captured parsed manifest and source bytes
        /// @param trustDecision source- or verifier-derived trust decision
        private DownloadProofContext(
                SourceContext sourceContext,
                PluginStoreRegistry.PluginStoreEntry entry,
                CachedManifest cachedManifest,
                PluginTrustResult trustDecision
        ) {
            this.sourceContext = sourceContext;
            this.entry = entry;
            this.cachedManifest = cachedManifest;
            this.trustDecision = trustDecision;
        }

        /// Returns the captured decision used by the proof-aware download gate.
        ///
        /// @return source- or verifier-derived trust
        private PluginTrustResult trustDecision() {
            return trustDecision;
        }

        /// Builds and independently re-verifies the concrete proof for downloaded bytes.
        ///
        /// @param artifact exact selected platform artifact
        /// @param identity downloaded package identity
        /// @param actualSize actual downloaded package size
        /// @return official or certified proof, or `null` for community content
        /// @throws IOException if required proof is absent, malformed, or does not bind the downloaded artifact
        private @Nullable PluginInstallationTrustProof createAndVerifyProof(
                PluginStoreArtifact artifact,
                PluginArtifactIdentity identity,
                long actualSize
        ) throws IOException {
            try {
                if (trustDecision.level() == PluginTrustLevel.OFFICIAL) {
                    byte @Nullable @Unmodifiable [] registryEnvelopeUtf8 = sourceContext.registryEnvelopeUtf8;
                    if (registryEnvelopeUtf8 == null) {
                        throw new IllegalArgumentException("Official source has no retained registry envelope");
                    }
                    PluginOfficialReceipt receipt = new PluginOfficialReceipt(
                            registryEnvelopeUtf8,
                            cachedManifest.manifestUtf8(),
                            entry.getManifestUrl(),
                            entry.getRepository(),
                            artifact.platform().getId(),
                            artifact.packageUrl(),
                            identity,
                            actualSize
                    );
                    receipt.verify(trustVerifier, identity, actualSize);
                    return PluginInstallationTrustProof.fromInstallDecision(trustDecision, receipt);
                }
                if (trustDecision.level() == PluginTrustLevel.CERTIFIED) {
                    PluginInstallationTrustProof proof =
                            PluginInstallationTrustProof.fromInstallDecision(trustDecision, null);
                    PluginCertificationReceipt receipt = Objects.requireNonNull(proof.certificationReceipt());
                    receipt.verify(trustVerifier, identity, actualSize);
                    return proof;
                }
                if (trustDecision.level() == PluginTrustLevel.COMMUNITY) {
                    return null;
                }
                throw new IllegalArgumentException("Rejected Store content has no installation proof");
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw new IOException("Plugin installation proof verification failed for "
                        + identity.getPluginId(), exception);
            }
        }
    }

    /// Creates an unloaded source-scoped store client.
    public PluginStoreManager() {
        this(DEFAULT_TRUST_VERIFIER, null, GITHUB_RAW_BASE_URL, createDefaultCompatibilityEvaluator(),
                PluginStoreManager::openValidatedConnection);
    }

    /// Creates an unloaded client with an explicit GitHub raw-content base for package-local tests.
    ///
    /// @param githubRawBaseUrl raw-content base used for Topic repository manifests
    PluginStoreManager(String githubRawBaseUrl) {
        this(DEFAULT_TRUST_VERIFIER, null, githubRawBaseUrl, createDefaultCompatibilityEvaluator(),
                PluginStoreManager::openValidatedConnection);
    }

    /// Creates an unloaded client with a deterministic compatibility evaluator for package-local tests.
    ///
    /// @param compatibilityEvaluator evaluator with the desired runtime registry and host platform
    PluginStoreManager(PluginCompatibilityEvaluator compatibilityEvaluator) {
        this(DEFAULT_TRUST_VERIFIER, null, GITHUB_RAW_BASE_URL, compatibilityEvaluator,
                PluginStoreManager::openValidatedConnection);
    }

    /// Loads the embedded plugin trust root for process-wide default Store behavior.
    ///
    /// @return configured trust verifier
    /// @throws IllegalStateException if the embedded trust root cannot be loaded
    private static PluginTrustVerifier loadDefaultTrustVerifier() {
        try {
            return PluginTrustVerifier.loadDefault();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Aura Launcher plugin trust root", exception);
        }
    }

    /// Resolves automatic official-source enablement from a validated root unless explicitly overridden.
    ///
    /// @param override explicit system-property value, or `null` to derive the default from trust capabilities
    /// @param verifier validated trust verifier embedded in this build
    /// @return whether the built-in official registry should be attempted
    static boolean defaultRegistryEnabled(
            @Nullable String override,
            PluginTrustVerifier verifier
    ) {
        return override == null
                ? verifier.supportsOfficialRegistry()
                : Boolean.parseBoolean(override);
    }

    /// Returns the production evaluator backed by the process-wide runtime provider registry.
    ///
    /// @return production compatibility evaluator
    private static PluginCompatibilityEvaluator createDefaultCompatibilityEvaluator() {
        return PluginCompatibilityEvaluator.processWide();
    }

    /// Creates a store manager with an explicit verifier for package-local tests.
    PluginStoreManager(PluginTrustVerifier trustVerifier) {
        this(trustVerifier, null, GITHUB_RAW_BASE_URL, createDefaultCompatibilityEvaluator(),
                PluginStoreManager::openValidatedConnection);
    }

    /// Creates a store manager with explicit trust and status services for package-local tests.
    ///
    /// @param trustVerifier role-separated signature verifier
    /// @param trustStatusCache legacy authenticated status cache, or `null` under the current policy
    PluginStoreManager(PluginTrustVerifier trustVerifier, @Nullable PluginTrustStatusCache trustStatusCache) {
        this(trustVerifier, trustStatusCache, GITHUB_RAW_BASE_URL, createDefaultCompatibilityEvaluator(),
                PluginStoreManager::openValidatedConnection);
    }

    /// Creates a manager with explicit trust services and package transport for package-local tests.
    ///
    /// @param trustVerifier role-separated signature verifier
    /// @param trustStatusCache authenticated trust-status cache
    /// @param packageConnectionFactory deterministic package transport invoked only after URL validation
    PluginStoreManager(
            PluginTrustVerifier trustVerifier,
            PluginTrustStatusCache trustStatusCache,
            PackageConnectionFactory packageConnectionFactory
    ) {
        this(trustVerifier, trustStatusCache, GITHUB_RAW_BASE_URL, createDefaultCompatibilityEvaluator(),
                packageConnectionFactory);
    }

    /// Creates a store manager with explicit trust, status, and Topic transport dependencies.
    ///
    /// @param trustVerifier role-separated signature verifier
    /// @param trustStatusCache legacy authenticated status cache, or `null` under the current policy
    /// @param githubRawBaseUrl raw-content base used for Topic repository manifests
    /// @param compatibilityEvaluator shared compatibility evaluator for store filtering
    /// @param packageConnectionFactory validated package transport
    private PluginStoreManager(
            PluginTrustVerifier trustVerifier,
            @Nullable PluginTrustStatusCache trustStatusCache,
            String githubRawBaseUrl,
            PluginCompatibilityEvaluator compatibilityEvaluator,
            PackageConnectionFactory packageConnectionFactory
    ) {
        this.trustVerifier = Objects.requireNonNull(trustVerifier, "trustVerifier");
        this.trustStatusCache = trustStatusCache;
        this.githubRawBaseUrl = Objects.requireNonNull(githubRawBaseUrl, "githubRawBaseUrl");
        this.compatibilityEvaluator = Objects.requireNonNull(compatibilityEvaluator, "compatibilityEvaluator");
        this.packageConnectionFactory = Objects.requireNonNull(packageConnectionFactory, "packageConnectionFactory");
    }

    /// Loads and validates one plugin source without persisting user configuration.
    ///
    /// @param source immutable source configuration to load
    /// @throws IOException if transport, parsing, URL policy, or validation fails
    public void loadSource(PluginSource source) throws IOException {
        Objects.requireNonNull(source, "source");
        if (source.isGitHubTopic()) {
            GitHubTopicDiscovery.Result discovery = new GitHubTopicDiscovery(
                    source.getUrl(),
                    githubRawBaseUrl,
                    "aura-launcher",
                    System.getProperty("aura.plugin_store.github_token"),
                    10
            ).discover();
            context = new SourceContext(
                    source,
                    discovery.registry(),
                    PluginTrustResult.community(),
                    null,
                    discovery.manifestContents(),
                    discovery.repositoryIdentities(),
                    discovery.skippedRepositoryCount()
            );
            return;
        }
        RegistryLoad loaded = loadRegistryForRequest(source);
        Map<String, String> identities = new LinkedHashMap<>();
        for (PluginStoreRegistry.PluginStoreEntry entry : loaded.registry().getPlugins()) {
            if (!entry.getRepository().isBlank()) {
                try {
                    identities.put(entry.getManifestUrl(), PluginTrustVerifier.normalizeRepository(entry.getRepository()));
                } catch (IllegalArgumentException ignored) {
                    // Non-GitHub registries remain valid community sources but cannot receive developer certification.
                }
            }
        }
        context = new SourceContext(
                source,
                loaded.registry(),
                loaded.trust(),
                loaded.registryEnvelopeUtf8(),
                Map.of(),
                identities,
                0
        );
    }

    /// Attempts a due root-controlled status refresh without making an offline source load fail.
    private void refreshTrustStatusIfDue() {
        @Nullable PluginTrustStatusCache cache = trustStatusCache;
        if (cache == null) {
            return;
        }
        try {
            cache.refreshIfDue();
        } catch (IOException exception) {
            LOG.warning("Unable to refresh plugin trust status; using the last authenticated cache", exception);
        }
    }

    /// Returns the source associated with the currently loaded registry.
    ///
    /// @return loaded source
    /// @throws IllegalStateException if no source has loaded successfully
    public PluginSource getSource() {
        return requireContext().source;
    }

    /// Returns Topic repositories excluded before they could become source items.
    ///
    /// @return skipped repository count, or zero before loading and for ordinary registry sources
    public int getSkippedRepositoryCount() {
        @Nullable SourceContext currentContext = context;
        return currentContext == null ? 0 : currentContext.skippedRepositoryCount;
    }

    /// Returns the current source context or rejects operations before a successful source load.
    ///
    /// @return atomically published source context
    /// @throws IllegalStateException if no source has loaded successfully
    private SourceContext requireContext() {
        @Nullable SourceContext currentContext = context;
        if (currentContext == null) {
            throw new IllegalStateException("Plugin source is not loaded");
        }
        return currentContext;
    }

    /// Loads and validates a registry response for the supplied source URL.
    ///
    /// The caller publishes source identity only after this validation succeeds, keeping failed requests from
    /// replacing the previously loaded source context.
    ///
    /// @param registryUrl registry URL
    /// @throws IOException if transport, parsing, URL policy, or validation fails
    private RegistryLoad loadRegistryForRequest(PluginSource source) throws IOException {
        String registryUrl = source.getUrl();
        validateRemoteUrl(registryUrl, "plugin registry");
        LOG.info("Loading plugin registry from: " + PluginSourceLabels.diagnosticUrl(registryUrl));
        try {
            BoundedUtf8Document content = fetchBoundedUtf8(
                    registryUrl,
                    "plugin registry",
                    MAX_REGISTRY_BYTES
            );
            JsonElement document = JsonParser.parseString(content.text());
            if (!document.isJsonObject()) {
                throw new IOException("Plugin registry is not an object");
            }
            PluginDocumentVerification verification = source.isOfficial()
                    ? trustVerifier.verifyOfficialRegistry(document.getAsJsonObject())
                    : new PluginDocumentVerification(document.getAsJsonObject(), PluginTrustResult.community());
            if (source.isOfficial() && verification.trust().level() != PluginTrustLevel.OFFICIAL) {
                throw new IOException("Official plugin registry signature verification failed");
            }
            @Nullable PluginStoreRegistry loadedRegistry = JsonUtils.GSON.fromJson(
                    verification.signed(),
                    PluginStoreRegistry.class
            );
            if (loadedRegistry == null) {
                throw new IOException("Empty plugin registry: " + PluginSourceLabels.diagnosticUrl(registryUrl));
            }
            loadedRegistry.validate();
            for (PluginStoreRegistry.PluginStoreEntry entry : loadedRegistry.getPlugins()) {
                validateRemoteUrl(entry.getManifestUrl(), "plugin manifest");
                if (source.isOfficial() && entry.getManifestSha256().isBlank()) {
                    throw new IOException("Official registry entry has no manifestSha256: " + entry.getId());
                }
            }

            LOG.info("Loaded " + loadedRegistry.getPlugins().size() + " plugins from registry");
            return new RegistryLoad(loadedRegistry, verification.trust(), content.exactBytes());
        } catch (JsonParseException exception) {
            throw new IOException("Failed to parse plugin registry", exception);
        }
    }

    /// Loads the fixed official plugin source without persisting any selection state.
    ///
    /// @throws IOException if loading fails
    public void loadDefaultRegistry() throws IOException {
        if (!DEFAULT_REGISTRY_ENABLED) {
            throw new IOException("Aura Launcher has no enabled built-in plugin registry");
        }
        loadSource(new PluginSource(
                PluginSource.OFFICIAL_ID,
                DEFAULT_REGISTRY_URL,
                null,
                DEFAULT_REGISTRY_ENABLED,
                true
        ));
    }

    /// Resolves and validates one plugin repository manifest.
    ///
    /// @param pluginId expected plugin ID
    /// @param manifestUrl repository manifest URL
    /// @return validated repository manifest
    /// @throws IOException if transport, parsing, identity, or schema validation fails
    public PluginStoreManifest getPluginManifest(String pluginId, String manifestUrl) throws IOException {
        return getPluginManifest(requireContext(), pluginId, manifestUrl);
    }

    /// Resolves one repository manifest through the supplied source context only.
    ///
    /// @param sourceContext source context captured before the request begins
    /// @param pluginId expected plugin ID
    /// @param manifestUrl repository manifest URL
    /// @return validated repository manifest
    /// @throws IOException if transport, parsing, identity, or schema validation fails
    private PluginStoreManifest getPluginManifest(
            SourceContext sourceContext,
            String pluginId,
            String manifestUrl
    ) throws IOException {
        @Nullable CachedManifest cached = sourceContext.manifestCache.get(manifestUrl);
        if (cached != null) {
            if (!pluginId.equals(cached.manifest().getId())) {
                throw new IOException("Cached plugin manifest ID mismatch for " + pluginId);
            }
            return cached.manifest();
        }

        validateRemoteUrl(manifestUrl, "plugin manifest");
        LOG.info("Fetching plugin manifest from: " + PluginSourceLabels.diagnosticUrl(manifestUrl));
        try {
            BoundedUtf8Document content = sourceContext.prefetchedManifestContents.containsKey(manifestUrl)
                    ? BoundedUtf8Document.fromTrustedText(sourceContext.prefetchedManifestContents.get(manifestUrl))
                    : fetchBoundedUtf8(manifestUrl, "plugin manifest", MAX_STORE_MANIFEST_BYTES);
            if (sourceContext.registryTrust.level() == PluginTrustLevel.OFFICIAL) {
                @Nullable PluginStoreRegistry.PluginStoreEntry entry = sourceContext.registry.findPlugin(pluginId);
                if (entry == null || !entry.getManifestUrl().equals(manifestUrl)) {
                    throw new IOException("Official registry manifest identity mismatch for " + pluginId);
                }
                String actualManifestSha256 = HexFormat.of().formatHex(
                        createSha256().digest(content.exactBytes())
                );
                if (!entry.getManifestSha256().equals(actualManifestSha256)) {
                    throw new IOException("Official plugin manifest SHA-256 mismatch for " + pluginId);
                }
            }
            JsonElement document = JsonParser.parseString(content.text());
            if (!document.isJsonObject()) {
                throw new IOException("Plugin manifest is not an object");
            }
            @Nullable String repositoryIdentity = sourceContext.repositoryIdentities.get(manifestUrl);
            PluginDocumentVerification verification;
            if (sourceContext.registryTrust.level() == PluginTrustLevel.OFFICIAL) {
                verification = new PluginDocumentVerification(document.getAsJsonObject(), sourceContext.registryTrust);
            } else {
                JsonObject manifestDocument = document.getAsJsonObject().has("signed")
                        && document.getAsJsonObject().get("signed").isJsonObject()
                        ? document.getAsJsonObject().getAsJsonObject("signed")
                        : document.getAsJsonObject();
                verification = new PluginDocumentVerification(manifestDocument, PluginTrustResult.community());
            }
            PluginStoreManifest manifest = PluginStoreManifest.fromJson(verification.signed(), pluginId);
            for (PluginStoreManifest.PluginVersionEntry version : manifest.getVersions()) {
                if (version.getArtifacts().isEmpty()) {
                    validateRemoteUrl(version.getPackageUrl(), "plugin package");
                } else {
                    for (PluginStoreArtifact artifact : version.getArtifacts()) {
                        validateRemoteUrl(artifact.packageUrl(), "plugin package");
                    }
                }
            }
            assignVersionTrust(manifest, verification.trust());
            @Nullable PluginStoreManifest.PluginVersionEntry latest = manifest.getLatestVersion();
            PluginTrustResult manifestTrust = latest == null ? verification.trust() : latest.getTrust();
            CachedManifest candidate = new CachedManifest(manifest, content.exactBytes(), manifestTrust);
            @Nullable CachedManifest existing = sourceContext.manifestCache.putIfAbsent(manifestUrl, candidate);
            CachedManifest published = existing == null ? candidate : existing;
            if (!pluginId.equals(published.manifest().getId())) {
                throw new IOException("Cached plugin manifest ID mismatch for " + pluginId);
            }
            return published.manifest();
        } catch (JsonParseException exception) {
            throw new IOException("Failed to parse plugin manifest", exception);
        }
    }

    /// Assigns official trust to official versions and community trust to every ordinary source version.
    ///
    /// @param manifest validated manifest
    /// @param documentTrust official registry or ordinary community trust
    private void assignVersionTrust(
            PluginStoreManifest manifest,
            PluginTrustResult documentTrust
    ) {
        if (documentTrust.level() == PluginTrustLevel.OFFICIAL) {
            manifest.getVersions().forEach(version -> version.setTrust(documentTrust));
            return;
        }
        manifest.getVersions().forEach(version -> version.setTrust(PluginTrustResult.community()));
    }

    /// Resolves one artifact-referenced historical repository proof from the root-controlled cache and origin.
    private RepositoryAttestationResolution resolveRepositoryAttestation(
            SourceContext sourceContext,
            PluginStoreManifest manifest,
            @Nullable String repositoryIdentity,
            PluginStoreManifest.PluginVersionEntry version
    ) {
        if (repositoryIdentity == null) {
            return RepositoryAttestationResolution.failed("certification has no externally established repository identity");
        }
        final String normalizedManifestRepository;
        try {
            normalizedManifestRepository = PluginTrustVerifier.normalizeRepository(manifest.getRepository());
        } catch (IllegalArgumentException exception) {
            return RepositoryAttestationResolution.failed("certification repository identity is invalid");
        }
        if (!normalizedManifestRepository.equals(repositoryIdentity)) {
            return RepositoryAttestationResolution.failed("certification repository does not match discovery identity");
        }
        @Nullable JsonObject artifactAttestation = version.getArtifactAttestation();
        if (artifactAttestation == null) {
            return RepositoryAttestationResolution.failed("partial artifact certification declaration");
        }
        final String verificationId;
        try {
            verificationId = trustVerifier.verifyArtifactRepositoryReference(artifactAttestation);
        } catch (RuntimeException exception) {
            return RepositoryAttestationResolution.failed("artifact attestation signature or repository reference is invalid");
        }
        synchronized (sourceContext.repositoryAttestations) {
            @Nullable RepositoryAttestationResolution cached =
                    sourceContext.repositoryAttestations.get(verificationId);
            if (cached != null) {
                return cached;
            }
            RepositoryAttestationResolution loaded;
            try {
                @Nullable PluginTrustStatusCache cache = trustStatusCache;
                if (cache == null) {
                    throw new IOException("authenticated repository attestation cache is unavailable");
                }
                PluginRepositoryAttestationDocument document =
                        cache.resolveRepositoryAttestationDocument(verificationId);
                PluginRepositoryAttestation attestation = document.attestation();
                if (!attestation.repository().equals(normalizedManifestRepository)) {
                    throw new IOException("Repository attestation identity does not match its manifest");
                }
                loaded = RepositoryAttestationResolution.verified(document);
            } catch (IOException | RuntimeException exception) {
                loaded = RepositoryAttestationResolution.failed("repository attestation verification failed");
            }
            sourceContext.repositoryAttestations.put(verificationId, loaded);
            return loaded;
        }
    }

    /// Evaluates one exact platform artifact against its NPL proof, weekly repository proof, and online status.
    ///
    /// @param manifest repository manifest owning the selected version
    /// @param repositoryIdentity externally established repository identity, or `null` when unavailable
    /// @param repositoryResolution verified historical repository proof, or `null` when unavailable
    /// @param version selected repository version
    /// @param artifact exact current-platform package metadata
    /// @return current trust decision for the selected artifact
    private PluginTrustResult evaluateCertifiedVersion(
            PluginStoreManifest manifest,
            @Nullable String repositoryIdentity,
            @Nullable RepositoryAttestationResolution repositoryResolution,
            PluginStoreManifest.PluginVersionEntry version,
            PluginStoreArtifact artifact
    ) {
        @Nullable JsonObject artifactAttestation = version.getArtifactAttestation();
        if (artifactAttestation == null) {
            return PluginTrustResult.rejected("partial artifact certification declaration");
        }
        if (repositoryIdentity == null || repositoryResolution == null || repositoryResolution.attestation() == null) {
            return PluginTrustResult.rejected(repositoryResolution == null
                    ? "repository attestation is unavailable"
                    : repositoryResolution.failure());
        }
        @Nullable PluginTrustStatusCache cache = trustStatusCache;
        @Nullable PluginTrustStatusSnapshot status = cache == null ? null : cache.getFreshSnapshot();
        if (status == null) {
            return PluginTrustResult.rejected("official trust status is unavailable or expired");
        }
        PluginTrustResult result = trustVerifier.verifyArtifactAttestation(
                artifactAttestation,
                repositoryResolution.attestation(),
                status,
                repositoryIdentity,
                manifest.getId(),
                version.getVersion(),
                artifact.packageUrl(),
                artifact.sha256(),
                artifact.size()
        );
        if (result.level() != PluginTrustLevel.CERTIFIED) {
            return result;
        }
        try {
            JsonObject repositoryEnvelope = Objects.requireNonNull(repositoryResolution.envelope());
            PluginVerifiedCertification certification = trustVerifier.verifyInstalledCertification(
                    artifactAttestation,
                    repositoryEnvelope,
                    manifest.getId(),
                    version.getVersion(),
                    artifact.sha256(),
                    artifact.size()
            );
            PluginCertificationReceipt receipt = PluginCertificationReceipt.fromVerified(
                    certification,
                    artifactAttestation,
                    repositoryEnvelope
            );
            return result.withCertificationReceipt(receipt);
        } catch (RuntimeException exception) {
            return PluginTrustResult.rejected("certification receipt proof binding failed");
        }
    }

    /// Returns the trust already assigned by source loading and official-reference aggregation.
    ///
    /// @param item source-bound item
    /// @param version selected version
    /// @return current exact-version trust
    public PluginTrustResult refreshVersionTrust(
            PluginStoreItem item,
            PluginStoreManifest.PluginVersionEntry version
    ) {
        return item.getTrust(version);
    }

    /// Resolves all registry entries, retaining unavailable repositories as partial source-bound items.
    ///
    /// @return resolved store items
    public @Unmodifiable List<PluginStoreItem> getStoreItems() {
        @Nullable SourceContext sourceContext = context;
        if (sourceContext == null) {
            return List.of();
        }
        return getStoreItems(sourceContext);
    }

    /// Resolves registry entries against one already captured immutable source generation.
    ///
    /// @param sourceContext exact source generation used for every item and manifest cache lookup
    /// @return resolved store items from that generation
    private @Unmodifiable List<PluginStoreItem> getStoreItems(SourceContext sourceContext) {
        List<PluginStoreItem> items = new ArrayList<>();
        for (PluginStoreRegistry.PluginStoreEntry entry : sourceContext.registry.getPlugins()) {
            try {
                items.add(new PluginStoreItem(
                        sourceContext.source,
                        sourceContext.registry,
                        this,
                        entry,
                        getPluginManifest(sourceContext, entry.getId(), entry.getManifestUrl()),
                        sourceContext,
                        Objects.requireNonNull(sourceContext.manifestCache.get(entry.getManifestUrl())).trust()
                ));
            } catch (IOException exception) {
                LOG.warning("Failed to load plugin manifest: " + entry.getId());
                items.add(new PluginStoreItem(
                        sourceContext.source,
                        sourceContext.registry,
                        this,
                        entry,
                        null,
                        sourceContext,
                        PluginTrustResult.rejected("plugin manifest could not be loaded")
                ));
            }
        }
        return List.copyOf(items);
    }

    /// Registry payload and its derived trust decision.
    private record RegistryLoad(
            PluginStoreRegistry registry,
            PluginTrustResult trust,
            byte @Unmodifiable [] registryEnvelopeUtf8
    ) {
        /// Defensively retains exact registry bytes while the source context is assembled.
        private RegistryLoad {
            registryEnvelopeUtf8 = registryEnvelopeUtf8.clone();
        }
    }

    /// Cached weekly repository proof or one credential-safe failure.
    @NotNullByDefault
    private record RepositoryAttestationResolution(
            @Nullable PluginRepositoryAttestation attestation,
            @Nullable JsonObject envelope,
            String failure
    ) {
        /// Creates one verified resolution.
        private static RepositoryAttestationResolution verified(PluginRepositoryAttestationDocument document) {
            return new RepositoryAttestationResolution(document.attestation(), document.envelope(), "");
        }

        /// Creates one failure before a URL can be accepted.
        private static RepositoryAttestationResolution failed(String failure) {
            return new RepositoryAttestationResolution(null, null, failure);
        }
    }

    /// Resolves a requested version and all transitive plugin dependencies before any package is downloaded.
    ///
    /// This compatibility overload never silently reuses installed dependencies because it has no exact
    /// artifact-bound permission snapshot. Callers that can prove reuse eligibility should use the overload accepting
    /// `reusableInstalledPluginIds`.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            Map<String, PluginManifest> installedManifests
    ) throws IOException {
        return resolveInstallPlan(pluginId, requestedVersion, installedManifests, Map.of(), Map.of());
    }

    /// Resolves a requested version and all transitive plugin dependencies before any package is downloaded.
    ///
    /// This compatibility overload cannot preserve exact package identities. An empty ID set delegates to the
    /// fail-closed resolver, while a non-empty set is rejected instead of allowing a key-only authorization decision.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @param reusableInstalledPluginIds legacy key-only reusable IDs, which must be empty
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            Map<String, PluginManifest> installedManifests,
            @Unmodifiable Set<String> reusableInstalledPluginIds
    ) throws IOException {
        if (!reusableInstalledPluginIds.isEmpty()) {
            throw new IllegalArgumentException("Reusable plugin IDs cannot authorize reuse without exact artifacts");
        }
        return resolveInstallPlan(pluginId, requestedVersion, installedManifests, Map.of(), Map.of());
    }

    /// Compatibility overload for callers that do not carry a complete installed-artifact snapshot.
    ///
    /// This overload is accepted only when no plugin is installed. Installed state requires the five-argument method
    /// so every update and every reuse decision is bound to one atomic exact-artifact snapshot.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @param reusableInstalledArtifacts legacy partial reusable artifact snapshot
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            Map<String, PluginManifest> installedManifests,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalledArtifacts
    ) throws IOException {
        if (!installedManifests.isEmpty() || !reusableInstalledArtifacts.isEmpty()) {
            throw new IllegalArgumentException("Installed plugin planning requires complete prior artifact identities");
        }
        return resolveInstallPlan(pluginId, requestedVersion, Map.of(), Map.of(), Map.of());
    }

    /// Resolves a requested version and all transitive dependencies using one complete exact-artifact snapshot.
    ///
    /// This compatibility facade derives a single-source winner map from the currently loaded manager. Aggregate
    /// catalog callers should construct {@link PluginStoreDependencyResolver} directly with their snapshot winners.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @param installedArtifactIdentities exact current artifact for every installed manifest
    /// @param reusableInstalledArtifacts exact installed artifacts approved for reuse during planning
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            Map<String, PluginManifest> installedManifests,
            @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifactIdentities,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalledArtifacts
    ) throws IOException {
        @Nullable SourceContext sourceContext = context;
        if (sourceContext == null) {
            throw new IOException("Plugin Store source is not loaded");
        }
        Map<String, PluginStoreItem> winningItems = new LinkedHashMap<>();
        for (PluginStoreItem item : getStoreItems(sourceContext)) {
            winningItems.putIfAbsent(item.getEntry().getId(), item);
        }
        return new PluginStoreDependencyResolver(winningItems, List.of(sourceContext.source)).resolveInstallPlan(
                pluginId,
                requestedVersion,
                installedManifests,
                installedArtifactIdentities,
                reusableInstalledArtifacts
        );
    }

    /// Downloads a package to a temporary file, validates size and SHA-256, then atomically replaces `pluginId.npl`.
    ///
    /// @param pluginId validated plugin ID
    /// @param version remote version metadata
    /// @param targetDirectory installed plugin directory
    /// @return verified installed package path
    /// @throws IOException if compatibility, transport, size, checksum, or replacement fails
    public Path downloadPlugin(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            Path targetDirectory
    ) throws IOException {
        PluginStoreArtifact artifact = version.requireArtifact(compatibilityEvaluator.getHostPlatform());
        return downloadPluginToFile(
                pluginId,
                version,
                artifact,
                targetDirectory.resolve(pluginId + ".npl"),
                null
        ).stagedPath();
    }

    /// Downloads and fully validates a package in a staging directory without touching installed files.
    ///
    /// The stable checksum prefix makes each selected version deterministic while keeping untrusted version text out
    /// of file names. Callers can download an entire dependency plan here before publishing any package.
    ///
    /// @param pluginId validated plugin ID
    /// @param version selected remote version metadata
    /// @param stagingDirectory isolated staging directory
    /// @return verified staged package path
    /// @throws IOException if compatibility, transport, or package verification fails
    public Path downloadPluginToStaging(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            Path stagingDirectory
    ) throws IOException {
        PluginStoreArtifact artifact = version.requireArtifact(compatibilityEvaluator.getHostPlatform());
        String checksumPrefix = artifact.sha256().substring(0, 12).toLowerCase(Locale.ROOT);
        return downloadPluginToFile(
                pluginId,
                version,
                artifact,
                stagingDirectory.resolve(pluginId + "-" + checksumPrefix + ".npl"),
                null
        ).stagedPath();
    }

    /// Downloads and fully validates one source-bound package while retaining concrete installation proof.
    ///
    /// The supplied version must be the exact instance published by the manager's captured source generation.
    /// The returned proof records verified installation evidence only and does not authorize package execution.
    ///
    /// @param pluginId validated plugin ID
    /// @param version exact source-bound remote version metadata
    /// @param stagingDirectory isolated staging directory
    /// @return normalized staged path, exact artifact identity and size, and source-derived proof when applicable
    /// @throws IOException if source binding, compatibility, transport, or package verification fails
    public PluginVerifiedDownload downloadPluginToStagingWithProof(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            Path stagingDirectory
    ) throws IOException {
        SourceContext sourceContext = requireContext();
        @Nullable PluginStoreRegistry.PluginStoreEntry entry = sourceContext.registry.findPlugin(pluginId);
        if (entry == null) {
            throw new IOException("Selected plugin is absent from the captured source generation");
        }
        @Nullable CachedManifest cachedManifest = sourceContext.manifestCache.get(entry.getManifestUrl());
        if (cachedManifest == null
                || cachedManifest.manifest().getVersion(version.getVersion()) != version) {
            throw new IOException("Selected plugin version is not bound to the captured source generation");
        }
        PluginStoreArtifact artifact = version.requireArtifact(compatibilityEvaluator.getHostPlatform());
        PluginTrustResult proofTrust;
        if (sourceContext.registryTrust.level() == PluginTrustLevel.OFFICIAL) {
            proofTrust = sourceContext.registryTrust;
        } else if (version.hasCertificationDeclaration()) {
            @Nullable String repositoryIdentity = sourceContext.repositoryIdentities.get(entry.getManifestUrl());
            RepositoryAttestationResolution repositoryResolution = resolveRepositoryAttestation(
                    sourceContext,
                    cachedManifest.manifest(),
                    repositoryIdentity,
                    version
            );
            proofTrust = evaluateCertifiedVersion(
                    cachedManifest.manifest(),
                    repositoryIdentity,
                    repositoryResolution,
                    version,
                    artifact
            );
        } else {
            proofTrust = PluginTrustResult.community();
        }
        if (!proofTrust.canInstall()) {
            throw new IOException("Plugin trust verification rejected " + pluginId + ": " + proofTrust.detail());
        }
        if (proofTrust.level() == PluginTrustLevel.CERTIFIED
                && proofTrust.certificationReceipt() == null) {
            throw new IOException("Certified plugin has no complete installation receipt: " + pluginId);
        }
        DownloadProofContext proofContext = new DownloadProofContext(
                sourceContext,
                entry,
                cachedManifest,
                proofTrust
        );
        String checksumPrefix = artifact.sha256().substring(0, 12).toLowerCase(Locale.ROOT);
        return downloadPluginToFile(
                pluginId,
                version,
                artifact,
                stagingDirectory.resolve(pluginId + "-" + checksumPrefix + ".npl"),
                proofContext
        );
    }

    /// Downloads and validates a package before atomically publishing it to an explicit target file.
    ///
    /// @param pluginId validated plugin ID
    /// @param version selected remote version metadata
    /// @param artifact exact current-platform package metadata
    /// @param targetFile final package path
    /// @param proofContext captured proof inputs, or `null` for compatibility download APIs
    /// @return verified target path
    /// @throws IOException if compatibility, transport, size, checksum, metadata, or replacement fails
    private PluginVerifiedDownload downloadPluginToFile(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            PluginStoreArtifact artifact,
            Path targetFile,
            @Nullable DownloadProofContext proofContext
    ) throws IOException {
        PluginTrustResult currentTrust = proofContext == null
                ? refreshDownloadTrust(pluginId, version, artifact)
                : proofContext.trustDecision();
        if (!currentTrust.canInstall()) {
            throw new IOException("Plugin trust verification rejected " + pluginId + ": " + currentTrust.detail());
        }
        validateCompatibility(version);
        validateRemoteUrl(artifact.packageUrl(), "plugin package");

        Path normalizedTarget = targetFile.toAbsolutePath().normalize();
        @Nullable Path targetDirectory = normalizedTarget.getParent();
        if (targetDirectory == null) {
            throw new IOException("Plugin package target has no parent directory");
        }
        Files.createDirectories(targetDirectory);
        Path temporaryFile = targetDirectory.resolve(
                "." + pluginId + "-" + UUID.randomUUID() + ".download"
        );
        long declaredSize = artifact.size();
        if (declaredSize > MAX_PACKAGE_BYTES) {
            throw new IOException("Plugin package exceeds the maximum allowed size");
        }

        MessageDigest digest = createSha256();
        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        LOG.info("Downloading plugin " + pluginId + " v" + version.getVersion()
                + " from " + PluginSourceLabels.diagnosticUrl(artifact.packageUrl()));

        @Nullable HttpURLConnection connection = null;
        try {
            connection = packageConnectionFactory.open(artifact.packageUrl(), "plugin package");
            int responseCode = connection.getResponseCode();
            if (responseCode / 100 != 2) {
                throw new IOException("Plugin package request failed with HTTP " + responseCode);
            }

            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(temporaryFile))) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    totalBytes = Math.addExact(totalBytes, read);
                    if (totalBytes > MAX_PACKAGE_BYTES || totalBytes > declaredSize) {
                        throw new IOException("Plugin package exceeds its declared or maximum size");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Plugin package size overflow", exception);
        } catch (IOException exception) {
            Files.deleteIfExists(temporaryFile);
            throw exception;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        try {
            if (totalBytes != declaredSize) {
                throw new IOException("Plugin package size mismatch. Expected " + declaredSize + ", got " + totalBytes);
            }
            String actualHash = toHex(digest.digest());
            if (!actualHash.equals(artifact.sha256())) {
                throw new IOException("Plugin checksum mismatch. Expected " + artifact.sha256()
                        + ", got " + actualHash);
            }
            validateDownloadedPackage(temporaryFile, pluginId, version);
            PluginArtifactIdentity identity = new PluginArtifactIdentity(
                    pluginId,
                    version.getVersion(),
                    actualHash
            );
            @Nullable PluginInstallationTrustProof trustProof = proofContext == null
                    ? null
                    : proofContext.createAndVerifyProof(artifact, identity, totalBytes);
            try {
                Files.move(
                        temporaryFile,
                        normalizedTarget,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("Downloaded and verified plugin package: " + normalizedTarget);
            return new PluginVerifiedDownload(
                    normalizedTarget,
                    identity,
                    totalBytes,
                    trustProof
            );
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Uses the snapshot-assigned trust immediately before network bytes are accepted for installation.
    ///
    /// @param pluginId selected plugin ID
    /// @param version selected version
    /// @param artifact exact current-platform package metadata
    /// @return current exact-version trust
    private PluginTrustResult refreshDownloadTrust(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            PluginStoreArtifact artifact
    ) {
        @Nullable SourceContext sourceContext = context;
        if (sourceContext == null || sourceContext.registryTrust.level() == PluginTrustLevel.OFFICIAL
                || !version.hasCertificationDeclaration()) {
            return version.getTrust();
        }
        @Nullable PluginStoreRegistry.PluginStoreEntry entry = sourceContext.registry.findPlugin(pluginId);
        if (entry == null) {
            return PluginTrustResult.rejected("selected plugin is absent from its source registry");
        }
        @Nullable CachedManifest cachedManifest = sourceContext.manifestCache.get(entry.getManifestUrl());
        if (cachedManifest == null
                || cachedManifest.manifest().getVersion(version.getVersion()) != version) {
            return PluginTrustResult.rejected("selected plugin version is not bound to the current source generation");
        }
        PluginStoreManifest manifest = cachedManifest.manifest();
        @Nullable String repositoryIdentity = sourceContext.repositoryIdentities.get(entry.getManifestUrl());
        RepositoryAttestationResolution repositoryResolution = resolveRepositoryAttestation(
                sourceContext,
                manifest,
                repositoryIdentity,
                version
        );
        PluginTrustResult current = evaluateCertifiedVersion(
                manifest,
                repositoryIdentity,
                repositoryResolution,
                version,
                artifact
        );
        version.setTrust(current);
        return current;
    }

    /// Validates shared compatibility requirements and the store-only Java version before downloading a package.
    ///
    /// @param version remote version metadata
    /// @throws IOException if the current runtime is incompatible
    public void validateCompatibility(PluginStoreManifest.PluginVersionEntry version) throws IOException {
        PluginCompatibilityResult compatibility = compatibilityEvaluator.evaluate(
                version.toCompatibilityRequirements(),
                Metadata.VERSION
        );
        if (!compatibility.isCompatible()) {
            throw new IOException(compatibility.detail());
        }
        if (!version.getArtifacts().isEmpty()) {
            version.requireArtifact(compatibilityEvaluator.getHostPlatform());
        }

        String requiredJava = version.getRequiredJavaVersion();
        if (!requiredJava.isBlank()) {
            Matcher matcher = JAVA_VERSION_PATTERN.matcher(requiredJava);
            if (!matcher.find()) {
                throw new IOException("Invalid requiredJavaVersion: " + requiredJava);
            }
            int requiredFeature;
            try {
                requiredFeature = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid requiredJavaVersion: " + requiredJava, exception);
            }
            if (Runtime.version().feature() < requiredFeature) {
                throw new IOException("This plugin requires Java " + requiredFeature + " or newer");
            }
        }
    }

    /// Returns whether one remote version is compatible with the current launcher and Java runtime.
    ///
    /// @param version remote version metadata
    /// @return compatibility state
    public boolean isCompatible(PluginStoreManifest.PluginVersionEntry version) {
        try {
            validateCompatibility(version);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    /// Returns compatible published versions sorted from newest to oldest.
    ///
    /// @param manifest plugin repository manifest
    /// @return immutable compatible version list
    public @Unmodifiable List<PluginStoreManifest.PluginVersionEntry> getCompatibleVersions(
            PluginStoreManifest manifest
    ) {
        return manifest.getVersionsNewestFirst().stream()
                .filter(this::isCompatible)
                .toList();
    }

    /// Returns the newest compatible version from a repository manifest.
    ///
    /// @param manifest plugin repository manifest or `null`
    /// @return newest compatible version or `null`
    public @Nullable PluginStoreManifest.PluginVersionEntry getLatestCompatibleVersion(
            @Nullable PluginStoreManifest manifest
    ) {
        return manifest == null ? null : getCompatibleVersions(manifest).stream().findFirst().orElse(null);
    }

    /// Returns whether a remote version is newer than the installed plugin manifest.
    ///
    /// @param installed installed package manifest or `null`
    /// @param remoteVersion remote version or `null`
    /// @return whether an update is available
    public boolean hasUpdate(
            @Nullable PluginManifest installed,
            @Nullable PluginStoreManifest.PluginVersionEntry remoteVersion
    ) {
        return installed != null
                && remoteVersion != null
                && PluginVersion.compare(
                remoteVersion.getVersion(),
                installed.getVersion()
        ) > 0;
    }

    /// Compares two plugin versions using the shared semantic-version-compatible comparator.
    ///
    /// @param left first version
    /// @param right second version
    /// @return version ordering
    public static int compareVersion(String left, String right) {
        return PluginVersion.compare(left, right);
    }

    /// Returns the registry URL associated with the currently loaded source.
    ///
    /// @return loaded source URL
    /// @throws IllegalStateException if no source has loaded successfully
    public String getRegistryUrl() {
        return getSource().getUrl();
    }

    /// Returns the currently loaded registry.
    ///
    /// @return loaded registry, or `null` before the first successful source load
    public @Nullable PluginStoreRegistry getRegistry() {
        @Nullable SourceContext currentContext = context;
        return currentContext == null ? null : currentContext.registry;
    }

    /// Downloads and caches a bounded UTF-8 README from one source-bound store item.
    ///
    /// @param item source-bound item declaring the repository manifest
    /// @return README Markdown text, or an empty string when no URL is declared
    /// @throws IOException if transport, URL policy, response status, or size validation fails
    public String fetchReadme(PluginStoreItem item) throws IOException {
        if (item.getSourceManager() != this) {
            throw new IllegalArgumentException("Plugin store item belongs to a different source manager");
        }
        @Nullable PluginStoreManifest manifest = item.getManifest();
        if (manifest == null) {
            throw new IOException("Plugin store item has no resolved manifest: " + item.getEntry().getId());
        }
        @Nullable SourceContext sourceContext = item.getSourceContext();
        if (sourceContext == null) {
            throw new IllegalArgumentException("Plugin store item has no source context");
        }
        return fetchReadme(sourceContext, manifest);
    }

    /// Downloads and caches a bounded UTF-8 README through the legacy explicit-manifest compatibility API.
    ///
    /// This overload never uses a loaded source context because a manifest alone cannot prove which source
    /// produced it. Source-bound callers must use [#fetchReadme(PluginStoreItem)].
    ///
    /// @param manifest plugin repository manifest
    /// @return README Markdown text, or an empty string when no URL is declared
    /// @throws IOException if transport, URL policy, response status, or size validation fails
    public String fetchReadme(PluginStoreManifest manifest) throws IOException {
        return fetchReadme(null, manifest);
    }

    /// Downloads and caches a bounded UTF-8 README through one captured context.
    ///
    /// @param sourceContext captured source context, or `null` before any source loads
    /// @param manifest plugin repository manifest
    /// @return README Markdown text, or an empty string when no URL is declared
    /// @throws IOException if transport, URL policy, response status, or size validation fails
    private String fetchReadme(@Nullable SourceContext sourceContext, PluginStoreManifest manifest) throws IOException {
        String readmeUrl = manifest.getReadmeUrl();
        if (readmeUrl.isBlank()) {
            return "";
        }
        Map<String, String> readmeCache = sourceContext == null
                ? unloadedReadmeCache
                : sourceContext.readmeCache;
        @Nullable String cached = readmeCache.get(readmeUrl);
        if (cached != null) {
            return cached;
        }

        String readme = fetchBoundedUtf8(readmeUrl, "plugin README", MAX_README_BYTES).text();
        readmeCache.put(readmeUrl, readme);
        return readme;
    }

    /// Clears request caches for the currently published source context or the unloaded README compatibility cache.
    public void clearCache() {
        @Nullable SourceContext currentContext = context;
        if (currentContext == null) {
            unloadedReadmeCache.clear();
            return;
        }
        currentContext.manifestCache.clear();
        currentContext.readmeCache.clear();
    }

    /// Enforces HTTPS for remote hosts while allowing loopback HTTP registries used for local development.
    ///
    /// @param url URL to validate
    /// @param purpose value used in diagnostics
    /// @throws IOException if the URL is malformed or insecure
    static void validateRemoteUrl(String url, String purpose) throws IOException {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid " + purpose + " URL: " + PluginSourceLabels.diagnosticUrl(url), exception);
        }
        @Nullable String scheme = uri.getScheme();
        @Nullable String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IOException("Invalid " + purpose + " URL: " + PluginSourceLabels.diagnosticUrl(url));
        }
        boolean loopback = host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
        if (!scheme.equalsIgnoreCase("https") && !(scheme.equalsIgnoreCase("http") && loopback)) {
            throw new IOException("Insecure " + purpose + " URL is not allowed: " + PluginSourceLabels.diagnosticUrl(url));
        }
    }

    /// Downloads bounded UTF-8 text and revalidates the final URL after redirects.
    ///
    /// @param url initial remote URL
    /// @param purpose value used in diagnostics
    /// @param maximumBytes maximum accepted response bytes
    /// @return decoded UTF-8 response
    /// @throws IOException if URL policy, transport, status, or size validation fails
    private static BoundedUtf8Document fetchBoundedUtf8(
            String url,
            String purpose,
            int maximumBytes
    ) throws IOException {
        @Nullable HttpURLConnection connection = null;
        try {
            connection = openValidatedConnection(url, purpose);
            int responseCode = connection.getResponseCode();
            if (responseCode / 100 != 2) {
                throw new IOException(purpose + " request failed with HTTP " + responseCode);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > maximumBytes) {
                throw new IOException(purpose + " exceeds the maximum allowed size");
            }
            byte @Unmodifiable [] bytes;
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                bytes = input.readNBytes(maximumBytes + 1);
            }
            if (bytes.length > maximumBytes) {
                throw new IOException(purpose + " exceeds the maximum allowed size");
            }
            return BoundedUtf8Document.fromRemote(bytes, purpose);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /// Immutable exact byte sequence and its strict UTF-8 decoding from one bounded response.
    @NotNullByDefault
    private static final class BoundedUtf8Document {
        /// Exact response bytes retained without decoding replacement.
        private final byte @Unmodifiable [] exactBytes;

        /// Strict decoded UTF-8 text corresponding byte-for-byte to [#exactBytes].
        private final String text;

        /// Retains one exact byte sequence and its strict decoding.
        ///
        /// @param exactBytes exact bounded response bytes
        /// @param text strict UTF-8 decoding
        private BoundedUtf8Document(byte @Unmodifiable [] exactBytes, String text) {
            this.exactBytes = exactBytes.clone();
            this.text = Objects.requireNonNull(text, "text");
        }

        /// Strictly decodes untrusted response bytes without replacement characters.
        ///
        /// @param exactBytes exact bounded response bytes
        /// @param purpose document description for diagnostics
        /// @return immutable exact response and decoded text
        /// @throws IOException if the bytes are not valid UTF-8
        private static BoundedUtf8Document fromRemote(
                byte @Unmodifiable [] exactBytes,
                String purpose
        ) throws IOException {
            try {
                String decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(exactBytes))
                        .toString();
                return new BoundedUtf8Document(exactBytes, decoded);
            } catch (CharacterCodingException exception) {
                throw new IOException(purpose + " is not valid UTF-8", exception);
            }
        }

        /// Encodes trusted discovery text for the common atomic manifest cache.
        ///
        /// @param text already decoded discovery manifest text
        /// @return immutable byte and text pair
        private static BoundedUtf8Document fromTrustedText(String text) {
            return new BoundedUtf8Document(text.getBytes(StandardCharsets.UTF_8), text);
        }

        /// Returns a defensive copy of the exact response bytes.
        ///
        /// @return exact bounded response bytes
        private byte @Unmodifiable [] exactBytes() {
            return exactBytes.clone();
        }

        /// Returns the strict UTF-8 decoding.
        ///
        /// @return decoded response text
        private String text() {
            return text;
        }
    }

    /// Opens a GET connection while validating every redirect target before any request is sent to that target.
    ///
    /// @param initialUrl initial request URL
    /// @param purpose value used in diagnostics
    /// @return connected response at the final validated URL
    /// @throws IOException if URL policy, redirect syntax, or redirect depth validation fails
    private static HttpURLConnection openValidatedConnection(String initialUrl, String purpose) throws IOException {
        URI currentUrl = parseRemoteUri(initialUrl, purpose);
        URI firstUrl = currentUrl;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validateRemoteUrl(currentUrl.toString(), redirect == 0 ? purpose : purpose + " redirect");
            if (redirect > 0) {
                validateRedirectTarget(firstUrl, currentUrl, purpose);
            }
            HttpURLConnection connection = HttpRequest.GET(currentUrl.toString()).createConnection();
            connection.setInstanceFollowRedirects(false);
            int responseCode = connection.getResponseCode();
            if (!isRedirect(responseCode)) {
                return connection;
            }

            @Nullable String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.isBlank()) {
                throw new IOException(purpose + " redirect has no Location header");
            }
            if (redirect == MAX_REDIRECTS) {
                throw new IOException(purpose + " has too many redirects");
            }
            try {
                currentUrl = currentUrl.resolve(new URI(location));
            } catch (IllegalArgumentException | URISyntaxException exception) {
                throw new IOException("Invalid " + purpose + " redirect URL: "
                        + PluginSourceLabels.diagnosticUrl(location), exception);
            }
        }
        throw new IOException(purpose + " has too many redirects");
    }

    /// Enforces that only an explicitly local development request may redirect to loopback HTTP.
    ///
    /// Remote HTTPS chains must remain HTTPS and cannot redirect to a loopback host.
    ///
    /// @param initialUrl first URL in the request chain
    /// @param redirectUrl validated redirect target
    /// @param purpose value used in diagnostics
    /// @throws IOException if a remote chain is downgraded or redirected to loopback
    static void validateRedirectTarget(URI initialUrl, URI redirectUrl, String purpose) throws IOException {
        if (!isLoopbackHttp(initialUrl)
                && (!"https".equalsIgnoreCase(redirectUrl.getScheme())
                || isLoopbackHost(redirectUrl.getHost()))) {
            throw new IOException("Remote " + purpose + " cannot redirect to a local or insecure URL: "
                    + PluginSourceLabels.diagnosticUrl(redirectUrl.toString()));
        }
    }

    /// Returns whether an HTTP response code represents a redirect followed by the store client.
    ///
    /// @param responseCode HTTP response code
    /// @return whether a Location redirect should be followed
    private static boolean isRedirect(int responseCode) {
        return responseCode >= 300 && responseCode <= 308 && responseCode != 304 && responseCode != 306;
    }

    /// Parses and structurally validates a remote URI before redirect resolution.
    ///
    /// @param url URL text
    /// @param purpose value used in diagnostics
    /// @return parsed URI
    /// @throws IOException if the URL is malformed
    private static URI parseRemoteUri(String url, String purpose) throws IOException {
        try {
            return new URI(url);
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid " + purpose + " URL: " + PluginSourceLabels.diagnosticUrl(url), exception);
        }
    }

    /// Returns whether a URI is an explicitly local HTTP endpoint used for plugin-store development.
    ///
    /// @param uri parsed URI
    /// @return whether the URI uses HTTP and a loopback host
    private static boolean isLoopbackHttp(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost());
    }

    /// Returns whether a nullable URI host is one of the accepted loopback spellings.
    ///
    /// @param host URI host or `null`
    /// @return whether the host is loopback
    private static boolean isLoopbackHost(@Nullable String host) {
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]"));
    }

    /// Creates a SHA-256 message digest.
    ///
    /// @return digest instance
    /// @throws IOException if SHA-256 is unavailable
    private static MessageDigest createSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    /// Validates the internal package manifest before replacing a working installed package.
    ///
    /// @param packageFile verified temporary `.npl` file
    /// @param expectedPluginId plugin ID from the registry
    /// @param expectedVersion complete remote version metadata
    /// @throws IOException if package identity, version, schema, permissions, required permissions, dependencies,
    /// runtime, ABI, platforms, or launcher version differ from the selected metadata
    private static void validateDownloadedPackage(
            Path packageFile,
            String expectedPluginId,
            PluginStoreManifest.PluginVersionEntry expectedVersion
    ) throws IOException {
        try (ZipFile zipFile = new ZipFile(packageFile.toFile())) {
            @Nullable ZipEntry manifestEntry = zipFile.getEntry("plugin.json");
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                throw new IOException("Downloaded package has no plugin.json");
            }
            if (manifestEntry.getSize() > MAX_PLUGIN_MANIFEST_BYTES) {
                throw new IOException("Downloaded package manifest is too large");
            }

            byte @Unmodifiable [] manifestBytes;
            try (InputStream input = zipFile.getInputStream(manifestEntry)) {
                manifestBytes = input.readNBytes(MAX_PLUGIN_MANIFEST_BYTES + 1);
            }
            if (manifestBytes.length > MAX_PLUGIN_MANIFEST_BYTES) {
                throw new IOException("Downloaded package manifest is too large");
            }

            PluginManifest packageManifest = PluginManifest.fromJson(new java.io.StringReader(
                    new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8)
            ));
            if (!expectedPluginId.equals(packageManifest.getId())) {
                throw new IOException("Downloaded package ID " + packageManifest.getId()
                        + " does not match registry entry " + expectedPluginId);
            }
            if (!packageManifest.getVersion().equals(expectedVersion.getVersion())) {
                throw new IOException("Downloaded package version " + packageManifest.getVersion()
                        + " does not match selected version " + expectedVersion.getVersion());
            }
            if (packageManifest.getSchemaVersion() != expectedVersion.getPluginApiVersion()) {
                throw new IOException("Downloaded package schemaVersion " + packageManifest.getSchemaVersion()
                        + " does not match pluginApiVersion " + expectedVersion.getPluginApiVersion());
            }
            if (!packageManifest.getRuntime().equals(expectedVersion.getRuntime())) {
                throw new IOException("Downloaded package runtime does not match selected version metadata");
            }
            if (packageManifest.getAbi() != expectedVersion.getAbi()) {
                throw new IOException("Downloaded package ABI does not match selected version metadata");
            }
            if (!packageManifest.getPlatforms().equals(expectedVersion.getPlatforms())) {
                throw new IOException("Downloaded package platforms do not match selected version metadata");
            }
            if (packageManifest.getPluginKind() != expectedVersion.getPluginKind()) {
                throw new IOException("Downloaded package pluginKind does not match selected version metadata");
            }
            if (expectedVersion.getPluginApiVersion() >= 3
                    && !new HashSet<>(packageManifest.getPermissions())
                    .equals(new HashSet<>(expectedVersion.getPermissions()))) {
                throw new IOException("Downloaded package permissions do not match selected version metadata");
            }
            if (expectedVersion.getPluginApiVersion() >= 3
                    && !new HashSet<>(packageManifest.getRequiredPermissions())
                    .equals(new HashSet<>(expectedVersion.getRequiredPermissions()))) {
                throw new IOException("Downloaded package requiredPermissions do not match selected version metadata");
            }
            if (!packageManifest.getLauncherVersion().equals(expectedVersion.getLauncherVersion())) {
                throw new IOException("Downloaded package launcherVersion does not match selected version metadata");
            }
            if (expectedVersion.hasAuthoritativeDependencies()
                    && !new HashSet<>(packageManifest.getPluginDependencies())
                    .equals(new HashSet<>(expectedVersion.getDependencies()))) {
                throw new IOException("Downloaded package dependencies do not match selected version metadata");
            }
        }
    }

    /// Converts digest bytes to lower-case hexadecimal text.
    ///
    /// @param bytes digest bytes
    /// @return hexadecimal digest
    private static String toHex(byte @Unmodifiable [] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
