/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.frontend;

import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.GameInstancePatch;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jackhuang.hmcl.plugin.ui.frontend.process.UiFrontendCommandHandler;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Dispatches the `core.*` command surface for an isolated native UI frontend.
///
/// The bridge reads launcher state directly from the game-directory and account managers. Mutating
/// commands schedule their work on the JavaFX application thread through the after-response action so
/// the launcher always replies before any visible state changes.
@NotNullByDefault
public final class NativeUiBridge {

    /// Prevents instantiation.
    private NativeUiBridge() {
    }

    /// Handles one validated native-frontend command.
    ///
    /// @param method fixed `core.*` command method
    /// @param params token-free command parameters
    /// @return asynchronous reply carrying launcher state or a typed failure
    public static CompletionStage<UiFrontendCommandHandler.Reply> handle(String method, BridgeValue params) {
        switch (method) {
            case "core.snapshot.get":
                return CompletableFuture.completedFuture(UiFrontendCommandHandler.Reply.result(buildSnapshot()));
            case "core.settings.get":
                return CompletableFuture.completedFuture(
                        UiFrontendCommandHandler.Reply.result(buildSettingsSnapshot()));
            case "core.instance.select":
                return selectInstance(params);
            case "core.instance.launch":
                return launchInstance(params);
            case "core.plugin.action":
                return CompletableFuture.failedFuture(new UnsupportedOperationException(
                        "plugin actions arrive with the contribution registry"));
            case "core.settings.set":
                return CompletableFuture.failedFuture(new UnsupportedOperationException(
                        "settings writes follow the typed settings contract"));
            default:
                return CompletableFuture.failedFuture(
                        new UnsupportedOperationException("Unsupported native UI command: " + method));
        }
    }

    /// Builds the full launcher state snapshot consumed by the Modern UI.
    ///
    /// @return token-free map with instances, accounts, settings, and contributions
    private static BridgeValue buildSnapshot() {
        Map<String, BridgeValue> snapshot = new LinkedHashMap<>();
        snapshot.put("instances", buildInstances());
        snapshot.put("accounts", buildAccounts());
        snapshot.put("settings", buildSettingsSnapshot());
        snapshot.put("pluginContributions", BridgeValue.array(List.of()));
        return BridgeValue.map(snapshot);
    }

    /// Lists every displayable instance of the selected game directory.
    ///
    /// @return immutable array of instance maps
    private static BridgeValue buildInstances() {
        List<BridgeValue> instances = new ArrayList<>();
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        repository.getDisplayInstanceManifests().forEach(manifest -> instances.add(toInstanceMap(manifest)));
        return BridgeValue.array(instances);
    }

    /// Converts one game-instance manifest into the wire instance shape.
    ///
    /// @param manifest resolved display manifest
    /// @return token-free instance map
    private static BridgeValue toInstanceMap(GameInstanceManifest manifest) {
        Map<String, BridgeValue> instance = new LinkedHashMap<>();
        instance.put("id", BridgeValue.string(manifest.id().id()));
        instance.put("name", BridgeValue.string(manifest.id().id()));
        instance.put("version", BridgeValue.string(manifest.id().id()));
        instance.put("loader", BridgeValue.string(inferLoader(manifest)));
        instance.put("lastPlayed", BridgeValue.string("从未"));
        instance.put("playTime", BridgeValue.string("0.0 小时"));
        instance.put("modCount", BridgeValue.integer(0L));
        instance.put("description", BridgeValue.string("由 Aura 启动器同步的本地实例。"));
        instance.put("isFavorite", BridgeValue.bool(false));
        return BridgeValue.map(instance);
    }

    /// Infers the mod-loader family from the instance patch identifiers.
    ///
    /// @param manifest resolved display manifest
    /// @return loader family used by the Modern UI
    static String inferLoader(GameInstanceManifest manifest) {
        List<GameInstancePatch> patches = manifest.patches();
        if (patches == null) {
            return "Vanilla";
        }
        List<String> identifiers = new ArrayList<>();
        for (GameInstancePatch patch : patches) {
            String id = patch.id();
            if (id != null) {
                identifiers.add(id);
            }
        }
        return inferLoaderFromIds(identifiers);
    }

    /// Infers the mod-loader family from lowercase-insensitive patch identifiers.
    ///
    /// @param identifiers patch identifiers in any casing
    /// @return loader family used by the Modern UI
    static String inferLoaderFromIds(List<String> identifiers) {
        for (String id : identifiers) {
            String normalized = id.toLowerCase(Locale.ROOT);
            if (normalized.contains("neoforge")) {
                return "NeoForge";
            } else if (normalized.contains("fabric")) {
                return "Fabric";
            } else if (normalized.contains("quilt")) {
                return "Quilt";
            } else if (normalized.contains("forge")) {
                return "Forge";
            }
        }
        return "Vanilla";
    }

    /// Lists every stored account with its selection state.
    ///
    /// @return immutable array of account maps
    private static BridgeValue buildAccounts() {
        List<BridgeValue> mapped = new ArrayList<>();
        Account selected = Accounts.selectedAccountProperty().get();
        for (Account account : Accounts.getAccounts()) {
            Map<String, BridgeValue> entry = new LinkedHashMap<>();
            entry.put("id", BridgeValue.string(account.getAccountID().toString()));
            entry.put("username", BridgeValue.string(account.getProfileName()));
            entry.put("uuid", BridgeValue.string(account.getProfileID().toString()));
            entry.put("type", BridgeValue.string(accountType(account)));
            entry.put("skinUrl", BridgeValue.string("https://minotar.net/helm/MHF_Steve/128.png"));
            entry.put("isActive", BridgeValue.bool(account == selected));
            mapped.add(BridgeValue.map(entry));
        }
        return BridgeValue.array(mapped);
    }

    /// Maps one concrete account implementation onto the wire account type.
    ///
    /// @param account stored launcher account
    /// @return account family used by the Modern UI
    private static String accountType(Account account) {
        if (account instanceof MicrosoftAccount) {
            return "microsoft";
        }
        if (account instanceof AuthlibInjectorAccount) {
            return "thirdparty";
        }
        if (account instanceof OfflineAccount) {
            return "offline";
        }
        return "offline";
    }

    /// Builds the currently exported settings allowlist.
    ///
    /// @return token-free settings map; grows as typed settings land
    private static BridgeValue buildSettingsSnapshot() {
        return BridgeValue.map(Map.of());
    }

    /// Selects one instance after extracting its identifier.
    ///
    /// @param params command parameters carrying `id`
    /// @return asynchronous reply performing the FX-thread selection
    private static CompletionStage<UiFrontendCommandHandler.Reply> selectInstance(BridgeValue params) {
        GameInstanceID instanceId = extractInstanceId(params);
        return CompletableFuture.completedFuture(new UiFrontendCommandHandler.Reply(
                BridgeValue.nullValue(),
                () -> FXUtils.runInFX(() ->
                        GameDirectoryManager.getSelectedRepository().setSelectedInstance(instanceId))
        ));
    }

    /// Selects and launches one instance from the supervised frontend.
    ///
    /// @param params command parameters carrying `id`
    /// @return asynchronous reply performing the FX-thread launch
    private static CompletionStage<UiFrontendCommandHandler.Reply> launchInstance(BridgeValue params) {
        GameInstanceID instanceId = extractInstanceId(params);
        return CompletableFuture.completedFuture(new UiFrontendCommandHandler.Reply(
                BridgeValue.nullValue(),
                () -> FXUtils.runInFX(() -> {
                    HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
                    repository.setSelectedInstance(instanceId);
                    Instances.launch(repository, repository.getSelectedInstance());
                })
        ));
    }

    /// Extracts the required `id` string parameter.
    ///
    /// @param params command parameters
    /// @return parsed game-instance identifier
    private static GameInstanceID extractInstanceId(BridgeValue params) {
        if (params instanceof BridgeValue.MapValue map) {
            BridgeValue id = map.values().get("id");
            if (id instanceof BridgeValue.StringValue text && !text.value().isBlank()) {
                return new GameInstanceID(text.value());
            }
        }
        throw new IllegalArgumentException("core.instance commands require a non-blank string id");
    }
}
