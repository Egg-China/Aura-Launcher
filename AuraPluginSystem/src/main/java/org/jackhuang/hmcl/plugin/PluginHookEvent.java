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

import java.time.Instant;
import java.util.Objects;

/// Carries one immutable, versioned Hook invocation to a plugin endpoint.
@NotNullByDefault
public final class PluginHookEvent {
    /// Contract generation implemented by this launcher.
    public static final int CURRENT_CONTRACT_VERSION = 1;

    /// Version of the event and result data contract.
    private final int contractVersion;

    /// Opaque identifier shared by all callbacks for one dispatch.
    private final String dispatchId;

    /// Hook point being dispatched.
    private final PluginHookPoint point;

    /// Time at which this event envelope was created.
    private final Instant occurredAt;

    /// Immutable operation data.
    private final PluginDataObject data;

    /// Permission-scoped out-of-band secret accessor.
    private final PluginSecretAccess secrets;

    /// Creates one Hook event envelope.
    ///
    /// @param contractVersion supported contract generation
    /// @param dispatchId opaque non-blank dispatch identifier
    /// @param point Hook point
    /// @param occurredAt event creation time
    /// @param data immutable operation data
    /// @param secrets permission-scoped secret accessor
    public PluginHookEvent(
            int contractVersion,
            String dispatchId,
            PluginHookPoint point,
            Instant occurredAt,
            PluginDataObject data,
            PluginSecretAccess secrets
    ) {
        if (contractVersion != CURRENT_CONTRACT_VERSION) {
            throw new IllegalArgumentException("Unsupported plugin Hook contract version: " + contractVersion);
        }
        if (dispatchId.isBlank()) {
            throw new IllegalArgumentException("Plugin Hook dispatch ID must not be blank");
        }
        this.contractVersion = contractVersion;
        this.dispatchId = dispatchId;
        this.point = Objects.requireNonNull(point, "point");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.data = Objects.requireNonNull(data, "data");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /// Returns the data contract generation.
    ///
    /// @return contract generation
    public int contractVersion() {
        return contractVersion;
    }

    /// Returns the opaque dispatch identifier.
    ///
    /// @return dispatch identifier
    public String dispatchId() {
        return dispatchId;
    }

    /// Returns the dispatched Hook point.
    ///
    /// @return Hook point
    public PluginHookPoint point() {
        return point;
    }

    /// Returns when this event was created.
    ///
    /// @return event creation time
    public Instant occurredAt() {
        return occurredAt;
    }

    /// Returns immutable operation data.
    ///
    /// @return operation data
    public PluginDataObject data() {
        return data;
    }

    /// Returns the permission-scoped secret accessor.
    ///
    /// @return secret accessor
    public PluginSecretAccess secrets() {
        return secrets;
    }
}
