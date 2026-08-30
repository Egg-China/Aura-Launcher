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
package org.jackhuang.hmcl.plugin.patch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;

/// Exact launcher-owned class and method selected for one Patch registration.
@NotNullByDefault
public final class PluginPatchTarget {
    /// Resolved full method identity.
    private final PluginPatchMethod method;

    /// JVM method access flags.
    private final int access;

    /// Launcher resource bytes used for method validation and future class definition.
    private final byte @Unmodifiable [] classBytes;

    /// Exact already loaded launcher class, or `null` for a future definition.
    private final @Nullable Class<?> loadedClass;

    /// Creates one resolved target.
    ///
    /// @param method resolved method identity
    /// @param access JVM method access flags
    /// @param classBytes validated launcher class bytes
    /// @param loadedClass exact loaded class, or `null`
    PluginPatchTarget(
            PluginPatchMethod method,
            int access,
            byte[] classBytes,
            @Nullable Class<?> loadedClass
    ) {
        this.method = Objects.requireNonNull(method, "method");
        this.access = access;
        this.classBytes = Objects.requireNonNull(classBytes, "classBytes").clone();
        this.loadedClass = loadedClass;
    }

    /// Returns the resolved full method identity.
    ///
    /// @return resolved method
    public PluginPatchMethod method() {
        return method;
    }

    /// Returns the JVM method access flags.
    ///
    /// @return access flags
    public int access() {
        return access;
    }

    /// Returns a defensive copy of the validated launcher resource bytes.
    ///
    /// @return class bytes
    public byte @Unmodifiable [] classBytes() {
        return classBytes.clone();
    }

    /// Returns the exact already loaded launcher class.
    ///
    /// @return loaded class, or `null` for a future definition
    public @Nullable Class<?> loadedClass() {
        return loadedClass;
    }
}
