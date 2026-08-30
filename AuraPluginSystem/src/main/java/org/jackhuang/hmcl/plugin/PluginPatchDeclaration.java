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

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/// One declarative method patch contributed by a schema-v5 plugin.
///
/// These declarations define the schema-v5 Patch contract. Aura Launcher validates them when the manifest loads;
/// an active launcher-owned Patch engine may register them only after instrumentation and lifecycle authorization
/// are available.
@NotNullByDefault
public final class PluginPatchDeclaration {
    /// Pattern accepted for fully-qualified binary class names such as org.hmcl.core.GameLaunchService.
    private static final Pattern TARGET_PATTERN = Pattern.compile(
            "[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)+");

    /// Pattern accepted for Java method names.
    private static final Pattern METHOD_PATTERN = Pattern.compile("[a-zA-Z_$][a-zA-Z0-9_$]*");

    /// Pattern accepted for primitive or fully qualified binary parameter names with optional array suffixes.
    private static final Pattern PARAMETER_PATTERN = Pattern.compile(
            "(?:boolean|byte|char|short|int|long|float|double|"
                    + "[a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)+)"
                    + "(?:\\[\\])*"
    );

    /// Fully-qualified launcher class whose method is patched.
    @SerializedName("target")
    private @Nullable String target;

    /// Name of the patched method.
    @SerializedName("method")
    private @Nullable String method;

    /// Where the plugin callback runs relative to the original method body.
    @SerializedName("type")
    private @Nullable PatchType type;

    /// Ordered Java binary parameter names used to distinguish overloads.
    @SerializedName("parameters")
    private @Nullable List<@Nullable String> parameters;

    /// Creates an empty declaration for Gson deserialization.
    public PluginPatchDeclaration() {
    }

    /// Creates a validated declaration programmatically.
    ///
    /// @param target fully-qualified launcher class
    /// @param method patched method name
    /// @param type callback position relative to the original body
    /// @param parameters ordered Java binary parameter names, or an empty list for a no-argument method
    public PluginPatchDeclaration(String target, String method, PatchType type, List<String> parameters) {
        this.target = target;
        this.method = method;
        this.type = type;
        this.parameters = List.copyOf(parameters);
        validate();
    }

    /// Validates the declaration, throwing on malformed entries.
    ///
    /// @throws IllegalArgumentException when target, method, type, or parameters are missing or invalid
    public void validate() {
        if (target == null || !TARGET_PATTERN.matcher(target).matches()) {
            throw new IllegalArgumentException("Invalid patch target class: " + target);
        }
        if (method == null || !METHOD_PATTERN.matcher(method).matches()) {
            throw new IllegalArgumentException("Invalid patch method name: " + method);
        }
        if (type == null) {
            throw new IllegalArgumentException("Missing patch type for " + target + "." + method);
        }
        if (parameters == null) {
            throw new IllegalArgumentException("Missing patch parameters for " + target + "." + method);
        }
        for (@Nullable String parameter : parameters) {
            if (parameter == null || !isValidParameterName(parameter)) {
                throw new IllegalArgumentException("Invalid patch parameter for "
                        + target + "." + method + ": " + parameter);
            }
        }
    }

    /// Returns whether one parameter uses the exact schema-v5 Java binary-name grammar.
    ///
    /// An uppercase or dollar-containing class segment must be the final dotted segment. This keeps source-only
    /// nested spelling such as `java.util.Map.Entry` out of the manifest while accepting `java.util.Map$Entry`.
    ///
    /// @param parameter candidate parameter name
    /// @return whether the parameter is canonical
    private static boolean isValidParameterName(String parameter) {
        if (!PARAMETER_PATTERN.matcher(parameter).matches()) {
            return false;
        }
        int arrayStart = parameter.indexOf('[');
        String baseName = arrayStart < 0 ? parameter : parameter.substring(0, arrayStart);
        if (baseName.indexOf('.') < 0) {
            return true;
        }
        String[] segments = baseName.split("\\.", -1);
        for (int index = 0; index < segments.length - 1; index++) {
            String segment = segments[index];
            if (segment.indexOf('$') >= 0 || Character.isUpperCase(segment.codePointAt(0))) {
                return false;
            }
        }
        return true;
    }

    /// Returns the fully-qualified launcher class whose method is patched.
    public String getTarget() {
        return java.util.Objects.requireNonNull(target);
    }

    /// Returns the name of the patched method.
    public String getMethod() {
        return java.util.Objects.requireNonNull(method);
    }

    /// Returns where the plugin callback runs relative to the original method body.
    public PatchType getType() {
        return Objects.requireNonNull(type);
    }

    /// Returns the ordered Java binary parameter names that identify the patched overload.
    ///
    /// @return immutable ordered Java binary parameter names
    /// @throws IllegalStateException if the parameter declaration is missing from an unvalidated declaration
    public @Unmodifiable List<String> getParameters() {
        @Nullable List<@Nullable String> values = parameters;
        if (values == null) {
            throw new IllegalStateException("Patch declaration has no parameters");
        }
        if (values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Compares the complete patched method identity and callback position.
    ///
    /// @param other comparison target
    /// @return whether both declarations describe the same ordered patch
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof PluginPatchDeclaration declaration
                && getTarget().equals(declaration.getTarget())
                && getMethod().equals(declaration.getMethod())
                && getType() == declaration.getType()
                && getParameters().equals(declaration.getParameters());
    }

    /// Returns a hash derived from the complete ordered patch identity.
    ///
    /// @return patch declaration hash
    @Override
    public int hashCode() {
        return Objects.hash(getTarget(), getMethod(), getType(), getParameters());
    }

    /// Callback position of a patch relative to the original method body.
    @NotNullByDefault
    public enum PatchType {
        /// Requests a callback before the original body with access to the invocation context.
        @SerializedName("before")
        BEFORE,

        /// Requests a callback after the original body with access to the result.
        @SerializedName("after")
        AFTER,

        /// Requests replacement of the original body; the strongest form of patch declaration.
        @SerializedName("replace")
        REPLACE
    }
}
