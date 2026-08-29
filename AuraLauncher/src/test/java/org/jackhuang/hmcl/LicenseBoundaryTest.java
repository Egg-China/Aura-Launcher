/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the repository's directory-specific license policy.
@NotNullByDefault
public final class LicenseBoundaryTest {
    /// Expected Apache license directory.
    private static final Path PLUGIN_SYSTEM_ROOT = repositoryRoot().resolve("AuraPluginSystem");

    /// Locates the repository root from Gradle or an IDE working directory.
    private static Path repositoryRoot() {
        @Nullable Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate Aura Launcher repository root");
    }

    /// Requires the plugin-system directory to carry its own canonical license files.
    @Test
    public void pluginSystemDeclaresApacheLicenseBoundary() throws IOException {
        Path license = PLUGIN_SYSTEM_ROOT.resolve("LICENSE");
        Path notice = PLUGIN_SYSTEM_ROOT.resolve("NOTICE");
        Path readme = PLUGIN_SYSTEM_ROOT.resolve("README.md");

        assertTrue(Files.isRegularFile(license));
        assertTrue(Files.readString(license).startsWith("                                 Apache License\n"));
        assertTrue(Files.readString(license).contains("Version 2.0, January 2004"));
        assertTrue(Files.readString(notice).contains("Copyright 2026 Aura Launcher contributors"));
        assertTrue(Files.readString(readme).contains("Apache License 2.0"));
        assertTrue(Files.readString(readme).contains("combined Aura Launcher distribution remains GPL"));
    }
}
