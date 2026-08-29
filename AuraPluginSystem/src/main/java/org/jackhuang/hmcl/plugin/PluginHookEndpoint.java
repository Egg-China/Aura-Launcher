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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/// Invokes one runtime-neutral Hook endpoint through the immutable public event contract.
@FunctionalInterface
@NotNullByDefault
public interface PluginHookEndpoint {
    /// Invokes the endpoint.
    ///
    /// @param event immutable Hook event
    /// @return endpoint result
    /// @throws Exception if the endpoint transport or plugin callback fails
    @Nullable PluginHookResult invoke(PluginHookEvent event) throws Exception;

    /// Invokes the endpoint with the dispatcher's exact callback deadline.
    ///
    /// Java endpoints retain the source-compatible single-argument callback. Runtime endpoints override this
    /// overload so their Provider transport can apply the same deadline as the launcher dispatcher.
    ///
    /// @param event immutable Hook event
    /// @param timeout positive per-subscriber callback deadline
    /// @return endpoint result, or `null` when a malformed endpoint violates the contract
    /// @throws Exception if the endpoint transport or plugin callback fails
    default @Nullable PluginHookResult invoke(PluginHookEvent event, Duration timeout) throws Exception {
        return invoke(event);
    }

    /// Prepares one exact callback invocation before executor submission.
    ///
    /// The default wrapper preserves Java endpoint source and binary behavior while allowing the dispatcher to
    /// cancel a queued invocation before its callback starts.
    ///
    /// @param event immutable Hook event
    /// @return one exact prepared invocation
    default Invocation prepareInvocation(PluginHookEvent event) {
        PluginHookEvent immutableEvent = Objects.requireNonNull(event, "event");
        return new Invocation() {
            /// Whether cancellation won before or during invocation.
            private final AtomicBoolean cancelled = new AtomicBoolean();

            /// Invokes the existing endpoint unless cancellation already won.
            ///
            /// @param remainingTimeout positive remaining dispatcher budget
            /// @return endpoint result
            /// @throws Exception if the endpoint transport or callback fails
            @Override
            public @Nullable PluginHookResult invoke(Duration remainingTimeout) throws Exception {
                if (cancelled.get()) {
                    throw new CancellationException("Plugin Hook invocation was cancelled");
                }
                return PluginHookEndpoint.this.invoke(immutableEvent, remainingTimeout);
            }

            /// Prevents a queued default invocation from entering the endpoint.
            @Override
            public void cancel() {
                cancelled.set(true);
            }
        };
    }

    /// One exact callback invocation prepared before dispatcher executor submission.
    @NotNullByDefault
    interface Invocation {
        /// Invokes the endpoint with the remaining portion of the dispatcher's original callback budget.
        ///
        /// @param remainingTimeout positive remaining dispatcher budget
        /// @return endpoint result, or `null` when a malformed endpoint violates the contract
        /// @throws Exception if the endpoint transport or callback fails
        @Nullable PluginHookResult invoke(Duration remainingTimeout) throws Exception;

        /// Cancels this exact invocation and synchronously revokes any invocation-scoped authority.
        void cancel();
    }
}
