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
package org.jackhuang.hmcl.plugin.mixin.bootstrap;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginMutationLock;
import org.jackhuang.hmcl.plugin.patch.PluginInstrumentation;
import org.jackhuang.hmcl.plugin.patch.PluginPatchTransformer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies fail-closed premain behavior that keeps the launcher process alive.
@NotNullByDefault
public final class HmclMixinAgentTest {
    /// Rejects direct non-agent attempts to install or clear process-wide Patch instrumentation.
    @Test
    public void restrictPatchInstrumentationPublicationToPremainClass() {
        InstrumentationProbe probe = instrumentationProbe(true, false);
        try {
            assertThrows(
                    SecurityException.class,
                    () -> PluginInstrumentation.installFromAgent(probe.instrumentation())
            );
            assertThrows(SecurityException.class, PluginInstrumentation::clearFromAgent);
            assertTrue(PluginInstrumentation.current().isEmpty());
            assertEquals(0, probe.addCalls().get());
        } finally {
            resetAgentState();
        }
    }

    /// Requires JVM retransformation support before publishing the Patch instrumentation service.
    @Test
    public void requireRetransformationSupportBeforePatchPublication() {
        InstrumentationProbe probe = instrumentationProbe(false, false);
        try {
            HmclMixinAgent.installPatchInstrumentation(probe.instrumentation());

            assertTrue(PluginInstrumentation.current().isEmpty());
            assertEquals(0, probe.addCalls().get());
        } finally {
            resetAgentState();
        }
    }

    /// Installs exactly one Patch transformer with JVM retransformation enabled.
    @Test
    public void installPatchTransformerWithRetransformation() {
        InstrumentationProbe probe = instrumentationProbe(true, false);
        try {
            HmclMixinAgent.installPatchInstrumentation(probe.instrumentation());

            assertTrue(PluginInstrumentation.current().isPresent());
            assertEquals(1, probe.addCalls().get());
            assertTrue(probe.canRetransform().get());
            assertInstanceOf(PluginPatchTransformer.class, probe.transformer().get());
        } finally {
            resetAgentState();
        }
    }

    /// Installs Patch instrumentation during premain even when no plugin declares a Mixin configuration.
    ///
    /// @param temporaryDirectory empty launcher-local home
    @Test
    public void installPatchTransformerWithoutMixinConfigurations(@TempDir Path temporaryDirectory) {
        InstrumentationProbe probe = instrumentationProbe(true, false);
        System.setProperty("hmcl.dir", temporaryDirectory.toString());
        try {
            HmclMixinAgent.premain(null, probe.instrumentation());

            assertTrue(PluginInstrumentation.current().isPresent());
            assertEquals(1, probe.addCalls().get());
            assertTrue(probe.canRetransform().get());
            assertTrue(PluginAgentSnapshot.current().getActiveArtifacts().isEmpty());
        } finally {
            resetAgentState();
        }
    }

    /// Leaves Patch instrumentation unpublished when transformer installation fails.
    ///
    /// @param temporaryDirectory empty launcher-local home
    @Test
    public void leavePatchInstrumentationEmptyAfterInstallationFailure(@TempDir Path temporaryDirectory) {
        InstrumentationProbe probe = instrumentationProbe(true, true);
        System.setProperty("hmcl.dir", temporaryDirectory.toString());
        try {
            assertDoesNotThrow(() -> HmclMixinAgent.premain(null, probe.instrumentation()));

            assertTrue(PluginInstrumentation.current().isEmpty());
            assertEquals(1, probe.addCalls().get());
            assertEquals("true", System.getProperty(HmclMixinBootstrap.DISABLE_PROPERTY));
        } finally {
            resetAgentState();
        }
    }

    /// Clears exact authorization and disables further Mixin relaunch after initialization failure.
    @Test
    public void failClosedAfterInitializationFailure() {
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                "dev.hmclce.test.agent-failure",
                "1.0.0",
                "a".repeat(64)
        );
        PluginAgentSnapshot.publish(List.of(PluginAgentSnapshot.registration(
                identity,
                PluginAgentSnapshot.calculateMixinConfigurationDigest(List.of("failure.json")),
                List.of()
        )));
        System.setProperty(HmclMixinBootstrap.ACTIVE_PROPERTY, identity.getPluginId());
        System.clearProperty(HmclMixinBootstrap.DISABLE_PROPERTY);
        try {
            assertDoesNotThrow(() -> HmclMixinAgent.handleInitializationFailure(new IOException("expected")));

            assertTrue(PluginAgentSnapshot.current().getActiveArtifacts().isEmpty());
            assertEquals("true", System.getProperty(HmclMixinBootstrap.DISABLE_PROPERTY));
            assertNull(System.getProperty(HmclMixinBootstrap.ACTIVE_PROPERTY));
        } finally {
            clearProperties();
            PluginAgentSnapshot.clear();
        }
    }

    /// Returns before touching instrumentation when safe mode disables plugin Mixins.
    @Test
    public void skipInstrumentationInSafeMode() {
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                HmclMixinAgentTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("Instrumentation must not be used in safe mode: " + method.getName());
                }
        );
        System.setProperty(HmclMixinBootstrap.DISABLE_PROPERTY, "true");
        System.setProperty(HmclMixinBootstrap.ACTIVE_PROPERTY, "forged");
        try {
            assertDoesNotThrow(() -> HmclMixinAgent.premain(null, instrumentation));

            assertTrue(PluginAgentSnapshot.current().getActiveArtifacts().isEmpty());
            assertNull(System.getProperty(HmclMixinBootstrap.ACTIVE_PROPERTY));
        } finally {
            clearProperties();
            PluginAgentSnapshot.clear();
        }
    }

    /// Keeps package, state, and permission mutations outside the complete Agent initialization window.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if concurrency coordination or lock acquisition fails
    @Test
    public void holdMutationLockUntilAgentInitializationCompletes(@TempDir Path temporaryDirectory) throws Exception {
        CountDownLatch initializationEntered = new CountDownLatch(1);
        CountDownLatch releaseInitialization = new CountDownLatch(1);
        CountDownLatch mutationAttempted = new CountDownLatch(1);
        CountDownLatch mutationEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> initialization = executor.submit(() -> {
                HmclMixinAgent.runInitializationUnderMutationLock(temporaryDirectory, () -> {
                    initializationEntered.countDown();
                    await(releaseInitialization);
                });
                return null;
            });
            initializationEntered.await();

            Future<?> mutation = executor.submit(() -> {
                mutationAttempted.countDown();
                new PluginMutationLock(temporaryDirectory).run(mutationEntered::countDown);
                return null;
            });
            mutationAttempted.await();

            assertEquals(1L, mutationEntered.getCount());
            releaseInitialization.countDown();
            initialization.get();
            mutation.get();
            assertEquals(0L, mutationEntered.getCount());
        } finally {
            executor.shutdownNow();
        }
    }

    /// Produces the same content digest from captured bytes and the exact open JAR handle used by premain.
    ///
    /// @param temporaryDirectory isolated JAR directory
    /// @throws Exception if archive creation or hashing fails
    @Test
    public void matchCapturedAndOpenJarContentDigest(@TempDir Path temporaryDirectory) throws Exception {
        byte @Unmodifiable [] jarBytes = createJar("verified.txt", "verified");
        Path jarPath = temporaryDirectory.resolve("verified.jar");
        Files.write(jarPath, jarBytes);

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            assertEquals(
                    HmclMixinBootstrap.calculateAgentJarDigest(jarBytes),
                    HmclMixinBootstrap.calculateAgentJarDigest(jarFile)
            );
        }
    }

    /// Refuses to append a JAR whose bytes changed after the Agent configuration captured its digest.
    ///
    /// @param temporaryDirectory isolated JAR directory
    /// @throws Exception if archive creation or mutation fails
    @Test
    public void rejectChangedJarBeforeSystemClassPathAppend(@TempDir Path temporaryDirectory) throws Exception {
        byte @Unmodifiable [] originalJar = createJar("verified.txt", "verified");
        Path jarPath = temporaryDirectory.resolve("plugin.jar");
        Files.write(jarPath, originalJar);
        HmclMixinBootstrap.AgentClassPathEntry entry = new HmclMixinBootstrap.AgentClassPathEntry(
                jarPath,
                HmclMixinBootstrap.calculateAgentJarDigest(originalJar)
        );
        Files.write(jarPath, createJar("replaced.txt", "replaced"));
        AtomicInteger appendCalls = new AtomicInteger();
        Instrumentation instrumentation = instrumentationRecordingAppends(appendCalls);

        assertThrows(
                IOException.class,
                () -> HmclMixinAgent.appendPluginClassPath(List.of(entry), instrumentation)
        );
        assertEquals(0, appendCalls.get());
    }

    /// Creates an Instrumentation proxy that records only system class-path append calls.
    ///
    /// @param appendCalls append invocation counter
    /// @return instrumentation proxy
    private static Instrumentation instrumentationRecordingAppends(AtomicInteger appendCalls) {
        return (Instrumentation) Proxy.newProxyInstance(
                HmclMixinAgentTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("appendToSystemClassLoaderSearch")) {
                        appendCalls.incrementAndGet();
                        return null;
                    }
                    throw new AssertionError("Ucepected Instrumentation call: " + method.getName());
                }
        );
    }

    /// Creates a strict Instrumentation proxy for Patch transformer publication tests.
    ///
    /// @param retransformSupported whether the JVM reports retransformation support
    /// @param failInstallation whether `addTransformer` must fail
    /// @return instrumentation proxy and observable calls
    private static InstrumentationProbe instrumentationProbe(
            boolean retransformSupported,
            boolean failInstallation
    ) {
        AtomicReference<@Nullable ClassFileTransformer> transformer = new AtomicReference<>();
        AtomicReference<Boolean> canRetransform = new AtomicReference<>(false);
        AtomicInteger addCalls = new AtomicInteger();
        AtomicInteger removeCalls = new AtomicInteger();
        InvocationHandler handler = (
                Object proxy,
                Method method,
                Object @Nullable [] arguments
        ) -> switch (method.getName()) {
            case "isRetransformClassesSupported" -> retransformSupported;
            case "addTransformer" -> {
                addCalls.incrementAndGet();
                if (failInstallation) {
                    throw new IllegalStateException("expected transformer installation failure");
                }
                Object[] values = Objects.requireNonNull(arguments, "addTransformer arguments");
                transformer.set((ClassFileTransformer) values[0]);
                canRetransform.set((Boolean) values[1]);
                yield null;
            }
            case "removeTransformer" -> {
                removeCalls.incrementAndGet();
                yield true;
            }
            case "toString" -> "Patch instrumentation probe";
            default -> throw new AssertionError("Unexpected Instrumentation call: " + method.getName());
        };
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                HmclMixinAgentTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                handler
        );
        return new InstrumentationProbe(
                instrumentation,
                transformer,
                canRetransform,
                addCalls,
                removeCalls
        );
    }

    /// Creates one deterministic JAR entry fixture.
    ///
    /// @param entryName archive entry name
    /// @param contents UTF-8 entry text
    /// @return complete JAR bytes
    /// @throws IOException if archive generation fails
    private static byte @Unmodifiable [] createJar(String entryName, String contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            JarEntry entry = new JarEntry(entryName);
            entry.setTime(0);
            output.putNextEntry(entry);
            output.write(contents.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    /// Waits for a coordination latch while preserving interruption as an I/O failure.
    ///
    /// @param latch latch to await
    /// @throws IOException if the current thread is interrupted
    private static void await(CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while coordinating the Agent lock test", exception);
        }
    }

    /// Clears every Mixin bootstrap diagnostic property changed by these tests.
    private static void clearProperties() {
        System.clearProperty(HmclMixinBootstrap.ACTIVE_PROPERTY);
        System.clearProperty(HmclMixinBootstrap.AGENT_ACTIVE_PROPERTY);
        System.clearProperty(HmclMixinBootstrap.DISABLE_PROPERTY);
        System.clearProperty("hmcl.dir");
    }

    /// Clears published Patch state, Mixin state, and diagnostic properties after one test.
    private static void resetAgentState() {
        HmclMixinAgent.handleInitializationFailure(new IOException("test cleanup"));
        clearProperties();
        PluginAgentSnapshot.clear();
        assertTrue(PluginInstrumentation.current().isEmpty());
    }

    /// Observable strict Instrumentation proxy state.
    ///
    /// @param instrumentation strict proxy
    /// @param transformer installed transformer, or `null`
    /// @param canRetransform installed retransformation flag
    /// @param addCalls transformer add call count
    /// @param removeCalls transformer removal call count
    @NotNullByDefault
    private record InstrumentationProbe(
            Instrumentation instrumentation,
            AtomicReference<@Nullable ClassFileTransformer> transformer,
            AtomicReference<Boolean> canRetransform,
            AtomicInteger addCalls,
            AtomicInteger removeCalls
    ) {
    }
}
