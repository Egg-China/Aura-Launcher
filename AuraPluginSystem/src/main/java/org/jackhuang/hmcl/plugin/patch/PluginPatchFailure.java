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

/// Stable redacted failure reported by the launcher-owned Patch engine.
@NotNullByDefault
public final class PluginPatchFailure extends Exception {
    /// Failure category suitable for diagnostics without callback-controlled details.
    private final Category category;

    /// Creates one categorized failure.
    ///
    /// @param category stable failure category
    /// @param message redacted diagnostic message
    public PluginPatchFailure(Category category, String message) {
        this(category, message, null);
    }

    /// Creates one categorized failure with an internal cause.
    ///
    /// @param category stable failure category
    /// @param message redacted diagnostic message
    /// @param cause internal failure cause, or `null`
    public PluginPatchFailure(Category category, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.category = java.util.Objects.requireNonNull(category, "category");
    }

    /// Returns the stable failure category.
    ///
    /// @return failure category
    public Category category() {
        return category;
    }

    /// Stable Patch registration and callback failure categories.
    @NotNullByDefault
    public enum Category {
        /// No active retransformation-capable engine exists.
        UNAVAILABLE_ENGINE,

        /// The class falls outside the launcher-owned safe target boundary.
        DENIED_TARGET,

        /// No method has the declared name and parameter identity.
        MISSING_METHOD,

        /// The selected method has no safely transformable body or is ambiguous.
        UNSUPPORTED_METHOD,

        /// Current artifact-bound permission does not authorize Patch use.
        PERMISSION_DENIED,

        /// Another active registration already replaces the exact method.
        REPLACEMENT_CONFLICT,

        /// JVM instrumentation reports that the target cannot be modified.
        UNMODIFIABLE_CLASS,

        /// Bytecode transformation or retransformation failed.
        TRANSFORM_FAILURE,

        /// The owning plugin or payload lifecycle generation is no longer active.
        LIFECYCLE_REVOKED,

        /// The callback exceeded its bounded deadline.
        TIMEOUT,

        /// External Runtime transport failed.
        TRANSPORT,

        /// Callback data did not satisfy the Patch value contract.
        MALFORMED_VALUE,

        /// A callback value is not assignable to the declared JVM type.
        TYPE_MISMATCH,

        /// Plugin callback code threw or returned no result.
        CALLBACK_EXCEPTION,

        /// Recursive Patch dispatch exceeded the global depth bound.
        DEPTH_LIMIT
    }
}
