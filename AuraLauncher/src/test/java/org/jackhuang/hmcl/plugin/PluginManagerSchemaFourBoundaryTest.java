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
package org.jackhuang.hmcl.plugin;

import org.jackhuang.hmcl.FXThreadTestSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that Aura can parse schema-v4 packages for management while blocking every executable entry point.
@NotNullByDefault
public final class PluginManagerSchemaFourBoundaryTest {
    /// Rejects a structurally valid schema-v4 package selected for a new Aura local installation.
    ///
    /// @param temporaryDirectory isolated launcher home and source directory
    /// @throws IOException if the legacy fixture cannot be created
    @Test
    public void rejectSchemaFourLocalInstallation(@TempDir Path temporaryDirectory) throws IOException {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        Path sourcePackage = temporaryDirectory.resolve("legacy-v4-install.npl");
        writeSchemaFourPluginPackage(
                sourcePackage,
                "dev.hmclce.test.legacy-v4-install",
                PackagedTestPlugin.class
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> manager.inspectLocalPluginPackage(sourcePackage)
        );
        assertEquals(PluginManifest.executableSchemaDiagnostic(4), exception.getMessage());
        assertTrue(manager.getInstalledManifests().isEmpty());
    }

    /// Blocks a manually installed schema-v4 package during startup discovery before construction.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation or discovery fails
    @Test
    public void blockSchemaFourPackageBeforeConstruction(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.legacy-v4-lifecycle";
        writeSchemaFourPluginPackage(
                manager.getPluginsDirectory().resolve(pluginId + ".npl"),
                pluginId,
                LifecycleProbePlugin.class
        );
        clearLifecycleProbeProperties();
        manager.enablePlugin(pluginId);

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertNull(manager.getPlugin(pluginId));
        assertEquals(PluginRuntimeStatus.BLOCKED_LEGACY, manager.getPluginRuntimeStatus(pluginId));
        assertLifecycleProbeNeverRan();
    }

    /// Writes one structurally valid schema-v4 package retained only for Aura rejection tests.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param entrypoint package-owned lifecycle entry point
    /// @throws IOException if package creation fails
    private static void writeSchemaFourPluginPackage(
            Path target,
            String pluginId,
            Class<? extends Plugin> entrypoint
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        String manifest = """
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Legacy Schema Four Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": []
                }
                """.formatted(pluginId, entrypoint.getName());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeEntry(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            writeClassEntry(output, entrypoint);
        }
    }

    /// Copies one compiled lifecycle class into a generated plugin package.
    ///
    /// @param output package output stream
    /// @param entrypoint lifecycle class whose bytes belong to the package
    /// @throws IOException if the compiled class resource cannot be read or written
    private static void writeClassEntry(ZipOutputStream output, Class<? extends Plugin> entrypoint)
            throws IOException {
        String resource = entrypoint.getName().replace('.', '/') + ".class";
        try (@Nullable var input = entrypoint.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Compiled test plugin class not found: " + resource);
            }
            writeEntry(output, resource, input.readAllBytes());
        }
    }

    /// Writes one deterministic package entry.
    ///
    /// @param output package output stream
    /// @param name entry path
    /// @param bytes entry bytes
    /// @throws IOException if the entry cannot be written
    private static void writeEntry(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    /// Clears process-global lifecycle markers before startup discovery.
    private static void clearLifecycleProbeProperties() {
        System.clearProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.LOADED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.ENABLED_PROPERTY);
    }

    /// Asserts that schema-v4 plugin code never reached construction or startup callbacks.
    private static void assertLifecycleProbeNeverRan() {
        assertNull(System.getProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.LOADED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.ENABLED_PROPERTY));
    }
}
