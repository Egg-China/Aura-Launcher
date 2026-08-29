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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the repository's directory-specific license policy.
@NotNullByDefault
public final class LicenseBoundaryTest {
    /// Expected Apache license directory.
    private static final Path PLUGIN_SYSTEM_ROOT = repositoryRoot().resolve("AuraPluginSystem");

    /// Identifies the Apache header without placing the complete marker in the GPL tree's source text.
    private static final String APACHE_MARKER = "Licensed under the Apache License, " + "Version 2.0";

    /// Identifies Java sources owned by the Apache plugin-system boundary.
    private static final String APACHE_OWNERSHIP_MARKER = "Copyright 2026 Aura Launcher " + "contributors";

    /// Canonical four-line Aura Plugin System attribution notice.
    private static final String EXPECTED_APACHE_NOTICE = "Aura Plugin System\n"
            + "Copyright 2026 Aura Launcher " + "contributors\n\n"
            + "This product includes software developed by Aura Launcher " + "contributors.\n";

    /// Generated and repository-metadata directories excluded from the first-party source scan.
    private static final @Unmodifiable Set<String> REPOSITORY_SCAN_EXCLUDED_DIRECTORIES =
            Set.of(".git", ".gradle", ".worktrees", "build");

    /// Classpath location reserved for the Aura Plugin System Apache license.
    private static final String PACKAGED_APACHE_LICENSE =
            "META-INF/licenses/Aura-Plugin-System-LICENSE.txt";

    /// Classpath location reserved for the Aura Plugin System attribution notice.
    private static final String PACKAGED_APACHE_NOTICE =
            "META-INF/notices/Aura-Plugin-System-NOTICE.txt";

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
        assertArrayEquals(EXPECTED_APACHE_NOTICE.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(notice));
        assertTrue(Files.readString(readme).contains("Apache License 2.0"));
        assertTrue(Files.readString(readme).contains("combined Aura Launcher distribution remains GPL"));
    }

    /// Requires runtime resources to reproduce the canonical plugin-system legal files byte for byte.
    @Test
    public void packagedPluginSystemLegalResourcesMatchCanonicalFiles() throws IOException {
        assertPackagedResourceMatches(PACKAGED_APACHE_LICENSE, PLUGIN_SYSTEM_ROOT.resolve("LICENSE"));
        assertPackagedResourceMatches(PACKAGED_APACHE_NOTICE, PLUGIN_SYSTEM_ROOT.resolve("NOTICE"));
    }

    /// Requires the complete plugin production and test source trees to use only Apache headers.
    @Test
    public void pluginSourcesUseOnlyApacheHeaders() throws IOException {
        assertApacheJavaTree(PLUGIN_SYSTEM_ROOT.resolve("src/main/java"), 159L);
        assertApacheJavaTree(PLUGIN_SYSTEM_ROOT.resolve("src/test/java"), 86L);
    }

    /// Requires every first-party Java source outside the plugin system to reject Apache ownership markers.
    @Test
    public void repositoryJavaSourcesOutsidePluginSystemRejectApacheOwnershipMarker() throws IOException {
        Path repositoryRoot = repositoryRoot();
        try (Stream<Path> files = Files.walk(repositoryRoot)) {
            @Unmodifiable List<Path> javaFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(PLUGIN_SYSTEM_ROOT))
                    .filter(path -> isFirstPartySourcePath(repositoryRoot.relativize(path)))
                    .toList();
            for (Path file : javaFiles) {
                String source = Files.readString(file);
                assertFalse(source.contains(APACHE_OWNERSHIP_MARKER), file.toString());
            }
        }
    }

    /// Requires an Apache Java source tree to meet its minimum size and contain only Apache headers.
    private static void assertApacheJavaTree(Path sourceRoot, long minimumFiles) throws IOException {
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            @Unmodifiable List<Path> javaFiles = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            assertTrue(javaFiles.size() >= minimumFiles,
                    () -> sourceRoot + " must contain at least " + minimumFiles + " Java files");
            for (Path file : javaFiles) {
                String source = Files.readString(file);
                assertTrue(source.contains(APACHE_MARKER), file.toString());
                assertFalse(source.contains("GNU General Public License"), file.toString());
            }
        }
    }

    /// Reports whether a repository-relative path is outside generated and metadata directories.
    private static boolean isFirstPartySourcePath(Path relativePath) {
        for (Path element : relativePath) {
            if (REPOSITORY_SCAN_EXCLUDED_DIRECTORIES.contains(element.toString())) {
                return false;
            }
        }
        return true;
    }

    /// Requires one classpath resource to match its canonical repository file exactly.
    private static void assertPackagedResourceMatches(String resourcePath, Path canonicalFile) throws IOException {
        @Nullable InputStream resource = LicenseBoundaryTest.class.getClassLoader().getResourceAsStream(resourcePath);
        assertNotNull(resource, resourcePath);
        try (resource) {
            assertArrayEquals(Files.readAllBytes(canonicalFile), resource.readAllBytes(), resourcePath);
        }
    }
}
