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

import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the deterministic JSON representation shared by every plugin signature role.
@NotNullByDefault
public final class CanonicalJsonTest {
    /// Sorts object keys recursively while retaining array order and exact integer values.
    @Test
    public void canonicalizesNestedJson() {
        String canonical = new String(CanonicalJson.canonicalize(JsonParser.parseString(
                "{\"z\":[3,{\"b\":true,\"a\":null}],\"a\":-12}"
        )), StandardCharsets.UTF_8);

        assertEquals("{\"a\":-12,\"z\":[3,{\"a\":null,\"b\":true}]}", canonical);
    }

    /// Uses minimal JSON escaping so equivalent strings have one signature representation.
    @Test
    public void escapesStringsDeterministically() {
        String canonical = new String(CanonicalJson.canonicalize(JsonParser.parseString(
                "{\"value\":\"line\\nquote\\\" slash/ snowman ☃\"}"
        )), StandardCharsets.UTF_8);

        assertEquals("{\"value\":\"line\\nquote\\\" slash/ snowman ☃\"}", canonical);
    }

    /// Rejects fractional values because the v1 trust schema intentionally permits exact integers only.
    @Test
    public void rejectsNonIntegralNumbers() {
        assertThrows(IllegalArgumentException.class, () ->
                CanonicalJson.canonicalize(JsonParser.parseString("{\"value\":1.5}")));
    }

    /// Retains both inclusive JavaScript-safe integer boundaries without precision loss.
    @Test
    public void canonicalizesSafeIntegerBoundaries() {
        String canonical = new String(CanonicalJson.canonicalize(JsonParser.parseString(
                "{\"maximum\":9007199254740991,\"minimum\":-9007199254740991}"
        )), StandardCharsets.UTF_8);

        assertEquals("{\"maximum\":9007199254740991,\"minimum\":-9007199254740991}", canonical);
    }

    /// Rejects integral values outside the exact number range shared with the Node.js signer.
    @Test
    public void rejectsIntegersOutsideSafeRange() {
        assertThrows(IllegalArgumentException.class, () ->
                CanonicalJson.canonicalize(JsonParser.parseString("{\"value\":9007199254740992}")));
        assertThrows(IllegalArgumentException.class, () ->
                CanonicalJson.canonicalize(JsonParser.parseString("{\"value\":-9007199254740992}")));
    }

    /// Prefixes the canonical payload with an explicit signature domain.
    @Test
    public void separatesSignatureDomains() {
        String input = new String(CanonicalJson.signatureInput(
                "HMCLCE-PLUGIN-MANIFEST-V1",
                JsonParser.parseString("{\"id\":\"dev.example\"}")
        ), StandardCharsets.UTF_8);

        assertEquals("HMCLCE-PLUGIN-MANIFEST-V1\n{\"id\":\"dev.example\"}", input);
    }
}
