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
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Package-owned lifecycle fixture that reports Patch callback scope and teardown order through process properties.
@NotNullByDefault
public final class PackagedPatchPlugin implements Plugin {
    /// Process-global event property shared by the host and isolated package class copies.
    private static final String EVENTS_PROPERTY = "aura.test.patch-plugin.events";

    /// Process-global flag recording whether Patch callback TCCL matched the package class loader.
    private static final String PATCH_TCCL_PROPERTY = "aura.test.patch-plugin.tccl";

    /// Manifest received during `onLoad`, or `null` before registration.
    private @Nullable PluginManifest manifest;

    /// Creates the package-owned Patch lifecycle fixture.
    public PackagedPatchPlugin() {
    }

    /// Stores the exact package context and records lifecycle loading.
    ///
    /// @param context plugin runtime context
    @Override
    public void onLoad(PluginContext context) {
        manifest = context.getManifest();
        recordEvent("onLoad");
    }

    /// Records successful lifecycle activation.
    @Override
    public void onEnable() {
        recordEvent("onEnable");
    }

    /// Records lifecycle deactivation for ordering assertions.
    @Override
    public void onDisable() {
        recordEvent("onDisable");
    }

    /// Records final lifecycle cleanup before the package class loader closes.
    @Override
    public void onUnload() {
        recordEvent("onUnload");
    }

    /// Verifies callback class-loader scope and replaces the first String argument when present.
    ///
    /// @param invocation immutable Patch invocation
    /// @return argument replacement for a supported `before` invocation, otherwise unchanged
    @Override
    public PluginPatchResult onPatch(PluginPatchInvocation invocation) {
        System.setProperty(
                PATCH_TCCL_PROPERTY,
                Boolean.toString(Thread.currentThread().getContextClassLoader() == getClass().getClassLoader())
        );
        recordEvent("onPatch");
        if (invocation.type() != PluginPatchDeclaration.PatchType.BEFORE
                || invocation.arguments().isEmpty()
                || !(invocation.arguments().get(0) instanceof String)) {
            return PluginPatchResult.unchanged();
        }
        List<@Nullable Object> arguments = new ArrayList<>(invocation.arguments());
        arguments.set(0, "patched");
        return PluginPatchResult.arguments(arguments);
    }

    /// Returns the manifest received during registration.
    ///
    /// @return plugin manifest
    @Override
    public PluginManifest getManifest() {
        return Objects.requireNonNull(manifest);
    }

    /// Appends one process-global event visible across the isolated package class loader boundary.
    ///
    /// @param event stable event token
    public static void recordEvent(String event) {
        synchronized (System.getProperties()) {
            String previous = System.getProperty(EVENTS_PROPERTY, "");
            System.setProperty(EVENTS_PROPERTY, previous.isEmpty() ? event : previous + "|" + event);
        }
    }

    /// Returns the immutable process-global event sequence.
    ///
    /// @return recorded lifecycle and registration events
    public static @Unmodifiable List<String> events() {
        String value = System.getProperty(EVENTS_PROPERTY, "");
        return value.isEmpty() ? List.of() : List.of(value.split("\\|", -1));
    }

    /// Returns whether the latest Patch callback used its package class loader as TCCL.
    ///
    /// @return whether callback TCCL was correct
    public static boolean patchCallbackUsedPluginClassLoader() {
        return Boolean.parseBoolean(System.getProperty(PATCH_TCCL_PROPERTY, "false"));
    }

    /// Clears every process-global probe value used by this fixture.
    public static void reset() {
        System.clearProperty(EVENTS_PROPERTY);
        System.clearProperty(PATCH_TCCL_PROPERTY);
    }
}
