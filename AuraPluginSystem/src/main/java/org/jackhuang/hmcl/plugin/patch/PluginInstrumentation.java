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

import org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinAgent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/// Opaque launcher-owned publication of the JVM Patch instrumentation service.
///
/// Raw `Instrumentation` and unguarded engine registration are intentionally not exposed. Only the premain class may
/// install or clear this process-wide service.
@NotNullByDefault
public final class PluginInstrumentation {
    /// Caller resolver used to restrict process-wide publication to the premain class identity.
    private static final StackWalker CALLER_WALKER = StackWalker.getInstance(
            StackWalker.Option.RETAIN_CLASS_REFERENCE
    );

    /// Current process service, or `null` before successful premain publication.
    private static final AtomicReference<@Nullable PluginInstrumentation> CURRENT = new AtomicReference<>();

    /// Private JVM instrumentation handle used only for transformer lifecycle.
    private final Instrumentation instrumentation;

    /// Sole process-wide retransformation-capable Patch transformer.
    private final PluginPatchTransformer transformer;

    /// Language-neutral callback engine owned by this instrumentation service.
    private final PluginPatchEngine engine;

    /// Creates one unpublished service after transformer installation succeeds.
    ///
    /// @param instrumentation private JVM instrumentation handle
    /// @param transformer installed Patch transformer
    /// @param engine Patch callback engine
    private PluginInstrumentation(
            Instrumentation instrumentation,
            PluginPatchTransformer transformer,
            PluginPatchEngine engine
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.transformer = Objects.requireNonNull(transformer, "transformer");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /// Returns the optional opaque service published by successful premain initialization.
    ///
    /// @return current service, or empty when Patch instrumentation is unavailable
    public static Optional<PluginInstrumentation> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /// Installs and publishes the sole retransformation-capable Patch transformer for this process.
    ///
    /// Unsupported JVM retransformation leaves the publication empty without affecting ordinary Mixin support.
    ///
    /// @param instrumentation active JVM instrumentation handle
    /// @throws SecurityException if called by anything except the exact premain class
    /// @throws IllegalStateException if another Patch service is already active
    public static void installFromAgent(Instrumentation instrumentation) {
        requireAgentCaller();
        Instrumentation value = Objects.requireNonNull(instrumentation, "instrumentation");
        if (CURRENT.get() != null) {
            throw new IllegalStateException("Patch instrumentation is already published");
        }
        if (!value.isRetransformClassesSupported()) {
            return;
        }

        ClassLoader launcherClassLoader = Objects.requireNonNull(
                PluginInstrumentation.class.getClassLoader(),
                "launcher class loader"
        );
        PluginPatchTargetPolicy policy = new PluginPatchTargetPolicy(
                PluginInstrumentation.class,
                () -> List.of(value.getAllLoadedClasses())
        );
        PluginPatchEngine engine = new PluginPatchEngine(policy);
        PluginPatchTransformer transformer = new PluginPatchTransformer(engine, launcherClassLoader);
        value.addTransformer(transformer, true);
        PluginInstrumentation service = new PluginInstrumentation(value, transformer, engine);
        if (!CURRENT.compareAndSet(null, service)) {
            value.removeTransformer(transformer);
            throw new IllegalStateException("Patch instrumentation was published concurrently");
        }
    }

    /// Unpublishes and removes the Patch transformer during premain rollback or test process cleanup.
    ///
    /// Removal failure cannot republish callback plans; the dispatcher remains fail-open once registrations close.
    ///
    /// @throws SecurityException if called by anything except the exact premain class
    public static void clearFromAgent() {
        requireAgentCaller();
        @Nullable PluginInstrumentation service = CURRENT.getAndSet(null);
        if (service == null) {
            return;
        }
        try {
            service.instrumentation.removeTransformer(service.transformer);
        } catch (RuntimeException ignored) {
            // Publication is already empty; a JVM removal failure must not break launcher recovery.
        }
    }

    /// Returns the internal engine to other classes in the guarded Patch implementation package.
    ///
    /// @return language-neutral Patch engine
    PluginPatchEngine engine() {
        return engine;
    }

    /// Requires the exact premain class as the immediate publication caller.
    ///
    /// @throws SecurityException if the caller is not the Aura premain class
    private static void requireAgentCaller() {
        Class<?> caller = CALLER_WALKER.walk(frames -> frames
                .skip(2)
                .findFirst()
                .orElseThrow(() -> new SecurityException("Patch instrumentation caller is unavailable"))
                .getDeclaringClass());
        if (caller != HmclMixinAgent.class) {
            throw new SecurityException("Patch instrumentation publication is restricted to premain");
        }
    }
}
