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
package org.jackhuang.hmcl.plugin.bridge;

import org.jackhuang.hmcl.plugin.runtime.RuntimeBridgeTransport;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadContext;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Adapts raw Runtime Host wire calls to authorized launcher Bridge services and UI handle ownership.
@NotNullByDefault
public final class LauncherRuntimeBridgeTransport implements RuntimeBridgeTransport {
    /// Authorized service registry receiving decoded operation calls.
    private final BridgeServiceRegistry services;

    /// Owner-verified UI handle retain and release boundary.
    private final HandleTransport handles;

    /// Creates one launcher-owned Runtime Bridge transport.
    ///
    /// @param services authorized Bridge service registry
    /// @param handles owner-verified handle boundary
    public LauncherRuntimeBridgeTransport(BridgeServiceRegistry services, HandleTransport handles) {
        this.services = Objects.requireNonNull(services, "services");
        this.handles = Objects.requireNonNull(handles, "handles");
    }

    /// Decodes, authorizes, dispatches, and encodes one Bridge operation.
    ///
    /// Portable Bridge failures are encoded as error values so every Runtime SDK observes the same category.
    ///
    /// @param context exact Java-owned payload context
    /// @param operation canonical Bridge operation
    /// @param input canonical Bridge Value v1 bytes
    /// @return canonical result or portable error bytes
    /// @throws IOException if wire decoding or encoding fails
    @Override
    public byte[] invoke(RuntimePayloadContext context, String operation, byte[] input) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operation, "operation");
        BridgeValue argument = RuntimeBridgeWireCodec.decode(Objects.requireNonNull(input, "input"));
        BridgeValue result;
        try {
            result = services.invoke(context, operation, argument);
        } catch (BridgeError error) {
            result = BridgeValue.error(error);
        }
        return RuntimeBridgeWireCodec.encode(result);
    }

    /// Delegates one owner-verified handle retain operation.
    ///
    /// @param context exact Java-owned payload context
    /// @param objectId launcher-owned object slot
    /// @param generation exact live generation
    /// @throws IOException if ownership validation fails
    @Override
    public void retainHandle(RuntimePayloadContext context, long objectId, long generation) throws IOException {
        try {
            handles.retain(context, objectId, generation);
        } catch (BridgeError error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    /// Delegates one owner-verified handle release operation.
    ///
    /// @param context exact Java-owned payload context
    /// @param objectId launcher-owned object slot
    /// @param generation exact live generation
    /// @throws IOException if ownership or cleanup validation fails
    @Override
    public void releaseHandle(RuntimePayloadContext context, long objectId, long generation) throws IOException {
        try {
            handles.release(context, objectId, generation);
        } catch (BridgeError error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    /// Performs owner-verified reference-count operations for launcher objects exposed to Runtime SDKs.
    @NotNullByDefault
    public interface HandleTransport {
        /// Retains one exact handle for its authenticated payload owner.
        void retain(RuntimePayloadContext context, long objectId, long generation) throws BridgeError;

        /// Releases one exact handle for its authenticated payload owner.
        void release(RuntimePayloadContext context, long objectId, long generation) throws BridgeError;
    }
}
