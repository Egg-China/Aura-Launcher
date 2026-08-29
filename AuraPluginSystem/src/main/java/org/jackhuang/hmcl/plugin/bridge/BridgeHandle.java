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
package org.jackhuang.hmcl.plugin.bridge;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.regex.Pattern;

/// Identifies one owner-scoped opaque JVM reference without exposing its owner or referenced object.
///
/// @param id registry-local numeric slot
/// @param generation slot generation that prevents ABA reuse
/// @param type stable language-neutral type descriptor
@NotNullByDefault
public record BridgeHandle(long id, long generation, String type) {
    /// Maximum encoded type descriptor length.
    public static final int MAX_TYPE_LENGTH = 128;

    /// Canonical syntax for a language-neutral type descriptor.
    private static final Pattern TYPE_PATTERN = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    /// Validates the positive slot identity, generation, and canonical type descriptor.
    public BridgeHandle {
        if (id <= 0L) {
            throw new IllegalArgumentException("Bridge handle ID must be positive");
        }
        if (generation <= 0L) {
            throw new IllegalArgumentException("Bridge handle generation must be positive");
        }
        type = requireValidType(type);
    }

    /// Validates and returns one canonical language-neutral type descriptor.
    ///
    /// @param type candidate type descriptor
    /// @return validated descriptor
    static String requireValidType(String type) {
        Objects.requireNonNull(type, "type");
        if (type.length() > MAX_TYPE_LENGTH || !TYPE_PATTERN.matcher(type).matches()) {
            throw new IllegalArgumentException("Bridge handle type must be canonical");
        }
        return type;
    }
}
