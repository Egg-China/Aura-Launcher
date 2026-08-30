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
package org.jackhuang.hmcl.plugin;

import org.jackhuang.hmcl.FXThreadTestSupport;
import org.jackhuang.hmcl.plugin.patch.PluginInstrumentation;
import org.jackhuang.hmcl.plugin.patch.PluginPatchCallback;
import org.jackhuang.hmcl.plugin.patch.PluginPatchFailure;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Java Patch declaration registration, diagnostics, callback scope, and lifecycle-owned cleanup.
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@NotNullByDefault
public final class PluginManagerPatchLifecycleTest {
    /// Exact target used by the package fixture's successful `before` declaration.
    private static final String FIXTURE_TARGET = "org.jackhuang.hmcl.patchfixture.PatchTargetFixture";

    /// Clears process-global package probes after every assertion.
    @AfterEach
    public void clearFixtureState() {
        PackagedPatchPlugin.reset();
    }

    /// Registers an enabled package declaration and invokes its callback under the package TCCL.
    ///
    /// @param temporaryDirectory isolated launcher and source-package directory
    /// @throws Exception if package installation, discovery, or callback execution fails
    @Test
    public void registerDeclarationAfterEnableAndRunCallbackWithPluginTccl(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        String pluginId = "dev.aura.test.patch-lifecycle-active";
        RecordingPatchRegistrar registrar = new RecordingPatchRegistrar();
        Path localHome = installPackage(temporaryDirectory, pluginId, successfulPatchJson());
        PluginManager manager = new PluginManager(localHome, registrar);

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
        PluginContainer container = Objects.requireNonNull(manager.getPlugin(pluginId));
        try {
            @Unmodifiable List<PluginContainer.PatchDeclarationStatus> statuses =
                    container.getPatchDeclarationStatuses();
            assertTrue(container.isEnabled());
            assertEquals(1, statuses.size());
            assertEquals(PluginContainer.PatchDeclarationState.ACTIVE, statuses.get(0).state());
            assertNull(statuses.get(0).failureCategory());
            assertThrows(UnsupportedOperationException.class, statuses::clear);

            RecordingPatchRegistrar.Registration registration = registrar.onlyRegistration();
            assertEquals(pluginId, registrar.lastArtifactIdentity().getPluginId());
            assertEquals(Set.of(), registrar.lastDependencyIds());
            assertEquals(Set.of(PluginPermission.LAUNCHER_PATCH), registrar.lastPermissions());
            PluginPatchResult result = registration.invoke(PluginPatchInvocation.before(
                    statuses.get(0).declaration(),
                    new Object(),
                    List.of("original", 4)
            ));

            assertEquals(PluginPatchResult.Action.ARGUMENTS, result.action());
            assertEquals(List.of("patched", 4), result.arguments());
            assertTrue(PackagedPatchPlugin.patchCallbackUsedPluginClassLoader());
        } finally {
            manager.unloadPlugin(pluginId);
        }
    }

    /// Keeps the plugin enabled while exposing an unavailable-engine status when premain did not publish Patch.
    ///
    /// @param temporaryDirectory isolated launcher and source-package directory
    /// @throws Exception if package installation or discovery fails
    @Test
    public void failClosedPerDeclarationWhenPatchInstrumentationIsUnavailable(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        String pluginId = "dev.aura.test.patch-lifecycle-unavailable";
        Path localHome = installPackage(temporaryDirectory, pluginId, successfulPatchJson());
        PluginManager manager = new PluginManager(localHome);

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
        PluginContainer container = Objects.requireNonNull(manager.getPlugin(pluginId));
        try {
            PluginContainer.PatchDeclarationStatus status = container.getPatchDeclarationStatuses().get(0);
            assertTrue(container.isEnabled());
            assertEquals(PluginRuntimeStatus.ENABLED, manager.getPluginRuntimeStatus(pluginId));
            assertEquals(PluginContainer.PatchDeclarationState.FAILED, status.state());
            assertEquals(PluginPatchFailure.Category.UNAVAILABLE_ENGINE, status.failureCategory());
        } finally {
            manager.unloadPlugin(pluginId);
        }
    }

    /// Rejects direct use of the process-wide registration facade outside the exact Plugin Manager class.
    ///
    /// @throws IOException if the fixture manifest cannot be parsed
    @Test
    public void restrictInstrumentationRegistrationFacadeToPluginManager() throws IOException {
        PluginPatchDeclaration declaration = manifest(
                "dev.aura.test.patch-facade-caller",
                successfulPatchJson()
        ).getPatches().get(0);

        assertThrows(SecurityException.class, () -> PluginInstrumentation.registerFromPluginManager(
                new PluginArtifactIdentity(
                        "dev.aura.test.patch-facade-caller",
                        "1.0.0",
                        "b".repeat(64)
                ),
                Set.of(),
                Set.of(PluginPermission.LAUNCHER_PATCH),
                declaration,
                invocation -> PluginPatchResult.unchanged()
        ));
    }

    /// Rejects a missing exact-artifact Patch grant without disabling an already loaded Java lifecycle.
    ///
    /// @param temporaryDirectory isolated manager, package, and storage paths
    /// @throws Exception if manifest parsing, registration, or lifecycle activation fails
    @Test
    public void isolateMissingPatchPermissionFromPluginEnablement(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        String pluginId = "dev.aura.test.patch-lifecycle-denied";
        PluginManifest manifest = manifest(pluginId, successfulPatchJson());
        RecordingPatchRegistrar registrar = new RecordingPatchRegistrar();
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"), registrar);
        PluginContainer container = registerPrepared(
                manager,
                temporaryDirectory,
                manifest,
                new PackagedPatchPlugin(),
                PluginManager.class.getClassLoader(),
                Set::of
        );

        assertEquals(
                PluginContainer.PatchDeclarationState.PENDING,
                container.getPatchDeclarationStatuses().get(0).state()
        );
        assertTrue(manager.enablePlugin(pluginId));
        try {
            PluginContainer.PatchDeclarationStatus status = container.getPatchDeclarationStatuses().get(0);
            assertTrue(container.isEnabled());
            assertEquals(PluginContainer.PatchDeclarationState.FAILED, status.state());
            assertEquals(PluginPatchFailure.Category.PERMISSION_DENIED, status.failureCategory());
            assertEquals(0, registrar.attempts());
        } finally {
            manager.unloadPlugin(pluginId);
        }
    }

    /// Closes every active Patch registration before invoking the plugin's disable callback.
    ///
    /// @param temporaryDirectory isolated launcher and source-package directory
    /// @throws Exception if package installation, discovery, or disablement fails
    @Test
    public void closePatchRegistrationsBeforePluginDisableCallback(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        String pluginId = "dev.aura.test.patch-lifecycle-disable";
        RecordingPatchRegistrar registrar = new RecordingPatchRegistrar();
        Path localHome = installPackage(temporaryDirectory, pluginId, successfulPatchJson());
        PluginManager manager = new PluginManager(localHome, registrar);
        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
        PluginContainer container = Objects.requireNonNull(manager.getPlugin(pluginId));
        RecordingPatchRegistrar.Registration registration = registrar.onlyRegistration();
        PackagedPatchPlugin.reset();

        manager.disablePlugin(pluginId);

        assertFalse(registration.isActive());
        assertEquals(List.of("patch-closed", "onDisable"), PackagedPatchPlugin.events());
        assertEquals(
                PluginContainer.PatchDeclarationState.RESTORED,
                container.getPatchDeclarationStatuses().get(0).state()
        );
        PluginPatchFailure lifecycleFailure = assertThrows(
                PluginPatchFailure.class,
                () -> registration.invokeRetainedCallback(PluginPatchInvocation.before(
                        container.getManifest().getPatches().get(0),
                        new Object(),
                        List.of("original", 4)
                ))
        );
        assertEquals(PluginPatchFailure.Category.LIFECYCLE_REVOKED, lifecycleFailure.category());
        manager.unloadPlugin(pluginId);
    }

    /// Releases the registration-held callback lease so unload can close the dedicated class loader.
    ///
    /// @param temporaryDirectory isolated manager and installed-package directory
    /// @throws Exception if package creation, registration, enablement, or unload fails
    @Test
    public void releasePatchLeaseBeforeClosingPluginClassLoaderOnUnload(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        String pluginId = "dev.aura.test.patch-lifecycle-loader";
        PluginManifest manifest = manifest(pluginId, successfulPatchJson());
        RecordingPatchRegistrar registrar = new RecordingPatchRegistrar();
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"), registrar);
        writePluginPackage(manager.getPluginsDirectory().resolve(pluginId + ".npl"), manifestJson(
                pluginId, successfulPatchJson()));
        TrackingClassLoader classLoader = new TrackingClassLoader();
        PluginContainer container = registerPrepared(
                manager,
                temporaryDirectory,
                manifest,
                new PackagedPatchPlugin(),
                classLoader,
                () -> Set.of(PluginPermission.LAUNCHER_PATCH)
        );

        assertTrue(manager.enablePlugin(pluginId));
        assertEquals(PluginContainer.PatchDeclarationState.ACTIVE,
                container.getPatchDeclarationStatuses().get(0).state());
        manager.unloadPlugin(pluginId);

        assertEquals(1, classLoader.closeCount());
        assertFalse(registrar.onlyRegistration().isActive());
        assertNull(manager.getPlugin(pluginId));
    }

    /// Keeps one successful declaration active when a neighboring declaration fails registration.
    ///
    /// @param temporaryDirectory isolated launcher and source-package directory
    /// @throws Exception if package installation or discovery fails
    @Test
    public void isolateOneFailedPatchDeclarationFromPluginAndNeighbors(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        String pluginId = "dev.aura.test.patch-lifecycle-isolation";
        RecordingPatchRegistrar registrar = new RecordingPatchRegistrar(Set.of("missing"));
        Path localHome = installPackage(temporaryDirectory, pluginId, twoPatchJson());
        PluginManager manager = new PluginManager(localHome, registrar);

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
        PluginContainer container = Objects.requireNonNull(manager.getPlugin(pluginId));
        try {
            @Unmodifiable List<PluginContainer.PatchDeclarationStatus> statuses =
                    container.getPatchDeclarationStatuses();
            assertTrue(container.isEnabled());
            assertEquals(PluginRuntimeStatus.ENABLED, manager.getPluginRuntimeStatus(pluginId));
            assertEquals(2, registrar.attempts());
            assertEquals(1, registrar.registrations().size());
            assertEquals(PluginContainer.PatchDeclarationState.FAILED, statuses.get(0).state());
            assertEquals(PluginPatchFailure.Category.MISSING_METHOD, statuses.get(0).failureCategory());
            assertEquals(PluginContainer.PatchDeclarationState.ACTIVE, statuses.get(1).state());
            assertNull(statuses.get(1).failureCategory());
        } finally {
            manager.unloadPlugin(pluginId);
        }
    }

    /// Installs one package with its exact required Patch grant and returns the launcher-local home.
    ///
    /// @param temporaryDirectory isolated test root
    /// @param pluginId package plugin ID
    /// @param patchesJson raw Patch declaration array
    /// @return launcher-local home containing the installed package and grant
    /// @throws IOException if package creation or installation fails
    private static Path installPackage(
            Path temporaryDirectory,
            String pluginId,
            String patchesJson
    ) throws IOException {
        Path localHome = temporaryDirectory.resolve("home");
        Path sourcePackage = temporaryDirectory.resolve(pluginId + ".npl");
        writePluginPackage(sourcePackage, manifestJson(pluginId, patchesJson));
        new PluginManager(localHome).prepareLocalPluginInstallation(
                sourcePackage,
                Set.of(PluginPermission.LAUNCHER_PATCH)
        );
        return localHome;
    }

    /// Registers one caller-owned prepared lifecycle through the normal manager registration path.
    ///
    /// @param manager target manager
    /// @param temporaryDirectory isolated package and data paths
    /// @param manifest authoritative manifest
    /// @param plugin lifecycle fixture
    /// @param classLoader callback context class loader
    /// @param permissions dynamic exact-artifact grants
    /// @return registered disabled container
    private static PluginContainer registerPrepared(
            PluginManager manager,
            Path temporaryDirectory,
            PluginManifest manifest,
            Plugin plugin,
            ClassLoader classLoader,
            Supplier<@Unmodifiable Set<PluginPermission>> permissions
    ) {
        PluginContext context = new PluginContext(
                manifest,
                temporaryDirectory.resolve("package-" + manifest.getId()),
                temporaryDirectory.resolve("data-" + manifest.getId()),
                classLoader,
                "a".repeat(64),
                permissions
        );
        PreparedPlugin prepared = new PreparedPlugin(
                plugin,
                context,
                manifest,
                manager.getPluginsDirectory().resolve(manifest.getId() + ".npl")
        );
        AtomicReference<@Nullable PluginContainer> registered = new AtomicReference<>();
        FXThreadTestSupport.runOnFxThread(() -> registered.set(manager.registerPreparedPlugin(prepared)));
        return Objects.requireNonNull(registered.get());
    }

    /// Parses one schema-v5 Java Patch manifest.
    ///
    /// @param pluginId plugin ID
    /// @param patchesJson raw Patch declaration array
    /// @return validated manifest
    /// @throws IOException if the generated manifest is invalid
    private static PluginManifest manifest(String pluginId, String patchesJson) throws IOException {
        return PluginManifest.fromJson(new StringReader(manifestJson(pluginId, patchesJson)));
    }

    /// Builds one schema-v5 Java Patch manifest JSON document.
    ///
    /// @param pluginId plugin ID
    /// @param patchesJson raw Patch declaration array
    /// @return manifest JSON
    private static String manifestJson(String pluginId, String patchesJson) {
        return """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Patch Lifecycle Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": ["launcher-patch"],
                  "requiredPermissions": ["launcher-patch"],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "patches": %s,
                  "dependencies": []
                }
                """.formatted(pluginId, PackagedPatchPlugin.class.getName(), patchesJson);
    }

    /// Returns one valid `before` Patch declaration JSON array.
    ///
    /// @return declaration JSON
    private static String successfulPatchJson() {
        return """
                [{
                  "target": "%s",
                  "method": "join",
                  "type": "before",
                  "parameters": ["java.lang.String", "int"]
                }]
                """.formatted(FIXTURE_TARGET);
    }

    /// Returns one failing declaration followed by one independently successful declaration.
    ///
    /// @return declaration JSON
    private static String twoPatchJson() {
        return """
                [
                  {
                    "target": "%s",
                    "method": "missing",
                    "type": "before",
                    "parameters": []
                  },
                  {
                    "target": "%s",
                    "method": "join",
                    "type": "before",
                    "parameters": ["java.lang.String", "int"]
                  }
                ]
                """.formatted(FIXTURE_TARGET, FIXTURE_TARGET);
    }

    /// Writes one deterministic package containing the manifest and lifecycle class bytes.
    ///
    /// @param target target `.npl` path
    /// @param manifestJson complete manifest JSON
    /// @throws IOException if package creation fails
    private static void writePluginPackage(Path target, String manifestJson) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeEntry(output, "plugin.json", manifestJson.getBytes(StandardCharsets.UTF_8));
            String classResource = PackagedPatchPlugin.class.getName().replace('.', '/') + ".class";
            try (InputStream input = Objects.requireNonNull(
                    PackagedPatchPlugin.class.getResourceAsStream("/" + classResource),
                    "Compiled Patch plugin fixture is unavailable"
            )) {
                writeEntry(output, classResource, input.readAllBytes());
            }
        }
    }

    /// Writes one deterministic package entry.
    ///
    /// @param output open package stream
    /// @param name package-relative entry name
    /// @param bytes immutable entry bytes
    /// @throws IOException if writing fails
    private static void writeEntry(
            ZipOutputStream output,
            String name,
            byte @Unmodifiable [] bytes
    ) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    /// Test registrar that records exact manager inputs and creates close-observable registrations.
    @NotNullByDefault
    private static final class RecordingPatchRegistrar implements PluginManager.JavaPatchRegistrar {
        /// Method names that deterministically fail registration.
        private final @Unmodifiable Set<String> failingMethods;

        /// Successful registrations retained for callback and teardown assertions.
        private final List<Registration> registrations = new ArrayList<>();

        /// Number of registration attempts, including isolated failures.
        private final AtomicInteger attempts = new AtomicInteger();

        /// Most recent exact artifact supplied by the manager, or `null` before an attempt.
        private @Nullable PluginArtifactIdentity lastArtifactIdentity;

        /// Most recent immutable dependency IDs.
        private @Unmodifiable Set<String> lastDependencyIds = Set.of();

        /// Most recent immutable exact-artifact permissions.
        private @Unmodifiable Set<PluginPermission> lastPermissions = Set.of();

        /// Creates a registrar that accepts every declaration.
        private RecordingPatchRegistrar() {
            this(Set.of());
        }

        /// Creates a registrar that rejects selected method names.
        ///
        /// @param failingMethods method names rejected as missing
        private RecordingPatchRegistrar(Set<String> failingMethods) {
            this.failingMethods = Set.copyOf(failingMethods);
        }

        /// Records exact manager inputs and returns one active close-observable handle.
        ///
        /// @param artifactIdentity exact owning artifact
        /// @param dependencyIds immutable dependency IDs
        /// @param permissions exact-artifact effective permissions
        /// @param declaration authoritative declaration
        /// @param callback Java plugin callback endpoint
        /// @return active registration handle
        /// @throws PluginPatchFailure when this fixture rejects the selected method
        @Override
        public PluginContainer.PatchRegistrationHandle register(
                PluginArtifactIdentity artifactIdentity,
                Set<String> dependencyIds,
                Set<PluginPermission> permissions,
                PluginPatchDeclaration declaration,
                PluginPatchCallback callback
        ) throws PluginPatchFailure {
            attempts.incrementAndGet();
            lastArtifactIdentity = artifactIdentity;
            lastDependencyIds = Set.copyOf(dependencyIds);
            lastPermissions = Set.copyOf(permissions);
            if (failingMethods.contains(declaration.getMethod())) {
                throw new PluginPatchFailure(
                        PluginPatchFailure.Category.MISSING_METHOD,
                        "Test registrar rejected a missing method"
                );
            }
            Registration registration = new Registration(callback);
            registrations.add(registration);
            return registration;
        }

        /// Returns the number of attempted declarations.
        ///
        /// @return attempt count
        private int attempts() {
            return attempts.get();
        }

        /// Returns the only successful registration.
        ///
        /// @return sole registration
        private Registration onlyRegistration() {
            assertEquals(1, registrations.size());
            return registrations.get(0);
        }

        /// Returns an immutable successful-registration snapshot.
        ///
        /// @return successful registrations
        private @Unmodifiable List<Registration> registrations() {
            return List.copyOf(registrations);
        }

        /// Returns the most recent exact artifact identity.
        ///
        /// @return exact artifact identity
        private PluginArtifactIdentity lastArtifactIdentity() {
            return Objects.requireNonNull(lastArtifactIdentity);
        }

        /// Returns the most recent dependency IDs.
        ///
        /// @return immutable dependency IDs
        private @Unmodifiable Set<String> lastDependencyIds() {
            return lastDependencyIds;
        }

        /// Returns the most recent exact-artifact permissions.
        ///
        /// @return immutable permissions
        private @Unmodifiable Set<PluginPermission> lastPermissions() {
            return lastPermissions;
        }

        /// Active test registration that delegates callbacks and reports idempotent closure.
        @NotNullByDefault
        private static final class Registration implements PluginContainer.PatchRegistrationHandle {
            /// Manager-wrapped Java Patch callback.
            private final PluginPatchCallback callback;

            /// Whether this handle still accepts callback invocation.
            private boolean active = true;

            /// Creates one active test registration.
            ///
            /// @param callback manager-wrapped callback
            private Registration(PluginPatchCallback callback) {
                this.callback = callback;
            }

            /// Invokes the callback only while this registration remains active.
            ///
            /// @param invocation callback input
            /// @return non-null callback result
            /// @throws Exception if callback execution fails
            private PluginPatchResult invoke(PluginPatchInvocation invocation) throws Exception {
                if (!active) {
                    throw new IllegalStateException("Patch registration is closed");
                }
                return Objects.requireNonNull(callback.invoke(invocation));
            }

            /// Replays the retained callback regardless of this fake registration's admission state.
            ///
            /// @param invocation callback input
            /// @return callback result when the manager lifecycle gate remains open
            /// @throws Exception if the manager rejects lifecycle admission or plugin execution fails
            private PluginPatchResult invokeRetainedCallback(PluginPatchInvocation invocation) throws Exception {
                return Objects.requireNonNull(callback.invoke(invocation));
            }

            /// Returns whether callback admission remains active.
            ///
            /// @return active state
            @Override
            public boolean isActive() {
                return active;
            }

            /// Returns no callback failure for this deterministic test handle.
            ///
            /// @return always `null`
            @Override
            public @Nullable PluginPatchFailure.Category failureCategory() {
                return null;
            }

            /// Closes this handle once and records ordering through the package fixture.
            @Override
            public void close() {
                if (active) {
                    active = false;
                    PackagedPatchPlugin.recordEvent("patch-closed");
                }
            }
        }
    }

    /// Close-counting callback class loader used to prove the Patch lease is released on unload.
    @NotNullByDefault
    private static final class TrackingClassLoader extends URLClassLoader {
        /// Number of physical close calls.
        private final AtomicInteger closeCount = new AtomicInteger();

        /// Creates an empty child loader with the launcher loader as parent.
        private TrackingClassLoader() {
            super(new URL[0], PluginManager.class.getClassLoader());
        }

        /// Records and performs physical close.
        ///
        /// @throws IOException if URL loader cleanup fails
        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            super.close();
        }

        /// Returns the physical close count.
        ///
        /// @return close count
        private int closeCount() {
            return closeCount.get();
        }
    }
}
