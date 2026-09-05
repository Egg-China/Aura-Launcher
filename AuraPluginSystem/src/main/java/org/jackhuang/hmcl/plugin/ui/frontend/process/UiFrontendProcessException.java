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
package org.jackhuang.hmcl.plugin.ui.frontend.process;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Reports a stable, redacted failure while supervising one native UI frontend process.
@NotNullByDefault
public final class UiFrontendProcessException extends Exception {
    /// Stable diagnostic category that callers may branch on without parsing messages.
    private final Category category;

    /// Creates one redacted session failure.
    ///
    /// @param category stable failure category
    /// @param message non-secret diagnostic message
    public UiFrontendProcessException(Category category, String message) {
        this(category, message, null);
    }

    /// Creates one redacted session failure retaining an inspectable local cause.
    ///
    /// @param category stable failure category
    /// @param message non-secret diagnostic message
    /// @param cause local cause, or `null` when none exists
    public UiFrontendProcessException(Category category, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    /// Returns the stable failure category.
    ///
    /// @return failure category
    public Category category() {
        return category;
    }

    /// Enumerates stable native frontend supervision failure classes.
    @NotNullByDefault
    public enum Category {
        /// The operating-system process could not be started.
        STARTUP,

        /// A package root or executable path failed containment validation.
        PATH,

        /// The child transport ended or failed unexpectedly.
        TRANSPORT,

        /// The child violated the strict UI wire session protocol.
        PROTOCOL,

        /// Startup, queueing, or an active request exceeded its deadline.
        TIMEOUT,

        /// The child returned a typed error for a current request.
        REMOTE_ERROR,

        /// The session no longer accepts requests.
        CLOSED,

        /// The shared active and waiting request capacity is exhausted.
        OVERLOAD
    }
}
