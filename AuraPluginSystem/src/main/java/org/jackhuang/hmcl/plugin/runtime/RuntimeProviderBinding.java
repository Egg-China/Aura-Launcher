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

/// Records the provider selected for one dependent plugin and runtime requirement.
///
/// @param dependentPluginId plugin whose payload consumes the runtime
/// @param providerId selected provider plugin ID
/// @param runtime canonical runtime capability bound to the dependent
@NotNullByDefault
public record RuntimeProviderBinding(String dependentPluginId, String providerId, String runtime) {
    /// Validates the immutable binding identity.
    public RuntimeProviderBinding {
        if (!PluginManifest.isCanonicalExecutableId(dependentPluginId)) {
            throw new IllegalArgumentException("Dependent plugin ID must be canonical: " + dependentPluginId);
        }
        if (!PluginManifest.isCanonicalExecutableId(providerId)) {
            throw new IllegalArgumentException("Runtime provider ID must be canonical: " + providerId);
        }
        String canonicalRuntime = PluginRuntimeTypes.requireValid(runtime);
        if (!canonicalRuntime.equals(runtime)) {
            throw new IllegalArgumentException("Bound runtime identifier must be canonical: " + runtime);
        }
    }
}
