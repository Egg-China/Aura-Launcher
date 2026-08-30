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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/// Stable launcher call surface used by transformed methods without plugin-class references.
@NotNullByDefault
public final class PluginPatchDispatcher {
    /// Live engines keyed by stable exact-method identity.
    private static final ConcurrentMap<Long, PluginPatchEngine> ENGINES = new ConcurrentHashMap<>();

    /// Prevents instantiation of the static dispatcher.
    private PluginPatchDispatcher() {
    }

    /// Runs method-entry callbacks and returns a validated immutable decision frame.
    ///
    /// Missing and concurrently removed plans produce pass-through frames.
    ///
    /// @param methodId stable exact-method identity
    /// @param receiver invocation receiver, or `null` for a static method
    /// @param arguments boxed invocation arguments whose reference elements may be `null`
    /// @return immutable dispatch frame
    public static PluginPatchDispatchFrame enter(
            long methodId,
            @Nullable Object receiver,
            @Nullable Object[] arguments
    ) {
        @Nullable PluginPatchEngine engine = ENGINES.get(methodId);
        if (engine == null) {
            return PluginPatchEngine.passThrough(arguments);
        }
        return engine.enter(methodId, receiver, arguments);
    }

    /// Runs normal-return callbacks for one method-entry frame.
    ///
    /// @param frame immutable frame returned by [#enter]
    /// @param result current normal method result, which may be `null`
    /// @return validated final nullable result
    public static @Nullable Object finish(
            PluginPatchDispatchFrame frame,
            @Nullable Object result
    ) {
        PluginPatchDispatchFrame value = Objects.requireNonNull(frame, "frame");
        @Nullable PluginPatchEngine engine = value.engine();
        return engine == null ? result : engine.finish(value, result);
    }

    /// Binds one stable method identity to its owning engine.
    ///
    /// @param methodId stable exact-method identity
    /// @param engine owning engine
    /// @throws IllegalStateException if another engine already owns the same identity
    static void publish(long methodId, PluginPatchEngine engine) {
        PluginPatchEngine value = Objects.requireNonNull(engine, "engine");
        @Nullable PluginPatchEngine previous = ENGINES.putIfAbsent(methodId, value);
        if (previous != null && previous != value) {
            throw new IllegalStateException("Patch method identity is already owned by another engine: "
                    + Long.toUnsignedString(methodId));
        }
    }

    /// Removes one stable method binding only when the expected engine still owns it.
    ///
    /// @param methodId stable exact-method identity
    /// @param engine expected owning engine
    static void remove(long methodId, PluginPatchEngine engine) {
        ENGINES.remove(methodId, Objects.requireNonNull(engine, "engine"));
    }
}
