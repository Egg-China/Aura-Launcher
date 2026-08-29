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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies canonical plugin target parsing, host detection, and asymmetric compatibility.
@NotNullByDefault
public final class PluginPlatformTargetTest {
    /// Parses HarmonyOS as a canonical schema-v5 operating-system target.
    @Test
    public void parseHarmonyOsTarget() {
        PluginPlatformTarget target = PluginPlatformTarget.parse(" HARMONYOS-ARM64 ");

        assertEquals("harmonyos", target.getOperatingSystem());
        assertEquals("arm64", target.getArchitecture());
        assertEquals("harmonyos-arm64", target.getId());
    }

    /// Detects explicit HarmonyOS names without consulting Linux release metadata.
    ///
    /// @param temporary isolated test directory
    @Test
    public void detectHarmonyOsName(@TempDir Path temporary) {
        Path missing = temporary.resolve("missing-release-file");

        assertEquals("harmonyos-arm64",
                PluginPlatformTarget.detect("HarmonyOS", "aarch64", missing).getId());
        assertEquals("harmonyos-arm64",
                PluginPlatformTarget.detect("OpenHarmony 6.0", "arm64", missing).getId());
    }

    /// Detects bounded HarmonyOS markers in Linux-compatible release metadata.
    ///
    /// @param temporary isolated test directory
    /// @throws IOException if a test fixture cannot be written
    @Test
    public void detectHarmonyOsReleaseMetadata(@TempDir Path temporary) throws IOException {
        Path id = Files.writeString(temporary.resolve("id"), "ID=openharmony\n", StandardCharsets.UTF_8);
        Path name = Files.writeString(temporary.resolve("name"), "NAME='HarmonyOS PC'\n", StandardCharsets.UTF_8);
        Path prettyName = Files.writeString(
                temporary.resolve("pretty-name"), "PRETTY_NAME=\"OpenHarmony Desktop\"\n", StandardCharsets.UTF_8);
        Path idLike = Files.writeString(
                temporary.resolve("id-like"), "ID_LIKE=linux harmonyos\n", StandardCharsets.UTF_8);

        for (Path release : List.of(id, name, prettyName, idLike)) {
            assertEquals("harmonyos-arm64",
                    PluginPlatformTarget.detect("Linux", "aarch64", release).getId());
        }
    }

    /// Treats absent, unreadable, malformed, oversized, and ordinary release metadata as Linux.
    ///
    /// @param temporary isolated test directory
    /// @throws IOException if a test fixture cannot be written
    @Test
    public void ignoreUntrustedReleaseMetadata(@TempDir Path temporary) throws IOException {
        Path missing = temporary.resolve("missing");
        Path directory = Files.createDirectory(temporary.resolve("directory"));
        Path malformed = Files.write(temporary.resolve("malformed"), new byte[]{(byte) 0xc3, 0x28});
        Path oversized = Files.write(temporary.resolve("oversized"), new byte[65_537]);
        Path linux = Files.writeString(
                temporary.resolve("linux"), "ID=linux\nNAME=Linux\n", StandardCharsets.UTF_8);

        for (Path release : List.of(missing, directory, malformed, oversized, linux)) {
            assertEquals("linux-arm64",
                    PluginPlatformTarget.detect("Linux", "aarch64", release).getId());
        }
    }

    /// Preserves existing operating-system and architecture normalization.
    ///
    /// @param temporary isolated test directory
    @Test
    public void preserveExistingHostDetection(@TempDir Path temporary) {
        Path missing = temporary.resolve("missing-release-file");

        assertEquals("windows-x64", PluginPlatformTarget.detect("Windows 11", "amd64", missing).getId());
        assertEquals("macos-arm64", PluginPlatformTarget.detect("Mac OS X", "aarch64", missing).getId());
        assertEquals("macos-x64", PluginPlatformTarget.detect("Darwin", "x86_64", missing).getId());
        assertEquals("freebsd-x64", PluginPlatformTarget.detect("FreeBSD", "x86_64", missing).getId());
        assertEquals("linux-riscv64", PluginPlatformTarget.detect("Linux", "riscv64", missing).getId());
    }

    /// Allows Linux ARM64 packages on HarmonyOS ARM64 without reverse or cross-architecture matches.
    @Test
    public void applyHarmonyOsCompatibilityOneWay() {
        PluginPlatformTarget harmonyArm64 = PluginPlatformTarget.parse("harmonyos-arm64");

        assertTrue(PluginPlatformTarget.parse("harmonyos-arm64").matches(harmonyArm64));
        assertTrue(PluginPlatformTarget.parse("harmonyos").matches(harmonyArm64));
        assertTrue(PluginPlatformTarget.parse("linux-arm64").matches(harmonyArm64));
        assertTrue(PluginPlatformTarget.parse("linux").matches(harmonyArm64));
        assertFalse(PluginPlatformTarget.parse("harmonyos-arm64")
                .matches(PluginPlatformTarget.parse("linux-arm64")));
        assertFalse(PluginPlatformTarget.parse("linux-x64").matches(harmonyArm64));
        assertFalse(PluginPlatformTarget.parse("linux-arm64")
                .matches(PluginPlatformTarget.parse("harmonyos-x64")));
        assertFalse(PluginPlatformTarget.parse("windows-arm64").matches(harmonyArm64));
    }
}
