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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies version ordering used by plugin compatibility and update checks.
@NotNullByDefault
public final class PluginVersionTest {
    /// Compares numeric components without lexical ordering mistakes.
    @Test
    public void compareNumericVersions() {
        assertTrue(PluginVersion.compare("1.10.0", "1.9.9") > 0);
        assertEquals(0, PluginVersion.compare("v2.0", "2.0.0"));
    }

    /// Sorts prereleases before their corresponding final release.
    @Test
    public void comparePrereleaseVersions() {
        assertTrue(PluginVersion.compare("3.0.0-rc.1", "3.0.0") < 0);
        assertTrue(PluginVersion.compare("3.0.0-beta.2", "3.0.0-rc.1") < 0);
    }

    /// Keeps numeric identifiers after a hyphen in the prerelease instead of the release core.
    @Test
    public void compareNumericPrereleaseVersions() {
        assertTrue(PluginVersion.compare("1.0-1", "1.0") < 0);
        assertTrue(PluginVersion.compare("1.0-1", "1.0-2") < 0);
        assertTrue(PluginVersion.compare("1.0-1", "1.0.0") < 0);
    }

    /// Preserves optional prefixes, build metadata, and multi-part nonnumeric qualifiers.
    @Test
    public void compareCompatibleVersionForms() {
        assertEquals(0, PluginVersion.compare(
                "v26.8-beta.3-fix+build.7",
                "26.8-beta.3-fix+other"));
        assertTrue(PluginVersion.compare("26.8-beta.3-fix", "26.8") < 0);
        assertTrue(PluginVersion.compare("3.dev-abcdef0", "3") < 0);
    }

    /// Rejects malformed versions or versions without a numeric release component on either comparison side.
    ///
    /// @param invalidVersion malformed or nonnumeric version
    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "garbage", ".", ".1", "1.", "1..2", "+", "v", "V+metadata", "-1",
            "1 rc", "1,<2", "1=2", "1*2", "1>2"
    })
    public void rejectVersionsWithoutNumericCore(String invalidVersion) {
        assertThrows(IllegalArgumentException.class, () -> PluginVersion.compare(invalidVersion, "1"));
        assertThrows(IllegalArgumentException.class, () -> PluginVersion.compare("1", invalidVersion));
    }

    /// Rejects empty prerelease identifiers and missing or repeated build metadata separators.
    ///
    /// @param invalidVersion malformed version
    @ParameterizedTest
    @ValueSource(strings = {
            "1-", "1--rc", "1-rc.", "1-rc..1",
            "1+", "1++build", "1+build+other"
    })
    public void rejectMalformedPrereleaseAndBuildSyntax(String invalidVersion) {
        assertThrows(IllegalArgumentException.class, () -> PluginVersion.compare(invalidVersion, "1"));
        assertThrows(IllegalArgumentException.class, () -> PluginVersion.compare("1", invalidVersion));
    }
}
