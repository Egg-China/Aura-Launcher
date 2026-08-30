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
import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;

/// Idempotent ownership handle for one exact active Patch callback.
@NotNullByDefault
public final class PluginPatchRegistration implements AutoCloseable {
    /// Owning engine used to remove this registration from immutable method plans.
    private final PluginPatchEngine engine;

    /// Engine-local identity used for recursion suppression.
    private final long registrationId;

    /// Stable exact-method dispatcher identity.
    private final long methodId;

    /// Exact immutable plugin artifact owning the callback.
    private final PluginArtifactIdentity artifactIdentity;

    /// Immutable declared dependency IDs used for deterministic ordering.
    private final @Unmodifiable Set<String> dependencyIds;

    /// Authoritative schema-v5 declaration.
    private final PluginPatchDeclaration declaration;

    /// Exact resolved JVM method identity.
    private final PluginPatchMethod method;

    /// Runtime-neutral callback endpoint.
    private final PluginPatchCallback callback;

    /// Current lifecycle state guarded by this registration's monitor.
    private State state = State.ACTIVE;

    /// Stable failure category retained after callback disablement, or `null`.
    private @Nullable PluginPatchFailure.Category failureCategory;

    /// Submitted callback tasks cancelled when this registration leaves the active state.
    private final Set<Future<?>> callbackTasks = new HashSet<>();

    /// Creates one active registration owned by an engine.
    ///
    /// @param engine owning engine
    /// @param registrationId engine-local registration identity
    /// @param methodId stable exact-method identity
    /// @param artifactIdentity exact owning artifact
    /// @param dependencyIds immutable dependency IDs
    /// @param declaration authoritative declaration
    /// @param method exact resolved method
    /// @param callback callback endpoint
    PluginPatchRegistration(
            PluginPatchEngine engine,
            long registrationId,
            long methodId,
            PluginArtifactIdentity artifactIdentity,
            Set<String> dependencyIds,
            PluginPatchDeclaration declaration,
            PluginPatchMethod method,
            PluginPatchCallback callback
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.registrationId = registrationId;
        this.methodId = methodId;
        this.artifactIdentity = Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        this.dependencyIds = Set.copyOf(dependencyIds);
        this.declaration = Objects.requireNonNull(declaration, "declaration");
        this.method = Objects.requireNonNull(method, "method");
        this.callback = Objects.requireNonNull(callback, "callback");
    }

    /// Returns the stable exact-method dispatcher identity.
    ///
    /// @return method identity
    public long methodId() {
        return methodId;
    }

    /// Returns the exact owning plugin artifact.
    ///
    /// @return owning artifact identity
    public PluginArtifactIdentity artifactIdentity() {
        return artifactIdentity;
    }

    /// Returns the immutable dependency IDs captured at registration.
    ///
    /// @return dependency IDs
    public @Unmodifiable Set<String> dependencyIds() {
        return dependencyIds;
    }

    /// Returns the authoritative schema-v5 declaration.
    ///
    /// @return Patch declaration
    public PluginPatchDeclaration declaration() {
        return declaration;
    }

    /// Returns whether the callback remains eligible for new invocations.
    ///
    /// @return whether this registration is active
    public synchronized boolean isActive() {
        return state == State.ACTIVE;
    }

    /// Returns whether the owner explicitly closed this registration.
    ///
    /// @return whether this registration is closed
    public synchronized boolean isClosed() {
        return state == State.CLOSED;
    }

    /// Returns the callback failure that disabled this registration.
    ///
    /// @return stable category, or `null` when no callback failure was recorded
    public synchronized @Nullable PluginPatchFailure.Category failureCategory() {
        return failureCategory;
    }

    /// Returns the engine-local registration identity.
    ///
    /// @return registration identity
    long registrationId() {
        return registrationId;
    }

    /// Returns the exact resolved JVM method.
    ///
    /// @return resolved method
    PluginPatchMethod method() {
        return method;
    }

    /// Returns the runtime-neutral callback endpoint.
    ///
    /// @return callback endpoint
    PluginPatchCallback callback() {
        return callback;
    }

    /// Retains one submitted callback task while this registration remains active.
    ///
    /// A task submitted concurrently with closure is cancelled before this method returns.
    ///
    /// @param task submitted callback task
    /// @return whether the active registration retained the task
    boolean retainCallbackTask(Future<?> task) {
        Future<?> value = Objects.requireNonNull(task, "task");
        synchronized (this) {
            if (state == State.ACTIVE) {
                callbackTasks.add(value);
                return true;
            }
        }
        value.cancel(true);
        return false;
    }

    /// Releases one completed or cancelled callback task from registration ownership.
    ///
    /// @param task callback task no longer executing for this registration
    synchronized void releaseCallbackTask(Future<?> task) {
        callbackTasks.remove(Objects.requireNonNull(task, "task"));
    }

    /// Atomically records one callback failure while the registration is active.
    ///
    /// @param category stable failure category
    /// @return whether this call changed active state
    boolean markFailed(PluginPatchFailure.Category category) {
        @Unmodifiable List<Future<?>> tasks;
        synchronized (this) {
            if (state != State.ACTIVE) {
                return false;
            }
            failureCategory = Objects.requireNonNull(category, "category");
            state = State.FAILED;
            tasks = drainCallbackTasks();
        }
        cancelCallbackTasks(tasks);
        return true;
    }

    /// Atomically closes this registration before asking the engine to publish a new plan.
    @Override
    public void close() {
        boolean changed;
        @Unmodifiable List<Future<?>> tasks;
        synchronized (this) {
            changed = state != State.CLOSED;
            state = State.CLOSED;
            tasks = drainCallbackTasks();
        }
        cancelCallbackTasks(tasks);
        if (changed) {
            engine.remove(this);
        }
    }

    /// Removes every retained callback task while this registration's monitor is held.
    ///
    /// @return immutable task snapshot for cancellation outside the monitor
    private @Unmodifiable List<Future<?>> drainCallbackTasks() {
        @Unmodifiable List<Future<?>> tasks = List.copyOf(callbackTasks);
        callbackTasks.clear();
        return tasks;
    }

    /// Cancels every callback task without waiting for plugin-controlled code to exit.
    ///
    /// @param tasks callback tasks owned by a completed state transition
    private static void cancelCallbackTasks(@Unmodifiable List<Future<?>> tasks) {
        tasks.forEach(task -> task.cancel(true));
    }

    /// Internal registration lifecycle states.
    @NotNullByDefault
    private enum State {
        /// Callback remains eligible.
        ACTIVE,

        /// Callback failed and has been removed from live plans.
        FAILED,

        /// Owner closed the registration.
        CLOSED
    }
}
