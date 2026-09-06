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
package org.jackhuang.hmcl.plugin.ui.frontend;

import org.jackhuang.hmcl.plugin.PluginKind;
import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Enumerates installed UI-provider packages and resolves their verified native descriptors.
///
/// The provider never invents an installation or trusts a stale extracted directory: each call re-reads the published
/// package, verifies its exact SHA-256 against the manifest identity, prepares the immutable content-addressed
/// extraction, and returns a descriptor whose executable was part of that verified inventory.
@NotNullByDefault
public final class UiFrontendProvider {
    /// Manager whose installed packages and launcher-owned cache roots are enumerated.
    private final PluginManager pluginManager;

    /// Launcher-owned cache root used for verified UI-provider extraction.
    private final Path packageRoot;

    /// Creates one provider bound to the process plugin manager.
    ///
    /// @param pluginManager trusted launcher-side plugin manager
    /// @param packageRoot launcher-owned extraction root
    public UiFrontendProvider(PluginManager pluginManager, Path packageRoot) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        this.packageRoot = Objects.requireNonNull(packageRoot, "packageRoot").toAbsolutePath().normalize();
    }

    /// Returns the always-available built-in JavaFX descriptor.
    ///
    /// @return immutable JavaFX descriptor
    public UiFrontendDescriptor builtInFrontend() {
        return UiFrontendDescriptor.javafx();
    }

    /// Enumerates every installed UI-provider package ordered by plugin ID.
    ///
    /// Damaged or unreadable packages are skipped because the settings UI must still be able to present the built-in
    /// frontend; the plugin management UI owns and reports their exact runtime diagnostics.
    ///
    /// @return immutable descriptors indexed by exact plugin ID
    public @Unmodifiable Map<String, UiFrontendDescriptor> installedFrontends() {
        Map<String, UiFrontendDescriptor> frontends = new LinkedHashMap<>();
        frontends.put(UiFrontendDescriptor.JAVAFX_ID, UiFrontendDescriptor.javafx());
        try {
            for (PluginManifest manifest : pluginManager.getInstalledManifests().values()) {
                if (manifest.getPluginKind() != PluginKind.UI_PROVIDER) {
                    continue;
                }
                @Nullable UiFrontendDescriptor descriptor = resolveDescriptor(manifest);
                if (descriptor != null) {
                    frontends.put(descriptor.getId(), descriptor);
                }
            }
        } catch (IOException exception) {
            // A failed repository listing must not remove the built-in fallback.
        }
        return Map.copyOf(frontends);
    }

    /// Resolves one exact installed UI-provider package into a verified descriptor.
    ///
    /// @param pluginId exact installed plugin ID
    /// @return verified descriptor
    /// @throws IOException if the package is absent, not a UI provider, or cannot be verified
    public UiFrontendDescriptor resolveFrontend(String pluginId) throws IOException {
        PluginManifest manifest = Objects.requireNonNull(
                pluginManager.getInstalledManifests().get(pluginId),
                "Installed UI provider: " + pluginId
        );
        if (manifest.getPluginKind() != PluginKind.UI_PROVIDER) {
            throw new IOException("Installed plugin is not a UI provider: " + pluginId);
        }
        UiFrontendDescriptor descriptor = resolveDescriptor(manifest);
        if (descriptor == null) {
            throw new IOException("Installed UI provider cannot be verified: " + pluginId);
        }
        return descriptor;
    }

    /// Verifies one exact manifest and extracts its launcher-selectable executable.
    ///
    /// @param manifest validated installed UI-provider manifest
    /// @return descriptor, or `null` when this package cannot currently be verified
    private @Nullable UiFrontendDescriptor resolveDescriptor(PluginManifest manifest) {
        try {
            Path nplFile = installedPackage(manifest.getId());
            String sha256 = PluginPackageVersions.calculateSha256(nplFile);
            VerifiedPluginPackage verified = PluginPackageVersions.prepareVerifiedLifecyclePackage(
                    nplFile,
                    packageRoot,
                    org.jackhuang.hmcl.plugin.PluginArtifactIdentity.of(manifest, sha256)
            );
            verified.verifyIntegrity();
            Path executable = verified.resolveVerifiedFile(manifest.getEntrypoint());
            if (!Files.isRegularFile(executable)) {
                return null;
            }
            return UiFrontendDescriptor.nativeFrontend(
                    manifest.getId(),
                    manifest.getName(),
                    executable
            );
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /// Resolves the stable published package owned by one exact installed plugin ID.
    ///
    /// Installation publication uses `<pluginId>.npl` inside the launcher-owned plugins directory; the complete
    /// SHA-256 verification performed by the caller still has to match the manifest's exact published artifact.
    ///
    /// @param pluginId exact plugin ID
    /// @return installed `.npl` package
    /// @throws IOException if the stable path is missing, irregular, symbolic, or escapes its directory
    private Path installedPackage(String pluginId) throws IOException {
        Path pluginsDirectory = pluginManager.getPluginsDirectory().toAbsolutePath().normalize();
        Path nplFile = pluginsDirectory.resolve(pluginId + ".npl").toAbsolutePath().normalize();
        if (!pluginsDirectory.equals(nplFile.getParent())
                || !Files.isRegularFile(nplFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(nplFile)) {
            throw new IOException("Installed UI-provider package is unavailable: " + pluginId);
        }
        return nplFile;
    }
}
