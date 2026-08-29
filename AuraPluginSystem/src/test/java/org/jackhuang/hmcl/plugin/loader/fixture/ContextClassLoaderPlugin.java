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
package org.jackhuang.hmcl.plugin.loader.fixture;

import org.jackhuang.hmcl.plugin.Plugin;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ServiceLoader;

/// Test lifecycle implementation that requires its own loader as the active thread context class loader.
@NotNullByDefault
public final class ContextClassLoaderPlugin implements Plugin {
    /// System property written after the constructor verifies context-loader service discovery.
    public static final String CONSTRUCTED_PROPERTY =
            "hmcl.test.plugin.context-class-loader.constructed";

    /// System property written after `onEnable` verifies context-loader service discovery.
    public static final String ENABLED_PROPERTY =
            "hmcl.test.plugin.context-class-loader.enabled";

    /// System property written after `onDisable` verifies context-loader service discovery.
    public static final String DISABLED_PROPERTY =
            "hmcl.test.plugin.context-class-loader.disabled";

    /// System property written after `onUnload` verifies context-loader service discovery.
    public static final String UNLOADED_PROPERTY =
            "hmcl.test.plugin.context-class-loader.unloaded";

    /// Manifest received during `onLoad`, or `null` before registration completes.
    private @Nullable PluginManifest manifest;

    /// Creates the lifecycle fixture and verifies default `ServiceLoader` discovery from the package.
    public ContextClassLoaderPlugin() {
        verifyServiceDiscovery();
        System.setProperty(CONSTRUCTED_PROPERTY, "true");
    }

    /// Verifies the lifecycle callback context without retaining launcher state.
    ///
    /// @param context plugin context supplied by the manager
    @Override
    public void onLoad(PluginContext context) {
        verifyServiceDiscovery();
        manifest = context.getManifest();
    }

    /// Verifies the reusable lifecycle scope and records successful enablement.
    @Override
    public void onEnable() {
        verifyServiceDiscovery();
        System.setProperty(ENABLED_PROPERTY, "true");
    }

    /// Verifies the reusable lifecycle scope and records successful disablement.
    @Override
    public void onDisable() {
        verifyServiceDiscovery();
        System.setProperty(DISABLED_PROPERTY, "true");
    }

    /// Verifies the reusable lifecycle scope and records successful unloading.
    @Override
    public void onUnload() {
        verifyServiceDiscovery();
        System.setProperty(UNLOADED_PROPERTY, "true");
    }

    /// Returns the manifest captured during `onLoad`.
    ///
    /// @return authoritative package manifest
    /// @throws IllegalStateException if the manager has not invoked `onLoad`
    @Override
    public PluginManifest getManifest() {
        @Nullable PluginManifest current = manifest;
        if (current == null) {
            throw new IllegalStateException("Plugin manifest is unavailable before onLoad");
        }
        return current;
    }

    /// Confirms that default service discovery uses this fixture's defining class loader.
    private static void verifyServiceDiscovery() {
        @Nullable ClassLoader definingLoader = ContextClassLoaderPlugin.class.getClassLoader();
        if (definingLoader == null || Thread.currentThread().getContextClassLoader() != definingLoader) {
            throw new IllegalStateException("Plugin defining loader is not the thread context class loader");
        }
        ProbeService service = ServiceLoader.load(ProbeService.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Package-owned service provider was not discovered"));
        if (!"verified-provider".equals(service.marker())) {
            throw new IllegalStateException("Ucepected package-owned service provider");
        }
    }

    /// Package-owned service contract used to exercise default `ServiceLoader` behavior.
    @NotNullByDefault
    public interface ProbeService {
        /// Returns the provider identity.
        ///
        /// @return stable provider marker
        String marker();
    }

    /// Package-owned service provider discovered through the fixture's `META-INF/services` entry.
    @NotNullByDefault
    public static final class ProbeServiceProvider implements ProbeService {
        /// Creates the public no-argument service provider.
        public ProbeServiceProvider() {
        }

        /// Returns the provider identity.
        ///
        /// @return stable provider marker
        @Override
        public String marker() {
            return "verified-provider";
        }
    }
}
