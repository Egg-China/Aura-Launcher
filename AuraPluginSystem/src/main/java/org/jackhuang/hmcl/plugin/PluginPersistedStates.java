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
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// JSON representation persisted in `plugin-states.json`.
@NotNullByDefault
final class PluginPersistedStates {
    /// IDs requested to be enabled, or `null` in malformed legacy files.
    @Nullable List<@Nullable String> enabled;

    /// IDs awaiting uninstall, or `null` in malformed legacy files.
    @Nullable List<@Nullable String> pendingUninstall;

    /// IDs retained but blocked from execution after startup recovery, or `null` in legacy or malformed files.
    @Nullable List<@Nullable String> quarantined;

    /// Secret-free recovery report, or `null` in legacy files and before the first recovery quarantine.
    @Nullable PluginQuarantineReport quarantineReport;

    /// Creates an empty state object for Gson and saving.
    PluginPersistedStates() {
    }
}
