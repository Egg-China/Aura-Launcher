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

import java.util.Objects;

/// Immutable compatibility outcome and its specific diagnostic detail.
///
/// @param status compatibility outcome category
/// @param detail non-null diagnostic detail for the outcome
@NotNullByDefault
public record PluginCompatibilityResult(PluginCompatibilityStatus status, String detail) {
    /// Validates both result components before constructing the immutable outcome.
    public PluginCompatibilityResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
    }

    /// Returns whether every plugin compatibility requirement was satisfied.
    public boolean isCompatible() {
        return status == PluginCompatibilityStatus.COMPATIBLE;
    }
}
