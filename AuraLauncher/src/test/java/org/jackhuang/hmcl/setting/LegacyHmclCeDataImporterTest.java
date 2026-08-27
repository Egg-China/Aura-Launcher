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
package org.jackhuang.hmcl.setting;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies transactional, allowlisted HMCL CE settings import into isolated Aura homes.
@NotNullByDefault
public final class LegacyHmclCeDataImporterTest {
    /// Copies only allowlisted settings files while preserving every source byte.
    ///
    /// @param temporaryDirectory isolated source, target, and staging parent
    /// @throws IOException if fixture files cannot be created or inspected
    @Test
    public void copyAllowlistedSettingsWithoutChangingSource(@TempDir Path temporaryDirectory) throws IOException {
        Homes homes = homes(temporaryDirectory);
        Map<Path, byte[]> sourceFiles = Map.of(
                homes.legacyLocal().resolve("config/launcher-settings.json"), json("local"),
                homes.legacyLocal().resolve("config/game-directories.json"), json("games"),
                homes.legacyUser().resolve("config/user-settings.json"), json("user"),
                homes.legacyUser().resolve("private/user-account-private-data.json"), json("private")
        );
        for (Map.Entry<Path, byte[]> entry : sourceFiles.entrySet()) {
            write(entry.getKey(), entry.getValue());
        }
        write(homes.legacyLocal().resolve("plugins/untrusted.npl"), "plugin".getBytes(StandardCharsets.UTF_8));
        write(homes.legacyLocal().resolve("cache/download.json"), json("cache"));
        write(homes.legacyLocal().resolve("state/plugin-permissions.json"), json("permission"));
        write(homes.legacyLocal().resolve("dependencies/runtime.jar"), new byte[]{1, 2, 3});

        LegacyHmclCeImportResult result = new LegacyHmclCeDataImporter().importData(
                homes.legacyUser(), homes.legacyLocal(), homes.auraUser(), homes.auraLocal(), false
        );

        assertTrue(result.isSuccessful(), result.getFailureMessage());
        assertFalse(result.isSkipped());
        assertEquals(4, result.getImportedFiles().size());
        assertArrayEquals(json("local"), Files.readAllBytes(
                homes.auraLocal().resolve("config/launcher-settings.json")));
        assertArrayEquals(json("user"), Files.readAllBytes(
                homes.auraUser().resolve("config/user-settings.json")));
        assertFalse(Files.exists(homes.auraLocal().resolve("plugins/untrusted.npl")));
        assertFalse(Files.exists(homes.auraLocal().resolve("cache/download.json")));
        assertFalse(Files.exists(homes.auraLocal().resolve("state/plugin-permissions.json")));
        assertFalse(Files.exists(homes.auraLocal().resolve("dependencies/runtime.jar")));
        for (Map.Entry<Path, byte[]> entry : sourceFiles.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
        }
    }

    /// Publishes no target when any selected JSON file is malformed.
    ///
    /// @param temporaryDirectory isolated source and target homes
    /// @throws IOException if fixture files cannot be created or inspected
    @Test
    public void invalidJsonPublishesNothing(@TempDir Path temporaryDirectory) throws IOException {
        Homes homes = homes(temporaryDirectory);
        write(homes.legacyLocal().resolve("config/launcher-settings.json"), json("valid"));
        write(
                homes.legacyUser().resolve("config/user-settings.json"),
                "{invalid".getBytes(StandardCharsets.UTF_8)
        );

        LegacyHmclCeImportResult result = new LegacyHmclCeDataImporter().importData(
                homes.legacyUser(), homes.legacyLocal(), homes.auraUser(), homes.auraLocal(), false
        );

        assertFalse(result.isSuccessful());
        assertNotNull(result.getFailureMessage());
        assertFalse(Files.exists(homes.auraLocal().resolve("config/launcher-settings.json")));
        assertFalse(Files.exists(homes.auraUser().resolve("config/user-settings.json")));
        assertFalse(Files.exists(LegacyHmclCeDataImporter.receiptPath(homes.auraLocal())));
    }

    /// Creates a byte-identical backup before a manual replacement publishes the imported file.
    ///
    /// @param temporaryDirectory isolated source and target homes
    /// @throws IOException if fixture files cannot be created or inspected
    @Test
    public void manualReplacementBacksUpExistingSettings(@TempDir Path temporaryDirectory) throws IOException {
        Homes homes = homes(temporaryDirectory);
        Path source = homes.legacyLocal().resolve("config/launcher-settings.json");
        Path target = homes.auraLocal().resolve("config/launcher-settings.json");
        write(source, json("legacy"));
        write(target, json("aura"));

        LegacyHmclCeImportResult result = new LegacyHmclCeDataImporter().importData(
                homes.legacyUser(), homes.legacyLocal(), homes.auraUser(), homes.auraLocal(), true
        );

        assertTrue(result.isSuccessful(), result.getFailureMessage());
        Path backup = result.getBackupFiles().get(target.toAbsolutePath().normalize());
        assertNotNull(backup);
        assertArrayEquals(json("aura"), Files.readAllBytes(backup));
        assertArrayEquals(json("legacy"), Files.readAllBytes(target));
    }

    /// Leaves an existing Aura file untouched when replacement permission is absent.
    ///
    /// @param temporaryDirectory isolated source and target homes
    /// @throws IOException if fixture files cannot be created or inspected
    @Test
    public void automaticImportDoesNotOverwriteExistingSettings(@TempDir Path temporaryDirectory) throws IOException {
        Homes homes = homes(temporaryDirectory);
        Path source = homes.legacyLocal().resolve("config/launcher-settings.json");
        Path target = homes.auraLocal().resolve("config/launcher-settings.json");
        write(source, json("legacy"));
        write(target, json("aura"));

        LegacyHmclCeImportResult result = new LegacyHmclCeDataImporter().importData(
                homes.legacyUser(), homes.legacyLocal(), homes.auraUser(), homes.auraLocal(), false
        );

        assertFalse(result.isSuccessful());
        assertArrayEquals(json("aura"), Files.readAllBytes(target));
        assertTrue(result.getBackupFiles().isEmpty());
    }

    /// Uses the successful receipt to make later automatic imports idempotent.
    ///
    /// @param temporaryDirectory isolated source and target homes
    /// @throws IOException if fixture files cannot be created or inspected
    @Test
    public void receiptPreventsSecondAutomaticImport(@TempDir Path temporaryDirectory) throws IOException {
        Homes homes = homes(temporaryDirectory);
        Path source = homes.legacyLocal().resolve("config/launcher-settings.json");
        Path target = homes.auraLocal().resolve("config/launcher-settings.json");
        write(source, json("first"));
        LegacyHmclCeDataImporter importer = new LegacyHmclCeDataImporter();

        LegacyHmclCeImportResult first = importer.importData(
                homes.legacyUser(), homes.legacyLocal(), homes.auraUser(), homes.auraLocal(), false
        );
        write(source, json("second"));
        LegacyHmclCeImportResult second = importer.importData(
                homes.legacyUser(), homes.legacyLocal(), homes.auraUser(), homes.auraLocal(), false
        );

        assertTrue(first.isSuccessful(), first.getFailureMessage());
        assertTrue(second.isSuccessful(), second.getFailureMessage());
        assertTrue(second.isSkipped());
        assertArrayEquals(json("first"), Files.readAllBytes(target));
    }

    /// Treats Aura as initialized only after an allowlisted settings file or import receipt exists.
    ///
    /// @param temporaryDirectory isolated Aura homes
    /// @throws IOException if the fixture cannot be created
    @Test
    public void detectAuraInitializationFromManagedFiles(@TempDir Path temporaryDirectory) throws IOException {
        Homes homes = homes(temporaryDirectory);

        assertTrue(LegacyHmclCeDataImporter.isAuraUninitialized(homes.auraUser(), homes.auraLocal()));
        write(homes.auraLocal().resolve("logs/latest.log"), new byte[]{1});
        assertTrue(LegacyHmclCeDataImporter.isAuraUninitialized(homes.auraUser(), homes.auraLocal()));
        write(homes.auraUser().resolve("config/user-settings.json"), json("aura"));
        assertFalse(LegacyHmclCeDataImporter.isAuraUninitialized(homes.auraUser(), homes.auraLocal()));
    }

    /// Detects a legacy installation only from importable settings rather than plugin or cache state.
    ///
    /// @param temporaryDirectory isolated legacy homes
    /// @throws IOException if the fixture cannot be created
    @Test
    public void detectLegacyHomesFromAllowlistedSettings(@TempDir Path temporaryDirectory) throws IOException {
        Homes homes = homes(temporaryDirectory);
        write(homes.legacyLocal().resolve("plugins/runtime-host.npl"), new byte[]{1});
        assertTrue(LegacyHmclCeDataImporter.detectLegacyHomes(
                homes.legacyUser(), homes.legacyLocal()
        ).isEmpty());

        write(homes.legacyUser().resolve("config/user-settings.json"), json("legacy"));
        LegacyHmclCeDataImporter.LegacyHomes detected = LegacyHmclCeDataImporter.detectLegacyHomes(
                homes.legacyUser(), homes.legacyLocal()
        ).orElseThrow();

        assertEquals(homes.legacyUser().toAbsolutePath().normalize(), detected.userHome());
        assertEquals(homes.legacyLocal().toAbsolutePath().normalize(), detected.localHome());
    }

    /// Creates deterministic source and target homes under one temporary directory.
    ///
    /// @param root test-owned root
    /// @return immutable home path group
    private static Homes homes(Path root) {
        return new Homes(
                root.resolve("legacy-user"),
                root.resolve("legacy-local"),
                root.resolve("aura-user"),
                root.resolve("aura-local")
        );
    }

    /// Encodes one valid JSON object fixture.
    ///
    /// @param value marker value
    /// @return UTF-8 JSON bytes
    private static byte[] json(String value) {
        return ("{\"value\":\"" + value + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /// Writes one test file after creating its parent directory.
    ///
    /// @param path target path
    /// @param bytes file contents
    /// @throws IOException if the fixture cannot be written
    private static void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    /// Groups source and target homes used by one importer test.
    ///
    /// @param legacyUser legacy HMCL CE user home
    /// @param legacyLocal legacy HMCL CE local home
    /// @param auraUser Aura user home
    /// @param auraLocal Aura local home
    @NotNullByDefault
    private record Homes(Path legacyUser, Path legacyLocal, Path auraUser, Path auraLocal) {
    }
}
