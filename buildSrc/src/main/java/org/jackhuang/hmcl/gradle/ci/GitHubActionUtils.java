/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.gradle.ci;

import java.util.Objects;
import org.jetbrains.annotations.NotNullByDefault;

/// Detects official Aura Launcher builds running in the canonical GitHub Actions repository.
///
/// @author Glavo
@NotNullByDefault
public final class GitHubActionUtils {
    /// Canonical GitHub repository slug used for official Aura build-channel selection.
    private static final String OFFICIAL_REPOSITORY = "Egg-China/Aura-Launcher";

    /// Whether the current process is a non-pull-request GitHub Actions build in the official repository.
    public static final boolean IS_ON_OFFICIAL_REPO =
            OFFICIAL_REPOSITORY.equalsIgnoreCase(System.getenv("GITHUB_REPOSITORY"))
                    && Objects.requireNonNullElse(System.getenv("GITHUB_BASE_REF"), "").isBlank();

    /// Prevents construction of the CI environment utility.
    private GitHubActionUtils() {
    }
}
