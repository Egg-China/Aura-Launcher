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
package org.jackhuang.hmcl.ui.frontend;

import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.modpack.ModAdviser;
import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the loader inference backing the native UI instance snapshot.
@NotNullByDefault
public class NativeUiBridgeTest {
    @Test
    public void infersVanillaWithoutPatches() {
        assertEquals("Vanilla", NativeUiBridge.inferLoaderFromIds(List.of()));
    }

    @Test
    public void infersFabricCaseInsensitively() {
        assertEquals("Fabric", NativeUiBridge.inferLoaderFromIds(List.of("FaBrIc-Loader")));
    }

    @Test
    public void prefersNeoForgeOverForge() {
        assertEquals("NeoForge", NativeUiBridge.inferLoaderFromIds(List.of("neoforge", "forge")));
    }

    @Test
    public void infersQuiltAndForge() {
        assertEquals("Quilt", NativeUiBridge.inferLoaderFromIds(List.of("quilt-base")));
        assertEquals("Forge", NativeUiBridge.inferLoaderFromIds(List.of("forge-1.20.1")));
    }

    /// Verifies nested forward-slash paths resolve inside the instance run directory.
    @Test
    public void resolvesNestedExportPaths(@TempDir Path root) {
        Path resolved = NativeUiBridge.resolveExportDirectory(root, "mods/config");
        assertEquals(root.toAbsolutePath().normalize().resolve("mods").resolve("config"), resolved);
    }

    /// Verifies escaping and malformed export paths are rejected before any filesystem access.
    @Test
    public void rejectsEscapingExportPaths(@TempDir Path root) {
        for (String path : new String[]{"..", "a/..", "a/../b", "/absolute", "a\\b", "C:", "a//b", "a/."}) {
            assertThrows(IllegalArgumentException.class,
                    () -> NativeUiBridge.resolveExportDirectory(root, path), path);
        }
    }

    /// Verifies an absent whitelist keeps full-export semantics.
    @Test
    public void absentWhitelistStaysNull() {
        assertNull(NativeUiBridge.optionalExportWhitelist(
                BridgeValue.map(Map.of("id", BridgeValue.string("instance")))));
    }

    /// Verifies valid whitelists survive as validated exact paths.
    @Test
    public void acceptsValidWhitelist() {
        BridgeValue params = BridgeValue.map(Map.of("whitelist", BridgeValue.array(List.of(
                BridgeValue.string("mods"),
                BridgeValue.string("mods/A.jar")))));
        assertEquals(List.of("mods", "mods/A.jar"), NativeUiBridge.optionalExportWhitelist(params));
    }

    /// Verifies empty, oversized, and malformed whitelists fail instead of widening the export.
    @Test
    public void rejectsUnsafeWhitelists() {
        assertThrows(IllegalArgumentException.class, () -> NativeUiBridge.optionalExportWhitelist(
                BridgeValue.map(Map.of("whitelist", BridgeValue.array(List.of())))));
        assertThrows(IllegalArgumentException.class, () -> NativeUiBridge.optionalExportWhitelist(
                BridgeValue.map(Map.of("whitelist", BridgeValue.array(List.of(
                        BridgeValue.string("mods"), BridgeValue.string("")))))));
        assertThrows(IllegalArgumentException.class, () -> NativeUiBridge.optionalExportWhitelist(
                BridgeValue.map(Map.of("whitelist", BridgeValue.array(List.of(
                        BridgeValue.string("mods\\A.jar")))))));
        assertThrows(IllegalArgumentException.class, () -> NativeUiBridge.optionalExportWhitelist(
                BridgeValue.map(Map.of("whitelist", BridgeValue.array(List.of(
                        BridgeValue.integer(1)))))));
    }

    /// Verifies listing mirrors the export wizard's hiding, suggestion, and ordering rules.
    @Test
    public void listsWizardCompatibleLevel(@TempDir Path root) throws IOException {
        GameInstanceID instanceId = new GameInstanceID("1.20.1-test");
        Files.createDirectories(root.resolve("mods"));
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("saves"));
        Files.createDirectories(root.resolve("logs"));
        Files.createDirectories(root.resolve("natives-win32"));
        Files.createDirectories(root.resolve(instanceId.id() + "-natives"));
        Files.writeString(root.resolve(".DS_Store"), "x");
        Files.writeString(root.resolve("desktop.ini"), "x");
        Files.writeString(root.resolve("._meta"), "x");
        Files.writeString(root.resolve(instanceId.id() + ".jar"), "x");
        Files.writeString(root.resolve("options.txt"), "x");
        Files.writeString(root.resolve("README.md"), "x");

        NativeUiBridge.ExportFileListing listing =
                NativeUiBridge.listExportFileEntries(root, "", instanceId, 4096);
        List<String> names = listing.entries().stream().map(NativeUiBridge.ExportFileEntry::name).toList();
        assertEquals(List.of("config", "mods", "saves", "options.txt", "README.md"), names);
        assertTrue(listing.entries().get(0).suggested());
        assertTrue(listing.entries().get(1).suggested());
        assertTrue(!listing.entries().get(2).suggested());
        assertTrue(!listing.truncated());
    }

    /// Verifies the natives hiding rule only applies to direct children of the run directory.
    @Test
    public void hidesNativesOnlyAtRootLevel(@TempDir Path root) throws IOException {
        GameInstanceID instanceId = new GameInstanceID("1.20.1-test");
        Path mods = Files.createDirectories(root.resolve("mods"));
        Files.createDirectories(mods.resolve("natives-linux"));
        NativeUiBridge.ExportFileListing listing =
                NativeUiBridge.listExportFileEntries(mods, "mods", instanceId, 4096);
        assertEquals(List.of("mods/natives-linux"),
                listing.entries().stream().map(NativeUiBridge.ExportFileEntry::path).toList());
    }

    /// Verifies oversized levels are truncated explicitly instead of silently dropped.
    @Test
    public void truncatesOversizedLevels(@TempDir Path root) throws IOException {
        GameInstanceID instanceId = new GameInstanceID("1.20.1-test");
        for (char letter = 'a'; letter <= 'e'; letter++) {
            Files.writeString(root.resolve(letter + ".txt"), "x");
        }
        NativeUiBridge.ExportFileListing listing =
                NativeUiBridge.listExportFileEntries(root, "", instanceId, 3);
        assertEquals(3, listing.entries().size());
        assertTrue(listing.truncated());
    }

    /// Verifies suggestion helpers stay directly testable for wizard-parity regressions.
    @Test
    public void suggestsWizardEntries() {
        GameInstanceID instanceId = new GameInstanceID("1.20.1-test");
        assertEquals(ModAdviser.ModSuggestion.SUGGESTED,
                NativeUiBridge.exportEntrySuggestion("mods", "mods", true, instanceId, 1));
        assertEquals(ModAdviser.ModSuggestion.NORMAL,
                NativeUiBridge.exportEntrySuggestion("saves", "saves", true, instanceId, 1));
        assertEquals(ModAdviser.ModSuggestion.HIDDEN,
                NativeUiBridge.exportEntrySuggestion("logs", "logs", true, instanceId, 1));
        assertEquals(ModAdviser.ModSuggestion.HIDDEN,
                NativeUiBridge.exportEntrySuggestion("Thumbs.db", "Thumbs.db", false, instanceId, 1));
    }
}
