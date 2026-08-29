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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.security.MessageDigest;
import java.util.Arrays;

/// Opaque unforgeable authority presented by a runtime Provider on behalf of one dependent plugin.
///
/// The type intentionally exposes neither its 256-bit identifier nor a public constructor. Only
/// [PluginPermissionAuthority] can create and interpret instances.
@NotNullByDefault
public final class PluginCapabilityToken {
    /// Required random token identifier size in bytes.
    static final int IDENTIFIER_BYTES = 32;

    /// Random identifier retained only for authority lookup and collision detection.
    private final byte @Unmodifiable [] identifier;

    /// Creates one token from a freshly generated 256-bit identifier.
    ///
    /// Package visibility confines creation to the launcher Bridge authority package.
    ///
    /// @param identifier random 256-bit identifier
    PluginCapabilityToken(byte[] identifier) {
        if (identifier.length != IDENTIFIER_BYTES) {
            throw new IllegalArgumentException("Capability token identifiers must be 256 bits");
        }
        this.identifier = identifier.clone();
    }

    /// Compares opaque token identity without exposing its bytes.
    ///
    /// Callers must not log the token or its identity-derived hash code.
    ///
    /// @param other candidate token
    /// @return whether both objects contain the same random identifier
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof PluginCapabilityToken token
                && MessageDigest.isEqual(identifier, token.identifier);
    }

    /// Returns a hash suitable only for launcher-private authority maps.
    ///
    /// Callers must not log, persist, or expose this identity-derived value.
    ///
    /// @return opaque identifier hash
    @Override
    public int hashCode() {
        return Arrays.hashCode(identifier);
    }

    /// Returns one fixed redacted representation shared by every capability token.
    ///
    /// @return constant non-identifying display text
    @Override
    public String toString() {
        return "PluginCapabilityToken[redacted]";
    }
}
