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

import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginVersion;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Immutable identity, availability, ranking, and capability contract for one runtime provider.
@NotNullByDefault
public final class RuntimeProviderDescriptor {
    /// Stable reserved provider ID owned by the launcher-supplied Java runtime.
    public static final String BUILTIN_JAVA_PROVIDER_ID = "hmcl.runtime.java";

    /// Canonical provider plugin ID.
    private final String providerId;

    /// Comparable provider package version.
    private final String version;

    /// Runtime capabilities keyed by canonical runtime identifier.
    private final @Unmodifiable Map<String, RuntimeProviderDeclaration> capabilities;

    /// Whether the provider package is installed locally.
    private final boolean installed;

    /// Whether an installed provider package is enabled.
    private final boolean enabled;

    /// Zero-based source priority, where a lower number has higher priority.
    private final int sourcePriority;

    /// Whether this descriptor is owned by immutable launcher code.
    private final boolean reserved;

    /// Creates one validated provider descriptor.
    ///
    /// @param providerId canonical provider plugin ID
    /// @param version comparable provider version
    /// @param declarations nonempty runtime capability declarations
    /// @param installed whether the provider package is installed
    /// @param enabled whether the installed provider package is enabled
    /// @param sourcePriority zero-based source priority
    /// @param reserved whether launcher code owns an immutable registration
    public RuntimeProviderDescriptor(
            String providerId,
            String version,
            List<RuntimeProviderDeclaration> declarations,
            boolean installed,
            boolean enabled,
            int sourcePriority,
            boolean reserved) {
        if (!PluginManifest.isCanonicalExecutableId(providerId)) {
            throw new IllegalArgumentException("Runtime provider ID must be canonical: " + providerId);
        }
        PluginVersion.compare(version, version);
        if (enabled && !installed) {
            throw new IllegalArgumentException("A runtime provider cannot be enabled before it is installed");
        }
        if (sourcePriority < 0) {
            throw new IllegalArgumentException("Runtime provider source priority cannot be negative");
        }
        Map<String, RuntimeProviderDeclaration> capabilitiesByRuntime = new LinkedHashMap<>();
        for (RuntimeProviderDeclaration declaration : List.copyOf(declarations)) {
            @Nullable RuntimeProviderDeclaration duplicate = capabilitiesByRuntime.putIfAbsent(
                    declaration.getRuntime(), declaration);
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "Duplicate runtime provider capability: " + declaration.getRuntime());
            }
        }
        if (capabilitiesByRuntime.isEmpty()) {
            throw new IllegalArgumentException("Runtime provider capabilities cannot be empty");
        }
        this.providerId = providerId;
        this.version = version;
        this.capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilitiesByRuntime));
        this.installed = installed;
        this.enabled = enabled;
        this.sourcePriority = sourcePriority;
        this.reserved = reserved;
    }

    /// Returns the canonical provider plugin ID.
    public String providerId() {
        return providerId;
    }

    /// Returns the provider package version used for deterministic ranking.
    public String version() {
        return version;
    }

    /// Returns an immutable runtime capability snapshot keyed by canonical runtime identifier.
    public @Unmodifiable Map<String, RuntimeProviderDeclaration> capabilities() {
        return capabilities;
    }

    /// Returns the declaration for one canonical runtime, if advertised.
    public Optional<RuntimeProviderDeclaration> capability(String runtime) {
        return Optional.ofNullable(capabilities.get(PluginRuntimeTypes.requireValid(runtime)));
    }

    /// Returns whether the provider package is installed locally.
    public boolean installed() {
        return installed;
    }

    /// Returns whether the installed provider package is enabled.
    public boolean enabled() {
        return enabled;
    }

    /// Returns the zero-based source priority, where lower values win.
    public int sourcePriority() {
        return sourcePriority;
    }

    /// Returns whether launcher code owns this immutable registration.
    public boolean reserved() {
        return reserved;
    }

    /// Compares every descriptor identity, availability, and capability field.
    ///
    /// @param other comparison target
    /// @return whether both descriptors are equivalent
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof RuntimeProviderDescriptor descriptor
                && installed == descriptor.installed
                && enabled == descriptor.enabled
                && sourcePriority == descriptor.sourcePriority
                && reserved == descriptor.reserved
                && providerId.equals(descriptor.providerId)
                && version.equals(descriptor.version)
                && capabilities.equals(descriptor.capabilities);
    }

    /// Returns a hash derived from every descriptor field.
    @Override
    public int hashCode() {
        return Objects.hash(providerId, version, capabilities, installed, enabled, sourcePriority, reserved);
    }
}
