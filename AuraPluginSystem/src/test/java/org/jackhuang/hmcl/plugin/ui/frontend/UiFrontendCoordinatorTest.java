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

import org.jackhuang.hmcl.FXThreadTestSupport;
import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jackhuang.hmcl.plugin.ui.frontend.process.UiFrontendCommandHandler;
import org.jackhuang.hmcl.plugin.ui.frontend.process.UiFrontendProcessException;
import org.jackhuang.hmcl.plugin.ui.frontend.process.UiFrontendProcessSession;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies frontend selection normalization, verified package resolution, and JavaFX fallback lifecycle.
@NotNullByDefault
public final class UiFrontendCoordinatorTest {
    /// Canonical UI-provider fixture ID.
    private static final String UI_PROVIDER_ID = "dev.aura.test.coordinator-ui";

    /// Rejects unknown frontend selections while retaining the built-in JavaFX option.
    @Test
    public void unknownSelectionNormalizesToJavaFx(@TempDir Path temporaryDirectory) throws Exception {
        UiFrontendCoordinator coordinator = coordinator(temporaryDirectory, (executable, packageRoot, snapshot, handler) -> {
            throw new AssertionError("Unknown selections must not launch native children");
        });

        UiFrontendDescriptor normalized = coordinator.normalizeSelection("dev.aura.test.missing");

        assertEquals(UiFrontendDescriptor.JAVAFX_ID, normalized.getId());
        assertTrue(coordinator.getFallbackReason().isPresent());
    }

    /// Verifies one installed UI-provider package and launches it with its extracted executable and package root.
    @Test
    public void startVerifiedNativeFrontend(@TempDir Path temporaryDirectory) throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        UiFrontendCoordinator coordinator = coordinator(temporaryDirectory, launcher);
        UiFrontendDescriptor normalized = coordinator.normalizeSelection(UI_PROVIDER_ID);
        assertFalse(normalized.isJavaFx());

        UiFrontendDescriptor started = coordinator.start(normalized, BridgeValue.nullValue());

        assertEquals(UI_PROVIDER_ID, started.getId());
        assertEquals(launcher.executable, started.getExecutable().orElseThrow());
        assertTrue(launcher.executable.getParent().equals(launcher.packageRoot));
        assertTrue(coordinator.getFallbackReason().isEmpty());
    }

    /// Falls back to JavaFX when the native child cannot become ready.
    @Test
    public void startupFailureFallsBackToJavaFx(@TempDir Path temporaryDirectory) throws Exception {
        UiFrontendCoordinator coordinator = coordinator(temporaryDirectory, (executable, packageRoot, snapshot, handler) -> {
            throw new UiFrontendProcessException(
                    UiFrontendProcessException.Category.STARTUP,
                    "startup fixture rejection"
            );
        });
        UiFrontendDescriptor normalized = coordinator.normalizeSelection(UI_PROVIDER_ID);

        UiFrontendDescriptor started = coordinator.start(normalized, BridgeValue.nullValue());

        assertEquals(UiFrontendDescriptor.JAVAFX_ID, started.getId());
        assertTrue(coordinator.getFallbackReason().isPresent());
    }

    /// Blocks game-launch commands until the provider also holds the game-launch grant.
    @Test
    public void commandGateBlocksGameLaunchWithoutGrant(@TempDir Path temporaryDirectory) throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        UiFrontendCoordinator coordinator = coordinator(temporaryDirectory, launcher);
        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());

        assertTrue(launcher.handler.handle("core.instance.launch", BridgeValue.nullValue())
                .handle((reply, failure) -> failure).toCompletableFuture().join() instanceof IllegalStateException);

        coordinator.stop();
    }

    /// Allows game-launch commands after the dedicated grant is present.
    @Test
    public void commandGateAllowsGameLaunchWithGrant(@TempDir Path temporaryDirectory) throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        UiFrontendCoordinator coordinator = coordinator(temporaryDirectory, launcher,
                PluginPermission.GAME_LAUNCH);
        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());

        assertEquals(BridgeValue.nullValue(), launcher.handler
                .handle("core.instance.launch", BridgeValue.nullValue())
                .toCompletableFuture().join().value());

        coordinator.stop();
    }

    /// Redacts account data from snapshot replies without the account grant.
    @Test
    public void snapshotRedactsAccountsWithoutGrant(@TempDir Path temporaryDirectory) throws Exception {
        BridgeValue snapshot = BridgeValue.map(java.util.Map.of(
                "instances", BridgeValue.array(java.util.List.of()),
                "accounts", BridgeValue.array(java.util.List.of())
        ));
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        PluginManager manager = fixtureManager(temporaryDirectory);
        UiFrontendCoordinator coordinator = new UiFrontendCoordinator(
                provider(temporaryDirectory, manager),
                manager,
                (method, params) -> CompletableFuture.completedFuture(
                        UiFrontendCommandHandler.Reply.result(snapshot)
                ),
                launcher::launch
        );
        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());

        BridgeValue redacted = launcher.handler.handle("core.snapshot.get", BridgeValue.nullValue())
                .toCompletableFuture().join().value();

        assertFalse(((BridgeValue.MapValue) redacted).values().containsKey("accounts"));

        coordinator.stop();
    }

    /// Publishes one granted UI-provider package for deterministic coordinator fixtures.
    private UiFrontendCoordinator coordinator(Path temporaryDirectory, SessionLaunch launch,
                                              PluginPermission... extraPermissions) throws Exception {
        PluginManager manager = fixtureManager(temporaryDirectory, extraPermissions);
        return new UiFrontendCoordinator(
                provider(temporaryDirectory, manager),
                manager,
                (method, params) -> CompletableFuture.completedFuture(
                        UiFrontendCommandHandler.Reply.result(BridgeValue.nullValue())
                ),
                launch::launch
        );
    }

    /// Creates one manager with the base provider grants plus optional extras.
    private PluginManager fixtureManager(Path temporaryDirectory,
                                         PluginPermission... extraPermissions) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        var constructor = PluginManager.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        PluginManager manager = constructor.newInstance(localHome);
        writeUiProviderPackage(manager.getPluginsDirectory().resolve(UI_PROVIDER_ID + ".npl"));
        java.util.Set<PluginPermission> granted = new java.util.HashSet<>(java.util.Set.of(
                PluginPermission.LAUNCHER_UI_PROVIDER,
                PluginPermission.NATIVE_CODE,
                PluginPermission.PROCESS
        ));
        granted.addAll(java.util.List.of(extraPermissions));
        manager.setGrantedPermissions(UI_PROVIDER_ID, granted);
        manager.enablePlugin(UI_PROVIDER_ID);
        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
        return manager;
    }

    /// Creates one frontend provider around a fixture manager.
    private UiFrontendProvider provider(Path temporaryDirectory, PluginManager manager) {
        return new UiFrontendProvider(
                manager,
                temporaryDirectory.resolve("home").resolve("ui-packages")
        );
    }

    /// Writes one valid schema-v5 native UI-provider package with a non-executable marker payload.
    private static void writeUiProviderPackage(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Coordinator UI Provider",
                  "version": "1.0.0",
                  "type": "native",
                  "entrypoint": "bin/ui-provider",
                  "permissions": ["launcher-ui-provider", "native-code", "process", "game-launch"],
                  "requiredPermissions": ["launcher-ui-provider", "native-code", "process"],
                  "launcherVersion": "*",
                  "runtime": "aura-ui",
                  "abi": 1,
                  "platforms": ["windows-x64"],
                  "pluginKind": "ui-provider",
                  "executionMode": "isolated"
                }
                """.formatted(UI_PROVIDER_ID);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            put(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            put(output, "bin/ui-provider", "fixture-executable".getBytes(StandardCharsets.UTF_8));
        }
    }

    /// Writes one deterministic archive entry.
    private static void put(ZipOutputStream output, String name, byte @Unmodifiable [] contents) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(contents);
        output.closeEntry();
    }

    /// Deterministic session-launch boundary.
    @FunctionalInterface
    private interface SessionLaunch {
        UiFrontendCoordinator.SupervisedSession launch(
                Path executable,
                Path packageRoot,
                BridgeValue snapshot,
                UiFrontendCommandHandler handler
        ) throws UiFrontendProcessException;
    }

    /// Records the exact launch paths supplied by the coordinator.
    private static final class RecordingLauncher implements SessionLaunch {
        /// Session returned to the coordinator.
        private final UiFrontendCoordinator.SupervisedSession session;

        /// Executable supplied on the most recent launch.
        private Path executable;

        /// Package root supplied on the most recent launch.
        private Path packageRoot;

        /// Gated command handler supplied on the most recent launch.
        private UiFrontendCommandHandler handler;

        /// Creates one recorder around a reusable session.
        RecordingLauncher(UiFrontendCoordinator.SupervisedSession session) {
            this.session = session;
        }

        /// Records one launch and returns the stub session.
        @Override
        public UiFrontendCoordinator.SupervisedSession launch(
                Path executable,
                Path packageRoot,
                BridgeValue snapshot,
                UiFrontendCommandHandler handler
        ) throws UiFrontendProcessException {
            this.executable = executable;
            this.packageRoot = packageRoot;
            this.handler = handler;
            return session;
        }
    }

    /// Minimal never-started session stub used only through the coordinator supervision surface.
    private static final class UiFrontendProcessSessionStub implements UiFrontendCoordinator.SupervisedSession {
        /// Terminal completion that never fires for this deterministic stub.
        private final CompletableFuture<UiFrontendProcessSession.Termination> termination = new CompletableFuture<>();

        /// Returns a completion that never fires for this deterministic stub.
        @Override
        public CompletableFuture<UiFrontendProcessSession.Termination> termination() {
            return termination;
        }

        /// Ignores close because no child process was created.
        @Override
        public void close() {
        }
    }
}
