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
package org.jackhuang.hmcl.plugin.runtime.process;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Represents one frozen schema-v5 message exchanged with an isolated runtime payload process.
@NotNullByDefault
public sealed interface RuntimeProcessMessage permits RuntimeProcessMessage.Hello, RuntimeProcessMessage.Load,
        RuntimeProcessMessage.Enable, RuntimeProcessMessage.Invoke, RuntimeProcessMessage.Disable,
        RuntimeProcessMessage.Shutdown, RuntimeProcessMessage.Ok, RuntimeProcessMessage.Result,
        RuntimeProcessMessage.Error, RuntimeProcessMessage.BridgeInvoke, RuntimeProcessMessage.RetainHandle,
        RuntimeProcessMessage.ReleaseHandle, RuntimeProcessMessage.CallbackResult, RuntimeProcessMessage.CallbackError {
    /// Returns the positive direction-scoped request identifier.
    ///
    /// @return request identifier
    long requestId();

    /// Negotiates the frozen process protocol.
    ///
    /// @param requestId positive odd parent request identifier
    @NotNullByDefault
    record Hello(long requestId) implements RuntimeProcessMessage {
    }

    /// Requests loading one payload library into the child process.
    ///
    /// @param requestId positive odd parent request identifier
    /// @param packageRoot canonical extracted package root
    /// @param entrypoint package-relative payload library path
    /// @param pluginId positive launcher-generated plugin identifier
    /// @param session positive launcher-generated Bridge session identifier
    @NotNullByDefault
    record Load(long requestId, String packageRoot, String entrypoint, long pluginId, long session)
            implements RuntimeProcessMessage {
        /// Rejects null text before the codec performs protocol validation.
        public Load {
            Objects.requireNonNull(packageRoot, "packageRoot");
            Objects.requireNonNull(entrypoint, "entrypoint");
        }
    }

    /// Enables the loaded payload.
    ///
    /// @param requestId positive odd parent request identifier
    @NotNullByDefault
    record Enable(long requestId) implements RuntimeProcessMessage {
    }

    /// Invokes one enabled payload operation.
    ///
    /// @param requestId positive odd parent request identifier
    /// @param operation nonblank payload operation
    /// @param input opaque canonical Bridge Value v1 bytes
    /// @param callbackId nonnegative payload-local callback identifier
    @NotNullByDefault
    record Invoke(long requestId, String operation, byte @Unmodifiable [] input, long callbackId)
            implements RuntimeProcessMessage {
        /// Copies mutable byte input and rejects null values.
        public Invoke {
            Objects.requireNonNull(operation, "operation");
            input = Objects.requireNonNull(input, "input").clone();
        }

        /// Returns a defensive copy of the opaque input.
        ///
        /// @return copied input bytes
        @Override
        public byte @Unmodifiable [] input() {
            return input.clone();
        }

        /// Compares opaque input by content.
        ///
        /// @param other candidate message
        /// @return whether every component is equal
        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Invoke that
                    && requestId == that.requestId
                    && callbackId == that.callbackId
                    && operation.equals(that.operation)
                    && Arrays.equals(input, that.input);
        }

        /// Hashes opaque input by content.
        ///
        /// @return content hash
        @Override
        public int hashCode() {
            return 31 * Objects.hash(requestId, operation, callbackId) + Arrays.hashCode(input);
        }
    }

    /// Disables the enabled payload.
    ///
    /// @param requestId positive odd parent request identifier
    @NotNullByDefault
    record Disable(long requestId) implements RuntimeProcessMessage {
    }

    /// Shuts down and unloads the payload before closing the child.
    ///
    /// @param requestId positive odd parent request identifier
    @NotNullByDefault
    record Shutdown(long requestId) implements RuntimeProcessMessage {
    }

    /// Confirms one successful lifecycle operation.
    ///
    /// @param requestId positive odd parent request identifier
    @NotNullByDefault
    record Ok(long requestId) implements RuntimeProcessMessage {
    }

    /// Returns one successful payload invocation result.
    ///
    /// @param requestId positive odd parent request identifier
    /// @param output opaque canonical Bridge Value v1 bytes
    @NotNullByDefault
    record Result(long requestId, byte @Unmodifiable [] output) implements RuntimeProcessMessage {
        /// Copies mutable byte output and rejects null values.
        public Result {
            output = Objects.requireNonNull(output, "output").clone();
        }

        /// Returns a defensive copy of the opaque output.
        ///
        /// @return copied output bytes
        @Override
        public byte @Unmodifiable [] output() {
            return output.clone();
        }

        /// Compares opaque output by content.
        ///
        /// @param other candidate message
        /// @return whether every component is equal
        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Result that
                    && requestId == that.requestId
                    && Arrays.equals(output, that.output);
        }

        /// Hashes opaque output by content.
        ///
        /// @return content hash
        @Override
        public int hashCode() {
            return 31 * Long.hashCode(requestId) + Arrays.hashCode(output);
        }
    }

    /// Reports one stable child-side operation failure.
    ///
    /// @param requestId positive odd parent request identifier
    /// @param code stable lower-case kebab error code
    /// @param message bounded diagnostic text
    @NotNullByDefault
    record Error(long requestId, String code, String message) implements RuntimeProcessMessage {
        /// Rejects null error fields before protocol validation.
        public Error {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    /// Requests one launcher-owned Runtime Bridge invocation from the child.
    ///
    /// @param requestId positive even child callback identifier
    /// @param operation nonblank Runtime Bridge operation
    /// @param input opaque canonical Bridge Value v1 bytes
    @NotNullByDefault
    record BridgeInvoke(long requestId, String operation, byte @Unmodifiable [] input)
            implements RuntimeProcessMessage {
        /// Copies mutable byte input and rejects null values.
        public BridgeInvoke {
            Objects.requireNonNull(operation, "operation");
            input = Objects.requireNonNull(input, "input").clone();
        }

        /// Returns a defensive copy of the opaque input.
        ///
        /// @return copied input bytes
        @Override
        public byte @Unmodifiable [] input() {
            return input.clone();
        }

        /// Compares opaque input by content.
        ///
        /// @param other candidate message
        /// @return whether every component is equal
        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof BridgeInvoke that
                    && requestId == that.requestId
                    && operation.equals(that.operation)
                    && Arrays.equals(input, that.input);
        }

        /// Hashes opaque input by content.
        ///
        /// @return content hash
        @Override
        public int hashCode() {
            return 31 * Objects.hash(requestId, operation) + Arrays.hashCode(input);
        }
    }

    /// Requests retaining one generation-safe launcher handle.
    ///
    /// @param requestId positive even child callback identifier
    /// @param objectId positive launcher object identifier
    /// @param generation positive handle generation
    @NotNullByDefault
    record RetainHandle(long requestId, long objectId, long generation) implements RuntimeProcessMessage {
    }

    /// Requests releasing one generation-safe launcher handle.
    ///
    /// @param requestId positive even child callback identifier
    /// @param objectId positive launcher object identifier
    /// @param generation positive handle generation
    @NotNullByDefault
    record ReleaseHandle(long requestId, long objectId, long generation) implements RuntimeProcessMessage {
    }

    /// Returns one successful parent-side Bridge callback result.
    ///
    /// @param requestId positive even child callback identifier
    /// @param output opaque canonical Bridge Value v1 bytes
    @NotNullByDefault
    record CallbackResult(long requestId, byte @Unmodifiable [] output) implements RuntimeProcessMessage {
        /// Copies mutable byte output and rejects null values.
        public CallbackResult {
            output = Objects.requireNonNull(output, "output").clone();
        }

        /// Returns a defensive copy of the opaque output.
        ///
        /// @return copied output bytes
        @Override
        public byte @Unmodifiable [] output() {
            return output.clone();
        }

        /// Compares opaque output by content.
        ///
        /// @param other candidate message
        /// @return whether every component is equal
        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof CallbackResult that
                    && requestId == that.requestId
                    && Arrays.equals(output, that.output);
        }

        /// Hashes opaque output by content.
        ///
        /// @return content hash
        @Override
        public int hashCode() {
            return 31 * Long.hashCode(requestId) + Arrays.hashCode(output);
        }
    }

    /// Reports one redacted parent-side Bridge callback failure.
    ///
    /// @param requestId positive even child callback identifier
    /// @param code stable lower-case kebab error code
    @NotNullByDefault
    record CallbackError(long requestId, String code) implements RuntimeProcessMessage {
        /// Rejects a null error code before protocol validation.
        public CallbackError {
            Objects.requireNonNull(code, "code");
        }
    }
}
