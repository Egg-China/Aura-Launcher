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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /// Requires the plugin-system directory to carry its canonical Apache license files and exact license text.
    @Test
    public void pluginSystemDeclaresApacheLicenseBoundary() throws IOException, NoSuchAlgorithmException {
        Path license = PLUGIN_SYSTEM_ROOT.resolve("LICENSE");
        Path notice = PLUGIN_SYSTEM_ROOT.resolve("NOTICE");
        Path readme = PLUGIN_SYSTEM_ROOT.resolve("README.md");

        assertTrue(Files.isRegularFile(license));
        assertTrue(Files.readString(license).startsWith("                                 Apache License\n"));
        assertTrue(Files.readString(license).contains("Version 2.0, January 2004"));
        assertEquals(11_357L, Files.size(license));
        assertEquals("c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(license))));
        assertTrue(Files.readString(notice).contains("Copyright 2026 Aura Launcher contributors"));
        assertTrue(Files.readString(readme).contains("Apache License 2.0"));
        assertTrue(Files.readString(readme).contains("combined Aura Launcher distribution remains GPL"));
    }

    /// Requires at least one plugin source and enforces its Apache-only header.
    @Test
    public void pluginSourcesUseOnlyApacheHeaders() throws IOException {
        Path sourceRoot = PLUGIN_SYSTEM_ROOT.resolve("src/main/java");
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            @Unmodifiable List<Path> javaFiles = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            assertFalse(javaFiles.isEmpty());
            for (Path file : javaFiles) {
                String source = Files.readString(file);
                assertTrue(source.contains("Licensed under the Apache License, Version 2.0"), file.toString());
                assertFalse(source.contains("GNU General Public License"), file.toString());
            }
        }
    }
}
