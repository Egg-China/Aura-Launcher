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
