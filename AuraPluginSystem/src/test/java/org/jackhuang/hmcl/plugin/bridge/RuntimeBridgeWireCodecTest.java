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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies exact wire parity with the schema-v5 Rust SDK Bridge Value v1 codec.
@NotNullByDefault
final class RuntimeBridgeWireCodecTest {
    /// Encodes fixed-width integers with the exact tagged canonical MessagePack representation.
    @Test
    void encodesCanonicalIntegerFixture() throws IOException {
        assertArrayEquals(new byte[]{
                (byte) 0x92, 0x02, (byte) 0xd3,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2a
        },
                RuntimeBridgeWireCodec.encode(BridgeValue.integer(42L)));
    }

    /// Matches the Rust SDK's fixed composite map, handle, and error fixture byte for byte.
    @Test
    void matchesRustSdkCompositeFixture() throws IOException {
        byte[] fixture = new byte[]{
                (byte) 0x92, 0x07, (byte) 0xdd, 0x00, 0x00, 0x00, 0x03,
                (byte) 0x92, (byte) 0xdb, 0x00, 0x00, 0x00, 0x07,
                'm', 'e', 's', 's', 'a', 'g', 'e',
                (byte) 0x92, 0x04, (byte) 0xdb, 0x00, 0x00, 0x00, 0x04,
                'A', 'u', 'r', 'a',
                (byte) 0x92, (byte) 0xdb, 0x00, 0x00, 0x00, 0x06,
                'h', 'a', 'n', 'd', 'l', 'e',
                (byte) 0x92, 0x08, (byte) 0x93,
                (byte) 0xcf, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x09,
                (byte) 0xcf, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03,
                (byte) 0xdb, 0x00, 0x00, 0x00, 0x07, 'u', 'i', '.', 'p', 'a', 'g', 'e',
                (byte) 0x92, (byte) 0xdb, 0x00, 0x00, 0x00, 0x05,
                'e', 'r', 'r', 'o', 'r',
                (byte) 0x92, 0x09, (byte) 0xdb, 0x00, 0x00, 0x00, 0x11,
                'p', 'e', 'r', 'm', 'i', 's', 's', 'i', 'o', 'n', '-',
                'd', 'e', 'n', 'i', 'e', 'd'
        };
        Map<String, BridgeValue> entries = new LinkedHashMap<>();
        entries.put("message", BridgeValue.string("Aura"));
        entries.put("handle", BridgeValue.handle(new BridgeHandle(9L, 3L, "ui.page")));
        entries.put("error", BridgeValue.error(BridgeError.of(BridgeError.Category.PERMISSION_DENIED)));
        BridgeValue expected = BridgeValue.map(entries);

        assertArrayEquals(fixture, RuntimeBridgeWireCodec.encode(expected));
        assertEquals(expected, RuntimeBridgeWireCodec.decode(fixture));
    }

    /// Round-trips every closed Bridge value while preserving insertion-ordered maps.
    @Test
    void roundTripsEveryBridgeValue() throws IOException {
        Map<String, BridgeValue> ordered = new LinkedHashMap<>();
        ordered.put("null", BridgeValue.nullValue());
        ordered.put("bool", BridgeValue.bool(true));
        ordered.put("integer", BridgeValue.integer(Long.MIN_VALUE));
        ordered.put("float", BridgeValue.floating(0.25D));
        ordered.put("string", BridgeValue.string("Aura"));
        ordered.put("bytes", BridgeValue.bytes(new byte[]{1, 2, 3}));
        ordered.put("array", BridgeValue.array(List.of(BridgeValue.integer(7L))));
        ordered.put("handle", BridgeValue.handle(new BridgeHandle(9L, 3L, "ui.page")));
        ordered.put("error", BridgeValue.error(BridgeError.of(BridgeError.Category.PERMISSION_DENIED)));
        BridgeValue source = BridgeValue.map(ordered);

        BridgeValue decoded = RuntimeBridgeWireCodec.decode(RuntimeBridgeWireCodec.encode(source));

        assertEquals(source, decoded);
        assertEquals(List.copyOf(ordered.keySet()),
                List.copyOf(((BridgeValue.MapValue) decoded).values().keySet()));
    }

    /// Rejects noncanonical, truncated, trailing, and unknown-tag payloads.
    @Test
    void rejectsMalformedOrNoncanonicalInput() {
        assertThrows(IOException.class, () -> RuntimeBridgeWireCodec.decode(new byte[]{}));
        assertThrows(IOException.class, () -> RuntimeBridgeWireCodec.decode(
                new byte[]{(byte) 0x92, 0x00, (byte) 0xc0, 0x00}));
        assertThrows(IOException.class, () -> RuntimeBridgeWireCodec.decode(
                new byte[]{(byte) 0x91, 0x00, (byte) 0xc0}));
        assertThrows(IOException.class, () -> RuntimeBridgeWireCodec.decode(
                new byte[]{(byte) 0x92, 0x7f, (byte) 0xc0}));
    }
}
