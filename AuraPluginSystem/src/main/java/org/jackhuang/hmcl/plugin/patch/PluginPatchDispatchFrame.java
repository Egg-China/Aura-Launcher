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
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;

/// Immutable method-entry decision retained until normal method completion.
@NotNullByDefault
public final class PluginPatchDispatchFrame {
    /// Engine that created the live plan snapshot, or `null` for a pass-through frame.
    private final @Nullable PluginPatchEngine engine;

    /// Immutable live method plan captured at entry, or `null` for pass-through.
    private final @Nullable PluginPatchEngine.MethodPlan plan;

    /// Invocation receiver, or `null` for a static method.
    private final @Nullable Object receiver;

    /// Complete validated arguments after successful `before` callbacks.
    private final @Nullable Object @Unmodifiable [] arguments;

    /// Whether a successful replacement supplied the current result.
    private final boolean shouldReturn;

    /// Explicit nullable replacement value when [#shouldReturn] is true.
    private final @Nullable Object returnValue;

    /// Absolute aggregate dispatch deadline on the monotonic clock.
    private final long deadlineNanos;

    /// Creates one immutable live or pass-through dispatch frame.
    ///
    /// @param engine owning engine, or `null`
    /// @param plan captured method plan, or `null`
    /// @param receiver invocation receiver, or `null`
    /// @param arguments complete current arguments
    /// @param shouldReturn whether replacement supplied a result
    /// @param returnValue explicit nullable replacement value
    /// @param deadlineNanos absolute aggregate deadline
    PluginPatchDispatchFrame(
            @Nullable PluginPatchEngine engine,
            @Nullable PluginPatchEngine.MethodPlan plan,
            @Nullable Object receiver,
            @Nullable Object[] arguments,
            boolean shouldReturn,
            @Nullable Object returnValue,
            long deadlineNanos
    ) {
        this.engine = engine;
        this.plan = plan;
        this.receiver = receiver;
        this.arguments = Objects.requireNonNull(arguments, "arguments").clone();
        this.shouldReturn = shouldReturn;
        this.returnValue = returnValue;
        this.deadlineNanos = deadlineNanos;
    }

    /// Returns a defensive copy of the arguments the original method must receive.
    ///
    /// @return complete arguments whose reference elements may be `null`
    public @Nullable Object @Unmodifiable [] arguments() {
        return arguments.clone();
    }

    /// Returns whether the original body must be skipped for a replacement result.
    ///
    /// @return whether a replacement result is present
    public boolean shouldReturn() {
        return shouldReturn;
    }

    /// Returns the explicit nullable replacement result.
    ///
    /// @return replacement result, or `null`
    /// @throws IllegalStateException when the original method must still run
    public @Nullable Object returnValue() {
        if (!shouldReturn) {
            throw new IllegalStateException("Patch frame does not contain a replacement result");
        }
        return returnValue;
    }

    /// Returns the engine that created this frame.
    ///
    /// @return owning engine, or `null` for pass-through
    @Nullable PluginPatchEngine engine() {
        return engine;
    }

    /// Returns the immutable plan captured at method entry.
    ///
    /// @return method plan, or `null` for pass-through
    @Nullable PluginPatchEngine.MethodPlan plan() {
        return plan;
    }

    /// Returns the invocation receiver.
    ///
    /// @return receiver, or `null` for static invocation
    @Nullable Object receiver() {
        return receiver;
    }

    /// Returns the absolute aggregate deadline.
    ///
    /// @return monotonic deadline in nanoseconds
    long deadlineNanos() {
        return deadlineNanos;
    }
}
