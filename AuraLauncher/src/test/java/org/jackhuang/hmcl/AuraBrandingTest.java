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

import org.jackhuang.hmcl.plugin.store.PluginStoreManager;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Verifies the launcher exposes its Aura distribution identity.
@NotNullByDefault
public final class AuraBrandingTest {
    /// Ensures both short and full product names use the Aura brand.
    @Test
    public void exposesAuraProductName() {
        assertEquals("Aura", Metadata.NAME);
        assertEquals("Aura Launcher", Metadata.FULL_NAME);
    }

    /// Ensures Windows treats Aura Launcher as a distinct application on the taskbar.
    @Test
    public void exposesIndependentWindowsTaskbarIdentity() {
        assertEquals("org.eggchina.auralauncher", Metadata.WINDOWS_APP_USER_MODEL_ID);
    }

    /// Ensures every Aura-owned endpoint targets the Egg China repository and private builds do not auto-update.
    @Test
    public void exposesAuraEndpoints() {
        assertEquals("https://github.com/Egg-China/Aura-Launcher", Metadata.PUBLISH_URL);
        assertEquals("https://github.com/Egg-China/Aura-Launcher/releases", Metadata.DOWNLOAD_URL);
        assertEquals("https://api.github.com/repos/Egg-China/Aura-Launcher/releases",
                Metadata.GITHUB_RELEASES_API_URL);
        assertEquals("", Metadata.AURA_UPDATE_URL);
        assertEquals("https://github.com/Egg-China/Aura-Launcher/releases", Metadata.MANUAL_UPDATE_URL);
        assertEquals("https://github.com/Egg-China/Aura-Launcher/issues/new/choose", Metadata.CONTACT_URL);
        assertEquals("https://raw.githubusercontent.com/Egg-China/Aura-Launcher-Plugin-Store/main/plugins.json",
                PluginStoreManager.DEFAULT_REGISTRY_URL);
    }

    /// Ensures launcher-owned translations never expose a predecessor's CE branding as the current product.
    @Test
    public void hidesLegacyCeBrandingFromTranslationValues() throws IOException, URISyntaxException {
        Path languageDirectory = Objects.requireNonNull(Path.of(Objects.requireNonNull(
                AuraBrandingTest.class.getResource("/assets/lang/I18N.properties")
        ).toURI()).getParent());
        try (DirectoryStream<Path> languageFiles = Files.newDirectoryStream(
                languageDirectory,
                "I18N*.properties"
        )) {
            for (Path source : languageFiles) {
                assertNoLegacyCeBranding(source);
            }
        }
    }

    /// Resolves Aura live storage only from Aura-specific inputs and Aura defaults.
    @Test
    public void resolvesIndependentAuraDataHomes() {
        Path root = Path.of("build", "metadata-path-test").toAbsolutePath().normalize();
        Path explicitUser = root.resolve("explicit-user");
        Path explicitLocal = root.resolve("explicit-local");
        Path explicitDependencies = root.resolve("explicit-dependencies");

        assertEquals(explicitUser, Metadata.resolveAuraUserHome(
                explicitUser.toString(), null, null, root, OperatingSystem.WINDOWS
        ));
        assertEquals(explicitLocal, Metadata.resolveAuraLocalHome(explicitLocal.toString(), root));
        assertEquals(explicitDependencies, Metadata.resolveAuraDependenciesHome(
                explicitDependencies.toString(), explicitLocal
        ));
        assertEquals(root.resolve(".aura"), Metadata.resolveAuraLocalHome(null, root));
        assertEquals(root.resolve(".local/share/aura-launcher"), Metadata.resolveAuraUserHome(
                null, null, null, root, OperatingSystem.LINUX
        ));
    }

    /// Resolves legacy HMCL CE sources independently from Aura's live storage configuration.
    @Test
    public void resolvesLegacyHmclCeImportHomesSeparately() {
        Path root = Path.of("build", "legacy-metadata-path-test").toAbsolutePath().normalize();
        Path explicitUser = root.resolve("legacy-user");
        Path explicitLocal = root.resolve("legacy-local");

        assertEquals(explicitUser, Metadata.resolveLegacyHmclCeUserHome(
                explicitUser.toString(), null, null, root, OperatingSystem.WINDOWS
        ));
        assertEquals(explicitLocal, Metadata.resolveLegacyHmclCeLocalHome(explicitLocal.toString(), root));
        assertEquals(root.resolve(".hmcl"), Metadata.resolveLegacyHmclCeLocalHome(null, root));
    }

    /// Scans every localized value in one launcher-owned translation file.
    ///
    /// @param source translation property file
    /// @throws IOException if the translation cannot be read
    private static void assertNoLegacyCeBranding(Path source) throws IOException {
        Properties translations = new Properties();
        try (var input = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            translations.load(input);
        }
        for (String key : translations.stringPropertyNames()) {
            String value = Objects.requireNonNull(translations.getProperty(key));
            String normalized = value.toLowerCase(Locale.ROOT);
            assertFalse(normalized.contains("hmcl ce"), source + ":" + key + " exposes HMCL CE");
            assertFalse(normalized.contains("pcl ce"), source + ":" + key + " exposes PCL CE");
        }
    }
}
