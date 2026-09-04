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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Correlates synchronous JVM retransformation requests with transformer outcomes that the JVM may suppress.
@NotNullByDefault
final class PluginPatchRetransformationMonitor {
    /// Active attempts keyed by the exact thread that initiated synchronous retransformation.
    private final Map<Thread, Attempt> attempts = new ConcurrentHashMap<>();

    /// Begins one current-thread retransformation attempt for an exact loaded class.
    ///
    /// @param targetClass exact class requested from Instrumentation
    /// @return active attempt that must be closed
    /// @throws IllegalStateException if the current thread already has an active attempt
    Attempt begin(Class<?> targetClass) {
        Class<?> target = Objects.requireNonNull(targetClass, "targetClass");
        Thread initiatingThread = Thread.currentThread();
        Attempt attempt = new Attempt(this, initiatingThread, target);
        @Nullable Attempt previous = attempts.putIfAbsent(initiatingThread, attempt);
        if (previous != null) {
            throw new IllegalStateException("Current thread already has an active Patch retransformation attempt");
        }
        return attempt;
    }

    /// Returns the active attempt for the exact class currently being redefined.
    ///
    /// @param targetClass exact class supplied to the transformer, or `null` during first definition
    /// @return active attempt, or `null` outside launcher-requested retransformation
    private @Nullable Attempt current(@Nullable Class<?> targetClass) {
        if (targetClass == null) {
            return null;
        }
        @Nullable Attempt attempt = attempts.get(Thread.currentThread());
        return attempt != null && attempt.targetClass == targetClass ? attempt : null;
    }

    /// Removes one exact completed attempt without disturbing a newer attempt.
    ///
    /// @param attempt completed attempt
    private void remove(Attempt attempt) {
        attempts.remove(attempt.initiatingThread, attempt);
    }

    /// Records the outcome of this transformer's invocation for one class definition.
    ///
    /// @param targetClass class currently being redefined, or `null`
    /// @param failure internal transformer failure, or `null` after successful processing
    void record(@Nullable Class<?> targetClass, @Nullable Throwable failure) {
        @Nullable Attempt attempt = current(targetClass);
        if (attempt == null) {
            return;
        }
        if (failure == null) {
            attempt.recordSuccess();
        } else {
            attempt.recordFailure(failure);
        }
    }

    /// One exact retransformation scope shared between the Instrumentation caller and transformer callback.
    @NotNullByDefault
    static final class Attempt implements AutoCloseable {
        /// Owning monitor used for exact removal.
        private final PluginPatchRetransformationMonitor monitor;

        /// Exact thread that initiated synchronous retransformation.
        private final Thread initiatingThread;

        /// Exact class requested from Instrumentation.
        private final Class<?> targetClass;

        /// Whether this transformer observed and processed the requested class.
        private final AtomicBoolean observed = new AtomicBoolean();

        /// First internal transformer failure suppressed by the JVM, or `null`.
        private final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();

        /// Creates one unpublished retransformation attempt.
        ///
        /// @param monitor owning monitor
        /// @param initiatingThread exact thread calling Instrumentation
        /// @param targetClass exact requested class
        private Attempt(
                PluginPatchRetransformationMonitor monitor,
                Thread initiatingThread,
                Class<?> targetClass
        ) {
            this.monitor = Objects.requireNonNull(monitor, "monitor");
            this.initiatingThread = Objects.requireNonNull(initiatingThread, "initiatingThread");
            this.targetClass = Objects.requireNonNull(targetClass, "targetClass");
        }

        /// Records successful transformer processing.
        private void recordSuccess() {
            observed.set(true);
        }

        /// Records the first internal transformer failure before publishing observation.
        ///
        /// @param throwable internal transformer failure
        private void recordFailure(Throwable throwable) {
            failure.compareAndSet(null, Objects.requireNonNull(throwable, "throwable"));
            observed.set(true);
        }

        /// Requires this transformer to have successfully processed the requested class.
        ///
        /// @throws PluginPatchFailure if the callback was absent or reported an internal failure
        void requireSuccess() throws PluginPatchFailure {
            if (!observed.get()) {
                throw new PluginPatchFailure(
                        PluginPatchFailure.Category.TRANSFORM_FAILURE,
                        "Aura Patch transformer did not process the requested target class: " + targetClass.getName()
                );
            }
            @Nullable Throwable transformerFailure = failure.get();
            if (transformerFailure != null) {
                throw new PluginPatchFailure(
                        PluginPatchFailure.Category.TRANSFORM_FAILURE,
                        "Aura Patch transformer rejected the requested target class: " + targetClass.getName(),
                        transformerFailure
                );
            }
        }

        /// Removes this attempt from transformer visibility.
        @Override
        public void close() {
            monitor.remove(this);
        }
    }
}
