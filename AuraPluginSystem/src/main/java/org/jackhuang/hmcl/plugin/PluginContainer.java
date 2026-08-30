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

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import org.jackhuang.hmcl.plugin.patch.PluginPatchFailure;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Holds one loaded plugin instance together with its runtime state and package location.
@NotNullByDefault
public final class PluginContainer {
    /// Loaded lifecycle implementation.
    private final Plugin plugin;

    /// Context passed to the lifecycle implementation.
    private final PluginContext context;

    /// Installed `.npl` package path.
    private Path nplFile;

    /// Observable lifecycle enablement state.
    private final BooleanProperty enabled = new SimpleBooleanProperty(false);

    /// Observable flag indicating that a Mixin-related state change needs a restart.
    private final BooleanProperty restartRequired = new SimpleBooleanProperty(false);

    /// Optional plugin-owned value retained for compatibility with existing integrations.
    private @Nullable Object userData;

    /// Number of dispatch snapshots currently retaining this container's callback endpoint.
    private int activeHookLeases;

    /// Number of active Patch registrations and invocations retaining this container's callback endpoint.
    private int activePatchLeases;

    /// Whether the current enabled lifecycle generation admits Java Patch callbacks.
    private volatile boolean patchCallbacksEnabled;

    /// Mutable declaration ownership slots in authoritative manifest order.
    private final @Unmodifiable List<PatchDeclarationSlot> patchDeclarationSlots;

    /// Whether unload requested that the dedicated class loader close after active callbacks finish.
    private boolean classLoaderCloseRequested;

    /// Whether the dedicated class loader close path has already been selected once.
    private boolean classLoaderClosed;

    /// Creates a plugin container.
    ///
    /// @param plugin lifecycle implementation
    /// @param context plugin context
    /// @param nplFile installed package path
    PluginContainer(Plugin plugin, PluginContext context, Path nplFile) {
        this.plugin = plugin;
        this.context = context;
        this.nplFile = nplFile;
        patchDeclarationSlots = context.getManifest().getPatches().stream()
                .map(PatchDeclarationSlot::new)
                .toList();
    }

    /// Returns the lifecycle implementation.
    ///
    /// @return plugin instance
    Plugin getPlugin() {
        return plugin;
    }

    /// Returns the plugin context.
    ///
    /// @return plugin context
    PluginContext getContext() {
        return context;
    }

    /// Returns the installed package path.
    ///
    /// @return `.npl` path
    public Path getNplFile() {
        return nplFile;
    }

    /// Replaces the installed package path after an update is staged for restart.
    ///
    /// The current lifecycle classes continue using their already extracted package version.
    ///
    /// @param nplFile replacement package path
    void setNplFile(Path nplFile) {
        this.nplFile = nplFile;
    }

    /// Returns whether the lifecycle is currently enabled.
    ///
    /// @return enablement state
    public boolean isEnabled() {
        return enabled.get();
    }

    /// Updates the lifecycle enablement state.
    ///
    /// @param enabled new enablement state
    void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    /// Returns the observable enablement property.
    ///
    /// @return enablement property
    BooleanProperty enabledProperty() {
        return enabled;
    }

    /// Returns whether a restart is required to apply the requested plugin state.
    ///
    /// @return restart-required state
    public boolean isRestartRequired() {
        return restartRequired.get();
    }

    /// Updates the restart-required state.
    ///
    /// @param restartRequired new restart-required state
    void setRestartRequired(boolean restartRequired) {
        this.restartRequired.set(restartRequired);
    }

    /// Returns the observable restart-required property.
    ///
    /// @return restart-required property
    BooleanProperty restartRequiredProperty() {
        return restartRequired;
    }

    /// Returns the authoritative package manifest.
    ///
    /// @return plugin manifest
    public PluginManifest getManifest() {
        return context.getManifest();
    }

    /// Returns the optional compatibility value owned by the plugin.
    ///
    /// @return stored value or `null`
    public @Nullable Object getUserData() {
        return userData;
    }

    /// Replaces the optional compatibility value owned by the plugin.
    ///
    /// @param userData new value or `null`
    public void setUserData(@Nullable Object userData) {
        this.userData = userData;
    }

    /// Returns immutable current status for every manifest Patch declaration in declaration order.
    ///
    /// Callback failures are read from their live registration handle, so a failure becomes visible without a
    /// separate manager mutation. No invocation values or plugin-controlled exception details are exposed.
    ///
    /// @return immutable declaration status snapshot
    public synchronized @Unmodifiable List<PatchDeclarationStatus> getPatchDeclarationStatuses() {
        return patchDeclarationSlots.stream().map(PatchDeclarationSlot::snapshot).toList();
    }

    /// Resets every declaration to pending immediately before one Java lifecycle registration pass.
    void preparePatchDeclarations() {
        synchronized (this) {
            for (PatchDeclarationSlot slot : patchDeclarationSlots) {
                if (slot.registration != null) {
                    throw new IllegalStateException("Plugin Patch registration is already retained: "
                            + getManifest().getId());
                }
                slot.state = PatchDeclarationState.PENDING;
                slot.failureCategory = null;
            }
        }
    }

    /// Opens callback admission for the current enabled Java lifecycle generation.
    void openPatchCallbackGate() {
        patchCallbacksEnabled = true;
    }

    /// Returns whether the current Java lifecycle generation still admits Patch callbacks.
    ///
    /// @return callback admission state
    boolean acceptsPatchCallbacks() {
        return patchCallbacksEnabled;
    }

    /// Retains one active registration and its lifetime class-loader lease.
    ///
    /// @param declaration authoritative manifest declaration
    /// @param registration active registration handle
    /// @param releaseLease idempotent registration-lifetime lease release
    void retainPatchRegistration(
            PluginPatchDeclaration declaration,
            PatchRegistrationHandle registration,
            Runnable releaseLease
    ) {
        synchronized (this) {
            PatchDeclarationSlot slot = findPatchDeclarationSlot(declaration);
            if (slot.registration != null) {
                throw new IllegalStateException("Plugin Patch declaration is already registered: "
                        + declaration.getTarget() + "." + declaration.getMethod());
            }
            slot.registration = Objects.requireNonNull(registration, "registration");
            slot.releaseLease = Objects.requireNonNull(releaseLease, "releaseLease");
            slot.state = PatchDeclarationState.ACTIVE;
            slot.failureCategory = null;
        }
    }

    /// Records an isolated registration failure without changing the plugin lifecycle state.
    ///
    /// @param declaration authoritative manifest declaration
    /// @param category stable redacted failure category
    void failPatchDeclaration(
            PluginPatchDeclaration declaration,
            PluginPatchFailure.Category category
    ) {
        synchronized (this) {
            PatchDeclarationSlot slot = findPatchDeclarationSlot(declaration);
            if (slot.registration != null) {
                throw new IllegalStateException("Cannot replace an active Plugin Patch registration diagnostic");
            }
            slot.state = PatchDeclarationState.FAILED;
            slot.failureCategory = Objects.requireNonNull(category, "category");
        }
    }

    /// Closes every retained Patch registration before plugin disable or unload proceeds.
    ///
    /// Registration closure precedes its lifetime lease release. A failed callback keeps its stable failure status;
    /// an ordinarily closed registration becomes restored.
    void closePatchRegistrations() {
        List<Runnable> closures = new ArrayList<>();
        patchCallbacksEnabled = false;
        synchronized (this) {
            for (PatchDeclarationSlot slot : patchDeclarationSlots) {
                @Nullable PatchRegistrationHandle registration = slot.registration;
                @Nullable Runnable releaseLease = slot.releaseLease;
                if (registration == null || releaseLease == null) {
                    continue;
                }
                @Nullable PluginPatchFailure.Category failureCategory = registration.failureCategory();
                slot.state = failureCategory == null
                        ? PatchDeclarationState.RESTORED
                        : PatchDeclarationState.FAILED;
                slot.failureCategory = failureCategory;
                slot.registration = null;
                slot.releaseLease = null;
                closures.add(() -> {
                    try {
                        registration.close();
                    } finally {
                        releaseLease.run();
                    }
                });
            }
        }
        for (Runnable closure : closures) {
            try {
                closure.run();
            } catch (RuntimeException exception) {
                LOG.warning("Failed to close plugin Patch registration: " + getManifest().getId(), exception);
            }
        }
    }

    /// Acquires one callback lease that prevents the dedicated class loader from closing during Hook dispatch.
    ///
    /// @return idempotent lease release action
    Runnable acquireHookLease() {
        return acquireCallbackLease(false);
    }

    /// Acquires one callback lease that prevents class-loader close while a Patch registration or invocation lives.
    ///
    /// @return idempotent lease release action
    Runnable acquirePatchLease() {
        return acquireCallbackLease(true);
    }

    /// Acquires one Hook or Patch callback lease.
    ///
    /// @param patchLease whether the lease belongs to Patch rather than Hook execution
    /// @return idempotent lease release action
    private Runnable acquireCallbackLease(boolean patchLease) {
        synchronized (this) {
            if (classLoaderCloseRequested || classLoaderClosed) {
                throw new IllegalStateException("Plugin class loader is already closing: " + getManifest().getId());
            }
            if (patchLease) {
                activePatchLeases++;
            } else {
                activeHookLeases++;
            }
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                releaseCallbackLease(patchLease);
            }
        };
    }

    /// Closes a dedicated plugin URL class loader when the plugin is unloadable.
    ///
    /// Startup Mixin plugins share HMCL's transforming loader and are intentionally left open for the process lifetime.
    ///
    /// @throws IOException if closing a dedicated loader fails
    void closeClassLoader() throws IOException {
        @Nullable URLClassLoader classLoader;
        synchronized (this) {
            if (classLoaderCloseRequested || classLoaderClosed) {
                return;
            }
            classLoaderCloseRequested = true;
            if (activeHookLeases > 0 || activePatchLeases > 0) {
                return;
            }
            classLoaderClosed = true;
            classLoader = dedicatedClassLoader();
        }
        if (classLoader != null) {
            classLoader.close();
        }
    }

    /// Closes every runtime Provider registration owned by this Host context.
    ///
    /// @throws IOException if Provider or dependent payload cleanup fails
    void closeRuntimeProviderRegistrations() throws IOException {
        context.closeRuntimeProviderRegistrations();
    }

    /// Revokes every capability token issued for this exact loaded artifact.
    void revokeCapabilityTokens() {
        context.revokeCapabilityTokens();
    }

    /// Resumes this external payload's capability session before its enable callback.
    void resumeCapabilitySession() {
        context.resumeCapabilitySession();
    }

    /// Suspends this external payload's capability session after disable or failed enablement.
    void suspendCapabilitySession() {
        context.suspendCapabilitySession();
    }

    /// Rotates this external payload's capability generation after an effective permission change.
    void rotateCapabilitySession() {
        context.rotateCapabilitySession();
    }

    /// Permanently closes this external payload's capability session before unload callbacks.
    void closeCapabilitySession() {
        context.closeCapabilitySession();
    }

    /// Releases one Hook or Patch callback lease and performs a pending close after the final callback exits.
    ///
    /// @param patchLease whether the released lease belongs to Patch rather than Hook execution
    private void releaseCallbackLease(boolean patchLease) {
        @Nullable URLClassLoader classLoader = null;
        synchronized (this) {
            int activeLeases = patchLease ? activePatchLeases : activeHookLeases;
            if (activeLeases <= 0) {
                throw new IllegalStateException("Plugin " + (patchLease ? "Patch" : "Hook")
                        + " lease count underflow: " + getManifest().getId());
            }
            if (patchLease) {
                activePatchLeases--;
            } else {
                activeHookLeases--;
            }
            if (activeHookLeases == 0
                    && activePatchLeases == 0
                    && classLoaderCloseRequested
                    && !classLoaderClosed) {
                classLoaderClosed = true;
                classLoader = dedicatedClassLoader();
            }
        }
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException exception) {
                LOG.warning("Failed to close plugin class loader after plugin callback: "
                        + getManifest().getId(), exception);
            }
        }
    }

    /// Finds the mutable ownership slot for one authoritative declaration.
    ///
    /// @param declaration declaration from this container's manifest
    /// @return matching ownership slot
    /// @throws IllegalArgumentException if the declaration does not belong to this container
    private PatchDeclarationSlot findPatchDeclarationSlot(PluginPatchDeclaration declaration) {
        PluginPatchDeclaration value = Objects.requireNonNull(declaration, "declaration");
        return patchDeclarationSlots.stream()
                .filter(slot -> slot.declaration.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Patch declaration is not owned by plugin: "
                        + getManifest().getId()));
    }

    /// Returns the dedicated closeable loader, excluding HMCL's shared application loader.
    ///
    /// @return dedicated URL class loader, or `null` when the loader is shared or not closeable
    private @Nullable URLClassLoader dedicatedClassLoader() {
        ClassLoader pluginClassLoader = context.getClassLoader();
        return pluginClassLoader != PluginContainer.class.getClassLoader()
                && pluginClassLoader instanceof URLClassLoader urlClassLoader
                ? urlClassLoader
                : null;
    }

    /// Immutable public state of one manifest Patch declaration.
    ///
    /// @param declaration authoritative manifest declaration
    /// @param state current lifecycle state
    /// @param failureCategory stable failure category, or `null` outside the failed state
    @NotNullByDefault
    public record PatchDeclarationStatus(
            PluginPatchDeclaration declaration,
            PatchDeclarationState state,
            @Nullable PluginPatchFailure.Category failureCategory
    ) {
        /// Validates state and diagnostic consistency.
        public PatchDeclarationStatus {
            Objects.requireNonNull(declaration, "declaration");
            Objects.requireNonNull(state, "state");
            if ((state == PatchDeclarationState.FAILED) != (failureCategory != null)) {
                throw new IllegalArgumentException("Only failed Patch declarations have a failure category");
            }
        }
    }

    /// Observable lifecycle state of one manifest Patch declaration.
    @NotNullByDefault
    public enum PatchDeclarationState {
        /// Declaration has not yet completed its current enablement registration attempt.
        PENDING,

        /// Declaration owns an active callback registration.
        ACTIVE,

        /// Declaration registration or callback failed in isolation.
        FAILED,

        /// A previously active declaration was closed and original behavior was restored.
        RESTORED
    }

    /// Restricted manager-owned view of one Patch engine registration.
    @NotNullByDefault
    interface PatchRegistrationHandle extends AutoCloseable {
        /// Returns whether new callbacks remain admissible.
        ///
        /// @return active state
        boolean isActive();

        /// Returns the stable callback failure category.
        ///
        /// @return failure category, or `null` while active or ordinarily closed
        @Nullable PluginPatchFailure.Category failureCategory();

        /// Closes callback admission and restores the affected method when possible.
        @Override
        void close();
    }

    /// Mutable internal ownership state for one manifest declaration.
    @NotNullByDefault
    private static final class PatchDeclarationSlot {
        /// Authoritative manifest declaration.
        private final PluginPatchDeclaration declaration;

        /// Last manager-owned lifecycle state.
        private PatchDeclarationState state = PatchDeclarationState.PENDING;

        /// Last stable failure category, or `null`.
        private @Nullable PluginPatchFailure.Category failureCategory;

        /// Retained engine registration, or `null` when inactive.
        private @Nullable PatchRegistrationHandle registration;

        /// Retained registration-lifetime class-loader lease release, or `null`.
        private @Nullable Runnable releaseLease;

        /// Creates one pending ownership slot.
        ///
        /// @param declaration authoritative declaration
        private PatchDeclarationSlot(PluginPatchDeclaration declaration) {
            this.declaration = Objects.requireNonNull(declaration, "declaration");
        }

        /// Creates one immutable current status snapshot.
        ///
        /// @return current public declaration status
        private PatchDeclarationStatus snapshot() {
            @Nullable PatchRegistrationHandle currentRegistration = registration;
            if (currentRegistration == null) {
                return new PatchDeclarationStatus(declaration, state, failureCategory);
            }
            @Nullable PluginPatchFailure.Category currentFailure = currentRegistration.failureCategory();
            if (currentFailure != null) {
                return new PatchDeclarationStatus(
                        declaration,
                        PatchDeclarationState.FAILED,
                        currentFailure
                );
            }
            return new PatchDeclarationStatus(
                    declaration,
                    currentRegistration.isActive()
                            ? PatchDeclarationState.ACTIVE
                            : PatchDeclarationState.RESTORED,
                    null
            );
        }
    }
}
