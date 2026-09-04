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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies stable failure mapping around the package-internal JVM retransformation boundary.
@NotNullByDefault
public final class PluginInstrumentationRetransformationTest {
    /// Ignores an unrelated agent retransformation callback running on another thread.
    ///
    /// @throws InterruptedException if the unrelated callback thread cannot be joined
    @Test
    public void ignoreRetransformationOutcomeFromAnotherThread() throws InterruptedException {
        PluginPatchRetransformationMonitor monitor = new PluginPatchRetransformationMonitor();
        try (PluginPatchRetransformationMonitor.Attempt attempt = monitor.begin(PluginInstrumentation.class)) {
            Thread unrelatedRetransformation = new Thread(
                    () -> monitor.record(PluginInstrumentation.class, null),
                    "unrelated-patch-retransformation"
            );
            unrelatedRetransformation.start();
            unrelatedRetransformation.join();

            PluginPatchFailure failure = assertThrows(PluginPatchFailure.class, attempt::requireSuccess);
            assertEquals(PluginPatchFailure.Category.TRANSFORM_FAILURE, failure.category());
        }
    }

    /// Maps an explicit non-modifiable result without invoking JVM retransformation.
    @Test
    public void mapNonModifiableClass() {
        AtomicInteger retransforms = new AtomicInteger();
        Instrumentation instrumentation = instrumentation(false, null, null, retransforms);

        PluginPatchFailure failure = assertThrows(PluginPatchFailure.class, () ->
                PluginInstrumentation.retransform(
                        instrumentation,
                        new PluginPatchRetransformationMonitor(),
                        PluginInstrumentation.class
                ));

        assertEquals(PluginPatchFailure.Category.UNMODIFIABLE_CLASS, failure.category());
        assertEquals(0, retransforms.get());
    }

    /// Maps the checked JVM race failure and releases the monitor scope for a later attempt.
    @Test
    public void mapUnmodifiableClassExceptionAndReleaseAttempt() {
        AtomicInteger retransforms = new AtomicInteger();
        Instrumentation instrumentation = instrumentation(
                true,
                null,
                new UnmodifiableClassException("expected"),
                retransforms
        );
        PluginPatchRetransformationMonitor monitor = new PluginPatchRetransformationMonitor();

        PluginPatchFailure first = assertThrows(PluginPatchFailure.class, () ->
                PluginInstrumentation.retransform(instrumentation, monitor, PluginInstrumentation.class));
        PluginPatchFailure second = assertThrows(PluginPatchFailure.class, () ->
                PluginInstrumentation.retransform(instrumentation, monitor, PluginInstrumentation.class));

        assertEquals(PluginPatchFailure.Category.UNMODIFIABLE_CLASS, first.category());
        assertEquals(PluginPatchFailure.Category.UNMODIFIABLE_CLASS, second.category());
        assertEquals(2, retransforms.get());
    }

    /// Maps a JVM linkage failure to the stable transformation category.
    @Test
    public void mapClassFormatFailure() {
        AtomicInteger retransforms = new AtomicInteger();
        Instrumentation instrumentation = instrumentation(
                true,
                null,
                new ClassFormatError("expected"),
                retransforms
        );

        PluginPatchFailure failure = assertThrows(PluginPatchFailure.class, () ->
                PluginInstrumentation.retransform(
                        instrumentation,
                        new PluginPatchRetransformationMonitor(),
                        PluginInstrumentation.class
                ));

        assertEquals(PluginPatchFailure.Category.TRANSFORM_FAILURE, failure.category());
        assertEquals(1, retransforms.get());
    }

    /// Maps Instrumentation inspection failure before opening a retransformation scope.
    @Test
    public void mapModifiabilityInspectionFailure() {
        AtomicInteger retransforms = new AtomicInteger();
        Instrumentation instrumentation = instrumentation(
                true,
                new IllegalStateException("expected"),
                null,
                retransforms
        );

        PluginPatchFailure failure = assertThrows(PluginPatchFailure.class, () ->
                PluginInstrumentation.retransform(
                        instrumentation,
                        new PluginPatchRetransformationMonitor(),
                        PluginInstrumentation.class
                ));

        assertEquals(PluginPatchFailure.Category.TRANSFORM_FAILURE, failure.category());
        assertEquals(0, retransforms.get());
    }

    /// Creates one strict Instrumentation proxy for retransformation mapping.
    ///
    /// @param modifiable modifiability result
    /// @param inspectionFailure failure thrown by `isModifiableClass`, or `null`
    /// @param retransformationFailure failure thrown by `retransformClasses`, or `null`
    /// @param retransforms retransformation invocation counter
    /// @return strict Instrumentation proxy
    private static Instrumentation instrumentation(
            boolean modifiable,
            @Nullable Throwable inspectionFailure,
            @Nullable Throwable retransformationFailure,
            AtomicInteger retransforms
    ) {
        InvocationHandler handler = (
                Object proxy,
                Method method,
                Object @Nullable [] arguments
        ) -> switch (method.getName()) {
            case "isModifiableClass" -> {
                if (inspectionFailure != null) {
                    throw inspectionFailure;
                }
                yield modifiable;
            }
            case "retransformClasses" -> {
                retransforms.incrementAndGet();
                if (retransformationFailure != null) {
                    throw retransformationFailure;
                }
                yield null;
            }
            case "toString" -> "Patch retransformation instrumentation probe";
            default -> throw new AssertionError("Unexpected Instrumentation call: " + method.getName());
        };
        return (Instrumentation) Proxy.newProxyInstance(
                PluginInstrumentationRetransformationTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                handler
        );
    }
}
