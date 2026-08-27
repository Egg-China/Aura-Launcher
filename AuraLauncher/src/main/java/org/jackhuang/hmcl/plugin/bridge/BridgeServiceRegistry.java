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
package org.jackhuang.hmcl.plugin.bridge;

import javafx.application.Platform;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadContext;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Dispatches stable Core and UI methods after schema, capability, ownership, and thread validation.
@NotNullByDefault
public final class BridgeServiceRegistry {
    /// Capability domain shared by one external payload lifecycle session.
    public static final String CALLBACK_DOMAIN = "runtime.payload";

    /// Maximum synchronous wait for one JavaFX Bridge operation.
    private static final long FX_WAIT_SECONDS = 30L;

    /// Launcher-owned authority used to verify and revoke call-scoped tokens.
    private final PluginPermissionAuthority permissionAuthority;

    /// Executor enforcing JavaFX method thread policy.
    private final FxExecutor fxExecutor;

    /// Registered handlers indexed by stable numeric method ID.
    private final Map<Long, RegisteredMethod> methodsById = new LinkedHashMap<>();

    /// Registered handlers indexed by canonical operation name.
    private final Map<String, RegisteredMethod> methodsByOperation = new LinkedHashMap<>();

    /// Creates an empty registry using the production JavaFX executor.
    ///
    /// @param permissionAuthority launcher-owned capability authority
    public BridgeServiceRegistry(PluginPermissionAuthority permissionAuthority) {
        this(permissionAuthority, BridgeServiceRegistry::runOnFxThreadOrNow);
    }

    /// Creates an empty registry with an explicit JavaFX executor.
    ///
    /// Package visibility confines deterministic executor substitution to Bridge tests.
    ///
    /// @param permissionAuthority launcher-owned capability authority
    /// @param fxExecutor JavaFX execution adapter
    BridgeServiceRegistry(PluginPermissionAuthority permissionAuthority, FxExecutor fxExecutor) {
        this.permissionAuthority = Objects.requireNonNull(permissionAuthority, "permissionAuthority");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "fxExecutor");
    }

    /// Registers one exact stable method and refuses duplicate numeric or textual keys.
    ///
    /// @param method stable method descriptor
    /// @param handler operation implementation
    public synchronized void register(BridgeMethod method, Handler handler) {
        BridgeMethod descriptor = Objects.requireNonNull(method, "method");
        Handler implementation = Objects.requireNonNull(handler, "handler");
        if (methodsById.containsKey(descriptor.id())
                || methodsByOperation.containsKey(descriptor.operation())) {
            throw new IllegalStateException("Bridge method is already registered: " + descriptor.operation());
        }
        RegisteredMethod registered = new RegisteredMethod(descriptor, implementation);
        methodsById.put(descriptor.id(), registered);
        methodsByOperation.put(descriptor.operation(), registered);
    }

    /// Invokes one method selected by its stable numeric ID.
    ///
    /// @param context exact calling payload context
    /// @param methodId stable numeric method ID
    /// @param input closed Bridge input value
    /// @return validated Bridge result
    /// @throws BridgeError when lookup, permission, validation, execution, or result checking fails
    public BridgeValue invoke(RuntimePayloadContext context, long methodId, BridgeValue input) throws BridgeError {
        @Nullable RegisteredMethod registered;
        synchronized (this) {
            registered = methodsById.get(methodId);
        }
        if (registered == null) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return invokeRegistered(context, registered, input);
    }

    /// Invokes one method selected by its canonical Runtime ABI operation.
    ///
    /// @param context exact calling payload context
    /// @param operation canonical operation name
    /// @param input closed Bridge input value
    /// @return validated Bridge result
    /// @throws BridgeError when lookup, permission, validation, execution, or result checking fails
    public BridgeValue invoke(RuntimePayloadContext context, String operation, BridgeValue input) throws BridgeError {
        Objects.requireNonNull(operation, "operation");
        @Nullable RegisteredMethod registered;
        synchronized (this) {
            registered = methodsByOperation.get(operation);
        }
        if (registered == null) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return invokeRegistered(context, registered, input);
    }

    /// Returns the registered method descriptors in deterministic registration order.
    ///
    /// @return immutable method snapshot
    public synchronized @Unmodifiable Collection<BridgeMethod> methods() {
        return methodsById.values().stream().map(RegisteredMethod::method).toList();
    }

    /// Validates input and executes one already resolved method under its thread policy.
    ///
    /// @param context exact calling payload context
    /// @param registered resolved method and handler
    /// @param input closed Bridge input value
    /// @return validated Bridge result
    private BridgeValue invokeRegistered(
            RuntimePayloadContext context,
            RegisteredMethod registered,
            BridgeValue input
    ) {
        RuntimePayloadContext payloadContext = Objects.requireNonNull(context, "context");
        BridgeValue argument = Objects.requireNonNull(input, "input");
        BridgeMethod method = registered.method();
        if (!method.inputSchema().accepts(argument)) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }

        Callable<BridgeValue> invocation = () -> invokeAuthorized(payloadContext, registered, argument);
        try {
            @Nullable BridgeValue result = method.threadPolicy() == BridgeMethod.ThreadPolicy.FX_APPLICATION
                    ? fxExecutor.call(invocation)
                    : invocation.call();
            if (result == null || !method.resultSchema().accepts(result)) {
                throw BridgeError.of(BridgeError.Category.INVALID_RESULT);
            }
            return result;
        } catch (BridgeError error) {
            throw error;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw BridgeError.of(BridgeError.Category.CANCELLED);
        } catch (Exception exception) {
            throw BridgeError.of(BridgeError.Category.INTERNAL);
        }
    }

    /// Issues and verifies one call-scoped token, invokes the handler, and always revokes the token.
    ///
    /// @param context exact calling payload context
    /// @param registered resolved method and handler
    /// @param input validated input value
    /// @return handler result
    private BridgeValue invokeAuthorized(
            RuntimePayloadContext context,
            RegisteredMethod registered,
            BridgeValue input
    ) {
        @Nullable PluginCapabilityToken token = null;
        try {
            token = Objects.requireNonNull(
                    context.capabilityTokenSupplier().get(), "capabilityTokenSupplier result");
            BridgeMethod method = registered.method();
            permissionAuthority.requirePermission(
                    token,
                    context.artifactIdentity().getPluginId(),
                    context.artifactIdentity(),
                    context.executionMode(),
                    method.permission(),
                    CALLBACK_DOMAIN
            );
            return Objects.requireNonNull(
                    registered.handler().invoke(new Invocation(context, token), input),
                    "Bridge handler result"
            );
        } catch (BridgeError error) {
            throw error;
        } catch (SecurityException exception) {
            throw BridgeError.of(BridgeError.Category.PERMISSION_DENIED);
        } catch (IllegalArgumentException exception) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        } catch (IllegalStateException exception) {
            throw BridgeError.of(BridgeError.Category.UNAVAILABLE);
        } finally {
            if (token != null) {
                permissionAuthority.revoke(token);
            }
        }
    }

    /// Runs one method synchronously on JavaFX, or immediately before toolkit startup.
    ///
    /// @param action Bridge handler action
    /// @return handler result
    private static BridgeValue runOnFxThreadOrNow(Callable<BridgeValue> action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return action.call();
        }

        FutureTask<BridgeValue> task = new FutureTask<>(action);
        try {
            Platform.runLater(task);
        } catch (IllegalStateException exception) {
            return action.call();
        }
        try {
            return task.get(FX_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            @Nullable Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw BridgeError.of(BridgeError.Category.INTERNAL);
        } catch (TimeoutException exception) {
            task.cancel(true);
            throw BridgeError.of(BridgeError.Category.UNAVAILABLE);
        }
    }

    /// Implements one registered Bridge operation.
    @FunctionalInterface
    @NotNullByDefault
    public interface Handler {
        /// Invokes one operation with its verified call identity and validated root input.
        ///
        /// @param invocation verified call identity and token
        /// @param input validated input value
        /// @return Bridge result matching the method descriptor
        BridgeValue invoke(Invocation invocation, BridgeValue input);
    }

    /// Executes one callable according to the JavaFX thread policy.
    @FunctionalInterface
    @NotNullByDefault
    interface FxExecutor {
        /// Executes and returns one handler result.
        ///
        /// @param action handler action
        /// @return handler result
        /// @throws Exception if dispatch or handler execution fails
        BridgeValue call(Callable<BridgeValue> action) throws Exception;
    }

    /// Carries one exact verified payload call and its short-lived authority token.
    ///
    /// @param context exact runtime payload context
    /// @param token verified call-scoped token
    @NotNullByDefault
    public record Invocation(RuntimePayloadContext context, PluginCapabilityToken token) {
        /// Validates non-null invocation components.
        public Invocation {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(token, "token");
        }

        /// Returns the canonical calling plugin ID.
        ///
        /// @return owner plugin ID
        public String ownerPluginId() {
            return context.artifactIdentity().getPluginId();
        }
    }

    /// Associates one stable method descriptor with its implementation.
    ///
    /// @param method method descriptor
    /// @param handler registered implementation
    @NotNullByDefault
    private record RegisteredMethod(BridgeMethod method, Handler handler) {
    }
}
