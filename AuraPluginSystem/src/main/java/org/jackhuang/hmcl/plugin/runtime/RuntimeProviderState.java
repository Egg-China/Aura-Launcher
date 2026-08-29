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

/// Launcher-owned lifecycle states for an external runtime Provider Host.
@NotNullByDefault
public enum RuntimeProviderState {
    /// The Provider package was found during plugin discovery.
    DISCOVERED,

    /// Concrete and virtual Provider dependencies were resolved.
    RESOLVED,

    /// The Host's Java bootstrap lifecycle was instantiated.
    BOOTSTRAP_LOADED,

    /// The Host registered its Provider implementation.
    REGISTERED,

    /// The registered descriptor matched the Host's advertised capability contract.
    NEGOTIATED,

    /// Provider-owned runtime resources were initialized.
    INITIALIZED,

    /// The initialized Provider passed its health check.
    HEALTHY,

    /// The Provider may accept dependent payload work.
    READY,

    /// New callbacks are blocked while dependent payloads stop in reverse order.
    STOPPING,

    /// Every payload and Provider-owned resource was released.
    STOPPED,

    /// A lifecycle transition failed and the Provider cannot accept work.
    FAILED
}
