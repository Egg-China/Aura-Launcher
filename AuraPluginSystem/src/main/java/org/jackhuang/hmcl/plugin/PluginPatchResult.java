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
package org.jackhuang.hmcl.plugin;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// Immutable transactional result returned by a Java Patch callback.
@NotNullByDefault
public final class PluginPatchResult {
    /// Shared result that preserves the current arguments or result.
    private static final PluginPatchResult UNCHANGED = new PluginPatchResult(Action.UNCHANGED, null, null);

    /// Requested callback action.
    private final Action action;

    /// Complete replacement arguments for [Action.ARGUMENTS], otherwise `null`.
    private final @Nullable @Unmodifiable List<@Nullable Object> arguments;

    /// Explicit nullable result for [Action.RETURN], otherwise `null`.
    private final @Nullable Object returnValue;

    /// Creates one action-specific callback result.
    ///
    /// @param action requested action
    /// @param arguments complete replacement arguments, or `null`
    /// @param returnValue explicit nullable result, or `null`
    private PluginPatchResult(
            Action action,
            @Nullable List<@Nullable Object> arguments,
            @Nullable Object returnValue
    ) {
        this.action = Objects.requireNonNull(action, "action");
        this.arguments = arguments == null ? null : copyNullableValues(arguments);
        this.returnValue = returnValue;
    }

    /// Returns the shared result that preserves the current invocation state.
    ///
    /// @return unchanged result
    public static PluginPatchResult unchanged() {
        return UNCHANGED;
    }

    /// Creates a complete argument replacement for a `before` callback.
    ///
    /// @param arguments complete ordered replacement arguments
    /// @return argument-replacement result
    public static PluginPatchResult arguments(List<@Nullable Object> arguments) {
        return new PluginPatchResult(Action.ARGUMENTS, Objects.requireNonNull(arguments, "arguments"), null);
    }

    /// Creates an explicit nullable method result for a `replace` or `after` callback.
    ///
    /// @param returnValue explicit method result, which may be `null`
    /// @return return-value result
    public static PluginPatchResult returnValue(@Nullable Object returnValue) {
        return new PluginPatchResult(Action.RETURN, null, returnValue);
    }

    /// Returns the requested callback action.
    ///
    /// @return action
    public Action action() {
        return action;
    }

    /// Returns the complete replacement arguments.
    ///
    /// @return immutable replacement arguments, whose reference elements may be `null`
    /// @throws IllegalStateException if this result does not replace arguments
    public @Unmodifiable List<@Nullable Object> arguments() {
        @Nullable List<@Nullable Object> value = arguments;
        if (action != Action.ARGUMENTS || value == null) {
            throw new IllegalStateException("Patch result does not replace arguments");
        }
        return value;
    }

    /// Returns the explicit nullable method result.
    ///
    /// @return explicit method result, which may be `null`
    /// @throws IllegalStateException if this result does not replace the method result
    public @Nullable Object returnValue() {
        if (action != Action.RETURN) {
            throw new IllegalStateException("Patch result does not replace the method return value");
        }
        return returnValue;
    }

    /// Copies a nullable-element list without retaining caller mutation authority.
    ///
    /// @param values caller-owned values
    /// @return immutable nullable-element list
    private static @Unmodifiable List<@Nullable Object> copyNullableValues(List<@Nullable Object> values) {
        List<@Nullable Object> copy = new ArrayList<>(values);
        return Collections.unmodifiableList(copy);
    }

    /// Callback action interpreted by the launcher-owned Patch engine.
    @NotNullByDefault
    public enum Action {
        /// Preserves the current arguments or normal result.
        UNCHANGED,

        /// Replaces the complete argument list before the original method runs.
        ARGUMENTS,

        /// Supplies an explicit replacement or post-processed method result.
        RETURN
    }
}
