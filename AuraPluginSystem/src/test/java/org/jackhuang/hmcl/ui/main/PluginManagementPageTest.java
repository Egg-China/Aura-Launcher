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
package org.jackhuang.hmcl.ui.main;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies deterministic installed-plugin summaries used by the minimum-width management list.
@NotNullByDefault
public final class PluginManagementPageTest {
    /// Normalizes line breaks and bounds both narrow and wide text without splitting Unicode code points.
    @Test
    public void summarizeInstalledPluginText() {
        assertEquals("First second", PluginManagementPage.summarizeDisplayText(" First\n\nsecond ", 20));

        String narrow = PluginManagementPage.summarizeDisplayText("x".repeat(80), 20);
        assertTrue(narrow.endsWith("\u2026"));
        assertTrue(narrow.length() <= 19);

        String wide = PluginManagementPage.summarizeDisplayText("\u63d2\u4ef6".repeat(30), 20);
        assertTrue(wide.endsWith("\u2026"));
        assertTrue(wide.codePointCount(0, wide.length()) <= 10);
    }

}
