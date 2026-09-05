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
/// The launcher owns the built-in `java` and `aura-ui` runtimes. `aura-ui` is restricted to isolated native
/// schema-v5 UI provider packages and is never an external Runtime Host. Optional Provider plugins implement other
/// identifiers; the launcher resolves their Store dependencies, supervises their lifecycle, and delegates payloads.
@NotNullByDefault
public final class PluginRuntimeTypes {
    /// Built-in JVM runtime loaded directly by the launcher; never provided by an external plugin.
    public static final String JAVA = "java";

    /// Official Rust runtime identifier, distinct from the `native` runtime category.
    public static final String RUST = "rust";

    /// Official WebAssembly runtime identifier.
    public static final String WASM = "wasm";

    /// Built-in Aura user-interface runtime used only by isolated native UI provider packages.
    public static final String AURA_UI = "aura-ui";

    /// Exact platform targets on which isolated Aura UI provider packages may execute.
    public static final @Unmodifiable Set<String> AURA_UI_PLATFORM_TARGETS = Set.of(
            "windows-x64", "windows-arm64", "linux-x64", "linux-arm64", "macos-x64", "macos-arm64");

    /// Runtime identifiers reserved for the official runtime repository.
    public static final @Unmodifiable Set<String> RESERVED = Set.of(
            JAVA, "dotnet", "python", "javascript", "native", RUST, WASM, AURA_UI);

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

    /// Returns whether a canonical target is eligible for an isolated Aura UI provider package.
    ///
    /// @param platformTarget canonical platform target
    /// @return whether the target is one of Aura UI's exact supported target pairs
    public static boolean isAuraUiPlatformTarget(String platformTarget) {
        return AURA_UI_PLATFORM_TARGETS.contains(platformTarget);
    }
}
