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

/// Risk classification of declared plugin permissions used by install-time consent screens.
///
/// The tier drives how loudly the launcher warns before granting a permission: normal capabilities
/// install silently once declared, advanced capabilities require an explicit consent prompt, and
/// dangerous capabilities additionally explain that the plugin can execute system commands, load
/// native code, or modify launcher behavior. Shell access, native code, and launcher patching are
/// governed by this unified permission system instead of being treated as an implicit escape hatch.
@NotNullByDefault
public enum PluginPermissionTier {
    /// Capabilities confined to launcher-provided surfaces.
    NORMAL,

    /// Capabilities that reach the operating system or sensitive account data.
    ADVANCED,

    /// Capabilities that can execute arbitrary code or alter launcher behavior.
    DANGEROUS;

    /// Returns the tier of one declared permission.
    public static PluginPermissionTier tierOf(PluginPermission permission) {
        switch (permission) {
            case LAUNCHER_UI:
            case GAME_LAUNCH:
            case CLIPBOARD:
                return NORMAL;
            case FILESYSTEM:
            case NETWORK:
            case PROCESS:
            case ACCOUNT:
            case LAUNCHER_CORE:
                return ADVANCED;
            case MIXIN:
            case NATIVE_CODE:
            case LAUNCHER_HOOK:
            case LAUNCHER_PATCH:
            case JVM_RAW:
            case SHELL:
            default:
                return DANGEROUS;
        }
    }
}
