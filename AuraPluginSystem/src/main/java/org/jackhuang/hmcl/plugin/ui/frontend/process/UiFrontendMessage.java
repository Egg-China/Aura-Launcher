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

import java.util.Map;
import java.util.Objects;

/// Models one token-free message exchanged with an isolated Aura UI frontend.
@NotNullByDefault
public sealed interface UiFrontendMessage permits UiFrontendMessage.Request, UiFrontendMessage.Result,
        UiFrontendMessage.Error {
    /// Returns the positive direction-scoped request identifier.
    ///
    /// @return request identifier
    long requestId();

    /// Carries one UI method invocation with a token-free structured parameter value.
    ///
    /// @param requestId positive direction-scoped request identifier
    /// @param method non-null UI method text
    /// @param params token-free parameter value
    @NotNullByDefault
    record Request(long requestId, String method, BridgeValue params) implements UiFrontendMessage {
        /// Validates the locally constructed request before any wire encoding occurs.
        public Request {
            requirePositiveRequestId(requestId);
            requireText(method, "method");
            requireTokenFree(params, "params");
        }
    }

    /// Carries one token-free successful reply to a preceding UI request.
    ///
    /// @param requestId positive request identifier preserved from the request
    /// @param value token-free result value
    @NotNullByDefault
    record Result(long requestId, BridgeValue value) implements UiFrontendMessage {
        /// Validates the locally constructed result before any wire encoding occurs.
        public Result {
            requirePositiveRequestId(requestId);
            requireTokenFree(value, "value");
        }
    }

    /// Carries a redacted typed failure reply with no stacktrace or opaque object.
    ///
    /// @param requestId positive request identifier preserved from the request
    /// @param code non-null error code text
    /// @param message non-null diagnostic text
    @NotNullByDefault
    record Error(long requestId, String code, String message) implements UiFrontendMessage {
        /// Validates the locally constructed error before any wire encoding occurs.
        public Error {
            requirePositiveRequestId(requestId);
            requireText(code, "code");
            requireText(message, "message");
        }
    }

    /// Requires one positive direction-scoped request ID.
    ///
    /// @param requestId candidate request identifier
    private static void requirePositiveRequestId(long requestId) {
        if (requestId <= 0L) {
            throw new IllegalArgumentException("UI request ID must be positive");
        }
    }

    /// Requires one non-null text field while deferring policy interpretation to the session layer.
    ///
    /// @param value candidate text
    /// @param field field label for a local failure
    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
    }

    /// Rejects every recursively embedded Bridge handle from this UI protocol.
    ///
    /// @param value candidate Bridge value
    /// @param field field label for a local failure
    private static void requireTokenFree(BridgeValue value, String field) {
        Objects.requireNonNull(value, field);
        if (containsHandle(value)) {
            throw new IllegalArgumentException("UI " + field + " must not contain Bridge handles");
        }
    }

    /// Detects a Bridge handle at any structured value depth.
    ///
    /// @param value current Bridge value
    /// @return whether the value tree contains a handle
    private static boolean containsHandle(BridgeValue value) {
        if (value instanceof BridgeValue.HandleValue) {
            return true;
        }
        if (value instanceof BridgeValue.ArrayValue array) {
            return array.values().stream().anyMatch(UiFrontendMessage::containsHandle);
        }
        if (value instanceof BridgeValue.MapValue map) {
            for (Map.Entry<String, BridgeValue> entry : map.values().entrySet()) {
                if (containsHandle(entry.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }
}
