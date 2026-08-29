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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;

/// Strict structural parser for signed plugin metadata envelopes.
@NotNullByDefault
final class PluginSignatureEnvelope {
    /// Signed payload object.
    private final JsonObject signed;

    /// Signature declarations.
    private final JsonArray signatures;

    /// Parses a complete envelope.
    PluginSignatureEnvelope(JsonObject document) {
        JsonElement signedElement = document.get("signed");
        JsonElement signaturesElement = document.get("signatures");
        if (signedElement == null || !signedElement.isJsonObject()
                || signaturesElement == null || !signaturesElement.isJsonArray()) {
            throw new IllegalArgumentException("Signed envelope requires object signed and array signatures");
        }
        signed = signedElement.getAsJsonObject();
        signatures = signaturesElement.getAsJsonArray();
        if (signatures.isEmpty()) {
            throw new IllegalArgumentException("Signed envelope has no signatures");
        }
    }

    /// Returns the signed payload.
    JsonObject signed() {
        return signed;
    }

    /// Returns the signature declarations.
    JsonArray signatures() {
        return signatures;
    }
}
