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

import org.jackhuang.hmcl.FXThreadTestSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies discovery publishes management status for native UI-provider packages without Java lifecycle loading.
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@NotNullByDefault
public final class PluginManagerUiProviderDiscoveryTest {
    /// Canonical UI-provider fixture ID shared by both discovery cases.
    private static final String UI_PROVIDER_ID = "dev.aura.test.discovery-ui";

    /// Reports one compatible native UI provider as installed while never constructing its Java lifecycle.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws IOException if package creation or discovery fails
    @Test
    public void compatibleUiProviderStaysInstalledWithoutJavaLifecycle(@TempDir Path temporaryDirectory)
            throws IOException {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        writeUiProviderPackage(manager.getPluginsDirectory().resolve(UI_PROVIDER_ID + ".npl"), "*");
        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertEquals(PluginRuntimeStatus.INSTALLED_DISABLED, manager.getPluginRuntimeStatus(UI_PROVIDER_ID));
        assertNull(manager.getPlugin(UI_PROVIDER_ID));
    }

    /// Retains the exact compatibility diagnostic when a native UI provider fails version policy.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws IOException if package creation or discovery fails
    @Test
    public void incompatibleUiProviderPublishesCompatibilityDiagnostic(@TempDir Path temporaryDirectory)
            throws IOException {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        writeUiProviderPackage(manager.getPluginsDirectory().resolve(UI_PROVIDER_ID + ".npl"), "99999999");
        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(UI_PROVIDER_ID));
        assertTrue(Objects.requireNonNull(manager.getPluginRuntimeDetail(UI_PROVIDER_ID)).contains("99999999"));
    }

    /// Writes one schema-v5 native UI-provider package with the requested compatibility policy.
    ///
    /// @param target package path
    /// @param launcherVersion launcher version constraint
    /// @throws IOException if package creation fails
    private static void writeUiProviderPackage(Path target, String launcherVersion) throws IOException {
        Files.createDirectories(target.getParent());
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Discovery UI Provider",
                  "version": "1.0.0",
                  "type": "native",
                  "entrypoint": "bin/ui-provider",
                  "permissions": ["launcher-ui-provider", "native-code", "process"],
                  "requiredPermissions": ["launcher-ui-provider", "native-code", "process"],
                  "launcherVersion": "%s",
                  "runtime": "aura-ui",
                  "abi": 1,
                  "platforms": ["windows-x64", "linux-x64", "macos-x64", "macos-arm64"],
                  "pluginKind": "ui-provider",
                  "executionMode": "isolated"
                }
                """.formatted(UI_PROVIDER_ID, launcherVersion);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            put(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            put(output, "bin/ui-provider", "fixture-executable".getBytes(StandardCharsets.UTF_8));
        }
    }

    /// Writes one deterministic archive entry.
    ///
    /// @param output package stream
    /// @param name entry path
    /// @param contents exact entry bytes
    /// @throws IOException if entry writing fails
    private static void put(ZipOutputStream output, String name, byte @Unmodifiable [] contents) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(contents);
        output.closeEntry();
    }
}
