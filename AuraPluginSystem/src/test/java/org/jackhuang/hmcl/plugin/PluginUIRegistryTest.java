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

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.FXThreadTestSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies plugin-owned sidebar UI registrations on the JavaFX application thread.
@NotNullByDefault
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
final class PluginUIRegistryTest {
    /// Verifies a page-backed sidebar item retains its original lazy page supplier.
    @Test
    void retainsThePageSupplierForThemeOwnedSidebarRendering() {
        FXThreadTestSupport.runOnFxThread(() -> {
            Node page = new StackPane();

            try {
                PluginUIRegistry.registerSidebarPage("test.theme-page", "Theme page", () -> page);

                PluginUIRegistry.SidebarItem item = PluginUIRegistry.getSidebarItems()
                        .get(PluginUIRegistry.getSidebarItems().size() - 1);
                assertSame(page, item.getPageSupplier().get());
            } finally {
                PluginUIRegistry.unregisterAll("test.theme-page");
            }
        });
    }
}
