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
package org.jackhuang.hmcl.plugin.runtime;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jackhuang.hmcl.plugin.PluginPatchInvocation;
import org.jackhuang.hmcl.plugin.PluginPatchResult;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jackhuang.hmcl.plugin.patch.PluginPatchCallback;
import org.jackhuang.hmcl.plugin.patch.PluginPatchFailure;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/// Registers external Runtime Patch declarations and bridges callbacks through `aura.patch.v1`.
///
/// The endpoint retains no public engine or Instrumentation reference. Every callback revalidates the exact artifact,
/// execution mode, lifecycle generation, and `launcher-patch` permission before entering the external Provider.
@NotNullByDefault
public final class RuntimePatchEndpoint implements AutoCloseable {
    /// Exact external payload package identity.
    private final PluginArtifactIdentity artifactIdentity;

    /// External payload execution boundary.
    private final PluginExecutionMode executionMode;

    /// Launcher-owned authority which verifies every callback token.
    private final PluginPermissionAuthority permissionAuthority;

    /// Issues a token from the payload's current lifecycle-session generation.
    private final Supplier<PluginCapabilityToken> capabilityTokenSupplier;

    /// Immutable authoritative manifest declarations accepted from this payload.
    private final @Unmodifiable List<PluginPatchDeclaration> declarations;

    /// Immutable dependency IDs used by the Patch engine for deterministic ordering.
    private final @Unmodifiable Set<String> dependencyIds;

    /// Private monitor which owns endpoint registration replacement and teardown.
    private final Object registrationMonitor;

    /// Launcher-owned exact payload lifecycle validator.
    private final RegistrationGate registrationGate;

    /// Restricted engine registration boundary, or `null` for an independently constructed endpoint.
    private final @Nullable EngineRegistrar engineRegistrar;

    /// Exact-generation Provider transport, or `null` for an independently constructed endpoint.
    private final @Nullable ProviderInvoker providerInvoker;

    /// Active registration handles keyed by authoritative declaration.
    private final Map<PluginPatchDeclaration, RegistrationHandle> registrations = new LinkedHashMap<>();

    /// Endpoint lifecycle revision advanced before every aggregate registration close.
    private long registrationRevision;

    /// Creates one independently constructed fail-closed endpoint bound to an exact external payload.
    ///
    /// @param artifactIdentity exact external payload identity
    /// @param executionMode payload execution boundary
    /// @param permissionAuthority launcher-owned token verifier
    /// @param capabilityTokenSupplier current payload-session token source
    /// @param declarations authoritative manifest Patch declarations
    public RuntimePatchEndpoint(
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            PluginPermissionAuthority permissionAuthority,
            Supplier<PluginCapabilityToken> capabilityTokenSupplier,
            Collection<PluginPatchDeclaration> declarations
    ) {
        this(
                artifactIdentity,
                executionMode,
                permissionAuthority,
                capabilityTokenSupplier,
                declarations,
                () -> {
                }
        );
    }

    /// Creates one independently constructed fail-closed endpoint with an exact payload lifecycle gate.
    ///
    /// @param artifactIdentity exact external payload identity
    /// @param executionMode payload execution boundary
    /// @param permissionAuthority launcher-owned token verifier
    /// @param capabilityTokenSupplier current payload-session token source
    /// @param declarations authoritative manifest Patch declarations
    /// @param registrationGate exact payload lifecycle validator
    public RuntimePatchEndpoint(
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            PluginPermissionAuthority permissionAuthority,
            Supplier<PluginCapabilityToken> capabilityTokenSupplier,
            Collection<PluginPatchDeclaration> declarations,
            RegistrationGate registrationGate
    ) {
        this(
                artifactIdentity,
                executionMode,
                permissionAuthority,
                capabilityTokenSupplier,
                declarations,
                Set.of(),
                new Object(),
                registrationGate,
                null,
                null
        );
    }

    /// Creates one engine-backed endpoint for a launcher-owned exact payload generation.
    ///
    /// Package visibility confines construction to the Runtime Supervisor and endpoint tests.
    ///
    /// @param artifactIdentity exact external payload identity
    /// @param executionMode payload execution boundary
    /// @param permissionAuthority launcher-owned token verifier
    /// @param capabilityTokenSupplier current payload-session token source
    /// @param declarations authoritative manifest Patch declarations
    /// @param dependencyIds canonical dependency IDs used for engine ordering
    /// @param registrationMonitor exact payload-generation registration monitor
    /// @param registrationGate exact payload lifecycle validator
    /// @param engineRegistrar restricted engine registration boundary
    /// @param providerInvoker exact payload-generation Provider transport
    RuntimePatchEndpoint(
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            PluginPermissionAuthority permissionAuthority,
            Supplier<PluginCapabilityToken> capabilityTokenSupplier,
            Collection<PluginPatchDeclaration> declarations,
            Collection<String> dependencyIds,
            Object registrationMonitor,
            RegistrationGate registrationGate,
            @Nullable EngineRegistrar engineRegistrar,
            @Nullable ProviderInvoker providerInvoker
    ) {
        this.artifactIdentity = Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        this.permissionAuthority = Objects.requireNonNull(permissionAuthority, "permissionAuthority");
        this.capabilityTokenSupplier = Objects.requireNonNull(
                capabilityTokenSupplier, "capabilityTokenSupplier");
        this.declarations = copyDeclarations(declarations);
        this.dependencyIds = Set.copyOf(Objects.requireNonNull(dependencyIds, "dependencyIds"));
        this.registrationMonitor = Objects.requireNonNull(registrationMonitor, "registrationMonitor");
        this.registrationGate = Objects.requireNonNull(registrationGate, "registrationGate");
        this.engineRegistrar = engineRegistrar;
        this.providerInvoker = providerInvoker;
    }

    /// Registers one exact declared Patch through the restricted launcher engine boundary.
    ///
    /// @param declaration requested manifest declaration
    /// @return `REGISTERED`, or `PATCH_ENGINE_UNAVAILABLE` when no engine boundary is available
    /// @throws IllegalArgumentException if the Patch was not declared by this payload
    /// @throws SecurityException if current plugin-scoped Patch authority is invalid
    /// @throws IllegalStateException if engine target or conflict validation fails
    public RegistrationStatus register(PluginPatchDeclaration declaration) {
        PluginPatchDeclaration requested = requireOwnedDeclaration(declaration);
        final long expectedRevision;
        synchronized (registrationMonitor) {
            expectedRevision = registrationRevision;
        }
        authorizeRegistration();
        synchronized (registrationMonitor) {
            if (registrationRevision != expectedRevision) {
                throw new IllegalStateException("Runtime Patch lifecycle changed during registration");
            }
            @Nullable RegistrationHandle existing = registrations.get(requested);
            if (existing != null && existing.isActive()) {
                return RegistrationStatus.REGISTERED;
            }
            if (existing != null) {
                registrations.remove(requested);
                existing.closeEngineRegistration();
            }
            if (engineRegistrar == null || providerInvoker == null) {
                return RegistrationStatus.PATCH_ENGINE_UNAVAILABLE;
            }
            final EngineRegistration engineRegistration;
            long callbackRevision = registrationRevision;
            try {
                engineRegistration = Objects.requireNonNull(engineRegistrar.register(
                        artifactIdentity,
                        dependencyIds,
                        requested,
                        invocation -> invokeProvider(requested, invocation, callbackRevision)
                ), "engineRegistrar result");
            } catch (PluginPatchFailure failure) {
                if (failure.category() == PluginPatchFailure.Category.UNAVAILABLE_ENGINE) {
                    return RegistrationStatus.PATCH_ENGINE_UNAVAILABLE;
                }
                throw new IllegalStateException("Runtime Patch engine registration failed", failure);
            }
            RegistrationHandle registration = new RegistrationHandle(requested, engineRegistration);
            registrations.put(requested, registration);
            return RegistrationStatus.REGISTERED;
        }
    }

    /// Returns the active opaque registration for one authoritative declaration.
    ///
    /// @param declaration authoritative manifest declaration
    /// @return active endpoint-owned registration handle
    /// @throws IllegalArgumentException if the declaration is not owned by this endpoint
    /// @throws IllegalStateException if no active registration exists
    public RegistrationHandle registration(PluginPatchDeclaration declaration) {
        PluginPatchDeclaration requested = requireOwnedDeclaration(declaration);
        synchronized (registrationMonitor) {
            @Nullable RegistrationHandle registration = registrations.get(requested);
            if (registration == null || !registration.isActive()) {
                throw new IllegalStateException("Runtime Patch declaration is not registered: "
                        + requested.getTarget() + "." + requested.getMethod());
            }
            return registration;
        }
    }

    /// Returns the immutable authoritative Patch declaration snapshot.
    ///
    /// @return immutable Patch declarations
    public @Unmodifiable List<PluginPatchDeclaration> declarations() {
        return declarations;
    }

    /// Closes every endpoint-owned engine registration without invalidating later lifecycle re-registration.
    @Override
    public void close() {
        @Unmodifiable List<RegistrationHandle> closing;
        synchronized (registrationMonitor) {
            registrationRevision++;
            closing = List.copyOf(registrations.values());
            registrations.clear();
        }
        closing.forEach(RegistrationHandle::closeEngineRegistration);
    }

    /// Invokes the exact-generation Provider after callback-local lifecycle and permission authorization.
    ///
    /// @param declaration authoritative registered declaration
    /// @param invocation immutable engine callback invocation
    /// @param expectedRevision endpoint registration revision captured by this callback
    /// @return validated Patch result
    /// @throws PluginPatchFailure if authorization, lifecycle, transport, or wire validation fails
    private PluginPatchResult invokeProvider(
            PluginPatchDeclaration declaration,
            PluginPatchInvocation invocation,
            long expectedRevision
    ) throws PluginPatchFailure {
        synchronized (registrationMonitor) {
            if (registrationRevision != expectedRevision) {
                throw failure(PluginPatchFailure.Category.LIFECYCLE_REVOKED,
                        "Runtime Patch registration lifecycle is no longer active", null);
            }
            return invokeProviderWhileRegistered(declaration, invocation);
        }
    }

    /// Invokes one Provider while aggregate endpoint teardown is excluded by the registration monitor.
    ///
    /// @param declaration authoritative registered declaration
    /// @param invocation immutable engine callback invocation
    /// @return validated Patch result
    /// @throws PluginPatchFailure if authorization, lifecycle, transport, or wire validation fails
    private PluginPatchResult invokeProviderWhileRegistered(
            PluginPatchDeclaration declaration,
            PluginPatchInvocation invocation
    ) throws PluginPatchFailure {
        if (!declaration.equals(invocation.declaration())) {
            throw failure(PluginPatchFailure.Category.MALFORMED_VALUE,
                    "Runtime Patch invocation does not match its registration", null);
        }
        try {
            registrationGate.requireActive();
        } catch (IllegalStateException exception) {
            throw failure(PluginPatchFailure.Category.LIFECYCLE_REVOKED,
                    "Runtime Patch payload lifecycle is no longer active", exception);
        }

        @Nullable PluginCapabilityToken token = null;
        try {
            try {
                token = Objects.requireNonNull(
                        capabilityTokenSupplier.get(), "capabilityTokenSupplier result");
                permissionAuthority.requirePermission(
                        token,
                        artifactIdentity.getPluginId(),
                        artifactIdentity,
                        executionMode,
                        PluginPermission.LAUNCHER_PATCH,
                        RuntimeHookEndpoint.CALLBACK_DOMAIN
                );
            } catch (SecurityException exception) {
                throw failure(PluginPatchFailure.Category.PERMISSION_DENIED,
                        "Runtime Patch callback permission was denied", exception);
            } catch (IllegalStateException exception) {
                throw failure(PluginPatchFailure.Category.LIFECYCLE_REVOKED,
                        "Runtime Patch capability generation is no longer active", exception);
            }

            try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
                final byte[] request;
                try {
                    request = codec.encodeInvocation(invocation);
                } catch (RuntimePatchWireCodec.TypeMismatchException exception) {
                    throw failure(PluginPatchFailure.Category.TYPE_MISMATCH,
                            "Runtime Patch invocation contains an incompatible value", exception);
                } catch (IOException exception) {
                    throw failure(PluginPatchFailure.Category.MALFORMED_VALUE,
                            "Runtime Patch invocation is malformed", exception);
                }

                final byte[] response;
                try {
                    response = Objects.requireNonNull(providerInvoker).invoke(request);
                } catch (PluginPatchFailure failure) {
                    throw failure;
                } catch (IOException exception) {
                    throw failure(PluginPatchFailure.Category.TRANSPORT,
                            "Runtime Patch Provider transport failed", exception);
                } catch (Exception exception) {
                    throw failure(PluginPatchFailure.Category.CALLBACK_EXCEPTION,
                            "Runtime Patch Provider callback failed", exception);
                }

                try {
                    return codec.decodeResult(response, invocation);
                } catch (RuntimePatchWireCodec.TypeMismatchException exception) {
                    throw failure(PluginPatchFailure.Category.TYPE_MISMATCH,
                            "Runtime Patch response contains an incompatible value", exception);
                } catch (IOException exception) {
                    throw failure(PluginPatchFailure.Category.MALFORMED_VALUE,
                            "Runtime Patch response is malformed", exception);
                }
            }
        } finally {
            if (token != null) {
                permissionAuthority.revoke(token);
            }
        }
    }

    /// Verifies current registration authority and immediately revokes its short-lived token.
    private void authorizeRegistration() {
        PluginCapabilityToken token = Objects.requireNonNull(
                capabilityTokenSupplier.get(), "capabilityTokenSupplier result");
        try {
            permissionAuthority.requirePermission(
                    token,
                    artifactIdentity.getPluginId(),
                    artifactIdentity,
                    executionMode,
                    PluginPermission.LAUNCHER_PATCH,
                    RuntimeHookEndpoint.CALLBACK_DOMAIN
            );
            registrationGate.requireActive();
        } finally {
            permissionAuthority.revoke(token);
        }
    }

    /// Returns and validates one exact declaration owned by this endpoint.
    ///
    /// @param declaration candidate declaration
    /// @return authoritative equal declaration
    private PluginPatchDeclaration requireOwnedDeclaration(PluginPatchDeclaration declaration) {
        PluginPatchDeclaration requested = Objects.requireNonNull(declaration, "declaration");
        requested.validate();
        if (!declarations.contains(requested)) {
            throw new IllegalArgumentException("Patch declaration is not owned by plugin: "
                    + artifactIdentity.getPluginId());
        }
        return requested;
    }

    /// Copies and validates authoritative declarations without retaining caller collection state.
    ///
    /// @param declarations caller declaration collection
    /// @return immutable validated declaration list
    private static @Unmodifiable List<PluginPatchDeclaration> copyDeclarations(
            Collection<PluginPatchDeclaration> declarations
    ) {
        @Unmodifiable List<PluginPatchDeclaration> copy = List.copyOf(
                Objects.requireNonNull(declarations, "declarations"));
        copy.forEach(PluginPatchDeclaration::validate);
        return copy;
    }

    /// Creates one stable categorized callback failure without exposing Provider-controlled details.
    ///
    /// @param category stable failure category
    /// @param message launcher-owned redacted diagnostic
    /// @param cause internal cause, or `null`
    /// @return categorized callback failure
    private static PluginPatchFailure failure(
            PluginPatchFailure.Category category,
            String message,
            @Nullable Throwable cause
    ) {
        return new PluginPatchFailure(category, message, cause);
    }

    /// Stable Runtime Patch registration outcome.
    @NotNullByDefault
    public enum RegistrationStatus {
        /// Declaration is registered with the launcher-owned Patch engine.
        REGISTERED,

        /// Declaration and authority are valid, but no JVM Patch engine is installed.
        PATCH_ENGINE_UNAVAILABLE
    }

    /// Opaque endpoint-owned view of one engine registration.
    @NotNullByDefault
    public final class RegistrationHandle implements AutoCloseable {
        /// Authoritative declaration keyed by this handle.
        private final PluginPatchDeclaration declaration;

        /// Restricted engine registration hidden behind the endpoint API.
        private final EngineRegistration engineRegistration;

        /// Whether this endpoint handle has already closed its engine registration.
        private boolean closed;

        /// Creates one endpoint-owned handle around a restricted engine registration.
        ///
        /// @param declaration authoritative declaration
        /// @param engineRegistration restricted engine registration
        private RegistrationHandle(
                PluginPatchDeclaration declaration,
                EngineRegistration engineRegistration
        ) {
            this.declaration = declaration;
            this.engineRegistration = Objects.requireNonNull(engineRegistration, "engineRegistration");
        }

        /// Returns whether the engine registration remains active.
        ///
        /// @return whether callbacks remain eligible
        public boolean isActive() {
            synchronized (registrationMonitor) {
                return !closed && engineRegistration.isActive();
            }
        }

        /// Returns the stable callback failure retained by the engine registration.
        ///
        /// @return failure category, or `null` when no callback failure was recorded
        public @Nullable PluginPatchFailure.Category failureCategory() {
            return engineRegistration.failureCategory();
        }

        /// Closes this exact registration and removes it from endpoint lookup.
        @Override
        public void close() {
            synchronized (registrationMonitor) {
                registrations.remove(declaration, this);
            }
            closeEngineRegistration();
        }

        /// Closes the restricted engine handle during aggregate endpoint teardown.
        private void closeEngineRegistration() {
            synchronized (registrationMonitor) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            engineRegistration.close();
        }
    }

    /// Launcher-owned validator for one exact retained payload registration generation.
    @FunctionalInterface
    @NotNullByDefault
    public interface RegistrationGate {
        /// Requires that the exact payload registration remains active and enabled.
        void requireActive();
    }

    /// Restricted language-neutral Patch engine registration boundary.
    @FunctionalInterface
    @NotNullByDefault
    public interface EngineRegistrar {
        /// Registers one exact declaration and callback without exposing the engine.
        ///
        /// @param artifactIdentity exact owning artifact
        /// @param dependencyIds immutable dependency IDs used for ordering
        /// @param declaration authoritative declaration
        /// @param callback Runtime callback endpoint
        /// @return opaque engine registration
        /// @throws PluginPatchFailure if engine validation or registration fails
        EngineRegistration register(
                PluginArtifactIdentity artifactIdentity,
                Set<String> dependencyIds,
                PluginPatchDeclaration declaration,
                PluginPatchCallback callback
        ) throws PluginPatchFailure;
    }

    /// Restricted engine registration operations needed by the Runtime endpoint.
    @NotNullByDefault
    public interface EngineRegistration extends AutoCloseable {
        /// Returns whether callbacks remain eligible.
        ///
        /// @return active registration state
        boolean isActive();

        /// Returns the stable callback failure retained by the engine.
        ///
        /// @return failure category, or `null` when no callback failure was recorded
        @Nullable PluginPatchFailure.Category failureCategory();

        /// Closes the engine registration.
        @Override
        void close();
    }

    /// Exact payload-generation Provider invocation boundary.
    @FunctionalInterface
    @NotNullByDefault
    public interface ProviderInvoker {
        /// Invokes `aura.patch.v1` for one encoded callback request.
        ///
        /// @param input canonical Bridge Value v1 request bytes
        /// @return canonical Bridge Value v1 response bytes
        /// @throws Exception if Provider transport or callback execution fails
        byte[] invoke(byte[] input) throws Exception;
    }
}
