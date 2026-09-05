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

import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Resolves the launcher-visible frontend selection from one start's command line.
///
/// The selector intentionally knows nothing about settings persistence or installed packages: it validates only the
/// command-line override spelling so the launcher can apply one exact selection for this process and leave fallback,
/// permission, and package verification to [UiFrontendCoordinator].
@NotNullByDefault
public final class UiFrontendSelector {
    /// Exact command-line flag prefix selecting the visible frontend for this process.
    public static final String FLAG = "--ui=";

    /// Prevents construction of this stateless selector.
    private UiFrontendSelector() {
    }

    /// Resolves one command-line frontend selection.
    ///
    /// @param args launcher arguments after restart-barrier stripping
    /// @param persistedSelection stored frontend selection
    /// @return exact frontend ID for this process
    /// @throws IllegalArgumentException when an `--ui` value is malformed, duplicated, or not a canonical plugin ID
    public static String select(String @Nullable [] args, String persistedSelection) {
        String persisted = normalizePersisted(persistedSelection);
        if (args == null) {
            return persisted;
        }
        @Nullable String override = null;
        for (@Nullable String argument : args) {
            if (argument == null || !argument.startsWith(FLAG)) {
                continue;
            }
            if (override != null) {
                throw new IllegalArgumentException("Duplicate UI frontend selection: " + argument);
            }
            override = requireCanonicalSelection(argument.substring(FLAG.length()));
        }
        return override == null ? persisted : override;
    }

    /// Returns whether the command line explicitly forces the built-in frontend for this process.
    ///
    /// An explicit built-in override is also the documented rescue switch: the launcher persists the default
    /// selection so the next ordinary start uses JavaFX again without editing settings files.
    ///
    /// @param args launcher arguments after restart-barrier stripping
    /// @return whether one `--ui` override selects the built-in frontend
    /// @throws IllegalArgumentException when an `--ui` value is malformed, duplicated, or not a canonical ID
    public static boolean forcesBuiltIn(String @Nullable [] args) {
        return args != null && UiFrontendDescriptor.JAVAFX_ID.equals(select(args, UiFrontendDescriptor.JAVAFX_ID))
                && hasOverride(args);
    }

    /// Returns whether the command line carries any `--ui` override.
    ///
    /// @param args launcher arguments after restart-barrier stripping
    /// @return whether at least one `--ui` argument is present
    private static boolean hasOverride(String @Nullable [] args) {
        for (@Nullable String argument : args) {
            if (argument != null && argument.startsWith(FLAG)) {
                return true;
            }
        }
        return false;
    }

    /// Requires one command-line selection to be the built-in ID or a canonical executable plugin ID.
    ///
    /// @param value raw value after `--ui=`
    /// @return exact canonical selection
    private static String requireCanonicalSelection(String value) {
        if (value.isEmpty()) {
            return UiFrontendDescriptor.JAVAFX_ID;
        }
        if (UiFrontendDescriptor.JAVAFX_ID.equals(value) || PluginManifest.isCanonicalExecutableId(value)) {
            return value;
        }
        throw new IllegalArgumentException("Invalid UI frontend selection: " + value);
    }

    /// Normalizes the persisted selection while treating absent or blank legacy values as JavaFX.
    ///
    /// @param persistedSelection stored selection
    /// @return exact persisted selection or the built-in frontend ID
    private static String normalizePersisted(String persistedSelection) {
        Objects.requireNonNull(persistedSelection, "persistedSelection");
        return persistedSelection.isBlank() ? UiFrontendDescriptor.JAVAFX_ID : persistedSelection;
    }
}
