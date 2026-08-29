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
package org.jackhuang.hmcl.plugin.store;

import org.jackhuang.hmcl.plugin.trust.PluginTrustLevel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the public Aura Store envelope and a current-platform Runtime Host installation plan.
@NotNullByDefault
@EnabledIfEnvironmentVariable(named = "AURA_PUBLIC_STORE_SMOKE", matches = "true")
public final class PluginStorePublicSmokeTest {
    /// Loads every official entry and resolves the current Wasm Runtime Host release.
    ///
    /// @throws Exception if public transport, trust verification, or install planning fails
    @Test
    public void loadsOfficialStoreAndResolvesWasmInstallPlan() throws Exception {
        PluginStoreManager manager = new PluginStoreManager();

        manager.loadDefaultRegistry();

        assertFalse(manager.getStoreItems().isEmpty());
        assertTrue(manager.getStoreItems().stream()
                .allMatch(item -> item.getTrust().level() == PluginTrustLevel.OFFICIAL));
        PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(
                manager.getStoreItems().stream()
                        .filter(item -> item.getEntry().getId().equals("dev.hmclce.runtime.wasm-host"))
                        .findFirst()
                        .orElseThrow()
                        .getLatestVersion()
        );
        PluginInstallPlan plan = manager.resolveInstallPlan(
                "dev.hmclce.runtime.wasm-host",
                version,
                Map.of()
        );
        assertFalse(plan.getEntries().isEmpty());
    }
}
