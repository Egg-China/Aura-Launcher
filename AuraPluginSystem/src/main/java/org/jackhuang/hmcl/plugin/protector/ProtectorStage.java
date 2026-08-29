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
package org.jackhuang.hmcl.plugin.protector;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Startup milestones reported by the protected launcher child before ordinary operation begins.
@NotNullByDefault
public enum ProtectorStage {
    /// The child JVM has started and established the Protector session.
    JVM_STARTED("jvm-started"),

    /// Launcher Core initialization has completed.
    CORE_READY("core-ready"),

    /// Runtime Provider plugins are being initialized.
    RUNTIME_PROVIDERS_LOADING("runtime-providers-loading"),

    /// Ordinary third-party plugins are being initialized.
    ORDINARY_PLUGINS_LOADING("ordinary-plugins-loading"),

    /// The launcher UI is ready and startup supervision has completed.
    UI_READY("ui-ready");

    /// Stable control-protocol spelling.
    private final String wireName;

    /// Creates one stage with its stable wire spelling.
    ///
    /// @param wireName stable wire spelling
    ProtectorStage(String wireName) {
        this.wireName = wireName;
    }

    /// Returns the stable wire spelling.
    ///
    /// @return stable wire spelling
    public String wireName() {
        return wireName;
    }

    /// Resolves an exact wire spelling without accepting future values implicitly.
    ///
    /// @param wireName candidate wire spelling
    /// @return matching stage, or `null` when unknown
    static @Nullable ProtectorStage fromWireName(String wireName) {
        for (ProtectorStage stage : values()) {
            if (stage.wireName.equals(wireName)) {
                return stage;
            }
        }
        return null;
    }
}
