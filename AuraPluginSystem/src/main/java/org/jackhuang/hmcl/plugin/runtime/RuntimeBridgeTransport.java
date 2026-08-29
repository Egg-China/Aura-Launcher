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
package org.jackhuang.hmcl.plugin.runtime;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Exposes the launcher Runtime Bridge to optional Hosts using only wire bytes and numeric lookup handles.
@NotNullByDefault
public interface RuntimeBridgeTransport {
    /// Invokes one canonical Runtime Bridge operation for an exact Java-owned payload context.
    ///
    /// @param context exact payload context retained by the Runtime Host
    /// @param operation stable Bridge operation name
    /// @param input canonical Bridge Value v1 bytes
    /// @return canonical Bridge Value v1 result bytes
    /// @throws IOException if wire decoding, dispatch, or encoding fails
    byte[] invoke(RuntimePayloadContext context, String operation, byte[] input) throws IOException;

    /// Retains one launcher-owned generation-safe handle for the calling payload.
    ///
    /// @param context exact payload context retained by the Runtime Host
    /// @param objectId launcher-owned object slot
    /// @param generation exact live generation
    /// @throws IOException if ownership or generation validation fails
    void retainHandle(RuntimePayloadContext context, long objectId, long generation) throws IOException;

    /// Releases one launcher-owned generation-safe handle for the calling payload.
    ///
    /// @param context exact payload context retained by the Runtime Host
    /// @param objectId launcher-owned object slot
    /// @param generation exact live generation
    /// @throws IOException if ownership, generation, or cleanup validation fails
    void releaseHandle(RuntimePayloadContext context, long objectId, long generation) throws IOException;

    /// Returns the fail-closed transport used only by compatibility constructors and isolated tests.
    ///
    /// @return shared unavailable transport
    static RuntimeBridgeTransport unavailable() {
        return Unavailable.INSTANCE;
    }

    /// Shared fail-closed implementation for contexts created outside production loader wiring.
    @NotNullByDefault
    enum Unavailable implements RuntimeBridgeTransport {
        /// Singleton unavailable transport.
        INSTANCE;

        /// Rejects invocation without resolving capability authority.
        @Override
        public byte[] invoke(RuntimePayloadContext context, String operation, byte[] input) throws IOException {
            throw new IOException("Launcher Runtime Bridge transport is unavailable");
        }

        /// Rejects handle retention without resolving capability authority.
        @Override
        public void retainHandle(RuntimePayloadContext context, long objectId, long generation) throws IOException {
            throw new IOException("Launcher Runtime Bridge transport is unavailable");
        }

        /// Rejects handle release without resolving capability authority.
        @Override
        public void releaseHandle(RuntimePayloadContext context, long objectId, long generation) throws IOException {
            throw new IOException("Launcher Runtime Bridge transport is unavailable");
        }
    }
}
