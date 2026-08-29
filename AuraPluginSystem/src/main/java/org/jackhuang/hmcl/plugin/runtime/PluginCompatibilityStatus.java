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

/// Outcome of evaluating one plugin package against the current launcher host.
@NotNullByDefault
public enum PluginCompatibilityStatus {
    /// Every compatibility requirement is satisfied.
    COMPATIBLE,

    /// The package manifest schema cannot execute on this launcher.
    UNSUPPORTED_SCHEMA,

    /// The launcher version does not satisfy the package constraint.
    UNSUPPORTED_LAUNCHER,

    /// The current host does not match any declared package platform.
    UNSUPPORTED_PLATFORM,

    /// No provider is registered for the package runtime.
    MISSING_RUNTIME,

    /// The registered runtime provider does not implement the required ABI.
    UNSUPPORTED_ABI,

    /// Registered providers do not support the requested execution boundary.
    UNSUPPORTED_EXECUTION_MODE,

    /// Registered providers do not implement the required launcher-to-provider Bridge ABI.
    UNSUPPORTED_BRIDGE_ABI,

    /// Registered providers lack at least one required runtime feature.
    UNSUPPORTED_RUNTIME_FEATURE
}
