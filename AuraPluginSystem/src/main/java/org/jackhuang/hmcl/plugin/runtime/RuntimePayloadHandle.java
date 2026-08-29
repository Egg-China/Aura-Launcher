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
import org.jetbrains.annotations.NotNullByDefault;

/// Opaque identity returned by a provider for one loaded payload.
///
/// The handle contains no language engine object, preventing callers from crossing provider ownership boundaries.
///
/// @param ownerPluginId plugin that owns the loaded payload
/// @param providerId provider that issued and may consume the handle
/// @param payloadId provider-scoped opaque payload identifier
@NotNullByDefault
public record RuntimePayloadHandle(String ownerPluginId, String providerId, String payloadId) {
    /// Validates owner, provider, and opaque payload identities.
    public RuntimePayloadHandle {
        if (!PluginManifest.isCanonicalExecutableId(ownerPluginId)) {
            throw new IllegalArgumentException("Runtime payload owner ID must be canonical: " + ownerPluginId);
        }
        if (!PluginManifest.isCanonicalExecutableId(providerId)) {
            throw new IllegalArgumentException("Runtime provider ID must be canonical: " + providerId);
        }
        if (payloadId.isBlank()) {
            throw new IllegalArgumentException("Runtime payload ID cannot be blank");
        }
    }
}
