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

import org.jackhuang.hmcl.plugin.PluginDataObject;
import org.jackhuang.hmcl.plugin.PluginDataValue;
import org.jackhuang.hmcl.plugin.PluginHookEvent;
import org.jackhuang.hmcl.plugin.PluginHookPoint;
import org.jackhuang.hmcl.plugin.PluginHookResult;
import org.jackhuang.hmcl.plugin.PluginSecretAccess;
import org.jackhuang.hmcl.plugin.bridge.BridgeHandle;
import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jackhuang.hmcl.plugin.bridge.RuntimeBridgeWireCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the language-neutral Hook envelope carried by external Runtime Providers.
@NotNullByDefault
final class RuntimeHookWireCodecTest {
    /// Encodes every public event field while keeping the Java secret accessor out of the wire value.
    @Test
    void encodesCompleteEventWithoutSecretAccessor() throws IOException {
        PluginDataObject data = PluginDataObject.of(Map.of(
                "enabled", PluginDataValue.bool(true),
                "ratio", PluginDataValue.number(new BigDecimal("12.5")),
                "items", PluginDataValue.array(List.of(
                        PluginDataValue.string("first"),
                        PluginDataValue.nullValue()
                ))
        ));
        PluginHookEvent event = new PluginHookEvent(
                1,
                "dispatch-42",
                PluginHookPoint.BEFORE_GAME_LAUNCH,
                Instant.parse("2026-08-27T12:34:56Z"),
                data,
                PluginSecretAccess.denied("dev.example.rust")
        );

        BridgeValue encoded = RuntimeBridgeWireCodec.decode(RuntimeHookWireCodec.encodeEvent(event));

        Map<String, BridgeValue> expectedData = new LinkedHashMap<>();
        expectedData.put("enabled", BridgeValue.bool(true));
        expectedData.put("ratio", BridgeValue.floating(12.5));
        expectedData.put("items", BridgeValue.array(List.of(
                BridgeValue.string("first"),
                BridgeValue.nullValue()
        )));
        Map<String, BridgeValue> expectedEvent = new LinkedHashMap<>();
        expectedEvent.put("contractVersion", BridgeValue.integer(1L));
        expectedEvent.put("dispatchId", BridgeValue.string("dispatch-42"));
        expectedEvent.put("point", BridgeValue.string("before-game-launch"));
        expectedEvent.put("occurredAt", BridgeValue.string("2026-08-27T12:34:56Z"));
        expectedEvent.put("data", BridgeValue.map(expectedData));
        assertEquals(BridgeValue.map(expectedEvent), encoded);
        assertEquals("hook.before-game-launch", RuntimeHookWireCodec.operation(event.point()));
    }

    /// Decodes unchanged, replace, and cancel results without weakening action-specific validation.
    @Test
    void decodesAllHookResultActions() throws IOException {
        PluginHookResult unchanged = RuntimeHookWireCodec.decodeResult(wire(mapOf(
                "contractVersion", BridgeValue.integer(1L),
                "action", BridgeValue.string("unchanged")
        )));
        assertEquals(PluginHookResult.Action.UNCHANGED, unchanged.action());

        PluginHookResult replace = RuntimeHookWireCodec.decodeResult(wire(mapOf(
                "contractVersion", BridgeValue.integer(1L),
                "action", BridgeValue.string("replace"),
                "data", BridgeValue.map(mapOf(
                        "attempts", BridgeValue.integer(3L),
                        "nested", BridgeValue.map(mapOf("ready", BridgeValue.bool(true)))
                )),
                "protectedSecrets", BridgeValue.map(mapOf("access-token", BridgeValue.string("replacement")))
        )));
        assertEquals(PluginHookResult.Action.REPLACE, replace.action());
        assertEquals(new BigDecimal("3"), replace.data().requireNumber("attempts"));
        assertTrue(replace.data().requireObject("nested").requireBoolean("ready"));
        assertEquals(Map.of("access-token", "replacement"), replace.protectedSecrets());

        PluginHookResult cancel = RuntimeHookWireCodec.decodeResult(wire(mapOf(
                "contractVersion", BridgeValue.integer(1L),
                "action", BridgeValue.string("cancel"),
                "reasonCode", BridgeValue.string("runtime-policy"),
                "message", BridgeValue.string("Blocked by Rust plugin")
        )));
        assertEquals(PluginHookResult.Action.CANCEL, cancel.action());
        assertEquals("runtime-policy", cancel.reasonCode());
        assertEquals("Blocked by Rust plugin", cancel.message());
    }

    /// Rejects unknown fields, unsupported value kinds, and action envelopes with missing required data.
    @Test
    void rejectsMalformedProviderResults() throws IOException {
        assertNull(RuntimeHookWireCodec.decodeResult(wire(mapOf(
                "contractVersion", BridgeValue.integer(1L),
                "action", BridgeValue.string("unchanged"),
                "unexpected", BridgeValue.bool(true)
        ))));
        assertNull(RuntimeHookWireCodec.decodeResult(wire(mapOf(
                "contractVersion", BridgeValue.integer(1L),
                "action", BridgeValue.string("replace"),
                "data", BridgeValue.handle(new BridgeHandle(7L, 1L, "ui.page")),
                "protectedSecrets", BridgeValue.map(Map.of())
        ))));
        assertNull(RuntimeHookWireCodec.decodeResult(wire(mapOf(
                "contractVersion", BridgeValue.integer(1L),
                "action", BridgeValue.string("cancel"),
                "reasonCode", BridgeValue.string("runtime-policy")
        ))));
        assertNull(RuntimeHookWireCodec.decodeResult(new byte[]{0x01, 0x02}));
    }

    /// Creates one insertion-ordered Bridge map from alternating string keys and Bridge values.
    ///
    /// @param entries alternating key and value entries
    /// @return insertion-ordered map
    private static Map<String, BridgeValue> mapOf(Object... entries) {
        Map<String, BridgeValue> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], (BridgeValue) entries[index + 1]);
        }
        return values;
    }

    /// Encodes one manually constructed Provider result value.
    ///
    /// @param values result envelope fields
    /// @return canonical Bridge wire bytes
    /// @throws IOException if wire encoding fails
    private static byte[] wire(Map<String, BridgeValue> values) throws IOException {
        return RuntimeBridgeWireCodec.encode(BridgeValue.map(values));
    }
}
