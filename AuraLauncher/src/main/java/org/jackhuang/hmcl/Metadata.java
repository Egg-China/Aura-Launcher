/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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

import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.EnumSet;

/// Stores product identity, repository endpoints, release metadata, and local storage locations.
@NotNullByDefault
public final class Metadata {
    /// Prevents construction of the application metadata holder.
    private Metadata() {
    }

    /// Short product name displayed by the launcher.
    public static final String NAME = "Aura";
    /// Full product name displayed by the launcher.
    public static final String FULL_NAME = "Aura Launcher";
    /// Build version embedded by Gradle or overridden for development diagnostics.
    public static final String VERSION = System.getProperty(
            "aura.version.override", JarUtils.getAttribute("aura.version", "@develop@"));

    /// Explicit Application User Model ID used for Windows taskbar grouping and pinning.
    public static final String WINDOWS_APP_USER_MODEL_ID = "org.eggchina.auralauncher";

    /// Window title containing the short product name and version.
    public static final String TITLE = NAME + " " + VERSION;
    /// Full window title containing the product name and version.
    public static final String FULL_TITLE = FULL_NAME + " v" + VERSION;

    /// Oldest Java feature version capable of starting the launcher.
    public static final int MINIMUM_REQUIRED_JAVA_VERSION = 17;
    /// Oldest Java feature version supported by the project.
    public static final int MINIMUM_SUPPORTED_JAVA_VERSION = 17;
    /// Java feature version recommended for launcher operation.
    public static final int RECOMMENDED_JAVA_VERSION = 21;

    /// Canonical Aura Launcher source repository.
    public static final String PUBLISH_URL = "https://github.com/Egg-China/Aura-Launcher";
    /// Repository page containing Aura Launcher release artifacts.
    public static final String DOWNLOAD_URL = PUBLISH_URL + "/releases";
    /// GitHub Releases API endpoint reserved for a future public update feed.
    public static final String GITHUB_RELEASES_API_URL =
            "https://api.github.com/repos/Egg-China/Aura-Launcher/releases";
    /// Update endpoint; private builds disable automatic updates unless an explicit public feed is supplied.
    public static final String AURA_UPDATE_URL = System.getProperty("aura.update_source.override", "");
    /// Private repository release page used for collaborator-managed updates.
    public static final String MANUAL_UPDATE_URL = DOWNLOAD_URL;

    /// Aura Launcher project documentation entry point.
    public static final String DOCS_URL = PUBLISH_URL + "/wiki";
    /// Aura Launcher issue form used for launcher and crash feedback.
    public static final String CONTACT_URL = PUBLISH_URL + "/issues/new/choose";
    /// Prefix for the GitHub release page corresponding to a launcher version.
    public static final String CHANGELOG_URL = PUBLISH_URL + "/releases/tag/v";
    /// GPL license text distributed with Aura Launcher.
    public static final String EULA_URL = PUBLISH_URL + "/blob/main/LICENSE";
    /// Aura Launcher project discussions page.
    public static final String GROUPS_URL = PUBLISH_URL + "/discussions";

    /// Build channel embedded by Gradle.
    public static final String BUILD_CHANNEL = JarUtils.getAttribute("aura.version.type", "nightly");
    /// Git commit embedded by GitHub Actions, or `null` for local builds.
    public static final @Nullable String GITHUB_SHA = JarUtils.getAttribute("aura.version.hash", null);

    /// Process working directory normalized to an absolute path.
    public static final Path CURRENT_DIRECTORY = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    /// Default Minecraft game directory for the current platform.
    public static final Path MINECRAFT_DIRECTORY = OperatingSystem.getWorkingDirectory("minecraft");
    /// User-wide Aura data directory.
    public static final Path AURA_USER_HOME;
    /// Launcher-local Aura data directory.
    public static final Path AURA_LOCAL_HOME;
    /// Aura directory containing downloaded runtime dependencies.
    public static final Path AURA_DEPENDENCIES_DIRECTORY;
    /// Source-compatible alias for [#AURA_USER_HOME]; this never resolves an HMCL CE live directory.
    public static final Path HMCL_USER_HOME;
    /// Source-compatible alias for [#AURA_LOCAL_HOME]; this never resolves an HMCL CE live directory.
    public static final Path HMCL_LOCAL_HOME;
    /// Source-compatible alias for [#AURA_DEPENDENCIES_DIRECTORY].
    public static final Path DEPENDENCIES_DIRECTORY;
    /// User-wide HMCL CE directory inspected only as an optional import source.
    public static final Path LEGACY_HMCL_CE_USER_HOME;
    /// Launcher-local HMCL CE directory inspected only as an optional import source.
    public static final Path LEGACY_HMCL_CE_LOCAL_HOME;

    static {
        Path userHome = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        String appData = System.getenv("APPDATA");

        AURA_USER_HOME = resolveAuraUserHome(
                System.getProperty("aura.home", System.getenv("AURA_HOME")),
                xdgDataHome,
                appData,
                userHome,
                OperatingSystem.CURRENT_OS
        );
        AURA_LOCAL_HOME = resolveAuraLocalHome(
                System.getProperty("aura.dir", System.getenv("AURA_LOCAL_HOME")),
                CURRENT_DIRECTORY
        );
        AURA_DEPENDENCIES_DIRECTORY = resolveAuraDependenciesHome(
                System.getProperty("aura.dependencies.dir", System.getenv("AURA_DEPENDENCIES_DIR")),
                AURA_LOCAL_HOME
        );
        HMCL_USER_HOME = AURA_USER_HOME;
        HMCL_LOCAL_HOME = AURA_LOCAL_HOME;
        DEPENDENCIES_DIRECTORY = AURA_DEPENDENCIES_DIRECTORY;

        LEGACY_HMCL_CE_USER_HOME = resolveLegacyHmclCeUserHome(
                System.getProperty("hmcl.home", System.getenv("HMCL_USER_HOME")),
                xdgDataHome,
                appData,
                userHome,
                OperatingSystem.CURRENT_OS
        );
        LEGACY_HMCL_CE_LOCAL_HOME = resolveLegacyHmclCeLocalHome(
                System.getProperty("hmcl.dir", System.getenv("HMCL_LOCAL_HOME")),
                CURRENT_DIRECTORY
        );
    }

    /// Resolves the Aura user home from an explicit Aura override or platform-specific Aura default.
    ///
    /// @param override Aura-specific property or environment override, or `null`
    /// @param xdgDataHome XDG data root, or `null`
    /// @param appData Windows roaming application-data root, or `null`
    /// @param userHome normalized operating-system user home
    /// @param operatingSystem platform whose default layout should be used
    /// @return absolute normalized Aura user home
    static Path resolveAuraUserHome(
            @Nullable String override,
            @Nullable String xdgDataHome,
            @Nullable String appData,
            Path userHome,
            OperatingSystem operatingSystem
    ) {
        return resolveUserHome(
                override, xdgDataHome, appData, userHome, operatingSystem, "aura-launcher"
        );
    }

    /// Resolves the Aura local home from an Aura-specific override or the `.aura` default.
    ///
    /// @param override Aura-specific property or environment override, or `null`
    /// @param currentDirectory normalized process working directory
    /// @return absolute normalized Aura local home
    static Path resolveAuraLocalHome(@Nullable String override, Path currentDirectory) {
        return resolveLocalHome(override, currentDirectory, ".aura");
    }

    /// Resolves Aura's dependency directory from an Aura-specific override or its local home.
    ///
    /// @param override Aura-specific property or environment override, or `null`
    /// @param auraLocalHome normalized Aura local home
    /// @return absolute normalized Aura dependency directory
    static Path resolveAuraDependenciesHome(@Nullable String override, Path auraLocalHome) {
        return StringUtils.isNotBlank(override)
                ? Path.of(override).toAbsolutePath().normalize()
                : auraLocalHome.resolve("dependencies").toAbsolutePath().normalize();
    }

    /// Resolves the user-wide HMCL CE directory used only as an import source.
    ///
    /// @param override legacy HMCL property or environment override, or `null`
    /// @param xdgDataHome XDG data root, or `null`
    /// @param appData Windows roaming application-data root, or `null`
    /// @param userHome normalized operating-system user home
    /// @param operatingSystem platform whose legacy default layout should be used
    /// @return absolute normalized legacy user home
    static Path resolveLegacyHmclCeUserHome(
            @Nullable String override,
            @Nullable String xdgDataHome,
            @Nullable String appData,
            Path userHome,
            OperatingSystem operatingSystem
    ) {
        return resolveUserHome(override, xdgDataHome, appData, userHome, operatingSystem, "hmcl");
    }

    /// Resolves the launcher-local HMCL CE directory used only as an import source.
    ///
    /// @param override legacy HMCL property or environment override, or `null`
    /// @param currentDirectory normalized process working directory
    /// @return absolute normalized legacy local home
    static Path resolveLegacyHmclCeLocalHome(@Nullable String override, Path currentDirectory) {
        return resolveLocalHome(override, currentDirectory, ".hmcl");
    }

    /// Resolves one product's user-wide data directory without consulting global process state.
    ///
    /// @param override explicit product override, or `null`
    /// @param xdgDataHome XDG data root, or `null`
    /// @param appData Windows roaming application-data root, or `null`
    /// @param userHome normalized operating-system user home
    /// @param operatingSystem platform whose default layout should be used
    /// @param productDirectory unprefixed product directory name
    /// @return absolute normalized user-wide data directory
    private static Path resolveUserHome(
            @Nullable String override,
            @Nullable String xdgDataHome,
            @Nullable String appData,
            Path userHome,
            OperatingSystem operatingSystem,
            String productDirectory
    ) {
        if (StringUtils.isNotBlank(override)) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return switch (operatingSystem) {
            case LINUX, FREEBSD -> StringUtils.isNotBlank(xdgDataHome)
                    ? Path.of(xdgDataHome, productDirectory).toAbsolutePath().normalize()
                    : userHome.resolve(Path.of(".local", "share", productDirectory)).toAbsolutePath().normalize();
            case WINDOWS -> Path.of(
                    StringUtils.isNotBlank(appData) ? appData : userHome.toString(),
                    "." + productDirectory
            ).toAbsolutePath().normalize();
            case MACOS -> userHome.resolve(Path.of("Library", "Application Support", productDirectory))
                    .toAbsolutePath().normalize();
            default -> userHome.resolve(productDirectory).toAbsolutePath().normalize();
        };
    }

    /// Resolves one product's launcher-local directory from an override or current-directory child.
    ///
    /// @param override explicit product override, or `null`
    /// @param currentDirectory normalized process working directory
    /// @param defaultDirectory default child directory name
    /// @return absolute normalized launcher-local directory
    private static Path resolveLocalHome(
            @Nullable String override,
            Path currentDirectory,
            String defaultDirectory
    ) {
        return StringUtils.isNotBlank(override)
                ? Path.of(override).toAbsolutePath().normalize()
                : currentDirectory.resolve(defaultDirectory).toAbsolutePath().normalize();
    }

    /// Returns whether this build belongs to the stable release channel.
    ///
    /// @return whether the build channel is stable
    public static boolean isStable() {
        return "stable".equals(BUILD_CHANNEL);
    }

    /// Returns whether this build belongs to the development channel.
    ///
    /// @return whether the build channel is development
    public static boolean isDev() {
        return "dev".equals(BUILD_CHANNEL);
    }

    /// Returns whether this build belongs to the nightly channel.
    ///
    /// @return whether the build is neither stable nor development
    public static boolean isNightly() {
        return !isStable() && !isDev();
    }

    /// Selects a supported Java download guide for the current platform and architecture.
    ///
    /// @return Java download guide URL, or `null` when the platform is unsupported
    public static @Nullable String getSuggestedJavaDownloadLink() {
        if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX && Architecture.SYSTEM_ARCH == Architecture.LOONGARCH64_OW)
            return "https://www.loongnix.cn/zh/api/java/downloads-jdk21/index.html";
        else {
            EnumSet<Architecture> supportedArchitectures;
            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS)
                supportedArchitectures = EnumSet.of(Architecture.X86_64, Architecture.X86, Architecture.ARM64);
            else if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX)
                supportedArchitectures = EnumSet.of(
                        Architecture.X86_64, Architecture.X86,
                        Architecture.ARM64, Architecture.ARM32,
                        Architecture.RISCV64, Architecture.LOONGARCH64
                );
            else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS)
                supportedArchitectures = EnumSet.of(Architecture.X86_64, Architecture.ARM64);
            else
                supportedArchitectures = EnumSet.noneOf(Architecture.class);
            if (supportedArchitectures.contains(Architecture.SYSTEM_ARCH))
                return String.format("https://docs.hmcl.net/downloads/%s/%s.html",
                        OperatingSystem.CURRENT_OS.getCheckedName(),
                        Architecture.SYSTEM_ARCH.getCheckedName()
                );
            else
                return null;
        }
    }
}
