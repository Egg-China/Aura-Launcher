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

/// Immutable Java callback view of one method invocation at a declared Patch position.
@NotNullByDefault
public final class PluginPatchInvocation {
    /// Authoritative schema-v5 Patch declaration.
    private final PluginPatchDeclaration declaration;

    /// Invocation receiver, or `null` for a static method.
    private final @Nullable Object receiver;

    /// Immutable ordered invocation arguments, whose reference elements may be `null`.
    private final @Unmodifiable List<@Nullable Object> arguments;

    /// Current normal method result for an `after` callback, otherwise `null`.
    private final @Nullable Object result;

    /// Creates one immutable callback invocation after verifying its declared position.
    ///
    /// @param declaration authoritative Patch declaration
    /// @param expectedType required callback position
    /// @param receiver invocation receiver, or `null` for a static method
    /// @param arguments ordered invocation arguments
    /// @param result current normal result for `after`, otherwise `null`
    private PluginPatchInvocation(
            PluginPatchDeclaration declaration,
            PluginPatchDeclaration.PatchType expectedType,
            @Nullable Object receiver,
            List<@Nullable Object> arguments,
            @Nullable Object result
    ) {
        this.declaration = requireType(declaration, expectedType);
        this.receiver = receiver;
        this.arguments = copyNullableValues(arguments);
        this.result = result;
    }

    /// Creates an invocation for a callback before the original method body.
    ///
    /// @param declaration authoritative `before` declaration
    /// @param receiver invocation receiver, or `null` for a static method
    /// @param arguments ordered invocation arguments
    /// @return immutable invocation
    public static PluginPatchInvocation before(
            PluginPatchDeclaration declaration,
            @Nullable Object receiver,
            List<@Nullable Object> arguments
    ) {
        return new PluginPatchInvocation(
                declaration, PluginPatchDeclaration.PatchType.BEFORE, receiver, arguments, null);
    }

    /// Creates an invocation for a replacement callback before the original method body.
    ///
    /// @param declaration authoritative `replace` declaration
    /// @param receiver invocation receiver, or `null` for a static method
    /// @param arguments ordered invocation arguments
    /// @return immutable invocation
    public static PluginPatchInvocation replace(
            PluginPatchDeclaration declaration,
            @Nullable Object receiver,
            List<@Nullable Object> arguments
    ) {
        return new PluginPatchInvocation(
                declaration, PluginPatchDeclaration.PatchType.REPLACE, receiver, arguments, null);
    }

    /// Creates an invocation for a callback after a normal method result.
    ///
    /// @param declaration authoritative `after` declaration
    /// @param receiver invocation receiver, or `null` for a static method
    /// @param arguments ordered invocation arguments
    /// @param result current normal result, which may be `null`
    /// @return immutable invocation
    public static PluginPatchInvocation after(
            PluginPatchDeclaration declaration,
            @Nullable Object receiver,
            List<@Nullable Object> arguments,
            @Nullable Object result
    ) {
        return new PluginPatchInvocation(
                declaration, PluginPatchDeclaration.PatchType.AFTER, receiver, arguments, result);
    }

    /// Returns the authoritative Patch declaration.
    ///
    /// @return Patch declaration
    public PluginPatchDeclaration declaration() {
        return declaration;
    }

    /// Returns the callback position from the authoritative declaration.
    ///
    /// @return callback position
    public PluginPatchDeclaration.PatchType type() {
        return declaration.getType();
    }

    /// Returns the invocation receiver.
    ///
    /// @return receiver, or `null` for a static method
    public @Nullable Object receiver() {
        return receiver;
    }

    /// Returns the immutable ordered invocation arguments.
    ///
    /// @return invocation arguments, whose reference elements may be `null`
    public @Unmodifiable List<@Nullable Object> arguments() {
        return arguments;
    }

    /// Returns the current normal result supplied to an `after` callback.
    ///
    /// @return current result, or `null` when the result is null or this is not an `after` callback
    public @Nullable Object result() {
        return result;
    }

    /// Requires the declaration to be valid and to use one exact callback position.
    ///
    /// @param declaration candidate declaration
    /// @param expectedType required position
    /// @return validated declaration
    private static PluginPatchDeclaration requireType(
            PluginPatchDeclaration declaration,
            PluginPatchDeclaration.PatchType expectedType
    ) {
        PluginPatchDeclaration value = Objects.requireNonNull(declaration, "declaration");
        value.validate();
        if (value.getType() != expectedType) {
            throw new IllegalArgumentException("Patch invocation position does not match declaration: "
                    + value.getType());
        }
        return value;
    }

    /// Copies a nullable-element list without retaining caller mutation authority.
    ///
    /// @param values caller-owned values
    /// @return immutable nullable-element list
    private static @Unmodifiable List<@Nullable Object> copyNullableValues(List<@Nullable Object> values) {
        List<@Nullable Object> copy = new ArrayList<>(Objects.requireNonNull(values, "values"));
        return Collections.unmodifiableList(copy);
    }
}
