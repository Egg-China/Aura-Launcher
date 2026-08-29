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
package org.jackhuang.hmcl.plugin.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Locale;
import java.util.Set;

/// Canonical runtime identifiers shared by plugin manifests and the official runtime repository.
///
/// The launcher ships the built-in Java runtime and can discover optional Provider plugins for other identifiers,
/// resolve their Store dependencies, supervise their lifecycle, and delegate external payloads to them. Concrete
/// external Runtime Hosts are distributed separately and are not bundled with the launcher.
@NotNullByDefault
public final class PluginRuntimeTypes {
    /// Built-in JVM runtime loaded directly by the launcher; never provided by an external plugin.
    public static final String JAVA = "java";

    /// Official Rust runtime identifier, distinct from the `native` runtime category.
    public static final String RUST = "rust";

    /// Official WebAssembly runtime identifier.
    public static final String WASM = "wasm";

    /// Runtime identifiers reserved for the official runtime repository.
    public static final @Unmodifiable Set<String> RESERVED = Set.of(
            JAVA, "dotnet", "python", "javascript", "native", RUST, WASM);

    /// Prevents construction of the runtime identifier utility class.
    private PluginRuntimeTypes() {
    }

    /// Returns whether the identifier is structurally valid: lower-case letters, digits, and hyphens.
    public static boolean isValid(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 32) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            boolean accepted = (c >= 97 && c <= 122) || (c >= 48 && c <= 57) || c == 45;
            if (!accepted) {
                return false;
            }
        }
        return true;
    }

    /// Validates and canonicalizes a runtime identifier.
    ///
    /// @throws IllegalArgumentException when the identifier is malformed
    public static String requireValid(String value) {
        if (!isValid(value)) {
            throw new IllegalArgumentException("Invalid plugin runtime identifier: " + value);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
