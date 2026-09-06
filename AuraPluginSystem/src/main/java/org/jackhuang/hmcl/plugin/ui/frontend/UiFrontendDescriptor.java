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
package org.jackhuang.hmcl.plugin.ui.frontend;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/// Identifies one launcher-selectable visible UI frontend.
///
/// The descriptor deliberately carries only launcher-owned metadata. It never retains a plugin class loader,
/// lifecycle object, capability token, or UI process handle; the coordinator resolves these again from the installed
/// container immediately before supervision starts.
@NotNullByDefault
public final class UiFrontendDescriptor {
    /// Always-available built-in JavaFX frontend ID.
    public static final String JAVAFX_ID = "javafx";

    /// Canonical frontend ID: `javafx` or one exact installed plugin ID.
    private final String id;

    /// Display name suitable for logs and settings UI.
    private final String displayName;

    /// Exact extracted executable for a native provider, or empty for JavaFX.
    private final @Nullable Path executable;

    /// Creates one immutable descriptor.
    ///
    /// @param id canonical frontend ID
    /// @param displayName non-blank display name
    /// @param executable verified native executable, or `null` for the built-in frontend
    private UiFrontendDescriptor(String id, String displayName, @Nullable Path executable) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.executable = executable;
    }

    /// Describes the always-available built-in JavaFX frontend.
    ///
    /// @return immutable JavaFX descriptor
    public static UiFrontendDescriptor javafx() {
        return new UiFrontendDescriptor(JAVAFX_ID, "JavaFX", null);
    }

    /// Describes one native UI-provider package.
    ///
    /// @param pluginId exact installed plugin ID
    /// @param displayName non-blank package display name
    /// @param executable verified package-relative executable
    /// @return immutable native frontend descriptor
    public static UiFrontendDescriptor nativeFrontend(
            String pluginId,
            String displayName,
            Path executable
    ) {
        return new UiFrontendDescriptor(pluginId, displayName, executable);
    }

    /// Returns the canonical frontend ID.
    ///
    /// @return `javafx` or an exact plugin ID
    public String getId() {
        return id;
    }

    /// Returns the display name.
    ///
    /// @return non-blank settings and diagnostics name
    public String getDisplayName() {
        return displayName;
    }

    /// Returns the verified native executable when this descriptor is not JavaFX.
    ///
    /// @return executable, or empty for the built-in frontend
    public Optional<Path> getExecutable() {
        return Optional.ofNullable(executable);
    }

    /// Returns whether this is the always-available built-in frontend.
    ///
    /// @return whether the ID is exactly `javafx`
    public boolean isJavaFx() {
        return JAVAFX_ID.equals(id);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof UiFrontendDescriptor descriptor
                && id.equals(descriptor.id)
                && displayName.equals(descriptor.displayName)
                && Objects.equals(executable, descriptor.executable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, executable);
    }

    @Override
    public String toString() {
        return "UiFrontendDescriptor[" + id + "]";
    }
}
