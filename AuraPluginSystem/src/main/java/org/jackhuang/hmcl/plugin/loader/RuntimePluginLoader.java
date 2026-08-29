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
package org.jackhuang.hmcl.plugin.loader;

import org.jackhuang.hmcl.plugin.Plugin;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadContext;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadHandle;
import org.jackhuang.hmcl.plugin.runtime.RuntimeBridgeTransport;
import org.jackhuang.hmcl.plugin.runtime.RuntimeSupervisor;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/// Loads schema-v5 non-JVM payloads through their selected ready runtime Provider.
@NotNullByDefault
public final class RuntimePluginLoader implements PluginLoader {
    /// Provider lifecycle and payload delegation owner.
    private final RuntimeSupervisor supervisor;

    /// Resolves the persistent private data directory for one dependent plugin ID.
    private final Function<String, Path> dataDirectoryResolver;

    /// Resolves the current opaque capability-token supplier for one dependent plugin ID.
    private final Function<String, Supplier<PluginCapabilityToken>> capabilityTokenResolver;

    /// Launcher-owned authority used to retain manifest Patch declarations at the payload boundary.
    private final PluginPermissionAuthority permissionAuthority;

    /// Launcher-owned Runtime Bridge transport inserted into every exact payload context.
    private final RuntimeBridgeTransport bridgeTransport;

    /// Creates a Provider-backed external payload loader.
    ///
    /// @param supervisor Provider lifecycle owner
    /// @param dataDirectoryResolver dependent data-directory resolver
    /// @param capabilityTokenResolver dependent capability-authority resolver
    /// @param permissionAuthority launcher-owned capability verifier
    public RuntimePluginLoader(
            RuntimeSupervisor supervisor,
            Function<String, Path> dataDirectoryResolver,
            Function<String, Supplier<PluginCapabilityToken>> capabilityTokenResolver,
            PluginPermissionAuthority permissionAuthority
    ) {
        this(
                supervisor,
                dataDirectoryResolver,
                capabilityTokenResolver,
                permissionAuthority,
                RuntimeBridgeTransport.unavailable()
        );
    }

    /// Creates a Provider-backed external payload loader with production Bridge transport wiring.
    ///
    /// @param supervisor Provider lifecycle owner
    /// @param dataDirectoryResolver dependent data-directory resolver
    /// @param capabilityTokenResolver dependent capability-authority resolver
    /// @param permissionAuthority launcher-owned capability verifier
    /// @param bridgeTransport launcher-owned raw-byte Runtime Bridge transport
    public RuntimePluginLoader(
            RuntimeSupervisor supervisor,
            Function<String, Path> dataDirectoryResolver,
            Function<String, Supplier<PluginCapabilityToken>> capabilityTokenResolver,
            PluginPermissionAuthority permissionAuthority,
            RuntimeBridgeTransport bridgeTransport
    ) {
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.dataDirectoryResolver = Objects.requireNonNull(dataDirectoryResolver, "dataDirectoryResolver");
        this.capabilityTokenResolver = Objects.requireNonNull(capabilityTokenResolver, "capabilityTokenResolver");
        this.permissionAuthority = Objects.requireNonNull(permissionAuthority, "permissionAuthority");
        this.bridgeTransport = Objects.requireNonNull(bridgeTransport, "bridgeTransport");
    }

    /// Verifies one exact external payload and delegates loading through its selected ready Provider.
    ///
    /// @param manifest validated schema-v5 external-runtime manifest
    /// @param pluginPackage exact verified package inventory
    /// @param nplFile original package artifact
    /// @return lifecycle adapter around the opaque Provider payload handle
    /// @throws IOException if the package, binding, Provider state, or payload loading fails
    @Override
    public Plugin load(
            PluginManifest manifest,
            VerifiedPluginPackage pluginPackage,
            Path nplFile
    ) throws IOException {
        if (PluginRuntimeTypes.JAVA.equals(manifest.getRuntime())) {
            throw new IOException("Java and Kotlin packages must use the built-in Java plugin loader");
        }
        if (!pluginPackage.getIdentity().equals(
                org.jackhuang.hmcl.plugin.PluginArtifactIdentity.of(
                        manifest,
                        PluginPackageVersions.calculateSha256(nplFile)
                ))) {
            throw new IOException("Runtime payload package changed during loading: " + nplFile);
        }
        pluginPackage.verifyIntegrity();
        if (!pluginPackage.containsResource(manifest.getEntrypoint())) {
            throw new IOException("Runtime payload entry point is not present in the verified package: "
                    + manifest.getEntrypoint());
        }
        String pluginId = manifest.getId();
        Path dataDirectory = Objects.requireNonNull(dataDirectoryResolver.apply(pluginId), "dataDirectory");
        Supplier<PluginCapabilityToken> capabilityTokenSupplier = Objects.requireNonNull(
                capabilityTokenResolver.apply(pluginId),
                "capabilityTokenSupplier"
        );
        RuntimePayloadContext payloadContext = new RuntimePayloadContext(
                pluginPackage.getIdentity(),
                pluginPackage.getDirectory(),
                manifest.getEntrypoint(),
                manifest.getExecutionMode(),
                dataDirectory,
                capabilityTokenSupplier,
                bridgeTransport
        );
        RuntimePayloadHandle handle = supervisor.loadPayload(pluginId, payloadContext);
        try {
            if (!manifest.getPatches().isEmpty()) {
                supervisor.retainPatchEndpoint(
                        handle,
                        pluginPackage.getIdentity(),
                        manifest.getExecutionMode(),
                        permissionAuthority,
                        capabilityTokenSupplier,
                        manifest.getPatches()
                );
            }
        } catch (IOException | RuntimeException | Error exception) {
            try {
                supervisor.unloadPayload(handle);
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        return new ProviderPayloadPlugin(manifest, supervisor, handle);
    }

    /// Adapts the Java plugin lifecycle boundary to opaque Provider payload operations.
    @NotNullByDefault
    private static final class ProviderPayloadPlugin implements Plugin {
        /// Authoritative dependent manifest.
        private final PluginManifest manifest;

        /// Provider lifecycle and payload delegation owner.
        private final RuntimeSupervisor supervisor;

        /// Opaque Provider-issued payload handle.
        private final RuntimePayloadHandle handle;

        /// Creates one loaded disabled payload lifecycle adapter.
        ///
        /// @param manifest authoritative dependent manifest
        /// @param supervisor Provider lifecycle owner
        /// @param handle Provider-issued payload handle
        private ProviderPayloadPlugin(
                PluginManifest manifest,
                RuntimeSupervisor supervisor,
                RuntimePayloadHandle handle
        ) {
            this.manifest = manifest;
            this.supervisor = supervisor;
            this.handle = handle;
        }

        /// Accepts the manager-owned context after Provider payload loading already captured exact package authority.
        ///
        /// @param context manager-owned plugin context
        @Override
        public void onLoad(PluginContext context) {
            if (!manifest.getId().equals(context.getManifest().getId())) {
                throw new IllegalArgumentException("Runtime payload context belongs to another plugin");
            }
        }

        /// Enables the Provider-owned payload.
        @Override
        public void onEnable() {
            try {
                supervisor.enablePayload(handle);
            } catch (IOException exception) {
                throw new UncheckedIOException("Failed to enable runtime payload: " + manifest.getId(), exception);
            }
        }

        /// Disables the Provider-owned payload while retaining its loaded resources.
        @Override
        public void onDisable() {
            try {
                supervisor.disablePayload(handle);
            } catch (IOException exception) {
                throw new UncheckedIOException("Failed to disable runtime payload: " + manifest.getId(), exception);
            }
        }

        /// Unloads the Provider-owned payload and releases its binding.
        @Override
        public void onUnload() {
            try {
                supervisor.unloadPayload(handle);
            } catch (IOException exception) {
                throw new UncheckedIOException("Failed to unload runtime payload: " + manifest.getId(), exception);
            }
        }

        /// Returns the authoritative dependent manifest.
        ///
        /// @return plugin manifest
        @Override
        public PluginManifest getManifest() {
            return manifest;
        }
    }
}
