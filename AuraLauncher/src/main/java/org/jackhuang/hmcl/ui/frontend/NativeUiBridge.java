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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.frontend;

import org.jackhuang.hmcl.auracore.AuraCoreEngineManager;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.GameInstancePatch;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.plugin.PluginUIRegistry;
import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jackhuang.hmcl.plugin.ui.frontend.process.UiFrontendCommandHandler;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.DownloadSource;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.ProxyType;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;

/// Dispatches the `core.*` command surface for an isolated native UI frontend.
///
/// The bridge reads launcher state directly from the game-directory and account managers. Mutating
/// commands schedule their work on the JavaFX application thread through the after-response action so
/// the launcher always replies before any visible state changes.
@NotNullByDefault
public final class NativeUiBridge {

    /// Formatter rendering disk timestamps in the launcher time zone.
    private static final DateTimeFormatter DISK_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /// Prevents instantiation.
    private NativeUiBridge() {
    }

    /// Builds the initial snapshot for `ui.snapshot.replace`, degrading to null on failure.
    ///
    /// @return full snapshot value, or null when launcher state is not ready yet
    public static BridgeValue buildInitialSnapshot() {
        try {
            return buildSnapshot();
        } catch (RuntimeException failure) {
            return BridgeValue.nullValue();
        }
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
                return runPluginAction(params);
            case "core.auracore.status":
                return CompletableFuture.completedFuture(
                        UiFrontendCommandHandler.Reply.result(buildAuraCoreStatus()));
            case "core.auracore.instance.logs":
                return readAuraCoreInstanceLogs(params);
            case "core.auracore.instance.stop":
                return stopAuraCoreInstance(params);
            case "core.auracore.accounts.list":
                return listAuraCoreAccounts();
            case "core.auracore.accounts.add-offline":
                return addAuraCoreOfflineAccount(params);
            case "core.auracore.accounts.remove":
                return removeAuraCoreAccount(params);
            case "core.auracore.accounts.set-default":
                return setAuraCoreDefaultAccount(params);
            case "core.auracore.instance.create":
                return createAuraCoreInstance(params);
            case "core.auracore.migrate":
                return migrateAuraCoreSettings();
            case "core.settings.set":
                return updateSettings(params);
            default:
                return CompletableFuture.failedFuture(
                        new UnsupportedOperationException("Unsupported native UI command: " + method));
        }
    }

    /// Builds the AuraCore native-engine status object for `core.auracore.status`.
    ///
    /// @return token-free map describing engine availability and migration scope
    private static BridgeValue buildAuraCoreStatus() {
        Map<String, BridgeValue> fields = new LinkedHashMap<>();
        AuraCoreEngineManager manager = AuraCoreEngineManager.getInstance();
        Map<String, Object> status = manager.status();
        fields.put("engine", BridgeValue.string(settings().coreEngineProperty().get()));
        fields.put("dataDirectory", stringOrEmpty(String.valueOf(status.get("dataDirectory"))));
        fields.put("libraryPath", stringOrEmpty(String.valueOf(status.get("libraryPath"))));
        fields.put("libraryAvailable", BridgeValue.bool(Boolean.TRUE.equals(status.get("libraryAvailable"))));
        fields.put("backendRunning", BridgeValue.bool(Boolean.TRUE.equals(status.get("backendRunning"))));
        List<BridgeValue> allowList = new ArrayList<>();
        for (String key : manager.migrationAllowList()) {
            allowList.add(BridgeValue.string(key));
        }
        fields.put("migrationAllowList", BridgeValue.array(allowList));
        return BridgeValue.map(fields);
    }

    /// Copies the allowlisted launcher settings into the AuraCore backend.
    ///
    /// @return asynchronous reply carrying per-key migration outcomes
    private static CompletionStage<UiFrontendCommandHandler.Reply> migrateAuraCoreSettings() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("download.concurrent-downloads", settings().downloadThreadsProperty().get());
        String backendProxy = backendProxyType(settings().proxyTypeProperty().get());
        if (backendProxy != null) {
            values.put("proxy.type", backendProxy);
        }
        values.put("proxy.host", settings().proxyHostProperty().get());
        values.put("proxy.port", settings().proxyPortProperty().get());
        values.put("proxy.user", settings().proxyUserProperty().get());
        values.put("proxy.password", settings().proxyPasswordProperty().get());
        return AuraCoreEngineManager.getInstance().migrateSettings(values).thenApply(outcomes -> {
            Map<String, BridgeValue> fields = new LinkedHashMap<>();
            outcomes.forEach((key, outcome) -> fields.put(key, BridgeValue.string(outcome)));
            return UiFrontendCommandHandler.Reply.result(BridgeValue.map(fields));
        }).exceptionally(failure -> {
            Map<String, BridgeValue> fields = new LinkedHashMap<>();
            fields.put("error", BridgeValue.string(String.valueOf(failure.getMessage())));
            return UiFrontendCommandHandler.Reply.result(BridgeValue.map(fields));
        });
    }

    /// Maps a launcher proxy type onto the AuraCore backend setting value.
    ///
    /// @param type the launcher-side proxy type
    /// @return the backend ProxyType value, or null when no equivalent exists
    private static @Nullable String backendProxyType(ProxyType type) {
        return switch (type) {
            case DIRECT -> "None";
            case HTTP -> "Http";
            case SOCKS -> "Sock";
            case SYSTEM -> null;
        };
    }

    /// Creates a vanilla instance through the AuraCore backend.
    ///
    /// @param params command parameters carrying `name` and `version`
    /// @return asynchronous reply carrying the creation task id
    private static CompletionStage<UiFrontendCommandHandler.Reply> createAuraCoreInstance(BridgeValue params) {
        final String name = extractStringParameter(params, "name");
        final String gameVersion = extractStringParameter(params, "version");
        final String group;
        if (params instanceof BridgeValue.MapValue map
                && map.values().get("group") instanceof BridgeValue.StringValue groupValue
                && !groupValue.value().isBlank()) {
            group = groupValue.value();
        } else {
            group = null;
        }
        return AuraCoreEngineManager.getInstance().start().createInstance(name, gameVersion, group)
                .thenApply(reply -> UiFrontendCommandHandler.Reply.result(BridgeValue.string(
                        reply.isJsonObject() && reply.getAsJsonObject().has("taskId")
                                ? reply.getAsJsonObject().get("taskId").getAsString()
                                : "")))
                .exceptionally(failure -> {
                    Map<String, BridgeValue> fields = new LinkedHashMap<>();
                    fields.put("error", BridgeValue.string(String.valueOf(failure.getMessage())));
                    return UiFrontendCommandHandler.Reply.result(BridgeValue.map(fields));
                });
    }

    /// Lists AuraCore accounts when the native engine is selected.
    ///
    /// @return asynchronous reply carrying the backend account array
    private static CompletionStage<UiFrontendCommandHandler.Reply> listAuraCoreAccounts() {
        return AuraCoreEngineManager.getInstance().start().listAccounts()
                .thenApply(accounts -> UiFrontendCommandHandler.Reply.result(toBridgeValue(accounts)))
                .exceptionally(failure -> auraCoreError(failure.getMessage()));
    }

    /// Adds an offline account to the AuraCore backend.
    ///
    /// @param params command parameters carrying `username`
    /// @return asynchronous reply carrying the creation result
    private static CompletionStage<UiFrontendCommandHandler.Reply> addAuraCoreOfflineAccount(BridgeValue params) {
        final String username = extractStringParameter(params, "username");
        return AuraCoreEngineManager.getInstance().start().addOfflineAccount(username)
                .thenApply(result -> UiFrontendCommandHandler.Reply.result(toBridgeValue(result)))
                .exceptionally(failure -> auraCoreError(failure.getMessage()));
    }

    /// Removes an AuraCore account by profile name.
    ///
    /// @param params command parameters carrying `profile`
    /// @return asynchronous reply carrying the removal result
    private static CompletionStage<UiFrontendCommandHandler.Reply> removeAuraCoreAccount(BridgeValue params) {
        final String profile = extractStringParameter(params, "profile");
        return AuraCoreEngineManager.getInstance().start().removeAccount(profile)
                .thenApply(result -> UiFrontendCommandHandler.Reply.result(toBridgeValue(result)))
                .exceptionally(failure -> auraCoreError(failure.getMessage()));
    }

    /// Selects the AuraCore account used by future launches.
    ///
    /// @param params command parameters carrying `profile`
    /// @return asynchronous reply carrying the selection result
    private static CompletionStage<UiFrontendCommandHandler.Reply> setAuraCoreDefaultAccount(BridgeValue params) {
        final String profile = extractStringParameter(params, "profile");
        return AuraCoreEngineManager.getInstance().start().setDefaultAccount(profile)
                .thenApply(result -> UiFrontendCommandHandler.Reply.result(toBridgeValue(result)))
                .exceptionally(failure -> auraCoreError(failure.getMessage()));
    }

    /// Converts one parsed backend JSON reply into a bridge value.
    ///
    /// @param element the Gson element returned by the backend
    /// @return the bridge representation of the same JSON value
    private static BridgeValue toBridgeValue(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return BridgeValue.nullValue();
        }
        if (element.isJsonPrimitive()) {
            final com.google.gson.JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return BridgeValue.bool(primitive.getAsBoolean());
            }
            if (primitive.isNumber()) {
                return BridgeValue.floating(primitive.getAsNumber().doubleValue());
            }
            return BridgeValue.string(primitive.getAsString());
        }
        if (element.isJsonArray()) {
            List<BridgeValue> values = new ArrayList<>();
            for (com.google.gson.JsonElement entry : element.getAsJsonArray()) {
                values.add(toBridgeValue(entry));
            }
            return BridgeValue.array(values);
        }
        Map<String, BridgeValue> fields = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : element.getAsJsonObject().entrySet()) {
            fields.put(entry.getKey(), toBridgeValue(entry.getValue()));
        }
        return BridgeValue.map(fields);
    }

    /// Builds the typed error reply used by AuraCore bridge commands.
    ///
    /// @param message the backend failure text
    /// @return reply carrying `{ error }`
    private static UiFrontendCommandHandler.Reply auraCoreError(@Nullable String message) {
        Map<String, BridgeValue> fields = new LinkedHashMap<>();
        fields.put("error", BridgeValue.string(message == null ? "unknown AuraCore failure" : message));
        return UiFrontendCommandHandler.Reply.result(BridgeValue.map(fields));
    }

    /// Reads launch logs from a running AuraCore instance.
    ///
    /// @param params command parameters carrying `id` and optional `maxLines`
    /// @return asynchronous reply carrying the backend log object
    private static CompletionStage<UiFrontendCommandHandler.Reply> readAuraCoreInstanceLogs(BridgeValue params) {
        final String id = extractStringParameter(params, "id");
        final int maxLines;
        if (params instanceof BridgeValue.MapValue map
                && map.values().get("maxLines") instanceof BridgeValue.IntegerValue number) {
            maxLines = (int) number.value();
        } else {
            maxLines = 0;
        }
        return AuraCoreEngineManager.getInstance().start().readInstanceLogs(id, maxLines)
                .thenApply(logs -> UiFrontendCommandHandler.Reply.result(toBridgeValue(logs)))
                .exceptionally(failure -> auraCoreError(failure.getMessage()));
    }

    /// Stops a running AuraCore instance process.
    ///
    /// @param params command parameters carrying `id`
    /// @return asynchronous reply carrying the stop result
    private static CompletionStage<UiFrontendCommandHandler.Reply> stopAuraCoreInstance(BridgeValue params) {
        final String id = extractStringParameter(params, "id");
        return AuraCoreEngineManager.getInstance().start().stopInstance(id)
                .thenApply(result -> UiFrontendCommandHandler.Reply.result(toBridgeValue(result)))
                .exceptionally(failure -> auraCoreError(failure.getMessage()));
    }

    /// Builds the full launcher state snapshot consumed by the Modern UI.
    ///
    /// @return token-free map with instances, accounts, settings, and contributions
    private static BridgeValue buildSnapshot() {
        Map<String, BridgeValue> snapshot = new LinkedHashMap<>();
        snapshot.put("instances", buildInstances());
        snapshot.put("accounts", buildAccounts());
        snapshot.put("settings", buildSettingsSnapshot());
        snapshot.put("pluginContributions", buildContributions());
        return BridgeValue.map(snapshot);
    }

    /// Builds the plugin contribution list rendered by the Modern UI sidebar.
    ///
    /// @return immutable array of sidebar and button contribution maps
    private static BridgeValue buildContributions() {
        List<BridgeValue> contributions = new ArrayList<>();
        for (PluginUIRegistry.SidebarItem item : PluginUIRegistry.getSidebarItems()) {
            contributions.add(contributionMap(item.getContributionId(), item.getPluginId(),
                    "sidebar", item.getTitle()));
        }
        for (PluginUIRegistry.ButtonItem item : PluginUIRegistry.getButtonItems()) {
            contributions.add(contributionMap(item.getContributionId(), item.getPluginId(),
                    "button", item.getTitle()));
        }
        return BridgeValue.array(contributions);
    }

    /// Formats one contribution entry for the wire snapshot.
    ///
    /// @param id stable contribution identifier
    /// @param pluginId owning plugin identifier
    /// @param kind contribution family
    /// @param label displayed label
    /// @return token-free contribution map
    private static BridgeValue contributionMap(String id, String pluginId, String kind, String label) {
        Map<String, BridgeValue> entry = new LinkedHashMap<>();
        entry.put("id", BridgeValue.string(id));
        entry.put("pluginId", BridgeValue.string(pluginId));
        entry.put("kind", BridgeValue.string(kind));
        entry.put("label", BridgeValue.string(label));
        return BridgeValue.map(entry);
    }

    /// Runs one registered contribution action by its stable identifier.
    ///
    /// @param params command parameters carrying `id`
    /// @return asynchronous reply performing the FX-thread action
    private static CompletionStage<UiFrontendCommandHandler.Reply> runPluginAction(BridgeValue params) {
        String contributionId = extractStringParameter(params, "id");
        Runnable action = null;
        for (PluginUIRegistry.ButtonItem item : PluginUIRegistry.getButtonItems()) {
            if (item.getContributionId().equals(contributionId)) {
                action = item.getOnAction();
                break;
            }
        }
        if (action == null) {
            for (PluginUIRegistry.SidebarItem item : PluginUIRegistry.getSidebarItems()) {
                if (item.getContributionId().equals(contributionId)) {
                    action = item.getOnAction();
                    break;
                }
            }
        }
        Runnable finalAction = action;
        if (finalAction == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown plugin contribution: " + contributionId));
        }
        return CompletableFuture.completedFuture(new UiFrontendCommandHandler.Reply(
                BridgeValue.nullValue(),
                () -> FXUtils.runInFX(finalAction)
        ));
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
        instance.put("lastPlayed", BridgeValue.string(findLastPlayed(manifest.id())));
        instance.put("playTime", BridgeValue.string("0.0 小时"));
        instance.put("modCount", BridgeValue.integer(countMods(manifest.id())));
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
    /// @return token-free settings map mirroring the launcher settings manager
    private static BridgeValue buildSettingsSnapshot() {
        Map<String, BridgeValue> exported = new LinkedHashMap<>();
        exported.put("uiFrontend", stringOrEmpty(settings().selectedUiFrontendProperty().get()));
        exported.put("coreEngine", stringOrEmpty(settings().coreEngineProperty().get()));
        exported.put("downloadSource", BridgeValue.string(settings().fileDownloadSourceProperty().get().name()));
        exported.put("proxyType", BridgeValue.string(settings().proxyTypeProperty().get().name()));
        exported.put("proxyHost", stringOrEmpty(settings().proxyHostProperty().get()));
        exported.put("proxyPort", BridgeValue.integer(settings().proxyPortProperty().get()));
        exported.put("proxyUser", stringOrEmpty(settings().proxyUserProperty().get()));
        exported.put("proxyPassword", stringOrEmpty(settings().proxyPasswordProperty().get()));
        exported.put("hasProxyAuth", BridgeValue.bool(settings().hasProxyAuthProperty().get()));
        exported.put("commonDirectory", stringOrEmpty(settings().commonDirectoryProperty().get()));
        exported.put("themeBrightnessMode", stringOrEmpty(settings().themeBrightnessModeProperty().get()));
        exported.put("defaultAddonSource", stringOrEmpty(settings().defaultAddonSourceProperty().get()));
        exported.put("autoDownloadThreads", BridgeValue.bool(settings().autoDownloadThreadsProperty().get()));
        exported.put("downloadThreads", BridgeValue.integer(settings().downloadThreadsProperty().get()));
        exported.put("backgroundOpacity", BridgeValue.floating(settings().backgroundOpacityProperty().get()));
        exported.put("launcherFontFamily", stringOrEmpty(settings().launcherFontFamilyProperty().get()));
        exported.put("logFontFamily", stringOrEmpty(settings().logFontFamilyProperty().get()));
        return BridgeValue.map(exported);
    }

    /// Applies one allowlisted settings write on the JavaFX thread.
    ///
    /// @param params command parameters carrying `key` and `value`
    /// @return asynchronous reply applying the write after the response flushes
    private static CompletionStage<UiFrontendCommandHandler.Reply> updateSettings(BridgeValue params) {
        if (!(params instanceof BridgeValue.MapValue map)
                || !(map.values().get("key") instanceof BridgeValue.StringValue key)
                || key.value().isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("core.settings.set requires a non-blank string key"));
        }
        BridgeValue value = map.values().getOrDefault("value", BridgeValue.nullValue());
        Runnable write = settingsWrite(key.value(), value);
        return CompletableFuture.completedFuture(new UiFrontendCommandHandler.Reply(
                BridgeValue.nullValue(),
                () -> FXUtils.runInFX(write)
        ));
    }

    /// Resolves one typed settings write by allowlisted key.
    ///
    /// @param key settings key
    /// @param value typed replacement value
    /// @return FX-thread write action
    private static Runnable settingsWrite(String key, BridgeValue value) {
        switch (key) {
            case "coreEngine": {
                String engine = requireString(key, value);
                if (!AuraCoreEngineManager.ENGINE_HMCL.equals(engine)
                        && !AuraCoreEngineManager.ENGINE_AURACORE.equals(engine)) {
                    throw new IllegalArgumentException("Unsupported core engine: " + engine);
                }
                return () -> settings().coreEngineProperty().set(engine);
            }
            case "uiFrontend":
                return () -> settings().selectedUiFrontendProperty().set(requireString(key, value));
            case "downloadSource":
                return () -> settings().fileDownloadSourceProperty().set(
                        DownloadSource.valueOf(requireString(key, value)));
            case "proxyType":
                return () -> settings().proxyTypeProperty().set(
                        ProxyType.valueOf(requireString(key, value)));
            case "proxyHost":
                return () -> settings().proxyHostProperty().set(requireString(key, value));
            case "proxyPort":
                return () -> settings().proxyPortProperty().set(requireInteger(key, value));
            case "proxyUser":
                return () -> settings().proxyUserProperty().set(requireString(key, value));
            case "proxyPassword":
                return () -> settings().proxyPasswordProperty().set(requireString(key, value));
            case "hasProxyAuth":
                return () -> settings().hasProxyAuthProperty().set(requireBoolean(key, value));
            case "commonDirectory":
                return () -> settings().commonDirectoryProperty().set(requireString(key, value));
            case "themeBrightnessMode":
                return () -> settings().themeBrightnessModeProperty().set(requireString(key, value));
            case "defaultAddonSource":
                return () -> settings().defaultAddonSourceProperty().set(requireString(key, value));
            case "autoDownloadThreads":
                return () -> settings().autoDownloadThreadsProperty().set(requireBoolean(key, value));
            case "downloadThreads":
                return () -> settings().downloadThreadsProperty().set(requireInteger(key, value));
            case "launcherFontFamily":
                return () -> settings().launcherFontFamilyProperty().set(requireString(key, value));
            case "logFontFamily":
                return () -> settings().logFontFamilyProperty().set(requireString(key, value));
            default:
                throw new IllegalArgumentException("Unsupported settings key: " + key);
        }
    }

    /// Counts `.jar` files inside one instance `mods` directory.
    ///
    /// @param instanceId game-instance identifier
    /// @return bounded mod count, or zero when the directory is absent
    private static long countMods(GameInstanceID instanceId) {
        Path mods = GameDirectoryManager.getSelectedRepository().getRunDirectory(instanceId).resolve("mods");
        try (Stream<Path> entries = Files.list(mods)) {
            return entries
                    .limit(10_000L)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .count();
        } catch (IOException failure) {
            return 0L;
        }
    }

    /// Finds the newest on-disk activity marker for one instance.
    ///
    /// @param instanceId game-instance identifier
    /// @return formatted timestamp, or the never-played label
    private static String findLastPlayed(GameInstanceID instanceId) {
        Path root = GameDirectoryManager.getSelectedRepository().getRunDirectory(instanceId);
        Instant latest = null;
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                try {
                    Instant modified = Files.getLastModifiedTime(entry).toInstant();
                    if (latest == null || modified.isAfter(latest)) {
                        latest = modified;
                    }
                } catch (IOException ignored) {
                    // Unreadable entries simply do not contribute to the marker.
                }
            }
        } catch (IOException failure) {
            return "从未";
        }
        return latest == null ? "从未" : DISK_TIMESTAMP.format(latest);
    }

    /// Wraps one nullable settings string for the wire.
    ///
    /// @param value nullable settings value
    /// @return empty string for null, otherwise the value
    private static BridgeValue stringOrEmpty(@Nullable String value) {
        return BridgeValue.string(value == null ? "" : value);
    }

    /// Requires one string settings value.
    ///
    /// @param key settings key for diagnostics
    /// @param value candidate value
    /// @return unwrapped string
    private static String requireString(String key, BridgeValue value) {
        if (value instanceof BridgeValue.StringValue text) {
            return text.value();
        }
        throw new IllegalArgumentException("Settings key " + key + " requires a string value");
    }

    /// Requires one integer settings value.
    ///
    /// @param key settings key for diagnostics
    /// @param value candidate value
    /// @return unwrapped integer
    private static int requireInteger(String key, BridgeValue value) {
        if (value instanceof BridgeValue.IntegerValue integer) {
            return Math.toIntExact(integer.value());
        }
        throw new IllegalArgumentException("Settings key " + key + " requires an integer value");
    }

    /// Requires one boolean settings value.
    ///
    /// @param key settings key for diagnostics
    /// @param value candidate value
    /// @return unwrapped boolean
    private static boolean requireBoolean(String key, BridgeValue value) {
        if (value instanceof BridgeValue.BooleanValue bool) {
            return bool.value();
        }
        throw new IllegalArgumentException("Settings key " + key + " requires a boolean value");
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
        if (AuraCoreEngineManager.ENGINE_AURACORE.equals(settings().coreEngineProperty().get())) {
            return launchThroughAuraCore(instanceId);
        }
        return CompletableFuture.completedFuture(new UiFrontendCommandHandler.Reply(
                BridgeValue.nullValue(),
                () -> FXUtils.runInFX(() -> {
                    HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
                    repository.setSelectedInstance(instanceId);
                    Instances.launch(repository, repository.getSelectedInstance());
                })
        ));
    }

    /// Launches an instance through the native AuraCore backend.
    ///
    /// @param instanceId the AuraCore instance identifier
    /// @return asynchronous reply carrying the native launch result
    private static CompletionStage<UiFrontendCommandHandler.Reply> launchThroughAuraCore(GameInstanceID instanceId) {
        return AuraCoreEngineManager.getInstance().start().launchInstance(instanceId.id(), null, null)
                .thenApply(reply -> UiFrontendCommandHandler.Reply.result(BridgeValue.string(
                        reply.isJsonObject() && reply.getAsJsonObject().has("taskId")
                                ? reply.getAsJsonObject().get("taskId").getAsString()
                                : "")))
                .exceptionally(failure -> {
                    Map<String, BridgeValue> fields = new LinkedHashMap<>();
                    fields.put("error", BridgeValue.string(String.valueOf(failure.getMessage())));
                    return UiFrontendCommandHandler.Reply.result(BridgeValue.map(fields));
                });
    }

    /// Extracts the required `id` string parameter as a game instance identifier.
    ///
    /// @param params command parameters
    /// @return parsed game-instance identifier
    private static GameInstanceID extractInstanceId(BridgeValue params) {
        return new GameInstanceID(extractStringParameter(params, "id"));
    }

    /// Extracts one required non-blank string parameter.
    ///
    /// @param params command parameters
    /// @param key parameter key
    /// @return parsed non-blank string value
    private static String extractStringParameter(BridgeValue params, String key) {
        if (params instanceof BridgeValue.MapValue map) {
            BridgeValue value = map.values().get(key);
            if (value instanceof BridgeValue.StringValue text && !text.value().isBlank()) {
                return text.value();
            }
        }
        throw new IllegalArgumentException("core command requires a non-blank string " + key);
    }
}
