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
import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilitySession;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jackhuang.hmcl.plugin.bridge.RuntimeBridgeWireCodec;
import org.jackhuang.hmcl.plugin.patch.PluginPatchCallback;
import org.jackhuang.hmcl.plugin.patch.PluginPatchFailure;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Runtime Patch registration, exact callback authority, wire transport, and cleanup.
@NotNullByDefault
public final class RuntimePatchEndpointTest {
    /// Exact payload artifact used by every endpoint fixture.
    private static final PluginArtifactIdentity IDENTITY = new PluginArtifactIdentity(
            "dev.aura.test.runtime-patch", "1.0.0", "a".repeat(64));

    /// Registers, invokes, and closes one declaration without exposing the engine registration.
    ///
    /// @throws Exception if the valid callback path fails
    @Test
    public void registerInvokeAndCloseDeclaredPatch() throws Exception {
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        AtomicReference<Set<PluginPermission>> permissions =
                new AtomicReference<>(Set.of(PluginPermission.LAUNCHER_PATCH));
        PluginCapabilitySession session = session(authority, permissions);
        PluginPatchDeclaration declaration = declaration();
        AtomicReference<PluginPatchCallback> callback = new AtomicReference<>();
        AtomicReference<PluginCapabilityToken> issuedToken = new AtomicReference<>();
        AtomicBoolean registrationActive = new AtomicBoolean(true);
        AtomicReference<PluginPatchFailure.@Nullable Category> failure = new AtomicReference<>();
        RuntimePatchEndpoint endpoint = endpoint(
                authority,
                () -> {
                    PluginCapabilityToken token = session.issue();
                    issuedToken.set(token);
                    return token;
                },
                declaration,
                callback,
                registrationActive,
                failure,
                input -> unchangedResponse()
        );

        assertEquals(RuntimePatchEndpoint.RegistrationStatus.REGISTERED, endpoint.register(declaration));
        RuntimePatchEndpoint.RegistrationHandle registration = endpoint.registration(declaration);
        assertTrue(registration.isActive());
        assertEquals(PluginPatchResult.Action.UNCHANGED, callback.get().invoke(invocation(declaration)).action());
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                issuedToken.get(),
                IDENTITY.getPluginId(),
                IDENTITY,
                PluginExecutionMode.ISOLATED,
                PluginPermission.LAUNCHER_PATCH,
                RuntimeHookEndpoint.CALLBACK_DOMAIN
        ));

        registration.close();

        assertFalse(registrationActive.get());
        assertFalse(registration.isActive());
        assertThrows(IllegalStateException.class, () -> endpoint.registration(declaration));
        assertEquals(RuntimePatchEndpoint.RegistrationStatus.REGISTERED, endpoint.register(declaration));
        endpoint.close();
        assertThrows(IllegalStateException.class, () -> endpoint.registration(declaration));
    }

    /// Reauthorizes every callback and categorizes revoked permissions and lifecycle generations.
    ///
    /// @throws Exception if registration setup fails
    @Test
    public void reauthorizeEveryCallbackAgainstCurrentLifecycle() throws Exception {
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        AtomicReference<Set<PluginPermission>> permissions =
                new AtomicReference<>(Set.of(PluginPermission.LAUNCHER_PATCH));
        PluginCapabilitySession session = session(authority, permissions);
        PluginPatchDeclaration declaration = declaration();
        AtomicReference<PluginPatchCallback> callback = new AtomicReference<>();
        AtomicBoolean registrationActive = new AtomicBoolean(true);
        AtomicBoolean lifecycleActive = new AtomicBoolean(true);
        RuntimePatchEndpoint endpoint = endpoint(
                authority,
                session::issue,
                declaration,
                callback,
                registrationActive,
                new AtomicReference<>(),
                input -> unchangedResponse(),
                () -> {
                    if (!lifecycleActive.get()) {
                        throw new IllegalStateException("inactive fixture generation");
                    }
                }
        );
        endpoint.register(declaration);

        permissions.set(Set.of());
        PluginPatchFailure denied = assertThrows(
                PluginPatchFailure.class,
                () -> callback.get().invoke(invocation(declaration))
        );
        assertEquals(PluginPatchFailure.Category.PERMISSION_DENIED, denied.category());

        permissions.set(Set.of(PluginPermission.LAUNCHER_PATCH));
        lifecycleActive.set(false);
        PluginPatchFailure revoked = assertThrows(
                PluginPatchFailure.class,
                () -> callback.get().invoke(invocation(declaration))
        );
        assertEquals(PluginPatchFailure.Category.LIFECYCLE_REVOKED, revoked.category());
    }

    /// Separates Provider transport failure from malformed and type-incompatible callback output.
    ///
    /// @throws Exception if fixture registration fails
    @Test
    public void categorizeProviderAndWireFailures() throws Exception {
        assertCallbackFailure(
                input -> {
                    throw new IOException("provider fixture failed");
                },
                PluginPatchFailure.Category.TRANSPORT
        );
        assertCallbackFailure(
                input -> new byte[]{0x01, 0x02},
                PluginPatchFailure.Category.MALFORMED_VALUE
        );
        assertCallbackFailure(
                input -> argumentsResponse(BridgeValue.string("wrong")),
                PluginPatchFailure.Category.TYPE_MISMATCH
        );
        assertCallbackFailure(
                input -> {
                    throw new IllegalStateException("callback fixture failed");
                },
                PluginPatchFailure.Category.CALLBACK_EXCEPTION
        );
    }

    /// Maps structurally valid Java input values that violate the declared primitive type to `TYPE_MISMATCH`.
    ///
    /// @throws Exception if fixture registration or callback invocation fails unexpectedly
    @Test
    public void categorizeInputJvmTypeMismatches() throws Exception {
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        AtomicReference<Set<PluginPermission>> permissions =
                new AtomicReference<>(Set.of(PluginPermission.LAUNCHER_PATCH));
        PluginPatchDeclaration declaration = declaration();
        AtomicReference<PluginPatchCallback> callback = new AtomicReference<>();
        RuntimePatchEndpoint endpoint = endpoint(
                authority,
                session(authority, permissions)::issue,
                declaration,
                callback,
                new AtomicBoolean(true),
                new AtomicReference<>(),
                input -> unchangedResponse()
        );
        endpoint.register(declaration);

        PluginPatchFailure wrongBox = assertThrows(
                PluginPatchFailure.class,
                () -> callback.get().invoke(PluginPatchInvocation.before(declaration, null, List.of(4L)))
        );
        PluginPatchFailure nullPrimitive = assertThrows(
                PluginPatchFailure.class,
                () -> callback.get().invoke(PluginPatchInvocation.before(
                        declaration,
                        null,
                        Collections.singletonList((@Nullable Object) null)
                ))
        );

        assertEquals(PluginPatchFailure.Category.TYPE_MISMATCH, wrongBox.category());
        assertEquals(PluginPatchFailure.Category.TYPE_MISMATCH, nullPrimitive.category());
    }

    /// Invokes one endpoint callback and verifies its stable failure category.
    ///
    /// @param provider Provider fixture
    /// @param expected expected stable category
    /// @throws Exception if registration setup fails
    private static void assertCallbackFailure(
            RuntimePatchEndpoint.ProviderInvoker provider,
            PluginPatchFailure.Category expected
    ) throws Exception {
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        AtomicReference<Set<PluginPermission>> permissions =
                new AtomicReference<>(Set.of(PluginPermission.LAUNCHER_PATCH));
        PluginPatchDeclaration declaration = declaration();
        AtomicReference<PluginPatchCallback> callback = new AtomicReference<>();
        RuntimePatchEndpoint endpoint = endpoint(
                authority,
                session(authority, permissions)::issue,
                declaration,
                callback,
                new AtomicBoolean(true),
                new AtomicReference<>(),
                provider
        );
        endpoint.register(declaration);

        PluginPatchFailure failure = assertThrows(
                PluginPatchFailure.class,
                () -> callback.get().invoke(invocation(declaration))
        );

        assertEquals(expected, failure.category());
    }

    /// Creates a production-shaped endpoint with injectable Engine and Provider boundaries.
    ///
    /// @param authority permission authority
    /// @param tokenSupplier current generation token supplier
    /// @param declaration authoritative declaration
    /// @param callback captures the registered callback
    /// @param registrationActive mutable fake Engine state
    /// @param failure mutable fake Engine failure category
    /// @param provider Provider transport fixture
    /// @return active endpoint
    private static RuntimePatchEndpoint endpoint(
            PluginPermissionAuthority authority,
            java.util.function.Supplier<PluginCapabilityToken> tokenSupplier,
            PluginPatchDeclaration declaration,
            AtomicReference<PluginPatchCallback> callback,
            AtomicBoolean registrationActive,
            AtomicReference<PluginPatchFailure.@Nullable Category> failure,
            RuntimePatchEndpoint.ProviderInvoker provider
    ) {
        return endpoint(
                authority,
                tokenSupplier,
                declaration,
                callback,
                registrationActive,
                failure,
                provider,
                () -> {
                }
        );
    }

    /// Creates a production-shaped endpoint with an explicit exact-generation lifecycle gate.
    ///
    /// @param authority permission authority
    /// @param tokenSupplier current generation token supplier
    /// @param declaration authoritative declaration
    /// @param callback captures the registered callback
    /// @param registrationActive mutable fake Engine state
    /// @param failure mutable fake Engine failure category
    /// @param provider Provider transport fixture
    /// @param gate exact payload lifecycle gate
    /// @return active endpoint
    private static RuntimePatchEndpoint endpoint(
            PluginPermissionAuthority authority,
            java.util.function.Supplier<PluginCapabilityToken> tokenSupplier,
            PluginPatchDeclaration declaration,
            AtomicReference<PluginPatchCallback> callback,
            AtomicBoolean registrationActive,
            AtomicReference<PluginPatchFailure.@Nullable Category> failure,
            RuntimePatchEndpoint.ProviderInvoker provider,
            RuntimePatchEndpoint.RegistrationGate gate
    ) {
        return new RuntimePatchEndpoint(
                IDENTITY,
                PluginExecutionMode.ISOLATED,
                authority,
                tokenSupplier,
                List.of(declaration),
                Set.of("dev.aura.test.dependency"),
                new Object(),
                gate,
                (identity, dependencies, requested, registeredCallback) -> {
                    registrationActive.set(true);
                    assertEquals(IDENTITY, identity);
                    assertEquals(Set.of("dev.aura.test.dependency"), dependencies);
                    assertEquals(declaration, requested);
                    callback.set(registeredCallback);
                    return new RuntimePatchEndpoint.EngineRegistration() {
                        /// Returns the mutable fake Engine state.
                        @Override
                        public boolean isActive() {
                            return registrationActive.get();
                        }

                        /// Returns the mutable fake failure category.
                        @Override
                        public @Nullable PluginPatchFailure.Category failureCategory() {
                            return failure.get();
                        }

                        /// Closes this fake Engine registration.
                        @Override
                        public void close() {
                            registrationActive.set(false);
                        }
                    };
                },
                provider
        );
    }

    /// Creates a dynamic exact-artifact capability session.
    ///
    /// @param authority permission authority
    /// @param permissions current permissions
    /// @return open capability session
    private static PluginCapabilitySession session(
            PluginPermissionAuthority authority,
            AtomicReference<Set<PluginPermission>> permissions
    ) {
        return authority.openSession(
                IDENTITY,
                PluginExecutionMode.ISOLATED,
                permissions::get,
                RuntimeHookEndpoint.CALLBACK_DOMAIN,
                Duration.ofMinutes(1)
        );
    }

    /// Returns one declared `before` Patch with an integer parameter.
    ///
    /// @return declaration fixture
    private static PluginPatchDeclaration declaration() {
        return new PluginPatchDeclaration(
                RuntimePatchEndpointTest.class.getName(),
                "target",
                PluginPatchDeclaration.PatchType.BEFORE,
                List.of("int")
        );
    }

    /// Returns one valid invocation for the declaration fixture.
    ///
    /// @param declaration declaration fixture
    /// @return invocation fixture
    private static PluginPatchInvocation invocation(PluginPatchDeclaration declaration) {
        return PluginPatchInvocation.before(declaration, null, List.of(4));
    }

    /// Encodes a canonical unchanged response.
    ///
    /// @return Bridge Value v1 response
    /// @throws IOException if encoding unexpectedly fails
    private static byte[] unchangedResponse() throws IOException {
        Map<String, BridgeValue> response = new LinkedHashMap<>();
        response.put("schemaVersion", BridgeValue.integer(1L));
        response.put("action", BridgeValue.string("unchanged"));
        return RuntimeBridgeWireCodec.encode(BridgeValue.map(response));
    }

    /// Encodes a wrong-type replacement argument response.
    ///
    /// @param argument wrong-type argument value
    /// @return Bridge Value v1 response
    /// @throws IOException if encoding unexpectedly fails
    private static byte[] argumentsResponse(BridgeValue argument) throws IOException {
        Map<String, BridgeValue> response = new LinkedHashMap<>();
        response.put("schemaVersion", BridgeValue.integer(1L));
        response.put("action", BridgeValue.string("arguments"));
        response.put("arguments", BridgeValue.array(List.of(argument)));
        return RuntimeBridgeWireCodec.encode(BridgeValue.map(response));
    }

    /// Patch target signature used only for reflective type validation.
    ///
    /// @param value integer fixture
    private static void target(int value) {
    }
}
