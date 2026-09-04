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
package org.jackhuang.hmcl.plugin.patch;

import org.jackhuang.hmcl.patchfixture.PatchTargetFixture;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jackhuang.hmcl.plugin.PluginPatchResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable deterministic plans and bounded fail-open Patch dispatch.
@NotNullByDefault
public final class PluginPatchEngineTest {
    /// Exact callback timeout used by ordinary focused tests.
    private static final Duration CALLBACK_TIMEOUT = Duration.ofMillis(100);

    /// Aggregate callback budget used by ordinary focused tests.
    private static final Duration AGGREGATE_TIMEOUT = Duration.ofSeconds(2);

    /// Complete package digest used by exact-artifact test identities.
    private static final String ARTIFACT_SHA256 = "0".repeat(64);

    /// Real callback workers, sized to exercise the complete recursion-depth boundary without starvation.
    private ExecutorService callbackExecutor;

    /// Launcher-owned target policy backed by the real fixture class and descriptor bytes.
    private PluginPatchTargetPolicy targetPolicy;

    /// Engine under test.
    private PluginPatchEngine engine;

    /// Registrations retained for deterministic idempotent cleanup.
    private final List<PluginPatchRegistration> registrations = new ArrayList<>();

    /// Creates fresh callback workers and a dispatch engine.
    @BeforeEach
    public void setUp() {
        callbackExecutor = Executors.newFixedThreadPool(24);
        targetPolicy = new PluginPatchTargetPolicy(
                PatchTargetFixture.class,
                () -> List.of(PatchTargetFixture.class)
        );
        engine = new PluginPatchEngine(
                targetPolicy,
                callbackExecutor,
                CALLBACK_TIMEOUT,
                AGGREGATE_TIMEOUT
        );
    }

    /// Closes registrations and proves focused tests do not retain non-daemon workers.
    @AfterEach
    public void tearDown() throws InterruptedException {
        closeRegistrations();
        callbackExecutor.shutdownNow();
        assertTrue(callbackExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /// Runs `before` callbacks dependency-first and `after` callbacks in reverse wrapper order.
    @Test
    public void composeBeforeAndAfterInDependencyWrapperOrder() throws Exception {
        List<String> events = new ArrayList<>();
        PluginPatchRegistration baseBefore = register(
                "dev.example.base", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> record(events, "base-before"));
        PluginPatchRegistration childBefore = register(
                "dev.example.child", Set.of("dev.example.base"), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> record(events, "child-before"));
        PluginPatchRegistration baseAfter = register(
                "dev.example.base", Set.of(), PluginPatchDeclaration.PatchType.AFTER,
                invocation -> record(events, "base-after"));
        PluginPatchRegistration childAfter = register(
                "dev.example.child", Set.of("dev.example.base"), PluginPatchDeclaration.PatchType.AFTER,
                invocation -> record(events, "child-after"));

        Object result = dispatchJoin(baseBefore.methodId(), events, "value", 4);

        assertEquals("value4", result);
        assertEquals(
                List.of("base-before", "child-before", "original", "child-after", "base-after"),
                events
        );
        assertEquals(baseBefore.methodId(), childBefore.methodId());
        assertEquals(baseBefore.methodId(), baseAfter.methodId());
        assertEquals(baseBefore.methodId(), childAfter.methodId());
    }

    /// Rejects a second replacement before publishing it and keeps the first replacement active.
    @Test
    public void rejectSecondReplacementForExactMethod() throws Exception {
        PluginPatchRegistration first = register(
                "dev.example.first", Set.of(), PluginPatchDeclaration.PatchType.REPLACE,
                invocation -> PluginPatchResult.returnValue("replaced"));

        PluginPatchFailure failure = assertThrows(PluginPatchFailure.class, () -> register(
                "dev.example.second", Set.of(), PluginPatchDeclaration.PatchType.REPLACE,
                invocation -> PluginPatchResult.returnValue("wrong")));

        assertEquals(PluginPatchFailure.Category.REPLACEMENT_CONFLICT, failure.category());
        assertEquals("replaced", dispatchJoin(first.methodId(), new ArrayList<>(), "value", 4));
    }

    /// Removes a registration exactly once and leaves stale dispatcher calls as pass-through operations.
    @Test
    public void closeRegistrationIdempotently() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        PluginPatchRegistration registration = register(
                "dev.example.close", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> {
                    callbacks.incrementAndGet();
                    return PluginPatchResult.unchanged();
                });
        long methodId = registration.methodId();

        registration.close();
        registration.close();

        assertEquals("value4", dispatchJoin(methodId, new ArrayList<>(), "value", 4));
        assertEquals(0, callbacks.get());
        assertFalse(registration.isActive());
        assertTrue(registration.isClosed());
    }

    /// Publishes the first live plan before retransformation and removes it before bytecode restoration.
    @Test
    public void publishPlanBeforeRetransformationAndRemoveBeforeRestoration() throws Exception {
        List<Integer> liveMethodCounts = new ArrayList<>();
        List<Class<?>> retransformedClasses = new ArrayList<>();
        replaceEngine(targetClass -> {
            retransformedClasses.add(targetClass);
            liveMethodCounts.add(engine.snapshotMethods().size());
        });

        PluginPatchRegistration registration = register(
                "dev.example.retransform-order",
                Set.of(),
                PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> PluginPatchResult.unchanged()
        );
        registration.close();

        assertEquals(List.of(PatchTargetFixture.class, PatchTargetFixture.class), retransformedClasses);
        assertEquals(List.of(1, 0), liveMethodCounts);
    }

    /// Retransforms only for the first and last callback on one exact method.
    @Test
    public void avoidRetransformationForAdditionalCallbackOnExactMethod() throws Exception {
        AtomicInteger retransforms = new AtomicInteger();
        replaceEngine(targetClass -> retransforms.incrementAndGet());

        PluginPatchRegistration before = register(
                "dev.example.retransform-before",
                Set.of(),
                PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> PluginPatchResult.unchanged()
        );
        PluginPatchRegistration after = register(
                "dev.example.retransform-after",
                Set.of(),
                PluginPatchDeclaration.PatchType.AFTER,
                invocation -> PluginPatchResult.unchanged()
        );

        assertEquals(1, retransforms.get());
        before.close();
        assertEquals(1, retransforms.get());
        after.close();
        assertEquals(2, retransforms.get());
    }

    /// Retransforms a loaded class whenever another exact method gains or loses its first plan.
    @Test
    public void retransformSameClassForEachDistinctMethodPlan() throws Exception {
        AtomicInteger retransforms = new AtomicInteger();
        replaceEngine(targetClass -> retransforms.incrementAndGet());

        PluginPatchRegistration primary = register(
                "dev.example.retransform-primary",
                Set.of(),
                PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> PluginPatchResult.unchanged()
        );
        PluginPatchRegistration overload = registerJoinOverload(
                "dev.example.retransform-overload",
                invocation -> PluginPatchResult.unchanged()
        );

        assertEquals(2, retransforms.get());
        primary.close();
        assertEquals(3, retransforms.get());
        overload.close();
        assertEquals(4, retransforms.get());
    }

    /// Rolls back publication and attempts bytecode restoration after registration retransformation fails.
    @Test
    public void rollBackRegistrationAfterRetransformationFailure() throws Exception {
        AtomicInteger retransforms = new AtomicInteger();
        List<Integer> liveMethodCounts = new ArrayList<>();
        replaceEngine(targetClass -> {
            int invocation = retransforms.incrementAndGet();
            liveMethodCounts.add(engine.snapshotMethods().size());
            if (invocation == 1) {
                throw new PluginPatchFailure(
                        PluginPatchFailure.Category.TRANSFORM_FAILURE,
                        "expected registration failure"
                );
            }
        });

        PluginPatchFailure failure = assertThrows(PluginPatchFailure.class, () -> register(
                "dev.example.retransform-failure",
                Set.of(),
                PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> PluginPatchResult.unchanged()
        ));

        assertEquals(PluginPatchFailure.Category.TRANSFORM_FAILURE, failure.category());
        assertEquals(List.of(1, 0), liveMethodCounts);
        assertTrue(engine.snapshotMethods().isEmpty());

        PluginPatchRegistration recovered = register(
                "dev.example.retransform-recovered",
                Set.of(),
                PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> PluginPatchResult.unchanged()
        );
        assertTrue(recovered.isActive());
        assertEquals(3, retransforms.get());
    }

    /// Leaves stale transformed calls as pass-through operations when final bytecode restoration fails.
    @Test
    public void keepDispatcherFailOpenAfterRestorationFailure() throws Exception {
        AtomicInteger retransforms = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        replaceEngine(targetClass -> {
            if (retransforms.incrementAndGet() == 2) {
                throw new PluginPatchFailure(
                        PluginPatchFailure.Category.TRANSFORM_FAILURE,
                        "expected restoration failure"
                );
            }
        });
        PluginPatchRegistration registration = register(
                "dev.example.retransform-restore-failure",
                Set.of(),
                PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> {
                    callbacks.incrementAndGet();
                    return PluginPatchResult.arguments(List.of("wrong", 9));
                }
        );
        long methodId = registration.methodId();

        registration.close();

        assertEquals("value4", dispatchJoin(methodId, new ArrayList<>(), "value", 4));
        assertEquals(0, callbacks.get());
        assertTrue(engine.snapshotMethods().isEmpty());
    }

    /// Restores a target that was first defined after registration but before registration close.
    @Test
    public void restoreClassLoadedAfterRegistration() throws Exception {
        AtomicReference<List<Class<?>>> loadedClasses = new AtomicReference<>(List.of());
        targetPolicy = new PluginPatchTargetPolicy(PatchTargetFixture.class, loadedClasses::get);
        AtomicInteger retransforms = new AtomicInteger();
        engine = new PluginPatchEngine(
                targetPolicy,
                targetClass -> retransforms.incrementAndGet(),
                callbackExecutor,
                CALLBACK_TIMEOUT,
                AGGREGATE_TIMEOUT
        );

        PluginPatchRegistration registration = register(
                "dev.example.future-restore",
                Set.of(),
                PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> PluginPatchResult.unchanged()
        );
        assertEquals(0, retransforms.get());
        loadedClasses.set(List.of(PatchTargetFixture.class));

        registration.close();

        assertEquals(1, retransforms.get());
        assertTrue(engine.snapshotMethods().isEmpty());
    }

    /// Cancels a callback queued before registration close so plugin code never starts afterward.
    @Test
    public void cancelQueuedCallbackWhenRegistrationCloses() throws Exception {
        replaceExecutorWithSingleWorker();
        ThreadPoolExecutor singleWorker = (ThreadPoolExecutor) callbackExecutor;
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        callbackExecutor.submit(() -> {
            blockerStarted.countDown();
            releaseBlocker.await();
            return null;
        });
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
        AtomicInteger callbacks = new AtomicInteger();
        PluginPatchRegistration registration = register(
                "dev.example.queued-close",
                Set.of(),
                PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> {
                    callbacks.incrementAndGet();
                    return PluginPatchResult.unchanged();
                }
        );
        ExecutorService targetExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<@Nullable Object> dispatch = targetExecutor.submit(() ->
                    dispatchJoin(registration.methodId(), new ArrayList<>(), "value", 4));
            assertTrue(awaitQueueSize(singleWorker, 1, Duration.ofSeconds(1)));

            registration.close();
            releaseBlocker.countDown();

            assertEquals("value4", dispatch.get(1, TimeUnit.SECONDS));
            assertEquals(0, callbacks.get());
        } finally {
            releaseBlocker.countDown();
            targetExecutor.shutdownNow();
            assertTrue(targetExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Interrupts an already running callback promptly when its registration closes.
    @Test
    public void cancelRunningCallbackWhenRegistrationCloses() throws Exception {
        replaceEngine(Duration.ofSeconds(5), Duration.ofSeconds(5));
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        PluginPatchRegistration registration = register(
                "dev.example.running-close",
                Set.of(),
                PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> {
                    callbackStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException exception) {
                        callbackInterrupted.countDown();
                        throw exception;
                    }
                    return PluginPatchResult.unchanged();
                }
        );
        ExecutorService targetExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<@Nullable Object> dispatch = targetExecutor.submit(() ->
                    dispatchJoin(registration.methodId(), new ArrayList<>(), "value", 4));
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

            registration.close();

            assertTrue(callbackInterrupted.await(1, TimeUnit.SECONDS));
            assertEquals("value4", dispatch.get(1, TimeUnit.SECONDS));
        } finally {
            targetExecutor.shutdownNow();
            assertTrue(targetExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Disables only a timed-out callback while a healthy neighbor continues now and on later dispatches.
    @Test
    public void isolateTimedOutRegistration() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger blockedCalls = new AtomicInteger();
        AtomicInteger healthyCalls = new AtomicInteger();
        PluginPatchRegistration blocked = register(
                "dev.example.blocked", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> {
                    blockedCalls.incrementAndGet();
                    started.countDown();
                    new CountDownLatch(1).await();
                    return PluginPatchResult.unchanged();
                });
        register(
                "dev.example.healthy", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> {
                    healthyCalls.incrementAndGet();
                    return PluginPatchResult.unchanged();
                });

        assertEquals("value4", dispatchJoin(blocked.methodId(), new ArrayList<>(), "value", 4));
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertEquals("value5", dispatchJoin(blocked.methodId(), new ArrayList<>(), "value", 5));

        assertEquals(1, blockedCalls.get());
        assertEquals(2, healthyCalls.get());
        assertEquals(PluginPatchFailure.Category.TIMEOUT, blocked.failureCategory());
        assertFalse(blocked.isActive());
    }

    /// Stops admitting callbacks after the aggregate deadline without disabling untouched registrations.
    @Test
    public void enforceAggregateDispatchDeadline() throws Exception {
        replaceEngine(Duration.ofSeconds(1), Duration.ofMillis(1500));
        AtomicInteger lastCalls = new AtomicInteger();
        register(
                "dev.example.first", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> {
                    Thread.sleep(800L);
                    return PluginPatchResult.unchanged();
                });
        PluginPatchRegistration second = register(
                "dev.example.second", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> {
                    Thread.sleep(800L);
                    return PluginPatchResult.unchanged();
                });
        PluginPatchRegistration last = register(
                "dev.example.third", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> {
                    lastCalls.incrementAndGet();
                    return PluginPatchResult.unchanged();
                });

        assertEquals("value4", dispatchJoin(second.methodId(), new ArrayList<>(), "value", 4));

        assertEquals(PluginPatchFailure.Category.TIMEOUT, second.failureCategory());
        assertTrue(last.isActive());
        assertEquals(0, lastCalls.get());
    }

    /// Disables an exception-throwing callback without suppressing another callback or the original method.
    @Test
    public void isolateCallbackException() throws Exception {
        AtomicInteger healthyCalls = new AtomicInteger();
        PluginPatchRegistration failed = register(
                "dev.example.failed", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> {
                    throw new IllegalStateException("callback-controlled detail");
                });
        register(
                "dev.example.healthy", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> {
                    healthyCalls.incrementAndGet();
                    return PluginPatchResult.unchanged();
                });

        assertEquals("value4", dispatchJoin(failed.methodId(), new ArrayList<>(), "value", 4));
        assertEquals("value5", dispatchJoin(failed.methodId(), new ArrayList<>(), "value", 5));

        assertEquals(2, healthyCalls.get());
        assertEquals(PluginPatchFailure.Category.CALLBACK_EXCEPTION, failed.failureCategory());
    }

    /// Skips the currently executing registration when its callback recursively enters the same method.
    @Test
    public void skipActiveRegistrationDuringRecursion() throws Exception {
        AtomicInteger callbackCalls = new AtomicInteger();
        AtomicInteger originalCalls = new AtomicInteger();
        AtomicReference<PluginPatchRegistration> owner = new AtomicReference<>();
        PluginPatchRegistration registration = register(
                "dev.example.recursive", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> {
                    callbackCalls.incrementAndGet();
                    dispatchJoin(owner.get().methodId(), originalCalls, "nested", 1);
                    return PluginPatchResult.unchanged();
                });
        owner.set(registration);

        assertEquals("outer2", dispatchJoin(registration.methodId(), originalCalls, "outer", 2));

        assertEquals(1, callbackCalls.get());
        assertEquals(2, originalCalls.get());
        assertTrue(registration.isActive());
    }

    /// Disables the registration attempting callback depth seventeen while preserving the first sixteen levels.
    @Test
    public void enforceGlobalRecursionDepthSixteen() throws Exception {
        AtomicInteger nextRecursiveIndex = new AtomicInteger();
        AtomicInteger originalCalls = new AtomicInteger();
        List<PluginPatchRegistration> depthRegistrations = new ArrayList<>();
        AtomicReference<Long> methodId = new AtomicReference<>();
        for (int index = 0; index <= 16; index++) {
            int callbackIndex = index;
            PluginPatchRegistration registration = register(
                    "dev.example.depth" + String.format(java.util.Locale.ROOT, "%02d", index),
                    Set.of(),
                    PluginPatchDeclaration.PatchType.BEFORE,
                    invocation -> {
                        if (nextRecursiveIndex.compareAndSet(callbackIndex, callbackIndex + 1)) {
                            dispatchJoin(methodId.get(), originalCalls, "nested", callbackIndex);
                        }
                        return PluginPatchResult.unchanged();
                    }
            );
            depthRegistrations.add(registration);
            methodId.compareAndSet(null, registration.methodId());
        }

        assertEquals("outer1", dispatchJoin(methodId.get(), originalCalls, "outer", 1));

        assertTrue(depthRegistrations.get(15).isActive());
        assertEquals(PluginPatchFailure.Category.DEPTH_LIMIT,
                depthRegistrations.get(16).failureCategory());
        assertFalse(depthRegistrations.get(16).isActive());
        assertEquals(16, nextRecursiveIndex.get());
    }

    /// Retains a stable method ID across closure and assigns another ID to a distinct overload.
    @Test
    public void retainStableExactMethodIds() throws Exception {
        PluginPatchRegistration first = register(
                "dev.example.first", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> PluginPatchResult.unchanged());
        long originalId = first.methodId();
        first.close();
        PluginPatchRegistration reopened = register(
                "dev.example.reopened", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> PluginPatchResult.unchanged());
        PluginPatchRegistration overload = registerJoinOverload(
                "dev.example.overload", invocation -> PluginPatchResult.unchanged());

        assertEquals(originalId, reopened.methodId());
        assertTrue(originalId != overload.methodId());
    }

    /// Discards a malformed argument replacement and preserves the original invocation arguments.
    @Test
    public void rejectWrongArgumentCountAndType() throws Exception {
        PluginPatchRegistration wrongCount = register(
                "dev.example.count", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> PluginPatchResult.arguments(List.of("changed")));
        register(
                "dev.example.type", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> PluginPatchResult.arguments(List.of("changed", 4L)));

        assertEquals("value4", dispatchJoin(wrongCount.methodId(), new ArrayList<>(), "value", 4));

        assertEquals(PluginPatchFailure.Category.TYPE_MISMATCH, wrongCount.failureCategory());
        PluginPatchRegistration wrongType = registrations.get(registrations.size() - 1);
        assertEquals(PluginPatchFailure.Category.TYPE_MISMATCH, wrongType.failureCategory());
    }

    /// Preserves a valid null reference argument through an unchanged `before` callback.
    @Test
    public void preserveNullableReferenceArgument() throws Exception {
        PluginPatchRegistration registration = register(
                "dev.example.nullable", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> PluginPatchResult.unchanged());

        PatchTargetFixture fixture = new PatchTargetFixture();
        PluginPatchDispatchFrame frame = PluginPatchDispatcher.enter(
                registration.methodId(), fixture, new Object[]{null, 4});
        @Nullable Object[] arguments = frame.arguments();
        @Nullable Object result = PluginPatchDispatcher.finish(
                frame, fixture.join((String) arguments[0], (Integer) arguments[1]));

        assertEquals("null4", result);
        assertTrue(registration.isActive());
    }

    /// Rejects a foreign-loader value whose binary interface name matches the launcher descriptor.
    @Test
    public void rejectForeignLoaderValueWithMatchingBinaryName() throws Exception {
        String interfaceName = "org.jackhuang.hmcl.patchfixture.PatchValueSink";
        String implementationName = "org.jackhuang.hmcl.patchfixture.ForeignPatchValueSink";
        ByteArrayClassLoader foreignLoader = new ByteArrayClassLoader();
        foreignLoader.define(interfaceName, emptyInterface(interfaceName));
        Class<?> foreignImplementation = foreignLoader.define(
                implementationName,
                emptyImplementation(implementationName, interfaceName)
        );
        Object foreignValue = foreignImplementation.getConstructor().newInstance();
        PluginPatchRegistration registration = registerSinkType(
                "dev.example.foreign",
                invocation -> PluginPatchResult.arguments(List.of(foreignValue))
        );
        PatchTargetFixture fixture = new PatchTargetFixture();

        PluginPatchDispatchFrame frame = PluginPatchDispatcher.enter(
                registration.methodId(), fixture, new Object[]{fixture});

        assertEquals(fixture, frame.arguments()[0]);
        assertEquals(PluginPatchFailure.Category.TYPE_MISMATCH, registration.failureCategory());
    }

    /// Accepts null for a reference result and rejects it for a primitive result descriptor.
    @Test
    public void validateNullableReturnAgainstDescriptor() throws Exception {
        PluginPatchRegistration referenceReplacement = register(
                "dev.example.reference", Set.of(), PluginPatchDeclaration.PatchType.REPLACE,
                invocation -> PluginPatchResult.returnValue(null));

        assertNull(dispatchJoin(referenceReplacement.methodId(), new ArrayList<>(), "value", 4));

        referenceReplacement.close();
        PluginPatchRegistration primitiveReplacement = registerArrayLength(
                "dev.example.primitive", invocation -> PluginPatchResult.returnValue(null));
        assertEquals(2, dispatchArrayLength(
                primitiveReplacement.methodId(), new String[][]{{"one"}, {"two"}}));
        assertEquals(PluginPatchFailure.Category.TYPE_MISMATCH,
                primitiveReplacement.failureCategory());
    }

    /// Keeps an in-flight immutable frame isolated from a registration published after method entry.
    @Test
    public void keepDispatchFrameSnapshotImmutable() throws Exception {
        PluginPatchRegistration before = register(
                "dev.example.before", Set.of(), PluginPatchDeclaration.PatchType.BEFORE,
                invocation -> PluginPatchResult.unchanged());
        PatchTargetFixture fixture = new PatchTargetFixture();
        PluginPatchDispatchFrame frame = PluginPatchDispatcher.enter(
                before.methodId(), fixture, new Object[]{"value", 4});
        AtomicInteger afterCalls = new AtomicInteger();
        register(
                "dev.example.after", Set.of(), PluginPatchDeclaration.PatchType.AFTER,
                invocation -> {
                    afterCalls.incrementAndGet();
                    return PluginPatchResult.returnValue(invocation.result() + "-after");
                });

        Object first = PluginPatchDispatcher.finish(frame, fixture.join("value", 4));
        Object second = dispatchJoin(before.methodId(), new ArrayList<>(), "value", 5);

        assertEquals("value4", first);
        assertEquals("value5-after", second);
        assertEquals(1, afterCalls.get());
    }

    /// Replaces the engine timing policy while retaining the same real callback executor.
    ///
    /// @param callbackTimeout per-callback timeout
    /// @param aggregateTimeout aggregate dispatch timeout
    private void replaceEngine(Duration callbackTimeout, Duration aggregateTimeout) {
        closeRegistrations();
        engine = new PluginPatchEngine(targetPolicy, callbackExecutor, callbackTimeout, aggregateTimeout);
    }

    /// Replaces the engine with one focused loaded-class retransformation boundary.
    ///
    /// @param classRetransformer loaded-class retransformation behavior
    private void replaceEngine(ClassRetransformer classRetransformer) {
        closeRegistrations();
        engine = new PluginPatchEngine(
                targetPolicy,
                classRetransformer,
                callbackExecutor,
                CALLBACK_TIMEOUT,
                AGGREGATE_TIMEOUT
        );
    }

    /// Replaces the callback executor and engine with one deterministic worker for queue-order assertions.
    ///
    /// @throws InterruptedException if old worker termination is interrupted
    private void replaceExecutorWithSingleWorker() throws InterruptedException {
        closeRegistrations();
        callbackExecutor.shutdownNow();
        assertTrue(callbackExecutor.awaitTermination(5, TimeUnit.SECONDS));
        callbackExecutor = Executors.newFixedThreadPool(1);
        engine = new PluginPatchEngine(
                targetPolicy,
                callbackExecutor,
                CALLBACK_TIMEOUT,
                AGGREGATE_TIMEOUT
        );
    }

    /// Waits until a deterministic executor queue reaches the expected minimum size.
    ///
    /// @param executor observed executor
    /// @param expectedSize expected minimum queue size
    /// @param timeout maximum wait
    /// @return whether the queue reached the expected size before the deadline
    private static boolean awaitQueueSize(
            ThreadPoolExecutor executor,
            int expectedSize,
            Duration timeout
    ) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (executor.getQueue().size() < expectedSize && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return executor.getQueue().size() >= expectedSize;
    }

    /// Closes every retained registration and clears test ownership.
    private void closeRegistrations() {
        registrations.forEach(PluginPatchRegistration::close);
        registrations.clear();
    }

    /// Registers one callback for the fixture's primary `join(String, int)` method.
    ///
    /// @param pluginId canonical owner ID
    /// @param dependencies dependency IDs used for deterministic ordering
    /// @param type callback position
    /// @param callback callback behavior
    /// @return active registration
    /// @throws PluginPatchFailure if registration validation fails
    private PluginPatchRegistration register(
            String pluginId,
            Set<String> dependencies,
            PluginPatchDeclaration.PatchType type,
            PluginPatchCallback callback
    ) throws PluginPatchFailure {
        PluginPatchRegistration registration = engine.register(
                identity(pluginId),
                dependencies,
                declaration(type, "join", "java.lang.String", "int"),
                callback
        );
        registrations.add(registration);
        return registration;
    }

    /// Registers one callback for the fixture's `join(int, String)` overload.
    ///
    /// @param pluginId canonical owner ID
    /// @param callback callback behavior
    /// @return active registration
    /// @throws PluginPatchFailure if registration validation fails
    private PluginPatchRegistration registerJoinOverload(
            String pluginId,
            PluginPatchCallback callback
    ) throws PluginPatchFailure {
        PluginPatchRegistration registration = engine.register(
                identity(pluginId),
                Set.of(),
                declaration(PluginPatchDeclaration.PatchType.BEFORE,
                        "join", "int", "java.lang.String"),
                callback
        );
        registrations.add(registration);
        return registration;
    }

    /// Registers one replacement for the primitive-returning array fixture method.
    ///
    /// @param pluginId canonical owner ID
    /// @param callback callback behavior
    /// @return active registration
    /// @throws PluginPatchFailure if registration validation fails
    private PluginPatchRegistration registerArrayLength(
            String pluginId,
            PluginPatchCallback callback
    ) throws PluginPatchFailure {
        PluginPatchRegistration registration = engine.register(
                identity(pluginId),
                Set.of(),
                declaration(PluginPatchDeclaration.PatchType.REPLACE,
                        "arrayLength", "java.lang.String[][]"),
                callback
        );
        registrations.add(registration);
        return registration;
    }

    /// Registers one callback for a method whose parameter has a launcher-owned interface identity.
    ///
    /// @param pluginId canonical owner ID
    /// @param callback callback behavior
    /// @return active registration
    /// @throws PluginPatchFailure if registration validation fails
    private PluginPatchRegistration registerSinkType(
            String pluginId,
            PluginPatchCallback callback
    ) throws PluginPatchFailure {
        PluginPatchRegistration registration = engine.register(
                identity(pluginId),
                Set.of(),
                declaration(
                        PluginPatchDeclaration.PatchType.BEFORE,
                        "sinkType",
                        "org.jackhuang.hmcl.patchfixture.PatchValueSink"
                ),
                callback
        );
        registrations.add(registration);
        return registration;
    }

    /// Creates one valid exact artifact identity.
    ///
    /// @param pluginId canonical plugin ID
    /// @return exact artifact identity
    private static PluginArtifactIdentity identity(String pluginId) {
        return new PluginArtifactIdentity(pluginId, "1.0.0", ARTIFACT_SHA256);
    }

    /// Creates one validated declaration against the launcher-owned fixture.
    ///
    /// @param type callback position
    /// @param method method name
    /// @param parameters ordered Java parameter names
    /// @return declaration
    private static PluginPatchDeclaration declaration(
            PluginPatchDeclaration.PatchType type,
            String method,
            String... parameters
    ) {
        return new PluginPatchDeclaration(
                PatchTargetFixture.class.getName(), method, type, List.of(parameters));
    }

    /// Records one event and preserves the current callback state.
    ///
    /// @param events mutable test event sink
    /// @param event literal event
    /// @return unchanged callback result
    private static PluginPatchResult record(List<String> events, String event) {
        events.add(event);
        return PluginPatchResult.unchanged();
    }

    /// Generates one empty public interface with an exact binary name.
    ///
    /// @param binaryName generated interface binary name
    /// @return valid class bytes
    private static byte[] emptyInterface(String binaryName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                binaryName.replace('.', '/'),
                null,
                "java/lang/Object",
                null
        );
        writer.visitEnd();
        return writer.toByteArray();
    }

    /// Generates one public implementation of a foreign-loader interface.
    ///
    /// @param binaryName generated implementation binary name
    /// @param interfaceName implemented interface binary name
    /// @return valid class bytes
    private static byte[] emptyImplementation(String binaryName, String interfaceName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                binaryName.replace('.', '/'),
                null,
                "java/lang/Object",
                new String[]{interfaceName.replace('.', '/')}
        );
        org.objectweb.asm.MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /// Simulates transformed invocation of the fixture's primary join overload.
    ///
    /// @param methodId stable exact method ID
    /// @param events mutable event sink receiving the original-body marker
    /// @param value string argument
    /// @param count integer argument
    /// @return final nullable result
    private static @Nullable Object dispatchJoin(
            long methodId,
            List<String> events,
            String value,
            int count
    ) {
        PatchTargetFixture fixture = new PatchTargetFixture();
        PluginPatchDispatchFrame frame = PluginPatchDispatcher.enter(
                methodId, fixture, new Object[]{value, count});
        @Nullable Object current;
        if (frame.shouldReturn()) {
            current = frame.returnValue();
        } else {
            @Nullable Object[] arguments = frame.arguments();
            events.add("original");
            current = fixture.join((String) arguments[0], (Integer) arguments[1]);
        }
        return PluginPatchDispatcher.finish(frame, current);
    }

    /// Simulates transformed invocation while counting original method executions.
    ///
    /// @param methodId stable exact method ID
    /// @param originalCalls original-body counter
    /// @param value string argument
    /// @param count integer argument
    /// @return final nullable result
    private static @Nullable Object dispatchJoin(
            long methodId,
            AtomicInteger originalCalls,
            String value,
            int count
    ) {
        PatchTargetFixture fixture = new PatchTargetFixture();
        PluginPatchDispatchFrame frame = PluginPatchDispatcher.enter(
                methodId, fixture, new Object[]{value, count});
        @Nullable Object current;
        if (frame.shouldReturn()) {
            current = frame.returnValue();
        } else {
            @Nullable Object[] arguments = frame.arguments();
            originalCalls.incrementAndGet();
            current = fixture.join((String) arguments[0], (Integer) arguments[1]);
        }
        return PluginPatchDispatcher.finish(frame, current);
    }

    /// Simulates transformed invocation of the primitive-returning array fixture method.
    ///
    /// @param methodId stable exact method ID
    /// @param values array argument
    /// @return final primitive-compatible boxed result
    private static @Nullable Object dispatchArrayLength(long methodId, String[][] values) {
        PatchTargetFixture fixture = new PatchTargetFixture();
        PluginPatchDispatchFrame frame = PluginPatchDispatcher.enter(
                methodId, fixture, new Object[]{values});
        @Nullable Object current;
        if (frame.shouldReturn()) {
            current = frame.returnValue();
        } else {
            @Nullable Object[] arguments = frame.arguments();
            current = fixture.arrayLength((String[][]) arguments[0]);
        }
        return PluginPatchDispatcher.finish(frame, current);
    }

    /// Defines foreign-loader classes without delegating launcher fixture names.
    @NotNullByDefault
    private static final class ByteArrayClassLoader extends ClassLoader {
        /// Creates a loader whose parent exposes only bootstrap classes.
        private ByteArrayClassLoader() {
            super(null);
        }

        /// Defines one class from exact test bytes.
        ///
        /// @param binaryName class binary name
        /// @param bytes class bytes
        /// @return defined class
        private Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
