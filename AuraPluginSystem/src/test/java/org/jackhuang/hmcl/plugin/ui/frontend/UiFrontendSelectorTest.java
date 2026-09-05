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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies command-line frontend selection parsing without touching installed packages.
@NotNullByDefault
public final class UiFrontendSelectorTest {
    /// Uses the persisted selection when no command-line override is present.
    @Test
    public void keepsPersistedSelectionWithoutOverride() {
        assertEquals("dev.aura.test.ui", UiFrontendSelector.select(
                new String[]{"--_safe-flag", "value"},
                "dev.aura.test.ui"
        ));
    }

    /// Treats an empty override as the always-available built-in frontend.
    @Test
    public void emptyOverrideSelectsJavaFx() {
        assertEquals("javafx", UiFrontendSelector.select(
                new String[]{"--ui="},
                "dev.aura.test.ui"
        ));
    }

    /// Accepts one canonical plugin ID for this process only.
    @Test
    public void canonicalPluginIdOverridesPersistedSelection() {
        assertEquals("dev.aura.test.other-ui", UiFrontendSelector.select(
                new String[]{"--ui=dev.aura.test.other-ui"},
                "dev.aura.test.ui"
        ));
    }

    /// Rejects malformed IDs before any launcher UI is created.
    @Test
    public void malformedSelectionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> UiFrontendSelector.select(
                new String[]{"--ui=Not Canonical"},
                "javafx"
        ));
    }

    /// Rejects multiple selections because one process cannot own two visible frontends.
    @Test
    public void duplicateSelectionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> UiFrontendSelector.select(
                new String[]{"--ui=javafx", "--ui=dev.aura.test.ui"},
                "javafx"
        ));
    }
}