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
package org.jackhuang.hmcl.plugin.ui.frontend.process;

import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/// Handles the fixed launcher command surface exposed to an isolated native UI frontend.
@FunctionalInterface
@NotNullByDefault
public interface UiFrontendCommandHandler {
    /// Asynchronously handles one validated child command.
    ///
    /// The coordinator implementation must recheck its exact artifact, generation, selection, and grants for every
    /// invocation. This neutral transport layer does not perform those launcher-owned authorization checks.
    ///
    /// @param method one supported `core.*` method
    /// @param params token-free command parameters
    /// @return asynchronous response and optional launcher-owned action
    CompletionStage<Reply> handle(String method, BridgeValue params);

    /// Couples a token-free successful result with an optional action that runs only after its response is flushed.
    ///
    /// @param value token-free successful result
    /// @param afterResponseAction launcher-owned action, or `null` when no action follows the response
    @NotNullByDefault
    record Reply(BridgeValue value, @Nullable Runnable afterResponseAction) {
        /// Validates the immutable wire result.
        public Reply {
            Objects.requireNonNull(value, "value");
            new UiFrontendMessage.Result(1L, value);
        }

        /// Creates a result with no after-response action.
        ///
        /// @param value token-free result value
        /// @return immutable handler reply
        public static Reply result(BridgeValue value) {
            return new Reply(value, null);
        }
    }
}
