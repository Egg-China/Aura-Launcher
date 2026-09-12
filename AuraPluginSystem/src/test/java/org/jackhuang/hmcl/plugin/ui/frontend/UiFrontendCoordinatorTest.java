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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
        BridgeValue snapshot = launcherSnapshot();
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
        BridgeValue.MapValue settings = (BridgeValue.MapValue)
                ((BridgeValue.MapValue) redacted).values().get("settings");
        assertFalse(settings.values().containsKey("commonDirectory"));
        assertFalse(settings.values().containsKey("proxyUser"));
        assertFalse(settings.values().containsKey("proxyPassword"));

        coordinator.stop();
    }

    /// Redacts account data from the initial native snapshot without the account grant.
    @Test
    public void startRedactsInitialAccountsWithoutGrant(@TempDir Path temporaryDirectory) throws Exception {
        BridgeValue snapshot = launcherSnapshot();
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        UiFrontendCoordinator coordinator = coordinator(temporaryDirectory, launcher);

        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), snapshot);

        BridgeValue.MapValue redacted = (BridgeValue.MapValue) launcher.snapshot;
        assertFalse(redacted.values().containsKey("accounts"));
        assertTrue(redacted.values().containsKey("instances"));
        assertTrue(redacted.values().containsKey("settings"));
        BridgeValue.MapValue settings = (BridgeValue.MapValue) redacted.values().get("settings");
        assertFalse(settings.values().containsKey("commonDirectory"));
        assertFalse(settings.values().containsKey("proxyUser"));
        assertFalse(settings.values().containsKey("proxyPassword"));

        coordinator.stop();
    }

    /// Preserves the HMCL display snapshot when account and filesystem grants are present.
    @Test
    public void startPreservesInitialHmclAccountsWithGrants(@TempDir Path temporaryDirectory) throws Exception {
        BridgeValue snapshot = launcherSnapshot("hmcl", auraCoreSnapshotAccounts());
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        UiFrontendCoordinator coordinator = coordinator(
                temporaryDirectory, launcher, PluginPermission.ACCOUNT, PluginPermission.FILESYSTEM);

        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), snapshot);

        BridgeValue.MapValue redacted = (BridgeValue.MapValue) launcher.snapshot;
        BridgeValue.ArrayValue accounts = (BridgeValue.ArrayValue) redacted.values().get("accounts");
        assertEquals(5, accounts.values().size());
        BridgeValue.MapValue account = (BridgeValue.MapValue) accounts.values().get(0);
        assertTrue(account.values().containsKey("id"));
        assertTrue(account.values().containsKey("uuid"));
        assertTrue(account.values().containsKey("skinUrl"));
        assertTrue(account.values().containsKey("isActive"));
        BridgeValue.MapValue settings = (BridgeValue.MapValue) redacted.values().get("settings");
        assertTrue(settings.values().containsKey("commonDirectory"));
        assertFalse(settings.values().containsKey("proxyUser"));
        assertFalse(settings.values().containsKey("proxyPassword"));

        coordinator.stop();
    }

    /// Projects AuraCore snapshot accounts to Microsoft profile-name suggestions with the grant.
    @Test
    public void startProjectsAuraCoreAccountsToMicrosoftSuggestions(@TempDir Path temporaryDirectory)
            throws Exception {
        BridgeValue snapshot = launcherSnapshot("auracore", auraCoreSnapshotAccounts());
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        UiFrontendCoordinator coordinator = coordinator(temporaryDirectory, launcher, PluginPermission.ACCOUNT);

        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), snapshot);

        assertMicrosoftOnlyAccounts(launcher.snapshot);

        coordinator.stop();
    }

    /// Projects later snapshot replies to Microsoft suggestions with the account grant.
    @Test
    public void snapshotGetProjectsAuraCoreAccountsToMicrosoftSuggestions(@TempDir Path temporaryDirectory)
            throws Exception {
        BridgeValue snapshot = launcherSnapshot("auracore", auraCoreSnapshotAccounts());
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        PluginManager manager = fixtureManager(temporaryDirectory, PluginPermission.ACCOUNT);
        UiFrontendCoordinator coordinator = new UiFrontendCoordinator(
                provider(temporaryDirectory, manager),
                manager,
                (method, params) -> CompletableFuture.completedFuture(
                        UiFrontendCommandHandler.Reply.result(snapshot)
                ),
                launcher::launch
        );
        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());

        assertMicrosoftOnlyAccounts(launcher.handler
                .handle("core.snapshot.get", BridgeValue.nullValue())
                .toCompletableFuture().join().value());

        coordinator.stop();
    }

    /// Removes account data when the snapshot engine is unknown even with the account grant.
    @Test
    public void startRemovesAccountsForUnknownSnapshotEngineWithGrant(@TempDir Path temporaryDirectory)
            throws Exception {
        Map<String, BridgeValue> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("accounts", auraCoreSnapshotAccounts());
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        UiFrontendCoordinator coordinator = coordinator(temporaryDirectory, launcher, PluginPermission.ACCOUNT);

        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.map(snapshot));

        assertFalse(((BridgeValue.MapValue) launcher.snapshot).values().containsKey("accounts"));

        coordinator.stop();
    }

    /// Leaves a non-map initial snapshot unchanged instead of inventing protocol data.
    @Test
    public void startKeepsNullInitialSnapshotWithoutGrant(@TempDir Path temporaryDirectory) throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        UiFrontendCoordinator coordinator = coordinator(temporaryDirectory, launcher);

        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());

        assertEquals(BridgeValue.nullValue(), launcher.snapshot);

        coordinator.stop();
    }

    /// Blocks every AuraCore account and authentication command without the account grant.
    @Test
    public void accountCommandsRequireAccountGrant(@TempDir Path temporaryDirectory) throws Exception {
        List<String> methods = accountMethods();
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        UiFrontendCoordinator coordinator = coordinator(temporaryDirectory, launcher);
        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());

        for (String method : methods) {
            Throwable failure = launcher.handler.handle(method, BridgeValue.nullValue())
                    .handle((reply, error) -> error)
                    .toCompletableFuture()
                    .join();
            assertTrue(failure instanceof IllegalStateException, method + " must be gated");
            assertEquals("PERMISSION_DENIED: " + method, failure.getMessage());
        }

        coordinator.stop();
    }

    /// Forwards the account permission policy family at the coordinator boundary with the grant.
    /// The production transport still exposes only the Phase 4B read-and-login subset.
    @Test
    public void accountPermissionPolicyAllowsGrantedCommands(@TempDir Path temporaryDirectory) throws Exception {
        List<String> methods = accountMethods();
        List<String> handled = new CopyOnWriteArrayList<>();
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        PluginManager manager = fixtureManager(temporaryDirectory, PluginPermission.ACCOUNT);
        UiFrontendCoordinator coordinator = new UiFrontendCoordinator(
                provider(temporaryDirectory, manager),
                manager,
                (method, params) -> {
                    handled.add(method);
                    return CompletableFuture.completedFuture(UiFrontendCommandHandler.Reply.result(
                            BridgeValue.nullValue()));
                },
                launcher::launch
        );

        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());
        for (String method : methods) {
            launcher.handler.handle(method, BridgeValue.nullValue()).toCompletableFuture().join();
        }

        assertEquals(methods, handled);

        coordinator.stop();
    }

    /// Redacts AuraCore filesystem paths from status and instance-list replies without filesystem access.
    @Test
    public void auraCoreDisplayRepliesRedactPathsWithoutFilesystemGrant(@TempDir Path temporaryDirectory)
            throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        PluginManager manager = fixtureManager(temporaryDirectory);
        UiFrontendCoordinator coordinator = new UiFrontendCoordinator(
                provider(temporaryDirectory, manager),
                manager,
                (method, params) -> CompletableFuture.completedFuture(UiFrontendCommandHandler.Reply.result(
                        method.equals("core.auracore.status") ? auraCoreStatus() : auraCoreInstances())),
                launcher::launch
        );
        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());

        BridgeValue.MapValue status = (BridgeValue.MapValue) launcher.handler
                .handle("core.auracore.status", BridgeValue.nullValue())
                .toCompletableFuture().join().value();
        assertFalse(status.values().containsKey("dataDirectory"));
        assertFalse(status.values().containsKey("libraryPath"));
        assertTrue(status.values().containsKey("engine"));

        BridgeValue.ArrayValue instances = (BridgeValue.ArrayValue) launcher.handler
                .handle("core.auracore.instance.list", BridgeValue.nullValue())
                .toCompletableFuture().join().value();
        BridgeValue.MapValue instance = (BridgeValue.MapValue) instances.values().get(0);
        assertFalse(instance.values().containsKey("dir"));
        assertTrue(instance.values().containsKey("id"));

        coordinator.stop();
    }

    /// Replaces AuraCore error diagnostics and task free text without filesystem access.
    @Test
    public void auraCoreTaskAndErrorRepliesRedactFreeTextWithoutFilesystemGrant(
            @TempDir Path temporaryDirectory) throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        PluginManager manager = fixtureManager(temporaryDirectory);
        UiFrontendCoordinator coordinator = new UiFrontendCoordinator(
                provider(temporaryDirectory, manager),
                manager,
                (method, params) -> CompletableFuture.completedFuture(UiFrontendCommandHandler.Reply.result(
                        method.equals("core.auracore.task.status")
                                ? auraCoreTaskStatus()
                                : auraCoreInstanceError())),
                launcher::launch
        );
        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());

        BridgeValue.MapValue task = (BridgeValue.MapValue) launcher.handler
                .handle("core.auracore.task.status", BridgeValue.nullValue())
                .toCompletableFuture().join().value();
        assertEquals("AuraCore task status failed",
                ((BridgeValue.StringValue) task.values().get("error")).value());
        assertEquals(1, task.values().size());

        BridgeValue.MapValue instanceError = (BridgeValue.MapValue) launcher.handler
                .handle("core.auracore.instance.list", BridgeValue.nullValue())
                .toCompletableFuture().join().value();
        assertEquals("AuraCore instance list failed",
                ((BridgeValue.StringValue) instanceError.values().get("error")).value());

        coordinator.stop();
    }

    /// Replaces malformed AuraCore replies and account/auth diagnostics without filesystem access.
    @Test
    public void auraCoreRepliesFailClosedForUnexpectedShapesWithoutFilesystemGrant(
            @TempDir Path temporaryDirectory) throws Exception {
        Map<String, BridgeValue> replies = Map.of(
                "core.auracore.status", BridgeValue.string("C:\\private\\status"),
                "core.auracore.instance.list", BridgeValue.string("C:\\private\\instances"),
                "core.auracore.task.status", BridgeValue.string("C:\\private\\task"),
                "core.auracore.accounts.list", auraCoreInstanceError(),
                "core.auracore.auth.msa.begin", auraCoreInstanceError(),
                "core.auracore.auth.msa.info", auraCoreInstanceError()
        );
        Map<String, String> expected = Map.of(
                "core.auracore.status", "AuraCore status failed",
                "core.auracore.instance.list", "AuraCore instance list failed",
                "core.auracore.task.status", "AuraCore task status failed",
                "core.auracore.accounts.list", "AuraCore account list failed",
                "core.auracore.auth.msa.begin", "AuraCore Microsoft login failed",
                "core.auracore.auth.msa.info", "AuraCore Microsoft login status failed"
        );
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        PluginManager manager = fixtureManager(temporaryDirectory, PluginPermission.ACCOUNT);
        UiFrontendCoordinator coordinator = new UiFrontendCoordinator(
                provider(temporaryDirectory, manager),
                manager,
                (method, params) -> CompletableFuture.completedFuture(UiFrontendCommandHandler.Reply.result(
                        replies.get(method))),
                launcher::launch
        );
        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            BridgeValue.MapValue reply = (BridgeValue.MapValue) launcher.handler
                    .handle(entry.getKey(), BridgeValue.nullValue())
                    .toCompletableFuture().join().value();
            assertEquals(entry.getValue(), ((BridgeValue.StringValue) reply.values().get("error")).value(),
                    entry.getKey());
        }

        coordinator.stop();
    }

    /// Rejects nested safe-field values and error envelopes carrying extra fields.
    @Test
    public void auraCoreRepliesFailClosedForNestedSafeFieldsWithoutFilesystemGrant(
            @TempDir Path temporaryDirectory) throws Exception {
        Map<String, BridgeValue> replies = new java.util.LinkedHashMap<>();
        Map<String, BridgeValue> errorWithExtra = new java.util.LinkedHashMap<>();
        errorWithExtra.put("error", BridgeValue.string("cannot read C:\\private\\auracore"));
        errorWithExtra.put("engine", BridgeValue.string("auracore"));
        replies.put("core.auracore.status", BridgeValue.map(errorWithExtra));
        replies.put("core.auracore.instance.list", BridgeValue.array(java.util.List.of(
                safeFieldReply("name", nestedPathValue()))));
        replies.put("core.auracore.task.status", safeFieldReply("id", nestedPathValue()));
        replies.put("core.auracore.accounts.list", BridgeValue.array(java.util.List.of(
                safeFieldReply("profileName", nestedPathValue()))));
        replies.put("core.auracore.auth.msa.begin", safeFieldReply("taskId", nestedPathValue()));
        replies.put("core.auracore.auth.msa.info", safeFieldReply("userCode", nestedPathValue()));

        Map<String, String> expected = Map.of(
                "core.auracore.status", "AuraCore status failed",
                "core.auracore.instance.list", "AuraCore instance list failed",
                "core.auracore.task.status", "AuraCore task status failed",
                "core.auracore.accounts.list", "AuraCore account list failed",
                "core.auracore.auth.msa.begin", "AuraCore Microsoft login failed",
                "core.auracore.auth.msa.info", "AuraCore Microsoft login status failed"
        );
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        PluginManager manager = fixtureManager(temporaryDirectory, PluginPermission.ACCOUNT);
        UiFrontendCoordinator coordinator = new UiFrontendCoordinator(
                provider(temporaryDirectory, manager),
                manager,
                (method, params) -> CompletableFuture.completedFuture(UiFrontendCommandHandler.Reply.result(
                        replies.get(method))),
                launcher::launch
        );
        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            BridgeValue.MapValue reply = (BridgeValue.MapValue) launcher.handler
                    .handle(entry.getKey(), BridgeValue.nullValue())
                    .toCompletableFuture().join().value();
            assertEquals(entry.getValue(), ((BridgeValue.StringValue) reply.values().get("error")).value(),
                    entry.getKey());
        }

        coordinator.stop();
    }

    /// Preserves AuraCore task diagnostics when the filesystem grant is present.
    @Test
    public void auraCoreTaskReplyKeepsDiagnosticsWithFilesystemGrant(@TempDir Path temporaryDirectory)
            throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        PluginManager manager = fixtureManager(temporaryDirectory, PluginPermission.FILESYSTEM);
        UiFrontendCoordinator coordinator = new UiFrontendCoordinator(
                provider(temporaryDirectory, manager),
                manager,
                (method, params) -> CompletableFuture.completedFuture(UiFrontendCommandHandler.Reply.result(
                        auraCoreTaskStatus())),
                launcher::launch
        );
        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());

        BridgeValue.MapValue task = (BridgeValue.MapValue) launcher.handler
                .handle("core.auracore.task.status", BridgeValue.nullValue())
                .toCompletableFuture().join().value();
        assertTrue(task.values().containsKey("status"));
        assertTrue(task.values().containsKey("error"));

        coordinator.stop();
    }

    /// Preserves AuraCore filesystem paths when the filesystem grant is present.
    @Test
    public void auraCoreDisplayRepliesKeepPathsWithFilesystemGrant(@TempDir Path temporaryDirectory)
            throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(new UiFrontendProcessSessionStub());
        PluginManager manager = fixtureManager(temporaryDirectory, PluginPermission.FILESYSTEM);
        UiFrontendCoordinator coordinator = new UiFrontendCoordinator(
                provider(temporaryDirectory, manager),
                manager,
                (method, params) -> CompletableFuture.completedFuture(UiFrontendCommandHandler.Reply.result(
                        method.equals("core.auracore.status") ? auraCoreStatus() : auraCoreInstances())),
                launcher::launch
        );
        coordinator.start(coordinator.normalizeSelection(UI_PROVIDER_ID), BridgeValue.nullValue());

        BridgeValue.MapValue status = (BridgeValue.MapValue) launcher.handler
                .handle("core.auracore.status", BridgeValue.nullValue())
                .toCompletableFuture().join().value();
        assertTrue(status.values().containsKey("dataDirectory"));
        assertTrue(status.values().containsKey("libraryPath"));

        BridgeValue.ArrayValue instances = (BridgeValue.ArrayValue) launcher.handler
                .handle("core.auracore.instance.list", BridgeValue.nullValue())
                .toCompletableFuture().join().value();
        BridgeValue.MapValue instance = (BridgeValue.MapValue) instances.values().get(0);
        assertTrue(instance.values().containsKey("dir"));

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

    /// Returns the coordinator account permission family; transport reachability is intentionally narrower.
    private static List<String> accountMethods() {
        return List.of(
                "core.auracore.accounts.list",
                "core.auracore.accounts.add-offline",
                "core.auracore.accounts.remove",
                "core.auracore.accounts.set-default",
                "core.auracore.auth.msa.begin",
                "core.auracore.auth.msa.info"
        );
    }

    /// Creates one HMCL snapshot carrying both public and account-gated state.
    private static BridgeValue launcherSnapshot() {
        return launcherSnapshot("hmcl");
    }

    /// Creates one snapshot carrying both public and account-gated state.
    private static BridgeValue launcherSnapshot(String coreEngine) {
        return launcherSnapshot(coreEngine, BridgeValue.array(java.util.List.of()));
    }

    /// Creates one snapshot carrying the selected engine and account records.
    private static BridgeValue launcherSnapshot(String coreEngine, BridgeValue accounts) {
        Map<String, BridgeValue> settings = new java.util.LinkedHashMap<>();
        settings.put("coreEngine", BridgeValue.string(coreEngine));
        settings.put("commonDirectory", BridgeValue.string("C:\\private\\common"));
        settings.put("proxyUser", BridgeValue.string("proxy-user"));
        settings.put("proxyPassword", BridgeValue.string("proxy-secret"));
        Map<String, BridgeValue> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("instances", BridgeValue.array(java.util.List.of()));
        snapshot.put("accounts", accounts);
        snapshot.put("settings", BridgeValue.map(settings));
        return BridgeValue.map(snapshot);
    }

    /// Asserts that one snapshot exposes only Microsoft profile-name account suggestions.
    private static void assertMicrosoftOnlyAccounts(BridgeValue value) {
        BridgeValue.MapValue redacted = (BridgeValue.MapValue) value;
        BridgeValue.ArrayValue accounts = (BridgeValue.ArrayValue) redacted.values().get("accounts");
        assertEquals(2, accounts.values().size());
        for (BridgeValue entry : accounts.values()) {
            BridgeValue.MapValue account = (BridgeValue.MapValue) entry;
            assertEquals("microsoft", ((BridgeValue.StringValue) account.values().get("type")).value());
            assertTrue(account.values().containsKey("username"));
            assertFalse(account.values().containsKey("id"));
            assertFalse(account.values().containsKey("uuid"));
            assertFalse(account.values().containsKey("skinUrl"));
            assertFalse(account.values().containsKey("isActive"));
        }
    }

    /// Creates mixed valid and malformed launcher account records.
    private static BridgeValue auraCoreSnapshotAccounts() {
        Map<String, BridgeValue> microsoft = new java.util.LinkedHashMap<>();
        microsoft.put("id", BridgeValue.string("hmcl-id"));
        microsoft.put("username", BridgeValue.string("AuraPlayer"));
        microsoft.put("uuid", BridgeValue.string("00000000-0000-0000-0000-000000000001"));
        microsoft.put("type", BridgeValue.string("microsoft"));
        microsoft.put("skinUrl", BridgeValue.string("https://minotar.net/helm/AuraPlayer/128.png"));
        microsoft.put("isActive", BridgeValue.bool(true));
        Map<String, BridgeValue> secondMicrosoft = new java.util.LinkedHashMap<>();
        secondMicrosoft.put("id", BridgeValue.string("hmcl-id-2"));
        secondMicrosoft.put("username", BridgeValue.string("SecondPlayer"));
        secondMicrosoft.put("uuid", BridgeValue.string("00000000-0000-0000-0000-000000000002"));
        secondMicrosoft.put("type", BridgeValue.string("microsoft"));
        secondMicrosoft.put("skinUrl", BridgeValue.string("https://minotar.net/helm/SecondPlayer/128.png"));
        secondMicrosoft.put("isActive", BridgeValue.bool(false));
        Map<String, BridgeValue> offline = new java.util.LinkedHashMap<>();
        offline.put("username", BridgeValue.string("OfflinePlayer"));
        offline.put("type", BridgeValue.string("offline"));
        Map<String, BridgeValue> malformed = new java.util.LinkedHashMap<>();
        malformed.put("type", BridgeValue.string("microsoft"));
        malformed.put("username", BridgeValue.integer(7));
        return BridgeValue.array(java.util.List.of(
                BridgeValue.map(microsoft),
                BridgeValue.map(secondMicrosoft),
                BridgeValue.map(offline),
                BridgeValue.map(malformed),
                BridgeValue.string("invalid")));
    }

    /// Creates one AuraCore status reply containing filesystem paths.
    private static BridgeValue auraCoreStatus() {
        Map<String, BridgeValue> status = new java.util.LinkedHashMap<>();
        status.put("engine", BridgeValue.string("auracore"));
        status.put("dataDirectory", BridgeValue.string("C:\\private\\auracore"));
        status.put("libraryPath", BridgeValue.string("C:\\private\\backend.dll"));
        return BridgeValue.map(status);
    }

    /// Creates one AuraCore task reply containing backend free text.
    private static BridgeValue auraCoreTaskStatus() {
        Map<String, BridgeValue> task = new java.util.LinkedHashMap<>();
        task.put("id", BridgeValue.string("task"));
        task.put("type", BridgeValue.string("launch"));
        task.put("state", BridgeValue.string("succeeded"));
        task.put("progress", BridgeValue.integer(1));
        task.put("total", BridgeValue.integer(1));
        task.put("status", BridgeValue.string("Running C:\\private\\instance"));
        task.put("succeeded", BridgeValue.bool(true));
        task.put("error", BridgeValue.string("failed at C:\\private\\backend.dll"));
        return BridgeValue.map(task);
    }

    /// Creates one AuraCore instance-list error carrying a native diagnostic.
    private static BridgeValue auraCoreInstanceError() {
        Map<String, BridgeValue> error = new java.util.LinkedHashMap<>();
        error.put("error", BridgeValue.string("cannot read C:\\private\\auracore\\instances"));
        return BridgeValue.map(error);
    }

    /// Creates one reply map carrying a malformed value in an allowlisted field.
    private static BridgeValue safeFieldReply(String field, BridgeValue value) {
        Map<String, BridgeValue> reply = new java.util.LinkedHashMap<>();
        reply.put(field, value);
        return BridgeValue.map(reply);
    }

    /// Creates one nested value carrying a local path.
    private static BridgeValue nestedPathValue() {
        Map<String, BridgeValue> nested = new java.util.LinkedHashMap<>();
        nested.put("path", BridgeValue.string("C:\\private\\auracore"));
        return BridgeValue.map(nested);
    }

    /// Creates one AuraCore instance-list reply containing a directory path.
    private static BridgeValue auraCoreInstances() {
        Map<String, BridgeValue> instance = new java.util.LinkedHashMap<>();
        instance.put("id", BridgeValue.string("instance"));
        instance.put("name", BridgeValue.string("Instance"));
        instance.put("dir", BridgeValue.string("C:\\private\\instance"));
        return BridgeValue.array(java.util.List.of(BridgeValue.map(instance)));
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
                  "permissions": ["launcher-ui-provider", "native-code", "process", "game-launch", "account", "filesystem"],
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

        /// Initial snapshot supplied on the most recent launch.
        private BridgeValue snapshot;

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
            this.snapshot = snapshot;
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
