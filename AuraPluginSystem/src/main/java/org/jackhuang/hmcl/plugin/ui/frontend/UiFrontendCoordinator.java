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
package org.jackhuang.hmcl.plugin.ui.frontend;

import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jackhuang.hmcl.plugin.ui.frontend.process.UiFrontendCommandHandler;
import org.jackhuang.hmcl.plugin.ui.frontend.process.UiFrontendProcessException;
import org.jackhuang.hmcl.plugin.ui.frontend.process.UiFrontendProcessSession;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/// Selects, supervises, and replaces Aura's visible frontend while keeping JavaFX always recoverable.
///
/// The coordinator owns launcher-side frontend state only. A native frontend must pass installation, platform,
/// permission, and selection normalization immediately before its child is launched; any failure or later child
/// termination selects the built-in JavaFX frontend again so the launcher never remains without a visible UI.
@NotNullByDefault
public final class UiFrontendCoordinator {
    /// Provider used to re-resolve verified frontend packages before every supervision attempt.
    private final UiFrontendProvider provider;

    /// Trusted plugin manager used for exact-artifact permission checks.
    private final PluginManager pluginManager;

    /// Command handler for the supervised native session.
    private final UiFrontendCommandHandler commandHandler;

    /// Active session launcher owned by this coordinator.
    private final SessionLauncher sessionLauncher;

    /// Diagnostic for the most recent native selection failure, or `null`.
    private @Nullable String fallbackReason;

    /// Active native session, or `null` while JavaFX owns the visible UI.
    private @Nullable SupervisedSession session;

    /// Active native descriptor, or `null` while JavaFX owns the visible UI.
    private @Nullable UiFrontendDescriptor activeNative;

    /// Creates one coordinator bound to launcher-owned frontend resolution services.
    ///
    /// @param provider verified frontend package provider
    /// @param pluginManager trusted plugin manager
    /// @param commandHandler handler for native frontend commands
    public UiFrontendCoordinator(
            UiFrontendProvider provider,
            PluginManager pluginManager,
            UiFrontendCommandHandler commandHandler
    ) {
        this(provider, pluginManager, commandHandler, UiFrontendProcessSession::start);
    }

    /// Creates one coordinator with a deterministic session-launch boundary for isolated tests.
    ///
    /// @param provider verified frontend package provider
    /// @param pluginManager trusted plugin manager
    /// @param commandHandler handler for native frontend commands
    /// @param sessionLauncher boundary that starts one ready native session
    UiFrontendCoordinator(
            UiFrontendProvider provider,
            PluginManager pluginManager,
            UiFrontendCommandHandler commandHandler,
            SessionLauncher sessionLauncher
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
        this.sessionLauncher = Objects.requireNonNull(sessionLauncher, "sessionLauncher");
    }

    /// Normalizes a persisted selection against frontend packages that can currently be verified.
    ///
    /// @param requestedSelection persisted selected frontend ID
    /// @return descriptor for the built-in frontend when the request is absent, unknown, or unverifiable
    public UiFrontendDescriptor normalizeSelection(String requestedSelection) {
        @Unmodifiable Map<String, UiFrontendDescriptor> frontends = provider.installedFrontends();
        @Nullable UiFrontendDescriptor selected = frontends.get(requestedSelection);
        if (selected != null) {
            return selected;
        }
        UiFrontendDescriptor fallback = provider.builtInFrontend();
        fallbackReason = "Requested UI frontend is unavailable: " + requestedSelection;
        return fallback;
    }

    /// Returns the current launcher-visible frontend.
    ///
    /// @return JavaFX while no native session is active, otherwise the active native descriptor
    public UiFrontendDescriptor currentFrontend() {
        UiFrontendDescriptor active = activeNative;
        return active == null ? provider.builtInFrontend() : active;
    }

    /// Returns the diagnostic attached to the most recent built-in fallback.
    ///
    /// @return reason, or empty when the last requested frontend was accepted
    public Optional<String> getFallbackReason() {
        return Optional.ofNullable(fallbackReason);
    }

    /// Starts the selected native frontend or establishes the always-available JavaFX fallback.
    ///
    /// @param descriptor verified or built-in frontend selected for this launcher start
    /// @param initialSnapshot redacted initial UI state delivered before first readiness
    /// @return frontend that now owns the visible UI
    public UiFrontendDescriptor start(UiFrontendDescriptor descriptor, BridgeValue initialSnapshot) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        closeSessionQuietly();
        if (descriptor.isJavaFx()) {
            fallbackReason = null;
            return descriptor;
        }
        try {
            UiFrontendDescriptor verified = provider.resolveFrontend(descriptor.getId());
            @Unmodifiable Set<PluginPermission> granted = requireNativeSelection(verified);
            Path executable = verified.getExecutable().orElseThrow();
            SupervisedSession started = sessionLauncher.start(
                    executable,
                    executable.getParent(),
                    redactSnapshot(initialSnapshot, granted),
                    new PermissionGatedHandler(pluginManager, verified.getId(), commandHandler)
            );
            session = started;
            activeNative = verified;
            fallbackReason = null;
            started.termination().thenAccept(termination -> switchToFrontendAfterChildTermination(
                    descriptor,
                    termination
            ));
            return currentFrontend();
        } catch (IOException | UiFrontendProcessException | RuntimeException exception) {
            fallbackReason = "Native UI frontend failed to start: "
                    + (exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.toString()
                    : exception.getMessage());
            return provider.builtInFrontend();
        }
    }

    /// Stops a native child when present and returns to the built-in frontend.
    public void stop() {
        closeSessionQuietly();
    }

    /// Returns the active native session termination when a supervised child currently owns the UI.
    ///
    /// @return terminal native-child state, or an empty value while JavaFX owns the UI
    public Optional<CompletionStage<UiFrontendProcessSession.Termination>> activeSessionTermination() {
        @Nullable SupervisedSession active = session;
        return active == null ? Optional.empty() : Optional.of(active.termination());
    }

    /// Returns the additional permission one frontend command requires beyond session gating.
    ///
    /// @param method fixed `core.*` command method
    /// @return required permission, or `null` when the session-level grant suffices
    static @Nullable PluginPermission requiredPermission(String method) {
        switch (method) {
            case "core.instance.select":
            case "core.instance.launch":
                return PluginPermission.GAME_LAUNCH;
            case "core.plugin.action":
                return PluginPermission.LAUNCHER_UI;
            case "core.auracore.accounts.list":
            case "core.auracore.accounts.add-offline":
            case "core.auracore.accounts.remove":
            case "core.auracore.accounts.set-default":
            case "core.auracore.auth.msa.begin":
            case "core.auracore.auth.msa.info":
                return PluginPermission.ACCOUNT;
            default:
                return null;
        }
    }

    /// Removes account data from one snapshot reply when the account grant is absent.
    ///
    /// @param value launcher snapshot value
    /// @return redacted snapshot without the `accounts` entry
    static BridgeValue redactAccounts(BridgeValue value) {
        return removeMapFields(value, Set.of("accounts"));
    }

    /// Redacts snapshot state that exceeds the provider's current account and filesystem grants.
    ///
    /// @param value launcher snapshot value
    /// @param granted effective provider permissions
    /// @return snapshot without account metadata, credentials, or unauthorized paths
    private static BridgeValue redactSnapshot(BridgeValue value, Set<PluginPermission> granted) {
        BridgeValue redacted = granted.contains(PluginPermission.ACCOUNT)
                ? value
                : redactAccounts(value);
        if (granted.contains(PluginPermission.ACCOUNT)) {
            if (isEngineSnapshot(redacted, "auracore")) {
                redacted = projectMicrosoftAccountSuggestions(redacted);
            } else if (!isEngineSnapshot(redacted, "hmcl")) {
                redacted = redactAccounts(redacted);
            }
        }
        if (redacted instanceof BridgeValue.MapValue snapshot
                && snapshot.values().get("settings") instanceof BridgeValue.MapValue settings) {
            Set<String> removed = granted.contains(PluginPermission.FILESYSTEM)
                    ? Set.of("proxyUser", "proxyPassword")
                    : Set.of("commonDirectory", "proxyUser", "proxyPassword");
            Map<String, BridgeValue> updated = new java.util.LinkedHashMap<>(snapshot.values());
            updated.put("settings", removeMapFields(settings, removed));
            return BridgeValue.map(updated);
        }
        return redacted;
    }

    /// Determines whether one launcher snapshot identifies the selected engine.
    ///
    /// @param value candidate snapshot value
    /// @param expected exact engine identifier accepted by the launcher
    /// @return true only for a map carrying that exact engine setting
    private static boolean isEngineSnapshot(BridgeValue value, String expected) {
        return value instanceof BridgeValue.MapValue snapshot
                && snapshot.values().get("settings") instanceof BridgeValue.MapValue settings
                && settings.values().get("coreEngine") instanceof BridgeValue.StringValue engine
                && expected.equals(engine.value());
    }

    /// Reduces AuraCore snapshot accounts to Microsoft login suggestions.
    ///
    /// AuraCore must not receive the fuller HMCL account display record when it only needs a profile
    /// name to suggest reauthorization. Malformed or non-Microsoft entries are omitted.
    ///
    /// @param value AuraCore snapshot carrying launcher accounts
    /// @return snapshot whose accounts contain only `type` and `username` fields
    private static BridgeValue projectMicrosoftAccountSuggestions(BridgeValue value) {
        if (!(value instanceof BridgeValue.MapValue snapshot)
                || !(snapshot.values().get("accounts") instanceof BridgeValue.ArrayValue accounts)) {
            return removeMapFields(value, Set.of("accounts"));
        }
        List<BridgeValue> suggestions = new java.util.ArrayList<>(accounts.values().size());
        for (BridgeValue entry : accounts.values()) {
            if (entry instanceof BridgeValue.MapValue account
                    && account.values().get("type") instanceof BridgeValue.StringValue type
                    && "microsoft".equals(type.value())
                    && account.values().get("username") instanceof BridgeValue.StringValue username) {
                Map<String, BridgeValue> suggestion = new java.util.LinkedHashMap<>();
                suggestion.put("type", type);
                suggestion.put("username", username);
                suggestions.add(BridgeValue.map(suggestion));
            }
        }
        Map<String, BridgeValue> updated = new java.util.LinkedHashMap<>(snapshot.values());
        updated.put("accounts", BridgeValue.array(suggestions));
        return BridgeValue.map(updated);
    }

    /// Removes selected fields from one Bridge map without changing unrelated values.
    ///
    /// @param value candidate Bridge value
    /// @param fields top-level field names to remove
    /// @return copied map without selected fields for map values, otherwise the original value
    private static BridgeValue removeMapFields(BridgeValue value, Set<String> fields) {
        if (value instanceof BridgeValue.MapValue map) {
            Map<String, BridgeValue> redacted = new java.util.LinkedHashMap<>(map.values());
            fields.forEach(redacted::remove);
            return BridgeValue.map(redacted);
        }
        return value;
    }

    /// Redacts filesystem-adjacent data from one AuraCore reply when filesystem access is absent.
    ///
    /// Backend diagnostics are replaced and unexpected shapes fail closed because both are untrusted
    /// channels for local paths. Successful replies retain only explicitly safe fields.
    ///
    /// @param method fixed `core.*` command method
    /// @param value successful delegate reply value
    /// @return AuraCore reply containing only fields safe without the filesystem grant
    private static BridgeValue redactAuraCoreReply(String method, BridgeValue value) {
        return switch (method) {
            case "core.auracore.status" -> isErrorEnvelope(value) || !(value instanceof BridgeValue.MapValue)
                    ? genericError("AuraCore status failed")
                    : keepMapFields(value, Set.of(
                            "engine", "libraryAvailable", "backendRunning"),
                            "AuraCore status failed");
            case "core.auracore.instance.list" -> redactArrayFields(
                    value,
                    Set.of("id", "name", "group", "lastLaunch", "gameVersion",
                            "loader", "loaderVersion", "modCount", "icon"),
                    "AuraCore instance list failed");
            case "core.auracore.task.status" -> isErrorEnvelope(value) || !(value instanceof BridgeValue.MapValue)
                    ? genericError("AuraCore task status failed")
                    : keepMapFields(value, Set.of(
                            "id", "type", "state", "progress", "total", "succeeded"),
                            "AuraCore task status failed");
            case "core.auracore.accounts.list" -> redactArrayFields(
                    value,
                    Set.of("profileName", "type", "internalId", "hasProfile", "isDefault"),
                    "AuraCore account list failed");
            case "core.auracore.auth.msa.begin" -> isErrorEnvelope(value) || !(value instanceof BridgeValue.MapValue)
                    ? genericError("AuraCore Microsoft login failed")
                    : keepMapFields(value, Set.of("taskId", "started"),
                            "AuraCore Microsoft login failed");
            case "core.auracore.auth.msa.info" -> isErrorEnvelope(value) || !(value instanceof BridgeValue.MapValue)
                    ? genericError("AuraCore Microsoft login status failed")
                    : keepMapFields(value, Set.of(
                            "id", "codeIssued", "verificationUrl", "userCode", "expiresIn"),
                            "AuraCore Microsoft login status failed");
            default -> value;
        };
    }

    /// Redacts one AuraCore array while rejecting unexpected entry shapes.
    ///
    /// @param value candidate delegate reply
    /// @param fields entry fields considered safe
    /// @param failure generic failure message
    /// @return safe array or a generic failure envelope
    private static BridgeValue redactArrayFields(
            BridgeValue value, Set<String> fields, String failure) {
        if (isErrorEnvelope(value) || !(value instanceof BridgeValue.ArrayValue array)) {
            return genericError(failure);
        }
        List<BridgeValue> redacted = new java.util.ArrayList<>(array.values().size());
        for (BridgeValue entry : array.values()) {
            if (!(entry instanceof BridgeValue.MapValue)) {
                return genericError(failure);
            }
            BridgeValue safe = keepMapFields(entry, fields, failure);
            if (isErrorEnvelope(safe)) {
                return safe;
            }
            redacted.add(safe);
        }
        return BridgeValue.array(redacted);
    }

    /// Determines whether a Bridge map is a backend error envelope.
    ///
    /// Extra fields do not rehabilitate an error reply; the redactor replaces the whole envelope.
    ///
    /// @param value candidate Bridge value
    /// @return true when `error` is present as a top-level field
    private static boolean isErrorEnvelope(BridgeValue value) {
        return value instanceof BridgeValue.MapValue map && map.values().containsKey("error");
    }

    /// Keeps selected scalar fields from one Bridge map without passing unknown backend additions.
    ///
    /// Nested values are rejected because a field name allowlist cannot make arbitrary container
    /// contents safe.
    ///
    /// @param value candidate Bridge value
    /// @param fields top-level scalar field names considered safe
    /// @param failure generic failure message for malformed safe fields
    /// @return copied map, the original non-map value, or a generic failure envelope
    private static BridgeValue keepMapFields(BridgeValue value, Set<String> fields, String failure) {
        if (value instanceof BridgeValue.MapValue map) {
            Map<String, BridgeValue> retained = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, BridgeValue> entry : map.values().entrySet()) {
                if (fields.contains(entry.getKey())) {
                    if (!isScalarBridgeValue(entry.getValue())) {
                        return genericError(failure);
                    }
                    retained.put(entry.getKey(), entry.getValue());
                }
            }
            return BridgeValue.map(retained);
        }
        return value;
    }

    /// Determines whether one Bridge value is safe for an allowlisted display field.
    ///
    /// @param value candidate field value
    /// @return true for null, boolean, numeric, and string values
    private static boolean isScalarBridgeValue(BridgeValue value) {
        return value instanceof BridgeValue.NullValue
                || value instanceof BridgeValue.BooleanValue
                || value instanceof BridgeValue.IntegerValue
                || value instanceof BridgeValue.FloatValue
                || value instanceof BridgeValue.StringValue;
    }

    /// Creates a diagnostic reply that cannot carry a backend filesystem path.
    ///
    /// @param message generic user-facing failure message
    /// @return map containing only the redacted `error` field
    private static BridgeValue genericError(String message) {
        Map<String, BridgeValue> error = new java.util.LinkedHashMap<>();
        error.put("error", BridgeValue.string(message));
        return BridgeValue.map(error);
    }

    /// Rechecks exact-artifact grants for every frontend command before delegation.
    ///
    /// Commands with launcher-wide side effects require their dedicated permission on each
    /// invocation. Snapshot replies drop account data without the account permission, and AuraCore
    /// display/task replies, backend diagnostics, and snapshot settings drop filesystem-adjacent
    /// data without the filesystem permission.
    private record PermissionGatedHandler(
            PluginManager pluginManager,
            String pluginId,
            UiFrontendCommandHandler delegate
    ) implements UiFrontendCommandHandler {
        /// Handles one command after recomputing the provider's effective grants.
        ///
        /// @param method fixed `core.*` command method
        /// @param params token-free command parameters
        /// @return gated asynchronous reply
        @Override
        public java.util.concurrent.CompletionStage<Reply> handle(String method, BridgeValue params) {
            java.util.@Unmodifiable Set<PluginPermission> granted;
            try {
                granted = pluginManager.getGrantedPermissions(pluginId);
            } catch (IOException failure) {
                return java.util.concurrent.CompletableFuture.failedFuture(failure);
            }
            @Nullable PluginPermission required = requiredPermission(method);
            if (required != null && !granted.contains(required)) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("PERMISSION_DENIED: " + method));
            }
            java.util.concurrent.CompletionStage<Reply> stage = delegate.handle(method, params);
            if ("core.snapshot.get".equals(method)
                    && (!granted.contains(PluginPermission.ACCOUNT)
                    || !granted.contains(PluginPermission.FILESYSTEM))) {
                return stage.thenApply(reply -> new Reply(
                        redactSnapshot(reply.value(), granted),
                        reply.afterResponseAction()
                ));
            }
            if (("core.auracore.status".equals(method)
                    || "core.auracore.instance.list".equals(method)
                    || "core.auracore.task.status".equals(method)
                    || "core.auracore.accounts.list".equals(method)
                    || "core.auracore.auth.msa.begin".equals(method)
                    || "core.auracore.auth.msa.info".equals(method))
                    && !granted.contains(PluginPermission.FILESYSTEM)) {
                return stage.thenApply(reply -> new Reply(
                        redactAuraCoreReply(method, reply.value()),
                        reply.afterResponseAction()
                ));
            }
            return stage;
        }
    }

    /// Requires one verified native package to remain permission-granted.
    ///
    /// @param descriptor verified native descriptor
    /// @return effective permission grants for the exact provider artifact
    /// @throws IOException when any UI-provider required permission is no longer granted
    private @Unmodifiable Set<PluginPermission> requireNativeSelection(UiFrontendDescriptor descriptor)
            throws IOException {
        @Unmodifiable Set<PluginPermission> granted = pluginManager.getGrantedPermissions(descriptor.getId());
        if (!granted.containsAll(Set.of(
                PluginPermission.LAUNCHER_UI_PROVIDER,
                PluginPermission.NATIVE_CODE,
                PluginPermission.PROCESS
        ))) {
            throw new IOException("UI provider permissions are not fully granted: " + descriptor.getId());
        }
        return granted;
    }

    /// Closes the active native session while ignoring shutdown diagnostics.
    private void closeSessionQuietly() {
        @Nullable SupervisedSession active = session;
        session = null;
        activeNative = null;
        if (active != null) {
            active.close();
        }
    }

    /// Selects JavaFX after an active native child terminates on its own.
    ///
    /// @param descriptor descriptor of the terminated native frontend
    /// @param termination terminal child state
    private void switchToFrontendAfterChildTermination(
            UiFrontendDescriptor descriptor,
            UiFrontendProcessSession.Termination termination
    ) {
        if (session != null) {
            fallbackReason = "Native UI frontend terminated: " + descriptor.getId();
            session = null;
            activeNative = null;
        }
    }

    /// Starts one ready supervised native session.
    @FunctionalInterface
    interface SessionLauncher {
        /// Starts one ready session from a verified descriptor.
        ///
        /// @param executable verified native executable
        /// @param packageRoot verified extracted package root
        /// @param initialSnapshot redacted initial UI state
        /// @param handler launcher command handler
        /// @return ready supervised native UI session
        /// @throws UiFrontendProcessException if startup validation, launch, handshake, or readiness fails
        SupervisedSession start(
                Path executable,
                Path packageRoot,
                BridgeValue initialSnapshot,
                UiFrontendCommandHandler handler
        ) throws UiFrontendProcessException;
    }

    /// Minimal supervision surface retained after a native frontend becomes ready.
    @NotNullByDefault
    public interface SupervisedSession {
        /// Terminal child state completed after process exit and owned-thread cleanup.
        ///
        /// @return terminal state
        CompletionStage<UiFrontendProcessSession.Termination> termination();

        /// Gracefully stops the native child and its owned executors.
        void close();
    }
}
