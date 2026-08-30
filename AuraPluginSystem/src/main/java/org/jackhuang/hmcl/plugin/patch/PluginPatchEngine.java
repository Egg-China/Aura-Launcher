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
package org.jackhuang.hmcl.plugin.patch;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jackhuang.hmcl.plugin.PluginPatchInvocation;
import org.jackhuang.hmcl.plugin.PluginPatchResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Owns immutable method plans and bounded fail-open Patch callback dispatch.
@NotNullByDefault
public final class PluginPatchEngine {
    /// Production callback timeout required by Patch ABI v1.
    private static final Duration DEFAULT_CALLBACK_TIMEOUT = Duration.ofMillis(500);

    /// Production aggregate callback deadline required by Patch ABI v1.
    private static final Duration DEFAULT_AGGREGATE_TIMEOUT = Duration.ofSeconds(2);

    /// Maximum callback nesting across all patched methods.
    private static final int MAX_RECURSION_DEPTH = 16;

    /// Maximum callback workers retained by the process-wide production executor.
    private static final int DEFAULT_WORKERS = MAX_RECURSION_DEPTH;

    /// Process-wide bounded daemon workers used by the production engine.
    private static final ExecutorService DEFAULT_EXECUTOR = new ThreadPoolExecutor(
            0,
            DEFAULT_WORKERS,
            30L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            daemonThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
    );

    /// Callback recursion state propagated through engine worker submissions.
    private static final ThreadLocal<DispatchContext> DISPATCH_CONTEXT =
            ThreadLocal.withInitial(DispatchContext::root);

    /// Validates launcher ownership and resolves exact bytecode descriptors.
    private final PluginPatchTargetPolicy targetPolicy;

    /// Exact launcher loader used to preserve JVM reference type identity.
    private final ClassLoader launcherClassLoader;

    /// Executes callback code outside the transformed method's target thread.
    private final ExecutorService callbackExecutor;

    /// Per-callback timeout in nanoseconds.
    private final long callbackTimeoutNanos;

    /// Aggregate method-dispatch callback budget in nanoseconds.
    private final long aggregateTimeoutNanos;

    /// Serializes registration mutation and copy-on-write publication.
    private final Object mutationLock = new Object();

    /// Stable exact method keys retained for collision detection and re-registration.
    private final Map<Long, String> methodKeysById = new HashMap<>();

    /// Resolved targets retained by stable method identity.
    private final Map<Long, PluginPatchTarget> targetsById = new HashMap<>();

    /// Mutable owner registrations guarded by [#mutationLock].
    private final Map<Long, List<PluginPatchRegistration>> registrationsByMethod = new HashMap<>();

    /// Monotonic engine-local registration identity guarded by [#mutationLock].
    private long nextRegistrationId = 1L;

    /// Immutable live method plans read without the mutation lock.
    private volatile @Unmodifiable Map<Long, MethodPlan> plansById = Map.of();

    /// Creates a production engine with the Patch ABI v1 deadlines and shared daemon workers.
    ///
    /// @param targetPolicy launcher target policy
    PluginPatchEngine(PluginPatchTargetPolicy targetPolicy) {
        this(
                targetPolicy,
                DEFAULT_EXECUTOR,
                DEFAULT_CALLBACK_TIMEOUT,
                DEFAULT_AGGREGATE_TIMEOUT
        );
    }

    /// Creates an engine with injectable workers and deadlines for focused verification.
    ///
    /// @param targetPolicy launcher target policy
    /// @param callbackExecutor callback executor
    /// @param callbackTimeout positive per-callback timeout
    /// @param aggregateTimeout positive aggregate dispatch timeout
    PluginPatchEngine(
            PluginPatchTargetPolicy targetPolicy,
            ExecutorService callbackExecutor,
            Duration callbackTimeout,
            Duration aggregateTimeout
    ) {
        this.targetPolicy = Objects.requireNonNull(targetPolicy, "targetPolicy");
        launcherClassLoader = targetPolicy.launcherClassLoader();
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        callbackTimeoutNanos = positiveNanos(callbackTimeout, "callback timeout");
        aggregateTimeoutNanos = positiveNanos(aggregateTimeout, "aggregate timeout");
    }

    /// Validates and publishes one runtime-neutral callback registration.
    ///
    /// @param artifactIdentity exact owning plugin artifact
    /// @param dependencyIds canonical dependency IDs used for ordering
    /// @param declaration authoritative schema-v5 declaration
    /// @param callback runtime-neutral callback endpoint
    /// @return active idempotent registration
    /// @throws PluginPatchFailure if target validation or replacement conflict prevents registration
    public PluginPatchRegistration register(
            PluginArtifactIdentity artifactIdentity,
            Set<String> dependencyIds,
            PluginPatchDeclaration declaration,
            PluginPatchCallback callback
    ) throws PluginPatchFailure {
        PluginArtifactIdentity identity = Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        @Unmodifiable Set<String> dependencies = copyDependencyIds(identity.getPluginId(), dependencyIds);
        PluginPatchDeclaration declarationValue = Objects.requireNonNull(declaration, "declaration");
        PluginPatchCallback callbackValue = Objects.requireNonNull(callback, "callback");
        PluginPatchTarget target = targetPolicy.resolve(PluginPatchMethod.from(declarationValue));
        PluginPatchMethod method = target.method();
        String methodKey = exactMethodKey(method);
        long methodId = stableMethodId(methodKey);

        synchronized (mutationLock) {
            requireStableMethodCollisionFree(methodId, methodKey);
            List<PluginPatchRegistration> current = registrationsByMethod.getOrDefault(methodId, List.of());
            requireNoReplacementConflict(identity, declarationValue, current, method);
            long registrationId = nextRegistrationIdentity();
            PluginPatchRegistration registration = new PluginPatchRegistration(
                    this,
                    registrationId,
                    methodId,
                    identity,
                    dependencies,
                    declarationValue,
                    method,
                    callbackValue
            );
            List<PluginPatchRegistration> candidate = new ArrayList<>(current);
            candidate.add(registration);
            MethodPlan candidatePlan = MethodPlan.create(method, target.access(), candidate);
            PluginPatchDispatcher.publish(methodId, this);
            methodKeysById.putIfAbsent(methodId, methodKey);
            targetsById.put(methodId, target);
            registrationsByMethod.put(methodId, candidate);
            publishPlan(methodId, candidatePlan);
            return registration;
        }
    }

    /// Returns an immutable snapshot of exact methods that currently have callbacks.
    ///
    /// @return methods keyed by stable dispatcher identity
    @Unmodifiable Map<Long, PluginPatchMethod> snapshotMethods() {
        Map<Long, PluginPatchMethod> snapshot = new HashMap<>();
        plansById.forEach((methodId, plan) -> snapshot.put(methodId, plan.method()));
        return Map.copyOf(snapshot);
    }

    /// Returns the retained validated target for a live or previously registered method.
    ///
    /// @param methodId stable method identity
    /// @return validated target, or `null` when the identity is unknown
    @Nullable PluginPatchTarget target(long methodId) {
        synchronized (mutationLock) {
            return targetsById.get(methodId);
        }
    }

    /// Removes one closed or failed registration and publishes the next immutable plan.
    ///
    /// @param registration registration to remove
    void remove(PluginPatchRegistration registration) {
        PluginPatchRegistration value = Objects.requireNonNull(registration, "registration");
        synchronized (mutationLock) {
            @Nullable List<PluginPatchRegistration> current = registrationsByMethod.get(value.methodId());
            if (current == null || !current.contains(value)) {
                return;
            }
            List<PluginPatchRegistration> remaining = new ArrayList<>(current);
            remaining.remove(value);
            remaining.removeIf(candidate -> !candidate.isActive());
            if (remaining.isEmpty()) {
                registrationsByMethod.remove(value.methodId());
                publishPlan(value.methodId(), null);
                PluginPatchDispatcher.remove(value.methodId(), this);
                return;
            }
            registrationsByMethod.put(value.methodId(), remaining);
            PluginPatchTarget target = Objects.requireNonNull(targetsById.get(value.methodId()));
            publishPlan(value.methodId(), MethodPlan.create(value.method(), target.access(), remaining));
        }
    }

    /// Runs `before` and optional `replace` callbacks for one stable method identity.
    ///
    /// @param methodId stable method identity
    /// @param receiver invocation receiver, or `null` for a static method
    /// @param arguments boxed invocation arguments
    /// @return immutable entry decision
    PluginPatchDispatchFrame enter(
            long methodId,
            @Nullable Object receiver,
            @Nullable Object[] arguments
    ) {
        @Nullable Object[] originalArguments = Objects.requireNonNull(arguments, "arguments").clone();
        @Nullable MethodPlan plan = plansById.get(methodId);
        if (plan == null || !validInvocation(
                plan, receiver, originalArguments, launcherClassLoader)) {
            return passThrough(originalArguments);
        }
        long deadlineNanos = deadlineAfter(aggregateTimeoutNanos);
        @Unmodifiable List<@Nullable Object> currentArguments = immutableValues(originalArguments);
        for (PluginPatchRegistration registration : plan.before()) {
            if (deadlineExpired(deadlineNanos) || Thread.currentThread().isInterrupted()) {
                break;
            }
            @Nullable PluginPatchResult result = invokeSafely(
                    registration,
                    PluginPatchInvocation.before(
                            registration.declaration(), receiver, currentArguments),
                    deadlineNanos
            );
            if (result == null) {
                continue;
            }
            try {
                currentArguments = applyBeforeResult(
                        plan, result, currentArguments, launcherClassLoader);
            } catch (PluginPatchFailure failure) {
                disable(registration, failure.category());
            }
        }

        boolean shouldReturn = false;
        @Nullable Object returnValue = null;
        @Nullable PluginPatchRegistration replacement = plan.replacement();
        if (replacement != null
                && !deadlineExpired(deadlineNanos)
                && !Thread.currentThread().isInterrupted()) {
            @Nullable PluginPatchResult result = invokeSafely(
                    replacement,
                    PluginPatchInvocation.replace(
                            replacement.declaration(), receiver, currentArguments),
                    deadlineNanos
            );
            if (result != null) {
                try {
                    if (result.action() == PluginPatchResult.Action.RETURN) {
                        returnValue = result.returnValue();
                        requireReturnValue(plan.method(), returnValue, launcherClassLoader);
                        shouldReturn = true;
                    } else if (result.action() != PluginPatchResult.Action.UNCHANGED) {
                        throw failure(PluginPatchFailure.Category.MALFORMED_VALUE,
                                "Replacement Patch callback returned an unsupported action");
                    }
                } catch (PluginPatchFailure failure) {
                    disable(replacement, failure.category());
                    returnValue = null;
                    shouldReturn = false;
                }
            }
        }
        return new PluginPatchDispatchFrame(
                this,
                plan,
                receiver,
                currentArguments.toArray(),
                shouldReturn,
                returnValue,
                deadlineNanos
        );
    }

    /// Runs reverse-ordered `after` callbacks for a normal method result.
    ///
    /// @param frame immutable method-entry frame
    /// @param result current normal result
    /// @return validated final nullable result
    @Nullable Object finish(PluginPatchDispatchFrame frame, @Nullable Object result) {
        PluginPatchDispatchFrame frameValue = Objects.requireNonNull(frame, "frame");
        @Nullable MethodPlan plan = frameValue.plan();
        if (frameValue.engine() != this
                || plan == null
                || !isReturnValue(plan.method(), result, launcherClassLoader)) {
            return result;
        }
        @Nullable Object currentResult = result;
        @Unmodifiable List<@Nullable Object> arguments = immutableValues(frameValue.arguments());
        for (PluginPatchRegistration registration : plan.after()) {
            if (deadlineExpired(frameValue.deadlineNanos()) || Thread.currentThread().isInterrupted()) {
                break;
            }
            @Nullable PluginPatchResult callbackResult = invokeSafely(
                    registration,
                    PluginPatchInvocation.after(
                            registration.declaration(), frameValue.receiver(), arguments, currentResult),
                    frameValue.deadlineNanos()
            );
            if (callbackResult == null) {
                continue;
            }
            try {
                if (callbackResult.action() == PluginPatchResult.Action.RETURN) {
                    @Nullable Object candidate = callbackResult.returnValue();
                    requireReturnValue(plan.method(), candidate, launcherClassLoader);
                    currentResult = candidate;
                } else if (callbackResult.action() != PluginPatchResult.Action.UNCHANGED) {
                    throw failure(PluginPatchFailure.Category.MALFORMED_VALUE,
                            "After Patch callback returned an unsupported action");
                }
            } catch (PluginPatchFailure failure) {
                disable(registration, failure.category());
            }
        }
        return currentResult;
    }

    /// Creates one pass-through frame for a missing, stale, or malformed dispatcher call.
    ///
    /// @param arguments original boxed arguments
    /// @return pass-through frame
    static PluginPatchDispatchFrame passThrough(@Nullable Object[] arguments) {
        return new PluginPatchDispatchFrame(
                null,
                null,
                null,
                Objects.requireNonNull(arguments, "arguments"),
                false,
                null,
                0L
        );
    }

    /// Invokes one callback under recursion and deadline controls, isolating every failure.
    ///
    /// @param registration candidate callback registration
    /// @param invocation immutable callback input
    /// @param aggregateDeadlineNanos absolute aggregate deadline
    /// @return callback result, or `null` when skipped or disabled
    private @Nullable PluginPatchResult invokeSafely(
            PluginPatchRegistration registration,
            PluginPatchInvocation invocation,
            long aggregateDeadlineNanos
    ) {
        if (!registration.isActive()) {
            return null;
        }
        DispatchContext parentContext = DISPATCH_CONTEXT.get();
        if (parentContext.contains(registration.registrationId())) {
            return null;
        }
        if (parentContext.depth() >= MAX_RECURSION_DEPTH) {
            disable(registration, PluginPatchFailure.Category.DEPTH_LIMIT);
            return null;
        }
        long remainingNanos = Math.min(
                callbackTimeoutNanos,
                remainingNanos(aggregateDeadlineNanos)
        );
        if (remainingNanos <= 0L) {
            return null;
        }
        DispatchContext callbackContext = parentContext.enter(registration.registrationId());
        Future<@Nullable PluginPatchResult> future;
        try {
            future = callbackExecutor.submit(() -> invokeInContext(
                    registration, invocation, callbackContext));
        } catch (RejectedExecutionException exception) {
            disable(registration, PluginPatchFailure.Category.CALLBACK_EXCEPTION);
            return null;
        }
        try {
            @Nullable PluginPatchResult result = future.get(remainingNanos, TimeUnit.NANOSECONDS);
            if (result == null) {
                disable(registration, PluginPatchFailure.Category.CALLBACK_EXCEPTION);
                return null;
            }
            return registration.isActive() ? result : null;
        } catch (TimeoutException exception) {
            future.cancel(true);
            disable(registration, PluginPatchFailure.Category.TIMEOUT);
            return null;
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            disable(registration, PluginPatchFailure.Category.CALLBACK_EXCEPTION);
            return null;
        } catch (CancellationException exception) {
            disable(registration, PluginPatchFailure.Category.CALLBACK_EXCEPTION);
            return null;
        } catch (ExecutionException exception) {
            @Nullable Throwable cause = exception.getCause();
            if (cause instanceof PluginPatchFailure patchFailure) {
                disable(registration, patchFailure.category());
            } else {
                disable(registration, PluginPatchFailure.Category.CALLBACK_EXCEPTION);
            }
            return null;
        }
    }

    /// Executes callback code with one inherited immutable recursion context.
    ///
    /// @param registration callback registration
    /// @param invocation callback input
    /// @param callbackContext inherited recursion context
    /// @return callback result, possibly `null`
    /// @throws Exception if callback code fails
    private static @Nullable PluginPatchResult invokeInContext(
            PluginPatchRegistration registration,
            PluginPatchInvocation invocation,
            DispatchContext callbackContext
    ) throws Exception {
        DispatchContext previous = DISPATCH_CONTEXT.get();
        DISPATCH_CONTEXT.set(callbackContext);
        try {
            return registration.callback().invoke(invocation);
        } finally {
            DISPATCH_CONTEXT.set(previous);
        }
    }

    /// Applies one successful `before` result transactionally.
    ///
    /// @param plan immutable method plan
    /// @param result callback result
    /// @param currentArguments current validated arguments
    /// @param launcherClassLoader exact launcher class loader
    /// @return committed immutable argument list
    /// @throws PluginPatchFailure if action, count, or types are invalid
    private static @Unmodifiable List<@Nullable Object> applyBeforeResult(
            MethodPlan plan,
            PluginPatchResult result,
            List<@Nullable Object> currentArguments,
            ClassLoader launcherClassLoader
    ) throws PluginPatchFailure {
        if (result.action() == PluginPatchResult.Action.UNCHANGED) {
            return immutableValues(currentArguments);
        }
        if (result.action() != PluginPatchResult.Action.ARGUMENTS) {
            throw failure(PluginPatchFailure.Category.MALFORMED_VALUE,
                    "Before Patch callback returned an unsupported action");
        }
        @Unmodifiable List<@Nullable Object> candidate = result.arguments();
        requireArguments(plan.method(), candidate, launcherClassLoader);
        return immutableValues(candidate);
    }

    /// Atomically disables one registration, republishes its method plan, and records a redacted diagnostic.
    ///
    /// @param registration failed registration
    /// @param category stable failure category
    private void disable(
            PluginPatchRegistration registration,
            PluginPatchFailure.Category category
    ) {
        if (!registration.markFailed(category)) {
            return;
        }
        remove(registration);
        PluginPatchDeclaration declaration = registration.declaration();
        LOG.warning("Disabled Aura plugin Patch callback: plugin="
                + registration.artifactIdentity().getPluginId()
                + ", target=" + declaration.getTarget()
                + ", method=" + declaration.getMethod()
                + registration.method().descriptor()
                + ", type=" + declaration.getType()
                + ", failure=" + category);
    }

    /// Publishes one immutable copy-on-write plan map.
    ///
    /// @param methodId stable method identity
    /// @param plan replacement plan, or `null` to remove the method
    private void publishPlan(long methodId, @Nullable MethodPlan plan) {
        Map<Long, MethodPlan> updated = new HashMap<>(plansById);
        if (plan == null) {
            updated.remove(methodId);
        } else {
            updated.put(methodId, plan);
        }
        plansById = Map.copyOf(updated);
    }

    /// Validates one dispatcher call's receiver and complete original arguments.
    ///
    /// @param plan exact method plan
    /// @param receiver invocation receiver, or `null`
    /// @param arguments boxed arguments
    /// @param launcherClassLoader exact launcher class loader
    /// @return whether the dispatcher call matches the bytecode descriptor
    private static boolean validInvocation(
            MethodPlan plan,
            @Nullable Object receiver,
            @Nullable Object[] arguments,
            ClassLoader launcherClassLoader
    ) {
        boolean staticMethod = (plan.access() & Opcodes.ACC_STATIC) != 0;
        if (staticMethod ? receiver != null : receiver == null
                || receiver != null && !isValueCompatible(
                        Type.getObjectType(plan.method().target().replace('.', '/')),
                        receiver,
                        launcherClassLoader
                )) {
            return false;
        }
        return argumentsMatch(plan.method(), Arrays.asList(arguments), launcherClassLoader);
    }

    /// Requires complete replacement arguments to match the exact JVM descriptor.
    ///
    /// @param method exact resolved method
    /// @param arguments candidate arguments
    /// @param launcherClassLoader exact launcher class loader
    /// @throws PluginPatchFailure if count or element types differ
    private static void requireArguments(
            PluginPatchMethod method,
            List<@Nullable Object> arguments,
            ClassLoader launcherClassLoader
    ) throws PluginPatchFailure {
        if (!argumentsMatch(method, arguments, launcherClassLoader)) {
            throw failure(PluginPatchFailure.Category.TYPE_MISMATCH,
                    "Patch callback arguments do not match the target descriptor");
        }
    }

    /// Returns whether complete arguments match the exact JVM descriptor.
    ///
    /// @param method exact resolved method
    /// @param arguments candidate arguments
    /// @param launcherClassLoader exact launcher class loader
    /// @return whether count and element types match
    private static boolean argumentsMatch(
            PluginPatchMethod method,
            List<@Nullable Object> arguments,
            ClassLoader launcherClassLoader
    ) {
        Type[] expected = Type.getArgumentTypes(method.descriptor());
        if (expected.length != arguments.size()) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (!isValueCompatible(expected[index], arguments.get(index), launcherClassLoader)) {
                return false;
            }
        }
        return true;
    }

    /// Requires one callback result to match the exact JVM return descriptor.
    ///
    /// @param method exact resolved method
    /// @param value candidate nullable value
    /// @param launcherClassLoader exact launcher class loader
    /// @throws PluginPatchFailure if the method is void or the value type differs
    private static void requireReturnValue(
            PluginPatchMethod method,
            @Nullable Object value,
            ClassLoader launcherClassLoader
    ) throws PluginPatchFailure {
        Type returnType = Type.getReturnType(method.descriptor());
        if (returnType.getSort() == Type.VOID
                || !isValueCompatible(returnType, value, launcherClassLoader)) {
            throw failure(PluginPatchFailure.Category.TYPE_MISMATCH,
                    "Patch callback return value does not match the target descriptor");
        }
    }

    /// Returns whether an original normal result matches the exact JVM return descriptor.
    ///
    /// @param method exact resolved method
    /// @param value nullable normal result
    /// @param launcherClassLoader exact launcher class loader
    /// @return whether the value is descriptor-compatible
    private static boolean isReturnValue(
            PluginPatchMethod method,
            @Nullable Object value,
            ClassLoader launcherClassLoader
    ) {
        Type returnType = Type.getReturnType(method.descriptor());
        return returnType.getSort() == Type.VOID
                ? value == null
                : isValueCompatible(returnType, value, launcherClassLoader);
    }

    /// Returns whether one boxed or reference value matches an ASM field type.
    ///
    /// @param expected expected ASM type
    /// @param value nullable candidate value
    /// @param launcherClassLoader exact launcher class loader
    /// @return whether the value is assignable without class initialization
    private static boolean isValueCompatible(
            Type expected,
            @Nullable Object value,
            ClassLoader launcherClassLoader
    ) {
        return switch (expected.getSort()) {
            case Type.BOOLEAN -> value instanceof Boolean;
            case Type.BYTE -> value instanceof Byte;
            case Type.CHAR -> value instanceof Character;
            case Type.SHORT -> value instanceof Short;
            case Type.INT -> value instanceof Integer;
            case Type.LONG -> value instanceof Long;
            case Type.FLOAT -> value instanceof Float;
            case Type.DOUBLE -> value instanceof Double;
            case Type.OBJECT, Type.ARRAY -> value == null
                    || isReferenceInstance(expected, value, launcherClassLoader);
            case Type.VOID -> value == null;
            default -> false;
        };
    }

    /// Tests reference assignability against the exact launcher-loader descriptor type.
    ///
    /// @param expected expected reference or array type
    /// @param value non-null candidate value
    /// @param launcherClassLoader exact launcher class loader
    /// @return whether the exact descriptor class accepts the value
    private static boolean isReferenceInstance(
            Type expected,
            Object value,
            ClassLoader launcherClassLoader
    ) {
        String className = expected.getSort() == Type.ARRAY
                ? expected.getDescriptor().replace('/', '.')
                : expected.getClassName();
        try {
            return Class.forName(className, false, launcherClassLoader).isInstance(value);
        } catch (ClassNotFoundException | LinkageError exception) {
            return false;
        }
    }

    /// Copies nullable values into an immutable list without using `List.copyOf`, which rejects null elements.
    ///
    /// @param values source values
    /// @return immutable nullable-element list
    private static @Unmodifiable List<@Nullable Object> immutableValues(List<@Nullable Object> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /// Copies a nullable-element array into an immutable list.
    ///
    /// @param values source values
    /// @return immutable nullable-element list
    private static @Unmodifiable List<@Nullable Object> immutableValues(@Nullable Object[] values) {
        return immutableValues(Arrays.asList(values));
    }

    /// Copies and validates canonical dependency IDs.
    ///
    /// @param pluginId owning plugin ID
    /// @param dependencyIds candidate dependency IDs
    /// @return immutable validated dependency IDs
    private static @Unmodifiable Set<String> copyDependencyIds(
            String pluginId,
            Set<String> dependencyIds
    ) {
        @Unmodifiable Set<String> copy = Set.copyOf(
                Objects.requireNonNull(dependencyIds, "dependencyIds"));
        for (String dependencyId : copy) {
            if (!PluginManifest.isValidId(dependencyId) || pluginId.equals(dependencyId)) {
                throw new IllegalArgumentException("Invalid Patch dependency ID: " + dependencyId);
            }
        }
        return copy;
    }

    /// Rejects a second live replacement for one exact method.
    ///
    /// @param identity candidate owning artifact
    /// @param declaration candidate declaration
    /// @param current current method registrations
    /// @param method exact method identity
    /// @throws PluginPatchFailure if another replacement remains active
    private static void requireNoReplacementConflict(
            PluginArtifactIdentity identity,
            PluginPatchDeclaration declaration,
            List<PluginPatchRegistration> current,
            PluginPatchMethod method
    ) throws PluginPatchFailure {
        if (declaration.getType() != PluginPatchDeclaration.PatchType.REPLACE) {
            return;
        }
        for (PluginPatchRegistration registration : current) {
            if (registration.isActive()
                    && registration.declaration().getType() == PluginPatchDeclaration.PatchType.REPLACE) {
                throw new PluginPatchFailure(
                        PluginPatchFailure.Category.REPLACEMENT_CONFLICT,
                        "Patch replacement conflict for " + method.target() + "." + method.name()
                                + ": " + registration.artifactIdentity().getPluginId()
                                + " and " + identity.getPluginId()
                );
            }
        }
    }

    /// Returns one collision-detectable exact method key.
    ///
    /// @param method exact resolved method
    /// @return stable method key
    private static String exactMethodKey(PluginPatchMethod method) {
        return method.target() + '\0' + method.name() + '\0' + method.descriptor();
    }

    /// Computes one deterministic 64-bit FNV-1a method identity.
    ///
    /// @param methodKey exact method key
    /// @return non-zero stable method identity
    private static long stableMethodId(String methodKey) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : methodKey.getBytes(StandardCharsets.UTF_8)) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash == 0L ? 1L : hash;
    }

    /// Verifies that a deterministic method ID has not collided with another exact method.
    ///
    /// @param methodId stable method identity
    /// @param methodKey exact method key
    private void requireStableMethodCollisionFree(long methodId, String methodKey) {
        @Nullable String existing = methodKeysById.get(methodId);
        if (existing != null && !existing.equals(methodKey)) {
            throw new IllegalStateException("Stable Patch method identity collision: "
                    + Long.toUnsignedString(methodId));
        }
    }

    /// Allocates one non-zero engine-local registration identity.
    ///
    /// @return registration identity
    private long nextRegistrationIdentity() {
        long value = nextRegistrationId++;
        if (value == 0L || nextRegistrationId == 0L) {
            throw new IllegalStateException("Patch registration identity space exhausted");
        }
        return value;
    }

    /// Converts one positive duration to nanoseconds with a stable validation failure.
    ///
    /// @param duration candidate duration
    /// @param label diagnostic label
    /// @return positive nanoseconds
    private static long positiveNanos(Duration duration, String label) {
        Duration value = Objects.requireNonNull(duration, label);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Patch " + label + " must be positive");
        }
        try {
            return value.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Patch " + label + " is too large", exception);
        }
    }

    /// Creates an overflow-safe monotonic deadline after one positive budget.
    ///
    /// @param budgetNanos positive budget
    /// @return absolute monotonic deadline
    private static long deadlineAfter(long budgetNanos) {
        long now = System.nanoTime();
        long deadline = now + budgetNanos;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    /// Returns the positive unspent duration before one absolute deadline.
    ///
    /// @param deadlineNanos absolute monotonic deadline
    /// @return remaining nanoseconds clamped to zero
    private static long remainingNanos(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        return Math.max(0L, remaining);
    }

    /// Returns whether one aggregate deadline has elapsed.
    ///
    /// @param deadlineNanos absolute monotonic deadline
    /// @return whether no callback time remains
    private static boolean deadlineExpired(long deadlineNanos) {
        return remainingNanos(deadlineNanos) <= 0L;
    }

    /// Creates one stable failure without callback-controlled data.
    ///
    /// @param category stable category
    /// @param message redacted engine diagnostic
    /// @return categorized failure
    private static PluginPatchFailure failure(
            PluginPatchFailure.Category category,
            String message
    ) {
        return new PluginPatchFailure(category, message);
    }

    /// Creates named daemon callback workers for the process-wide engine.
    ///
    /// @return daemon thread factory
    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "Aura-Plugin-Patch-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /// Immutable exact-method callback plan published through copy-on-write snapshots.
    @NotNullByDefault
    static final class MethodPlan {
        /// Exact resolved JVM method identity.
        private final PluginPatchMethod method;

        /// JVM access flags used for receiver validation and transformation.
        private final int access;

        /// Dependency-first immutable `before` callbacks.
        private final @Unmodifiable List<PluginPatchRegistration> before;

        /// Reverse dependency-order immutable `after` callbacks.
        private final @Unmodifiable List<PluginPatchRegistration> after;

        /// Sole replacement callback, or `null`.
        private final @Nullable PluginPatchRegistration replacement;

        /// Creates one fully ordered immutable method plan.
        ///
        /// @param method exact method
        /// @param access JVM access flags
        /// @param before dependency-first callbacks
        /// @param after reverse dependency-order callbacks
        /// @param replacement sole replacement, or `null`
        private MethodPlan(
                PluginPatchMethod method,
                int access,
                List<PluginPatchRegistration> before,
                List<PluginPatchRegistration> after,
                @Nullable PluginPatchRegistration replacement
        ) {
            this.method = Objects.requireNonNull(method, "method");
            this.access = access;
            this.before = List.copyOf(before);
            this.after = List.copyOf(after);
            this.replacement = replacement;
        }

        /// Builds one method plan from active owner registrations.
        ///
        /// @param method exact method
        /// @param access JVM access flags
        /// @param registrations candidate registrations
        /// @return immutable ordered plan
        private static MethodPlan create(
                PluginPatchMethod method,
                int access,
                List<PluginPatchRegistration> registrations
        ) {
            List<PluginPatchRegistration> before = registrations.stream()
                    .filter(PluginPatchRegistration::isActive)
                    .filter(registration -> registration.declaration().getType()
                            == PluginPatchDeclaration.PatchType.BEFORE)
                    .toList();
            List<PluginPatchRegistration> after = registrations.stream()
                    .filter(PluginPatchRegistration::isActive)
                    .filter(registration -> registration.declaration().getType()
                            == PluginPatchDeclaration.PatchType.AFTER)
                    .toList();
            List<PluginPatchRegistration> replacements = registrations.stream()
                    .filter(PluginPatchRegistration::isActive)
                    .filter(registration -> registration.declaration().getType()
                            == PluginPatchDeclaration.PatchType.REPLACE)
                    .toList();
            if (replacements.size() > 1) {
                throw new IllegalStateException("Method plan contains multiple replacements");
            }
            List<PluginPatchRegistration> orderedBefore = dependencyOrder(before);
            List<PluginPatchRegistration> orderedAfter = dependencyOrder(after);
            Collections.reverse(orderedAfter);
            return new MethodPlan(
                    method,
                    access,
                    orderedBefore,
                    orderedAfter,
                    replacements.isEmpty() ? null : replacements.get(0)
            );
        }

        /// Returns the exact resolved method.
        ///
        /// @return method identity
        PluginPatchMethod method() {
            return method;
        }

        /// Returns the JVM access flags.
        ///
        /// @return access flags
        int access() {
            return access;
        }

        /// Returns dependency-first `before` callbacks.
        ///
        /// @return immutable callbacks
        @Unmodifiable List<PluginPatchRegistration> before() {
            return before;
        }

        /// Returns reverse dependency-order `after` callbacks.
        ///
        /// @return immutable callbacks
        @Unmodifiable List<PluginPatchRegistration> after() {
            return after;
        }

        /// Returns the sole replacement callback.
        ///
        /// @return replacement, or `null`
        @Nullable PluginPatchRegistration replacement() {
            return replacement;
        }

        /// Orders one callback phase by dependency topology and canonical plugin ID.
        ///
        /// Dependencies absent from this exact method phase do not form edges.
        ///
        /// @param registrations unordered active callbacks
        /// @return dependency-first mutable list
        private static List<PluginPatchRegistration> dependencyOrder(
                List<PluginPatchRegistration> registrations
        ) {
            Map<String, PluginPatchRegistration> byPluginId = new HashMap<>();
            Map<String, Integer> incomingEdges = new HashMap<>();
            Map<String, List<String>> dependentsById = new HashMap<>();
            for (PluginPatchRegistration registration : registrations) {
                String pluginId = registration.artifactIdentity().getPluginId();
                if (byPluginId.put(pluginId, registration) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate Patch callback phase for plugin: " + pluginId);
                }
                incomingEdges.put(pluginId, 0);
            }
            for (PluginPatchRegistration registration : registrations) {
                String pluginId = registration.artifactIdentity().getPluginId();
                for (String dependencyId : registration.dependencyIds()) {
                    if (!byPluginId.containsKey(dependencyId)) {
                        continue;
                    }
                    incomingEdges.merge(pluginId, 1, Integer::sum);
                    dependentsById.computeIfAbsent(dependencyId, ignored -> new ArrayList<>())
                            .add(pluginId);
                }
            }
            PriorityQueue<String> ready = new PriorityQueue<>();
            incomingEdges.forEach((pluginId, incoming) -> {
                if (incoming == 0) {
                    ready.add(pluginId);
                }
            });
            List<PluginPatchRegistration> ordered = new ArrayList<>(registrations.size());
            while (!ready.isEmpty()) {
                String pluginId = ready.remove();
                ordered.add(Objects.requireNonNull(byPluginId.get(pluginId)));
                for (String dependentId : dependentsById.getOrDefault(pluginId, List.of())) {
                    int remaining = incomingEdges.merge(dependentId, -1, Integer::sum);
                    if (remaining == 0) {
                        ready.add(dependentId);
                    }
                }
            }
            if (ordered.size() != registrations.size()) {
                throw new IllegalArgumentException("Patch callback dependency graph contains a cycle");
            }
            return ordered;
        }
    }

    /// Immutable callback nesting state inherited by child worker submissions.
    @NotNullByDefault
    private static final class DispatchContext {
        /// Number of currently nested callback invocations.
        private final int depth;

        /// Active engine-local registration identities.
        private final @Unmodifiable Set<Long> activeRegistrationIds;

        /// Creates one immutable callback nesting context.
        ///
        /// @param depth callback nesting depth
        /// @param activeRegistrationIds active registration identities
        private DispatchContext(int depth, Set<Long> activeRegistrationIds) {
            this.depth = depth;
            this.activeRegistrationIds = Set.copyOf(activeRegistrationIds);
        }

        /// Creates the empty thread-local root context.
        ///
        /// @return root context
        private static DispatchContext root() {
            return new DispatchContext(0, Set.of());
        }

        /// Returns callback nesting depth.
        ///
        /// @return nesting depth
        private int depth() {
            return depth;
        }

        /// Returns whether one registration is already active in this callback chain.
        ///
        /// @param registrationId engine-local registration identity
        /// @return whether the registration must be skipped
        private boolean contains(long registrationId) {
            return activeRegistrationIds.contains(registrationId);
        }

        /// Returns a child context containing one newly active registration.
        ///
        /// @param registrationId newly active registration identity
        /// @return immutable child context
        private DispatchContext enter(long registrationId) {
            Set<Long> active = new HashSet<>(activeRegistrationIds);
            active.add(registrationId);
            return new DispatchContext(depth + 1, active);
        }
    }
}
