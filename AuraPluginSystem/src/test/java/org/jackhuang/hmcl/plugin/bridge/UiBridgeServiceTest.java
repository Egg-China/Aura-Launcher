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
package org.jackhuang.hmcl.plugin.bridge;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.JavaFXLauncher;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadContext;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies declarative JavaFX contributions, callback dispatch, thread policy, and owner cleanup.
@NotNullByDefault
final class UiBridgeServiceTest {
    /// Worker used for external Runtime callbacks triggered by JavaFX actions.
    private final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();

    /// Temporary package and private-data roots used by runtime payload contexts.
    @TempDir
    private Path temporaryDirectory;

    /// Stops callback workers after each test.
    @AfterEach
    void closeCallbackExecutor() {
        callbackExecutor.shutdownNow();
    }

    /// Freezes the UI method IDs and canonical operation names used by native and isolated transports.
    @Test
    void freezesUiMethodTable() {
        assertMethod(BridgeMethod.UI_REGISTER_SIDEBAR_ACTION, 1_001L, "ui.sidebar.register-action");
        assertMethod(BridgeMethod.UI_REGISTER_PAGE, 1_002L, "ui.page.register");
        assertMethod(BridgeMethod.UI_SET_PROPERTY, 1_003L, "ui.node.set-property");
        assertMethod(BridgeMethod.UI_NAVIGATE, 1_004L, "ui.page.navigate");
        assertMethod(BridgeMethod.UI_UNREGISTER_OWNER, 1_005L, "ui.owner.unregister");
    }

    /// Dispatches a sidebar action asynchronously and removes its contribution during owner cleanup.
    @Test
    void dispatchesSidebarCallbacksAndUnregistersTheirOwner() throws Exception {
        CountDownLatch callbackInvoked = new CountDownLatch(1);
        try (Fixture fixture = fixture(Set.of(PluginPermission.LAUNCHER_UI),
                (owner, callbackId, event, cancellation) -> {
                    assertEquals("test.ui-bridge", owner);
                    assertEquals(17L, callbackId);
                    assertEquals(BridgeValue.map(Map.of("event", BridgeValue.string("action"))), event);
                    callbackInvoked.countDown();
                    return BridgeValue.nullValue();
                })) {
            BridgeValue registered = fixture.registry().invoke(
                    fixture.context(),
                    BridgeMethod.UI_REGISTER_SIDEBAR_ACTION.operation(),
                    BridgeValue.map(Map.of(
                            "title", BridgeValue.string("External action"),
                            "callback", BridgeValue.integer(17)
                    ))
            );

            assertInstanceOf(BridgeValue.HandleValue.class, registered);
            assertEquals(1, fixture.backend().contributions.size());
            fixture.backend().contributions.get(0).action().run();
            assertTrue(callbackInvoked.await(5, TimeUnit.SECONDS));

            fixture.registry().invoke(
                    fixture.context(), BridgeMethod.UI_UNREGISTER_OWNER.operation(), BridgeValue.nullValue());
            assertTrue(fixture.backend().contributions.isEmpty());
        }
    }

    /// Uses current payload authority to reference-count UI handles exposed through a Runtime Host.
    @Test
    void retainsAndReleasesRuntimeOwnedUiHandles() {
        try (Fixture fixture = fixture(Set.of(PluginPermission.LAUNCHER_UI),
                (owner, callbackId, event, cancellation) -> BridgeValue.nullValue())) {
            BridgeValue.HandleValue registered = assertInstanceOf(BridgeValue.HandleValue.class,
                    fixture.registry().invoke(
                            fixture.context(),
                            BridgeMethod.UI_REGISTER_SIDEBAR_ACTION.operation(),
                            BridgeValue.map(Map.of(
                                    "title", BridgeValue.string("Reference counted"),
                                    "callback", BridgeValue.integer(37L)
                            ))
                    ));
            BridgeHandle handle = registered.value();

            fixture.uiService().retain(fixture.context(), handle.id(), handle.generation());
            fixture.uiService().release(fixture.context(), handle.id(), handle.generation());

            assertEquals(1, fixture.backend().contributions.size());

            fixture.uiService().release(fixture.context(), handle.id(), handle.generation());

            assertTrue(fixture.backend().contributions.isEmpty());
            assertCategory(BridgeError.Category.STALE_HANDLE,
                    () -> fixture.uiService().release(
                            fixture.context(), handle.id(), handle.generation()));
        }
    }

    /// Completes owner cleanup when one exact contribution release callback fails.
    @Test
    void completesOwnerCleanupAfterContributionReleaseFailure() {
        try (Fixture fixture = fixture(Set.of(PluginPermission.LAUNCHER_UI),
                (owner, callbackId, event, cancellation) -> BridgeValue.nullValue())) {
            BridgeValue.HandleValue registered = assertInstanceOf(BridgeValue.HandleValue.class,
                    fixture.registry().invoke(
                            fixture.context(),
                            BridgeMethod.UI_REGISTER_SIDEBAR_ACTION.operation(),
                            BridgeValue.map(Map.of(
                                    "title", BridgeValue.string("Failing cleanup"),
                                    "callback", BridgeValue.integer(41L)
                            ))
                    ));
            fixture.backend().failNextUnregister.set(true);

            assertDoesNotThrow(() -> fixture.uiService().closeOwner("test.ui-bridge"));

            assertTrue(fixture.backend().contributions.isEmpty());
            assertCategory(BridgeError.Category.STALE_HANDLE,
                    () -> fixture.uiService().release(
                            fixture.context(), registered.value().id(), registered.value().generation()));
        }
    }

    /// Cancels an in-flight UI callback and prevents a retained stale action from admitting another callback.
    @Test
    void closesCallbackAdmissionBeforeOwnerCleanup() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        CountDownLatch staleCallbackEntered = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger();
        try (Fixture fixture = fixture(Set.of(PluginPermission.LAUNCHER_UI),
                (owner, callbackId, event, cancellation) -> {
                    int invocation = invocationCount.incrementAndGet();
                    if (invocation > 1) {
                        staleCallbackEntered.countDown();
                    }
                    callbackEntered.countDown();
                    while (!cancellation.isCancellationRequested()) {
                        Thread.onSpinWait();
                    }
                    cancellationObserved.countDown();
                    return BridgeValue.nullValue();
                })) {
            fixture.registry().invoke(
                    fixture.context(),
                    BridgeMethod.UI_REGISTER_SIDEBAR_ACTION.operation(),
                    BridgeValue.map(Map.of(
                            "title", BridgeValue.string("Cancellable action"),
                            "callback", BridgeValue.integer(29)
                    ))
            );
            Runnable retainedAction = fixture.backend().contributions.get(0).action();
            retainedAction.run();
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));

            fixture.registry().invoke(
                    fixture.context(), BridgeMethod.UI_UNREGISTER_OWNER.operation(), BridgeValue.nullValue());

            assertTrue(cancellationObserved.await(5, TimeUnit.SECONDS));
            retainedAction.run();
            assertFalse(staleCallbackEntered.await(250, TimeUnit.MILLISECONDS));
            assertEquals(1, invocationCount.get());
        }
    }

    /// Rechecks the current launcher-ui grant before admitting an event from an already rendered contribution.
    @Test
    void blocksCallbacksAfterPermissionRevocation() throws Exception {
        AtomicReference<@Unmodifiable Set<PluginPermission>> permissions =
                new AtomicReference<>(Set.of(PluginPermission.LAUNCHER_UI));
        CountDownLatch callbackEntered = new CountDownLatch(1);
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        BridgeServiceRegistry registry = new BridgeServiceRegistry(authority);
        try (Fixture fixture = fixture(
                authority,
                registry,
                permissions::get,
                (owner, callbackId, event, cancellation) -> {
                    callbackEntered.countDown();
                    return BridgeValue.nullValue();
                })) {
            fixture.registry().invoke(
                    fixture.context(),
                    BridgeMethod.UI_REGISTER_SIDEBAR_ACTION.operation(),
                    BridgeValue.map(Map.of(
                            "title", BridgeValue.string("Permission probe"),
                            "callback", BridgeValue.integer(31)
                    ))
            );
            Runnable retainedAction = fixture.backend().contributions.get(0).action();
            permissions.set(Set.of());
            fixture.session().rotate();

            retainedAction.run();

            assertFalse(callbackEntered.await(250, TimeUnit.MILLISECONDS));
            permissions.set(Set.of(PluginPermission.LAUNCHER_UI));
            fixture.session().rotate();
            fixture.registry().invoke(
                    fixture.context(), BridgeMethod.UI_UNREGISTER_OWNER.operation(), BridgeValue.nullValue());
        }
    }

    /// Registers a declarative page, mutates one logical node, and navigates the materialized JavaFX tree.
    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void mutatesAndNavigatesDeclarativePages() {
        try (Fixture fixture = fixture(Set.of(PluginPermission.LAUNCHER_UI),
                (owner, callbackId, event, cancellation) -> BridgeValue.nullValue())) {
            BridgeValue registration = fixture.registry().invoke(
                    fixture.context(), BridgeMethod.UI_REGISTER_PAGE.operation(), pageRegistration());
            BridgeValue.MapValue result = assertInstanceOf(BridgeValue.MapValue.class, registration);
            BridgeValue.HandleValue page = assertInstanceOf(
                    BridgeValue.HandleValue.class, result.values().get("page"));
            BridgeValue.MapValue nodes = assertInstanceOf(
                    BridgeValue.MapValue.class, result.values().get("nodes"));
            BridgeValue.HandleValue heading = assertInstanceOf(
                    BridgeValue.HandleValue.class, nodes.values().get("heading"));

            fixture.registry().invoke(
                    fixture.context(),
                    BridgeMethod.UI_SET_PROPERTY.operation(),
                    BridgeValue.map(Map.of(
                            "node", heading,
                            "property", BridgeValue.string("text"),
                            "value", BridgeValue.string("Changed by Rust")
                    ))
            );
            fixture.registry().invoke(
                    fixture.context(), BridgeMethod.UI_NAVIGATE.operation(), page);

            VBox root = assertInstanceOf(VBox.class, fixture.backend().navigated.get());
            Label label = assertInstanceOf(Label.class, root.getChildren().get(0));
            assertEquals("Changed by Rust", label.getText());

            fixture.registry().invoke(
                    fixture.context(), BridgeMethod.UI_UNREGISTER_OWNER.operation(), BridgeValue.nullValue());
            assertCategory(BridgeError.Category.STALE_HANDLE, () -> fixture.registry().invoke(
                    fixture.context(), BridgeMethod.UI_NAVIGATE.operation(), page));
        }
    }

    /// Executes UI mutations on JavaFX when the toolkit exists and waits for their completion.
    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void executesUiHandlersOnFxApplicationThread() {
        assertTrue(JavaFXLauncher.isStarted());
        try (Fixture fixture = fixture(Set.of(PluginPermission.LAUNCHER_UI),
                (owner, callbackId, event, cancellation) -> BridgeValue.nullValue())) {
            fixture.registry().invoke(
                    fixture.context(),
                    BridgeMethod.UI_REGISTER_SIDEBAR_ACTION.operation(),
                    BridgeValue.map(Map.of(
                            "title", BridgeValue.string("Thread probe"),
                            "callback", BridgeValue.integer(1)
                    ))
            );

            assertTrue(fixture.backend().registeredOnFxThread.get());
        }
    }

    /// Uses the deterministic caller-thread path supplied for startup and headless operation.
    @Test
    void supportsDeterministicPreToolkitExecution() {
        AtomicReference<Thread> executedThread = new AtomicReference<>();
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        BridgeServiceRegistry registry = new BridgeServiceRegistry(authority, action -> {
            executedThread.set(Thread.currentThread());
            return action.call();
        });
        try (Fixture fixture = fixture(
                authority,
                registry,
                Set.of(PluginPermission.LAUNCHER_UI),
                (owner, callbackId, event, cancellation) -> BridgeValue.nullValue())) {
            Thread callingThread = Thread.currentThread();
            fixture.registry().invoke(
                    fixture.context(),
                    BridgeMethod.UI_REGISTER_SIDEBAR_ACTION.operation(),
                    BridgeValue.map(Map.of(
                            "title", BridgeValue.string("Startup action"),
                            "callback", BridgeValue.integer(2)
                    ))
            );

            assertSame(callingThread, executedThread.get());
        }
    }

    /// Rejects every UI operation when launcher-ui is absent from the current token generation.
    @Test
    void requiresLauncherUiPermission() {
        try (Fixture fixture = fixture(Set.of(PluginPermission.LAUNCHER_CORE),
                (owner, callbackId, event, cancellation) -> BridgeValue.nullValue())) {
            assertCategory(BridgeError.Category.PERMISSION_DENIED, () -> fixture.registry().invoke(
                    fixture.context(),
                    BridgeMethod.UI_REGISTER_SIDEBAR_ACTION.operation(),
                    BridgeValue.map(Map.of(
                            "title", BridgeValue.string("Denied"),
                            "callback", BridgeValue.integer(3)
                    ))
            ));
        }
    }

    /// Creates the declarative page request used by mutation and navigation tests.
    ///
    /// @return immutable Bridge request tree
    private static BridgeValue pageRegistration() {
        Map<String, BridgeValue> label = new LinkedHashMap<>();
        label.put("type", BridgeValue.string("label"));
        label.put("id", BridgeValue.string("heading"));
        label.put("properties", BridgeValue.map(Map.of("text", BridgeValue.string("Initial"))));

        Map<String, BridgeValue> root = new LinkedHashMap<>();
        root.put("type", BridgeValue.string("vbox"));
        root.put("properties", BridgeValue.map(Map.of("spacing", BridgeValue.floating(8.0))));
        root.put("children", BridgeValue.array(List.of(BridgeValue.map(label))));

        return BridgeValue.map(Map.of(
                "title", BridgeValue.string("External page"),
                "root", BridgeValue.map(root)
        ));
    }

    /// Creates a fixture with the production FX executor and a fake contribution backend.
    ///
    /// @param permissions effective payload grants
    /// @param callbackInvoker external callback adapter
    /// @return closeable UI Bridge fixture
    private Fixture fixture(
            Set<PluginPermission> permissions,
            UiBridgeService.CallbackInvoker callbackInvoker
    ) {
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        return fixture(authority, new BridgeServiceRegistry(authority), permissions, callbackInvoker);
    }

    /// Creates a fixture around an explicit authority and service registry.
    ///
    /// @param authority launcher-owned capability authority
    /// @param registry target service registry
    /// @param permissions effective payload grants
    /// @param callbackInvoker external callback adapter
    /// @return closeable UI Bridge fixture
    private Fixture fixture(
            PluginPermissionAuthority authority,
            BridgeServiceRegistry registry,
            Set<PluginPermission> permissions,
            UiBridgeService.CallbackInvoker callbackInvoker
    ) {
        @Unmodifiable Set<PluginPermission> effectivePermissions = Set.copyOf(permissions);
        return fixture(authority, registry, () -> effectivePermissions, callbackInvoker);
    }

    /// Creates a fixture around a dynamic permission source.
    ///
    /// @param authority launcher-owned capability authority
    /// @param registry target service registry
    /// @param permissionProvider dynamic effective payload grants
    /// @param callbackInvoker external callback adapter
    /// @return closeable UI Bridge fixture
    private Fixture fixture(
            PluginPermissionAuthority authority,
            BridgeServiceRegistry registry,
            Supplier<@Unmodifiable Set<PluginPermission>> permissionProvider,
            UiBridgeService.CallbackInvoker callbackInvoker
    ) {
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                "test.ui-bridge", "1.0.0", "b".repeat(64));
        PluginCapabilitySession session = authority.openSession(
                identity,
                PluginExecutionMode.EMBEDDED,
                permissionProvider,
                BridgeServiceRegistry.CALLBACK_DOMAIN,
                Duration.ofMinutes(1)
        );
        RuntimePayloadContext context = new RuntimePayloadContext(
                identity,
                temporaryDirectory.resolve("package"),
                "payload/plugin.dll",
                PluginExecutionMode.EMBEDDED,
                temporaryDirectory.resolve("data"),
                session::issue
        );
        FakeBackend backend = new FakeBackend();
        BridgeDispatcher dispatcher = new BridgeDispatcher(callbackExecutor);
        UiBridgeService uiService = new UiBridgeService(
                registry, authority, dispatcher, callbackInvoker, backend);
        return new Fixture(registry, context, session, backend, uiService);
    }

    /// Verifies one stable method key pair.
    ///
    /// @param method method descriptor
    /// @param id expected numeric ID
    /// @param operation expected operation name
    private static void assertMethod(BridgeMethod method, long id, String operation) {
        assertEquals(id, method.id());
        assertEquals(operation, method.operation());
    }

    /// Verifies one Bridge invocation fails with the expected portable category.
    ///
    /// @param expected expected failure category
    /// @param invocation failing invocation
    private static void assertCategory(BridgeError.Category expected, Runnable invocation) {
        BridgeError error = assertThrows(BridgeError.class, invocation::run);
        assertEquals(expected, error.category());
    }

    /// Fake launcher UI backend retaining contributions and the most recent navigation target.
    @NotNullByDefault
    private static final class FakeBackend implements UiBridgeService.Backend {
        /// Active fake sidebar contributions.
        private final List<Contribution> contributions = new ArrayList<>();

        /// Most recently navigated JavaFX node.
        private final AtomicReference<@Nullable Node> navigated = new AtomicReference<>();

        /// Whether the most recent registration ran on JavaFX.
        private final AtomicBoolean registeredOnFxThread = new AtomicBoolean();

        /// Makes the next exact contribution cleanup fail before owner-level cleanup runs.
        private final AtomicBoolean failNextUnregister = new AtomicBoolean();

        /// Registers one action contribution.
        @Override
        public Object registerSidebarAction(String ownerPluginId, String title, Runnable action) {
            registeredOnFxThread.set(Platform.isFxApplicationThread());
            Contribution contribution = new Contribution(ownerPluginId, title, action, null);
            contributions.add(contribution);
            return contribution;
        }

        /// Registers one lazy page contribution.
        @Override
        public Object registerSidebarPage(String ownerPluginId, String title, Supplier<? extends Node> pageSupplier) {
            registeredOnFxThread.set(Platform.isFxApplicationThread());
            Contribution contribution = new Contribution(ownerPluginId, title, () -> {
            }, pageSupplier);
            contributions.add(contribution);
            return contribution;
        }

        /// Removes one exact contribution.
        @Override
        public void unregister(Object contribution) {
            if (failNextUnregister.compareAndSet(true, false)) {
                throw new IllegalStateException("Requested contribution cleanup failure");
            }
            contributions.remove(contribution);
        }

        /// Removes every contribution owned by one plugin.
        @Override
        public void unregisterOwner(String ownerPluginId) {
            contributions.removeIf(contribution -> contribution.ownerPluginId().equals(ownerPluginId));
        }

        /// Records one materialized page as the current navigation target.
        @Override
        public void navigate(Node page) {
            navigated.set(page);
        }
    }

    /// One fake action or lazy page contribution.
    ///
    /// @param ownerPluginId owning plugin ID
    /// @param title displayed title
    /// @param action selection action
    /// @param pageSupplier optional lazy page supplier
    @NotNullByDefault
    private record Contribution(
            String ownerPluginId,
            String title,
            Runnable action,
            @Nullable Supplier<? extends Node> pageSupplier
    ) {
    }

    /// Owns one configured registry, runtime context, session, and fake UI backend.
    ///
    /// @param registry configured service registry
    /// @param context exact payload context
    /// @param session closeable capability session
    /// @param backend fake UI backend
    /// @param uiService configured UI Bridge service
    @NotNullByDefault
    private record Fixture(
            BridgeServiceRegistry registry,
            RuntimePayloadContext context,
            PluginCapabilitySession session,
            FakeBackend backend,
            UiBridgeService uiService
    ) implements AutoCloseable {
        /// Revokes every token issued by this fixture.
        @Override
        public void close() {
            session.close();
        }
    }
}
