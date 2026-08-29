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
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;

/// Built-in runtime provider that executes Java and Kotlin plugins inside the launcher JVM.
///
/// This provider is always registered first and cannot be replaced by an installed runtime plugin, so
/// the Java plugin system keeps working even when every external runtime is missing.
@NotNullByDefault
public final class JavaRuntimeProvider implements RuntimeProvider {
    /// Immutable reserved descriptor for the launcher-supplied Java runtime.
    private static final RuntimeProviderDescriptor DESCRIPTOR = new RuntimeProviderDescriptor(
            RuntimeProviderDescriptor.BUILTIN_JAVA_PROVIDER_ID,
            "1.0.0",
            java.util.List.of(new RuntimeProviderDeclaration(
                    PluginRuntimeTypes.JAVA,
                    Set.of(PluginAbi.ABI_1, PluginAbi.ABI_2),
                    1,
                    Set.of(PluginExecutionMode.EMBEDDED),
                    Set.of(
                            RuntimeFeature.BRIDGE,
                            RuntimeFeature.HOOKS,
                            RuntimeFeature.PATCHES,
                            RuntimeFeature.RAW_JVM,
                            RuntimeFeature.NATIVE
                    )
            )),
            true,
            true,
            0,
            true
    );

    /// Returns the reserved built-in Java provider descriptor.
    @Override
    public RuntimeProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String runtimeType() {
        return PluginRuntimeTypes.JAVA;
    }

    /// Returns the Plugin ABI generations recognized for in-process Java and Kotlin packages.
    ///
    /// @return immutable set of supported ABI generations
    @Override
    public @Unmodifiable Set<Integer> implementedPluginAbis() {
        return Set.of(PluginAbi.ABI_1, PluginAbi.ABI_2);
    }

    @Override
    public String describe() {
        return "Built-in Java plugin runtime (in-process JVM)";
    }
}
