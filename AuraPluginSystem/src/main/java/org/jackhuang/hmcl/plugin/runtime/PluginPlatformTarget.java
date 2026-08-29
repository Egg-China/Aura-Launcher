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
package org.jackhuang.hmcl.plugin.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/// Immutable operating-system and CPU-architecture identifier pair used by schema-v5 plugin manifests.
///
/// A target is written as `os` or `os-arch`, for example `windows-x64`, `linux-arm64`, or `macos` for an
/// architecture-independent package. Aura Launcher uses targets to reject incompatible manifests and Store
/// entries and to apply its explicit platform-compatibility rules.
@NotNullByDefault
public final class PluginPlatformTarget {
    /// Canonical operating-system identifiers accepted in manifests and store indexes.
    public static final @Unmodifiable Set<String> KNOWN_OPERATING_SYSTEMS =
            Set.of("windows", "linux", "macos", "freebsd", "harmonyos");

    /// Canonical CPU-architecture identifiers accepted in manifests and store indexes.
    public static final @Unmodifiable Set<String> KNOWN_ARCHITECTURES = Set.of(
            "x86", "x64", "arm32", "arm64", "riscv64", "loongarch64", "mips64");

    /// Maximum accepted byte length for Linux-compatible release metadata.
    private static final int MAX_OS_RELEASE_BYTES = 64 * 1024;

    /// Release metadata fields allowed to identify HarmonyOS.
    private static final @Unmodifiable Set<String> HARMONY_RELEASE_KEYS =
            Set.of("ID", "NAME", "PRETTY_NAME", "ID_LIKE");

    private final String operatingSystem;
    private final @Nullable String architecture;

    private PluginPlatformTarget(String operatingSystem, @Nullable String architecture) {
        this.operatingSystem = operatingSystem;
        this.architecture = architecture;
    }

    /// Parses a canonical platform identifier such as `windows-x64` or `linux`.
    ///
    /// @throws IllegalArgumentException when the identifier is blank or references unknown components
    public static PluginPlatformTarget parse(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Blank plugin platform target");
        }
        String operatingSystem;
        @Nullable String architecture = null;
        int separator = normalized.indexOf('-');
        if (separator >= 0) {
            operatingSystem = normalized.substring(0, separator);
            architecture = normalized.substring(separator + 1);
            if (architecture.isEmpty()) {
                throw new IllegalArgumentException("Plugin platform target lacks architecture: " + value);
            }
        } else {
            operatingSystem = normalized;
        }
        if (!KNOWN_OPERATING_SYSTEMS.contains(operatingSystem)) {
            throw new IllegalArgumentException("Unknown plugin platform operating system: " + value);
        }
        if (architecture != null && !KNOWN_ARCHITECTURES.contains(architecture)) {
            throw new IllegalArgumentException("Unknown plugin platform architecture: " + value);
        }
        return new PluginPlatformTarget(operatingSystem, architecture);
    }

    /// Detects the platform the launcher is currently running on.
    public static PluginPlatformTarget current() {
        return detect(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                Path.of("/etc/os-release")
        );
    }

    /// Detects a target from explicit host inputs without mutating process-global properties.
    ///
    /// @param osName Java operating-system name
    /// @param osArch Java CPU-architecture name
    /// @param osReleasePath Linux-compatible release metadata path
    /// @return detected canonical plugin target
    static PluginPlatformTarget detect(String osName, String osArch, Path osReleasePath) {
        return new PluginPlatformTarget(
                detectOperatingSystem(osName, osReleasePath),
                normalizeArchitecture(osArch)
        );
    }

    /// Returns whether a package declaring this target can run on the given host.
    public boolean matches(PluginPlatformTarget host) {
        if (operatingSystem.equals(host.operatingSystem)) {
            return architecture == null || architecture.equals(host.architecture);
        }
        return operatingSystem.equals("linux")
                && host.operatingSystem.equals("harmonyos")
                && "arm64".equals(host.architecture)
                && (architecture == null || architecture.equals("arm64"));
    }

    /// Returns the canonical serialized form of this target.
    public String getId() {
        return architecture == null ? operatingSystem : operatingSystem + "-" + architecture;
    }

    /// Returns the canonical operating-system identifier.
    public String getOperatingSystem() {
        return operatingSystem;
    }

    /// Returns the canonical architecture identifier, or null when the target is architecture independent.
    public @Nullable String getArchitecture() {
        return architecture;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PluginPlatformTarget)) {
            return false;
        }
        PluginPlatformTarget target = (PluginPlatformTarget) other;
        return operatingSystem.equals(target.operatingSystem) && Objects.equals(architecture, target.architecture);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operatingSystem, architecture);
    }

    @Override
    public String toString() {
        return getId();
    }

    /// Detects a canonical operating-system identifier from explicit host metadata.
    ///
    /// @param osName Java operating-system name
    /// @param osReleasePath Linux-compatible release metadata path
    /// @return canonical operating-system identifier
    private static String detectOperatingSystem(String osName, Path osReleasePath) {
        String name = osName.toLowerCase(Locale.ROOT);
        if (containsHarmonyName(name)) {
            return "harmonyos";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "macos";
        }
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("freebsd")) {
            return "freebsd";
        }
        if (name.contains("linux") && containsHarmonyMarker(readOsRelease(osReleasePath))) {
            return "harmonyos";
        }
        return "linux";
    }

    /// Reads Linux-compatible release metadata with a strict size and UTF-8 boundary.
    ///
    /// @param path release metadata path
    /// @return decoded metadata, or `null` when the input is unavailable or invalid
    private static @Nullable String readOsRelease(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_OS_RELEASE_BYTES + 1);
            if (bytes.length > MAX_OS_RELEASE_BYTES) {
                return null;
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (IOException | SecurityException exception) {
            return null;
        }
    }

    /// Returns whether trusted release metadata fields identify HarmonyOS or OpenHarmony.
    ///
    /// @param releaseMetadata decoded release metadata, or `null`
    /// @return whether one accepted field contains a HarmonyOS marker
    private static boolean containsHarmonyMarker(@Nullable String releaseMetadata) {
        if (releaseMetadata == null) {
            return false;
        }
        for (String line : releaseMetadata.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            if (!HARMONY_RELEASE_KEYS.contains(key)) {
                continue;
            }
            @Nullable String value = normalizeReleaseValue(line.substring(separator + 1));
            if (value != null) {
                if (containsHarmonyName(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Returns whether a host name contains a HarmonyOS or OpenHarmony marker.
    ///
    /// @param value host name or trusted release field
    /// @return whether the value identifies HarmonyOS
    private static boolean containsHarmonyName(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("harmonyos") || normalized.contains("openharmony");
    }

    /// Removes one matching quote pair from an operating-system release value.
    ///
    /// @param value raw value after the first equals sign
    /// @return normalized value, or `null` for mismatched quotes
    private static @Nullable String normalizeReleaseValue(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return normalized;
        }
        char first = normalized.charAt(0);
        char last = normalized.charAt(normalized.length() - 1);
        if (first == '\'' || first == '"') {
            return last == first && normalized.length() >= 2
                    ? normalized.substring(1, normalized.length() - 1)
                    : null;
        }
        return last == '\'' || last == '"' ? null : normalized;
    }

    /// Normalizes Java architecture aliases into schema-v5 identifiers.
    ///
    /// @param osArch Java CPU-architecture name
    /// @return canonical architecture, or `null` when the architecture is unknown
    private static @Nullable String normalizeArchitecture(String osArch) {
        String arch = osArch.toLowerCase(Locale.ROOT);
        switch (arch) {
            case "x86_64":
            case "amd64":
                return "x64";
            case "aarch64":
            case "arm64":
                return "arm64";
            case "x86":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
                return "x86";
            case "arm":
            case "armv7l":
            case "armv8l":
                return "arm32";
            case "riscv64":
                return "riscv64";
            case "loongarch64":
            case "loongarch":
                return "loongarch64";
            case "mips64":
            case "mips64el":
                return "mips64";
            default:
                return KNOWN_ARCHITECTURES.contains(arch) ? arch : null;
        }
    }
}
