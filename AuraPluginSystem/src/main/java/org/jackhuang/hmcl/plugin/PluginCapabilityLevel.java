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

/// Capability tier a plugin operates at, derived from the declarations in its manifest.
///
/// Level 1 API plugins only call Core API functions. Level 2 describes lifecycle Hook declarations routed through
/// the Hook dispatcher, including supported external Runtime endpoints. Level 3 reserves declarative patches;
/// HMCL validates and exposes Patch declarations but does not yet apply them through a bytecode engine.
@NotNullByDefault
public enum PluginCapabilityLevel {
    /// Calls Core API functions only; the safest tier.
    API,

    /// Declares launcher lifecycle Hook subscriptions routed through the Hook dispatcher.
    HOOK,

    /// Declares method patches for the future JVM patch engine.
    PATCH;

    /// Returns the highest capability level a manifest enables.
    ///
    /// @param hookSubscribed whether the manifest declares hook points
    /// @param patchDeclared whether the manifest declares patches
    /// @return the highest enabled level, never null
    public static PluginCapabilityLevel of(boolean hookSubscribed, boolean patchDeclared) {
        if (patchDeclared) {
            return PATCH;
        }
        if (hookSubscribed) {
            return HOOK;
        }
        return API;
    }
}
