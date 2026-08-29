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
package org.jackhuang.hmcl.plugin.trust;

import org.jetbrains.annotations.NotNullByDefault;

/// Locally derived trust level for one plugin-store document.
@NotNullByDefault
public enum PluginTrustLevel {
    /// Registry content signed by the official repository role.
    OFFICIAL(3, true, false),

    /// Community content signed by a currently valid constrained developer certificate.
    CERTIFIED(2, true, false),

    /// Unsigned community content that requires explicit source confirmation.
    COMMUNITY(1, true, true),

    /// Content that declared signing material but failed verification.
    REJECTED(0, false, true);

    /// Selection priority used when multiple repositories publish the same plugin ID.
    private final int priority;

    /// Whether installation may proceed to normal permission review.
    private final boolean installable;

    /// Whether installation must include an untrusted-source warning.
    private final boolean sourceWarning;

    /// Creates one immutable trust policy value.
    PluginTrustLevel(int priority, boolean installable, boolean sourceWarning) {
        this.priority = priority;
        this.installable = installable;
        this.sourceWarning = sourceWarning;
    }

    /// Returns conflict-selection priority.
    public int getPriority() {
        return priority;
    }

    /// Returns whether normal installation may proceed.
    public boolean isInstallable() {
        return installable;
    }

    /// Returns whether a source warning is required.
    public boolean requiresSourceWarning() {
        return sourceWarning;
    }
}
