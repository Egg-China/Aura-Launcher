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
            requireNativeSelection(verified);
            Path executable = verified.getExecutable().orElseThrow();
            SupervisedSession started = sessionLauncher.start(
                    executable,
                    executable.getParent(),
                    initialSnapshot,
                    commandHandler
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

    /// Requires one verified native package to remain permission-granted.
    ///
    /// @param descriptor verified native descriptor
    /// @throws IOException when any UI-provider required permission is no longer granted
    private void requireNativeSelection(UiFrontendDescriptor descriptor) throws IOException {
        @Unmodifiable Set<PluginPermission> granted = pluginManager.getGrantedPermissions(descriptor.getId());
        if (!granted.containsAll(Set.of(
                PluginPermission.LAUNCHER_UI_PROVIDER,
                PluginPermission.NATIVE_CODE,
                PluginPermission.PROCESS
        ))) {
            throw new IOException("UI provider permissions are not fully granted: " + descriptor.getId());
        }
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
