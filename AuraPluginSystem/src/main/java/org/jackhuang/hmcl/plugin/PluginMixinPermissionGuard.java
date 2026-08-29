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

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/// Applies the persisted, artifact-bound permission policy before any plugin Mixin can run.
///
/// This guard deliberately reuses the regular permission store so startup and runtime decisions interpret the same
/// fail-closed document schema. Only Aura-executable schema-v5 artifacts are eligible, and they require every
/// effective required grant
/// while denied optional requests remain unavailable without blocking startup.
@NotNullByDefault
public final class PluginMixinPermissionGuard {
    /// Immutable snapshot of persisted permission decisions loaded for the current startup.
    private final PluginPermissionStore store;

    /// Loads startup permission decisions from the launcher's private permission document.
    ///
    /// Missing or malformed documents yield no grants through [PluginPermissionStore].
    ///
    /// @param permissionFile private `plugin-permissions.json` path
    public PluginMixinPermissionGuard(Path permissionFile) {
        store = new PluginPermissionStore(permissionFile);
    }

    /// Returns whether one exact package may contribute startup-time Mixin transformations.
    ///
    /// @param manifest validated package manifest
    /// @param packageSha256 lower-case SHA-256 digest of the complete `.npl` package
    /// @return whether the exact executable artifact may contribute Mixin transformations
    public boolean isGranted(PluginManifest manifest, String packageSha256) {
        if (!PluginManifest.isExecutableSchema(manifest.getSchemaVersion())
                || !manifest.hasMixins()
                || !manifest.isPermissionRequired(PluginPermission.MIXIN)) {
            return false;
        }

        return hasRequiredPermissions(manifest, packageSha256);
    }

    /// Returns whether one exact executable artifact has every effective required permission.
    ///
    /// Every executable schema-v5 artifact relies on exact-artifact stored grants and its explicit required subset.
    /// Earlier schema
    /// versions, missing decisions, and damaged permission state remain fail-closed during premain.
    ///
    /// @param manifest validated package manifest
    /// @param packageSha256 lower-case SHA-256 digest of the complete `.npl` package
    /// @return whether every effective required permission is available
    public boolean hasRequiredPermissions(PluginManifest manifest, String packageSha256) {
        if (!PluginManifest.isExecutableSchema(manifest.getSchemaVersion())) {
            return false;
        }

        PluginPermissionStore.Artifact artifact = new PluginPermissionStore.Artifact(
                manifest.getId(),
                manifest.getVersion(),
                packageSha256
        );
        EnumSet<PluginPermission> effective = EnumSet.noneOf(PluginPermission.class);
        effective.addAll(store.getGrantedPermissions(artifact));
        effective.retainAll(manifest.getPermissions());
        Set<PluginPermission> required = Set.copyOf(manifest.getRequiredPermissions());
        return effective.containsAll(required);
    }
}
