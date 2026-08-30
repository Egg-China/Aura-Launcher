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

import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Objects;

/// Immutable JVM method identity derived from one schema-v5 Patch declaration.
@NotNullByDefault
public final class PluginPatchMethod {
    /// Binary target class name.
    private final String target;

    /// Declared Java method name.
    private final String name;

    /// Immutable ordered Java binary parameter names.
    private final @Unmodifiable List<String> parameterNames;

    /// JVM argument descriptor including its surrounding parentheses and excluding the return type.
    private final String parameterDescriptor;

    /// Exact resolved JVM method descriptor, or `null` before bytecode resolution.
    private final @Nullable String descriptor;

    /// Creates one unresolved or resolved method identity.
    ///
    /// @param target binary target class name
    /// @param name Java method name
    /// @param parameterNames immutable ordered Java binary parameter names
    /// @param parameterDescriptor JVM argument descriptor
    /// @param descriptor exact resolved method descriptor, or `null`
    private PluginPatchMethod(
            String target,
            String name,
            List<String> parameterNames,
            String parameterDescriptor,
            @Nullable String descriptor
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.name = Objects.requireNonNull(name, "name");
        this.parameterNames = List.copyOf(parameterNames);
        this.parameterDescriptor = Objects.requireNonNull(parameterDescriptor, "parameterDescriptor");
        this.descriptor = descriptor;
    }

    /// Converts one validated schema-v5 declaration to an unresolved JVM method identity.
    ///
    /// @param declaration Patch declaration
    /// @return unresolved method identity
    public static PluginPatchMethod from(PluginPatchDeclaration declaration) {
        PluginPatchDeclaration value = Objects.requireNonNull(declaration, "declaration");
        value.validate();
        StringBuilder descriptorBuilder = new StringBuilder("(");
        for (String parameter : value.getParameters()) {
            descriptorBuilder.append(descriptorForParameter(parameter));
        }
        descriptorBuilder.append(')');
        return new PluginPatchMethod(
                value.getTarget(),
                value.getMethod(),
                value.getParameters(),
                descriptorBuilder.toString(),
                null
        );
    }

    /// Returns the binary target class name.
    ///
    /// @return target class name
    public String target() {
        return target;
    }

    /// Returns the declared Java method name.
    ///
    /// @return method name
    public String name() {
        return name;
    }

    /// Returns the immutable ordered Java binary parameter names.
    ///
    /// @return parameter names
    public @Unmodifiable List<String> parameterNames() {
        return parameterNames;
    }

    /// Returns the JVM argument descriptor without a return descriptor.
    ///
    /// @return parenthesized argument descriptor
    public String parameterDescriptor() {
        return parameterDescriptor;
    }

    /// Returns the exact resolved JVM method descriptor.
    ///
    /// @return full method descriptor
    /// @throws IllegalStateException if target bytecode has not resolved the return type
    public String descriptor() {
        @Nullable String value = descriptor;
        if (value == null) {
            throw new IllegalStateException("Patch method descriptor has not been resolved: " + target + "." + name);
        }
        return value;
    }

    /// Returns whether a bytecode method has the declared name and ordered argument descriptor.
    ///
    /// @param candidateName bytecode method name
    /// @param candidateDescriptor complete JVM method descriptor
    /// @return whether the candidate is the declared overload
    public boolean matches(String candidateName, String candidateDescriptor) {
        if (!name.equals(candidateName)) {
            return false;
        }
        String candidate = Objects.requireNonNull(candidateDescriptor, "candidateDescriptor");
        try {
            Type.getMethodType(candidate);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        int argumentEnd = candidate.indexOf(')');
        return argumentEnd >= 0 && parameterDescriptor.equals(candidate.substring(0, argumentEnd + 1));
    }

    /// Binds an exact bytecode descriptor after verifying that it belongs to this declared overload.
    ///
    /// @param resolvedDescriptor complete JVM method descriptor
    /// @return resolved immutable identity
    /// @throws IllegalArgumentException if the descriptor identifies another overload or is malformed
    public PluginPatchMethod withDescriptor(String resolvedDescriptor) {
        String value = Objects.requireNonNull(resolvedDescriptor, "resolvedDescriptor");
        if (!matches(name, value)) {
            throw new IllegalArgumentException("Resolved descriptor does not match Patch declaration: " + value);
        }
        return new PluginPatchMethod(target, name, parameterNames, parameterDescriptor, value);
    }

    /// Converts one validated Java binary parameter name to its JVM field descriptor.
    ///
    /// @param parameter validated Java parameter name
    /// @return JVM field descriptor
    private static String descriptorForParameter(String parameter) {
        String component = Objects.requireNonNull(parameter, "parameter");
        int dimensions = 0;
        while (component.endsWith("[]")) {
            dimensions++;
            component = component.substring(0, component.length() - 2);
        }
        String componentDescriptor = switch (component) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> "L" + component.replace('.', '/') + ";";
        };
        return "[".repeat(dimensions) + componentDescriptor;
    }
}
