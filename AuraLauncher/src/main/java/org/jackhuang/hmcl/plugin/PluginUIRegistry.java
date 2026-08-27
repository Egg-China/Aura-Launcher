/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.plugin;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import org.jackhuang.hmcl.ui.Controllers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.function.Supplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Stores sidebar actions registered through permission-checked plugin contexts.
@NotNullByDefault
public final class PluginUIRegistry {
    /// Mutable backing list changed only by package-internal trusted callers.
    private static final ObservableList<SidebarItem> SIDEBAR_ITEMS = FXCollections.observableArrayList();

    /// Read-only observable list exposed to launcher UI consumers.
    private static final @UnmodifiableView ObservableList<SidebarItem> SIDEBAR_ITEMS_VIEW =
            FXCollections.unmodifiableObservableList(SIDEBAR_ITEMS);

    /// Prevents construction of the process-wide registry.
    private PluginUIRegistry() {
    }

    /// Returns the read-only observable sidebar item view used by the main page.
    ///
    /// @return unmodifiable observable sidebar items
    public static @UnmodifiableView ObservableList<SidebarItem> getSidebarItems() {
        return SIDEBAR_ITEMS_VIEW;
    }

    /// Registers one sidebar item after the owning context has passed its permission check.
    ///
    /// Callers must use a permission-checked [PluginContext] or Bridge service. The public return value lets the
    /// Bridge revoke one exact generation-safe contribution without removing other items from the same owner.
    ///
    /// @param pluginId owning plugin ID
    /// @param title displayed sidebar title
    /// @param onAction action invoked when the item is selected
    /// @return exact immutable sidebar contribution
    public static SidebarItem registerSidebarItem(String pluginId, String title, Runnable onAction) {
        SidebarItem item = new SidebarItem(pluginId, title, onAction, null);
        runOnFxThreadOrNow(() -> SIDEBAR_ITEMS.add(item));
        LOG.info("Plugin " + pluginId + " registered sidebar item: " + title);
        return item;
    }

    /// Registers a page-backed sidebar item for embedded theme layouts.
    ///
    /// The native Aura sidebar still invokes the item action, while a theme can
    /// render the page supplier in its own content host without leaving its layout.
    ///
    /// @param pluginId owning plugin ID
    /// @param title displayed sidebar title
    /// @param pageSupplier lazy page supplier
    /// @return exact immutable sidebar contribution
    public static SidebarItem registerSidebarPage(
            String pluginId,
            String title,
            Supplier<? extends Node> pageSupplier
    ) {
        Supplier<Node> sharedPageSupplier = pageSupplier::get;
        SidebarItem item = new SidebarItem(pluginId, title,
                () -> Controllers.navigate(sharedPageSupplier.get()), sharedPageSupplier);
        runOnFxThreadOrNow(() -> SIDEBAR_ITEMS.add(item));
        LOG.info("Plugin " + pluginId + " registered sidebar page: " + title);
        return item;
    }

    /// Removes one exact sidebar contribution without affecting other contributions from the same owner.
    ///
    /// This operation supports generation-safe Bridge handle release. Ordinary plugin lifecycle teardown should
    /// continue to use [unregisterAll(String)] so every contribution is removed even after partial registration.
    ///
    /// @param item exact sidebar contribution returned by a registration method
    public static void unregisterSidebarItem(SidebarItem item) {
        runOnFxThreadOrNow(() -> SIDEBAR_ITEMS.remove(item));
    }

    /// Removes every sidebar item owned by one plugin.
    ///
    /// @param pluginId owning plugin ID
    public static void unregisterAll(String pluginId) {
        runOnFxThreadOrNow(() -> SIDEBAR_ITEMS.removeIf(item -> item.getPluginId().equals(pluginId)));
    }

    /// Runs a registry mutation on JavaFX when available, or synchronously before toolkit startup.
    ///
    /// Startup discovery and headless tests can revoke plugin UI before JavaFX has initialized. In that state no UI
    /// observer is active, so applying the mutation immediately is both deterministic and thread-safe.
    ///
    /// @param mutation registry mutation
    private static void runOnFxThreadOrNow(Runnable mutation) {
        if (Platform.isFxApplicationThread()) {
            mutation.run();
        } else {
            try {
                Platform.runLater(mutation);
            } catch (IllegalStateException exception) {
                mutation.run();
            }
        }
    }

    /// Immutable sidebar action contributed by one plugin.
    @NotNullByDefault
    public static final class SidebarItem {
        /// Stable ID of the plugin that owns this item.
        private final String pluginId;

        /// Text displayed in the launcher sidebar.
        private final String title;

        /// Action invoked when the user selects this item.
        private final Runnable onAction;

        /// Lazily creates an embedded page, or is null for an action-only item.
        private final @Nullable Supplier<Node> pageSupplier;

        /// Creates one immutable sidebar action for a trusted registry caller.
        ///
        /// @param pluginId owning plugin ID
        /// @param title displayed sidebar title
        /// @param onAction selection action
        private SidebarItem(String pluginId, String title, Runnable onAction,
                            @Nullable Supplier<Node> pageSupplier) {
            this.pluginId = pluginId;
            this.title = title;
            this.onAction = onAction;
            this.pageSupplier = pageSupplier;
        }

        /// Returns the stable ID of the owning plugin.
        ///
        /// @return owning plugin ID
        public String getPluginId() {
            return pluginId;
        }

        /// Returns the displayed sidebar title.
        ///
        /// @return sidebar title
        public String getTitle() {
            return title;
        }

        /// Returns the action invoked when this item is selected.
        ///
        /// @return selection action
        public Runnable getOnAction() {
            return onAction;
        }

        /// Returns the lazy page factory for an embedded layout, when present.
        public @Nullable Supplier<Node> getPageSupplier() {
            return pageSupplier;
        }
    }
}
