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

/// Describes the authoritative lifecycle state of the currently published plugin artifact.
@NotNullByDefault
public enum PluginRuntimeStatus {
    /// The package is installed but its lifecycle is not selected for startup.
    INSTALLED_DISABLED,

    /// The package uses a legacy manifest schema and is retained only for management or update.
    BLOCKED_LEGACY,

    /// A proof-backed certified package is revoked or its retained certification no longer verifies.
    BLOCKED_REVOKED,

    /// A Mixin package is missing a declaration or at least one requested capability was denied.
    BLOCKED_PERMISSION,

    /// The package or desired enablement changed and can execute only after a clean restart.
    WAITING_FOR_RESTART,

    /// The exact Mixin artifact was not confirmed by the active premain Agent.
    BLOCKED_AGENT,

    /// The exact artifact completed `onLoad` and `onEnable` in this process.
    ENABLED,

    /// Package validation, dependency resolution, loading, or lifecycle activation failed.
    LOAD_FAILED,

    /// The package remains visible but will be removed before the next startup discovery.
    PENDING_UNINSTALL
}
