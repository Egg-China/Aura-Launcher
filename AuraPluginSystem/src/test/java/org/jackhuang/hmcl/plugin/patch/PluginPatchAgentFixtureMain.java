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

import org.jackhuang.hmcl.patchfixture.PatchAgentLoadedTarget;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jackhuang.hmcl.plugin.PluginPatchResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Exercises loaded-class Patch activation and restoration inside a real `-javaagent` child JVM.
@NotNullByDefault
public final class PluginPatchAgentFixtureMain {
    /// Binary name of the target that must remain undefined until after its registration.
    private static final String FUTURE_TARGET = "org.jackhuang.hmcl.patchfixture.PatchAgentFutureTarget";

    /// Binary name of the loaded target whose incapable-transformer bytes omit the declared method.
    private static final String MALFORMED_TARGET = "org.jackhuang.hmcl.patchfixture.PatchAgentMalformedTarget";

    /// Exact artifact identity used by the child fixture registration.
    private static final PluginArtifactIdentity IDENTITY = new PluginArtifactIdentity(
            "dev.aura.test.patch-agent", "1.0.0", "a".repeat(64));

    /// Prevents instantiation.
    private PluginPatchAgentFixtureMain() {
    }

    /// Verifies loaded and future targets, callback kinds, conflicts, Mixin preservation, and restoration.
    ///
    /// @param arguments unused child-process arguments
    /// @throws Exception if agent publication, registration, transformation, or invocation fails
    public static void main(String @Unmodifiable [] arguments) throws Exception {
        requireValue("original", PatchAgentLoadedTarget.echo("original"), "initial loaded target");
        requireValue("mixin-marker", PatchAgentLoadedTarget.mixinMarker(), "initial Mixin marker");
        PluginPatchEngine engine = PluginInstrumentation.current()
                .orElseThrow(() -> new IllegalStateException("Patch instrumentation was not published"))
                .engine();
        List<PluginPatchRegistration> registrations = new ArrayList<>();
        try {
            registrations.add(engine.register(
                    IDENTITY,
                    Set.of(),
                    declaration(PatchAgentLoadedTarget.class.getName(), "echo",
                            PluginPatchDeclaration.PatchType.BEFORE, "java.lang.String"),
                    invocation -> PluginPatchResult.arguments(List.of("patched"))
            ));
            registrations.add(engine.register(
                    IDENTITY,
                    Set.of(),
                    declaration(PatchAgentLoadedTarget.class.getName(), "decorate",
                            PluginPatchDeclaration.PatchType.AFTER, "java.lang.String"),
                    invocation -> PluginPatchResult.returnValue(invocation.result() + "-after")
            ));
            registrations.add(engine.register(
                    IDENTITY,
                    Set.of(),
                    declaration(PatchAgentLoadedTarget.class.getName(), "replace",
                            PluginPatchDeclaration.PatchType.REPLACE, "java.lang.String"),
                    invocation -> PluginPatchResult.returnValue("replacement")
            ));
            registrations.add(engine.register(
                    IDENTITY,
                    Set.of(),
                    declaration(PatchAgentLoadedTarget.class.getName(), "mixinMarker",
                            PluginPatchDeclaration.PatchType.AFTER),
                    invocation -> PluginPatchResult.returnValue(invocation.result() + "-patch")
            ));
            registrations.add(engine.register(
                    IDENTITY,
                    Set.of(),
                    declaration(FUTURE_TARGET, "echo",
                            PluginPatchDeclaration.PatchType.BEFORE, "java.lang.String"),
                    invocation -> PluginPatchResult.arguments(List.of("future-patched"))
            ));

            requireReplacementConflict(engine);
            requireTransformerFailureRollback(engine);
            requireValue("patched", PatchAgentLoadedTarget.echo("original"), "registered loaded target");
            requireValue("original-after", PatchAgentLoadedTarget.decorate("original"), "after callback");
            requireValue("replacement", PatchAgentLoadedTarget.replace("original"), "replace callback");
            requireValue("mixin-marker-patch", PatchAgentLoadedTarget.mixinMarker(), "patched Mixin marker");
            requireValue("future-patched", invokeFuture("original"), "future target first definition");
        } finally {
            for (int index = registrations.size() - 1; index >= 0; index--) {
                registrations.get(index).close();
            }
        }
        requireValue("restored", PatchAgentLoadedTarget.echo("restored"), "restored loaded target");
        requireValue("restored", PatchAgentLoadedTarget.decorate("restored"), "restored after target");
        requireValue("restored", PatchAgentLoadedTarget.replace("restored"), "restored replace target");
        requireValue("mixin-marker", PatchAgentLoadedTarget.mixinMarker(), "restored Mixin marker");
        requireValue("restored", invokeFuture("restored"), "restored future target");
        System.out.println("PATCH_AGENT_LIFECYCLE_OK");
    }

    /// Requires a swallowed JVM transformer exception to fail registration and trigger one restoration attempt.
    ///
    /// @param engine active child-process Patch engine
    /// @throws Exception if failure mapping, rollback, or retransformation count is incorrect
    private static void requireTransformerFailureRollback(PluginPatchEngine engine) throws Exception {
        int plansBefore = engine.snapshotMethods().size();
        int retransformsBefore = PatchMixinFixtureAgent.malformedRetransformations();
        try {
            PluginPatchRegistration unexpected = engine.register(
                    new PluginArtifactIdentity("dev.aura.test.patch-transform-failure", "1.0.0", "c".repeat(64)),
                    Set.of(),
                    declaration(MALFORMED_TARGET, "echo",
                            PluginPatchDeclaration.PatchType.BEFORE, "java.lang.String"),
                    invocation -> PluginPatchResult.unchanged()
            );
            unexpected.close();
            throw new IllegalStateException("swallowed transformer failure was accepted");
        } catch (PluginPatchFailure failure) {
            if (failure.category() != PluginPatchFailure.Category.TRANSFORM_FAILURE) {
                throw failure;
            }
        }
        requireCount(plansBefore, engine.snapshotMethods().size(), "rolled-back method plan count");
        requireCount(
                2,
                PatchMixinFixtureAgent.malformedRetransformations() - retransformsBefore,
                "failure and restoration retransformation count"
        );
    }

    /// Creates one exact schema-v5 Patch declaration.
    ///
    /// @param target binary target class name
    /// @param method target method name
    /// @param type callback position
    /// @param parameters ordered Java parameter names
    /// @return Patch declaration
    private static PluginPatchDeclaration declaration(
            String target,
            String method,
            PluginPatchDeclaration.PatchType type,
            String... parameters
    ) {
        return new PluginPatchDeclaration(target, method, type, List.of(parameters));
    }

    /// Verifies that a second replacement is rejected without disturbing the active replacement.
    ///
    /// @param engine active child-process Patch engine
    /// @throws Exception if registration fails with the wrong category or unexpectedly succeeds
    private static void requireReplacementConflict(PluginPatchEngine engine) throws Exception {
        try {
            PluginPatchRegistration unexpected = engine.register(
                    new PluginArtifactIdentity("dev.aura.test.patch-conflict", "1.0.0", "b".repeat(64)),
                    Set.of(),
                    declaration(PatchAgentLoadedTarget.class.getName(), "replace",
                            PluginPatchDeclaration.PatchType.REPLACE, "java.lang.String"),
                    invocation -> PluginPatchResult.returnValue("wrong")
            );
            unexpected.close();
            throw new IllegalStateException("replacement conflict was accepted");
        } catch (PluginPatchFailure failure) {
            if (failure.category() != PluginPatchFailure.Category.REPLACEMENT_CONFLICT) {
                throw failure;
            }
        }
    }

    /// Invokes the future target without resolving its class before registration.
    ///
    /// @param value input value
    /// @return exact non-null invocation result
    /// @throws ReflectiveOperationException if the target cannot be loaded or invoked
    private static String invokeFuture(String value) throws ReflectiveOperationException {
        Class<?> target = Class.forName(FUTURE_TARGET, true, ClassLoader.getSystemClassLoader());
        Method echo = target.getMethod("echo", String.class);
        return (String) Objects.requireNonNull(echo.invoke(null, value), "future target result");
    }

    /// Requires one exact child-fixture value.
    ///
    /// @param expected expected value
    /// @param actual observed value
    /// @param stage stable stage label
    private static void requireValue(String expected, String actual, String stage) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(stage + " expected " + expected + " but observed " + actual);
        }
    }

    /// Requires one exact child-fixture count.
    ///
    /// @param expected expected count
    /// @param actual observed count
    /// @param stage stable stage label
    private static void requireCount(int expected, int actual, String stage) {
        if (expected != actual) {
            throw new IllegalStateException(stage + " expected " + expected + " but observed " + actual);
        }
    }
}
