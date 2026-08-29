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
package org.jackhuang.hmcl.plugin.trust;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Couples one historical repository proof's complete signed envelope with values derived by the root verifier.
@NotNullByDefault
public final class PluginRepositoryAttestationDocument {
    /// Complete immutable proof envelope serialized for later installation receipts.
    private final String envelopeJson;

    /// Values derived from the verified envelope.
    private final PluginRepositoryAttestation attestation;

    /// Creates one proof document after verification.
    ///
    /// @param envelope complete signed envelope
    /// @param attestation verified parsed values
    public PluginRepositoryAttestationDocument(
            JsonObject envelope,
            PluginRepositoryAttestation attestation
    ) {
        envelopeJson = Objects.requireNonNull(envelope, "envelope").toString();
        this.attestation = Objects.requireNonNull(attestation, "attestation");
    }

    /// Returns a fresh mutable JSON object containing the original complete proof envelope.
    ///
    /// @return defensive envelope copy
    public JsonObject envelope() {
        return com.google.gson.JsonParser.parseString(envelopeJson).getAsJsonObject();
    }

    /// Returns values derived from the verified envelope.
    ///
    /// @return immutable repository attestation
    public PluginRepositoryAttestation attestation() {
        return attestation;
    }
}
