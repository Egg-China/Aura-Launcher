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
package org.jackhuang.hmcl.plugin.ui.frontend.process;

import org.jackhuang.hmcl.plugin.bridge.BridgeHandle;
import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jackhuang.hmcl.plugin.bridge.RuntimeBridgeWireCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the bounded token-free Aura UI frontend wire protocol.
@NotNullByDefault
final class UiFrontendWireCodecTest {
    /// A manually calculated `aura.ui.v1` request frame for `ui.ping` with a null parameter value.
    private static final byte @Unmodifiable [] REQUEST_GOLDEN_FRAME = new byte[]{
            0, 0, 0, (byte) 0x80,
            (byte) 0x92, 0x07, (byte) 0xdd, 0, 0, 0, 5,
            (byte) 0x92, (byte) 0xdb, 0, 0, 0, 13,
            's', 'c', 'h', 'e', 'm', 'a', 'V', 'e', 'r', 's', 'i', 'o', 'n',
            (byte) 0x92, 0x02, (byte) 0xd3, 0, 0, 0, 0, 0, 0, 0, 1,
            (byte) 0x92, (byte) 0xdb, 0, 0, 0, 4, 't', 'y', 'p', 'e',
            (byte) 0x92, 0x04, (byte) 0xdb, 0, 0, 0, 7, 'r', 'e', 'q', 'u', 'e', 's', 't',
            (byte) 0x92, (byte) 0xdb, 0, 0, 0, 9, 'r', 'e', 'q', 'u', 'e', 's', 't', 'I', 'd',
            (byte) 0x92, 0x02, (byte) 0xd3, 0, 0, 0, 0, 0, 0, 0, 1,
            (byte) 0x92, (byte) 0xdb, 0, 0, 0, 6, 'm', 'e', 't', 'h', 'o', 'd',
            (byte) 0x92, 0x04, (byte) 0xdb, 0, 0, 0, 7, 'u', 'i', '.', 'p', 'i', 'n', 'g',
            (byte) 0x92, (byte) 0xdb, 0, 0, 0, 6, 'p', 'a', 'r', 'a', 'm', 's',
            (byte) 0x92, 0x00, (byte) 0xc0
    };

    /// Matches the independent canonical request fixture byte for byte in both directions.
    @Test
    void matchesHandComputedRequestGoldenFrame() throws IOException {
        UiFrontendMessage.Request request = new UiFrontendMessage.Request(1L, "ui.ping", BridgeValue.nullValue());

        assertArrayEquals(REQUEST_GOLDEN_FRAME, write(request));
        assertEquals(request, UiFrontendWireCodec.read(
                new ByteArrayInputStream(REQUEST_GOLDEN_FRAME), UiFrontendWireCodec.InboundEndpoint.FRONTEND));
    }

    /// Preserves every permitted nested Bridge value through request and reply envelopes.
    @Test
    void roundTripsNestedTokenFreeValues() throws IOException {
        BridgeValue value = BridgeValue.map(orderedMap(
                "title", BridgeValue.string("Aura"),
                "state", BridgeValue.array(List.of(
                        BridgeValue.bool(true),
                        BridgeValue.integer(-7L),
                        BridgeValue.floating(0.25D),
                        BridgeValue.bytes(new byte[]{1, 2, 3})
                ))
        ));
        @Unmodifiable List<UiFrontendMessage> messages = List.of(
                new UiFrontendMessage.Request(1L, "ui.state", value),
                new UiFrontendMessage.Result(1L, value),
                new UiFrontendMessage.Error(2L, "operation-failed", "The frontend declined the request")
        );

        for (UiFrontendMessage message : messages) {
            assertEquals(message, UiFrontendWireCodec.read(
                    new ByteArrayInputStream(write(message)), UiFrontendWireCodec.InboundEndpoint.FRONTEND));
        }
    }

    /// Accepts empty and otherwise unusual strings when they remain valid bounded Bridge text.
    @Test
    void acceptsSchemaValidStringsWithoutSessionPolicy() throws IOException {
        @Unmodifiable List<UiFrontendMessage> messages = List.of(
                new UiFrontendMessage.Request(1L, "", BridgeValue.nullValue()),
                new UiFrontendMessage.Error(2L, "UI Error / not-policy", "")
        );

        for (UiFrontendMessage message : messages) {
            assertEquals(message, UiFrontendWireCodec.read(
                    new ByteArrayInputStream(write(message)), UiFrontendWireCodec.InboundEndpoint.FRONTEND));
        }
    }

    /// Rejects unknown, duplicate-equivalent, missing, and out-of-order envelope fields.
    @Test
    void rejectsNonStrictEnvelopeFields() throws IOException {
        Map<String, BridgeValue> valid = requestEnvelope(1L, 1L, "ui.ping", BridgeValue.nullValue());
        Map<String, BridgeValue> unknown = new LinkedHashMap<>(valid);
        unknown.put("unexpected", BridgeValue.bool(true));
        Map<String, BridgeValue> outOfOrder = orderedMap(
                "type", valid.get("type"),
                "schemaVersion", valid.get("schemaVersion"),
                "requestId", valid.get("requestId"),
                "method", valid.get("method"),
                "params", valid.get("params")
        );
        Map<String, BridgeValue> wrongRequestFields = orderedMap(
                "schemaVersion", BridgeValue.integer(1L),
                "type", BridgeValue.string("request"),
                "requestId", BridgeValue.integer(1L),
                "params", BridgeValue.nullValue(),
                "method", BridgeValue.string("ui.ping")
        );

        assertInvalidFrame(frame(unknown));
        assertInvalidFrame(frame(outOfOrder));
        assertInvalidFrame(frame(wrongRequestFields));
        assertInvalidFrame(frame(requestEnvelope(2L, 1L, "ui.ping", BridgeValue.nullValue())));
        assertInvalidFrame(frame(requestEnvelope(1L, 0L, "ui.ping", BridgeValue.nullValue())));
        assertInvalidFrame(duplicateTypeFieldFrame());
    }

    /// Rejects malformed message types, field values, and unsupported nonfinite Bridge payloads.
    @Test
    void rejectsMalformedEnvelopeValues() throws IOException {
        Map<String, BridgeValue> unknownType = requestEnvelope(1L, 1L, "ui.ping", BridgeValue.nullValue());
        unknownType.put("type", BridgeValue.string("event"));
        Map<String, BridgeValue> badRequestId = requestEnvelope(1L, -1L, "ui.ping", BridgeValue.nullValue());
        Map<String, BridgeValue> wrongValueType = requestEnvelope(1L, 1L, "ui.ping", BridgeValue.nullValue());
        wrongValueType.put("requestId", BridgeValue.string("1"));

        assertInvalidFrame(frame(unknownType));
        assertInvalidFrame(frame(badRequestId));
        assertInvalidFrame(frame(wrongValueType));
        assertInvalidFrame(invalidUtf8Frame());
        assertInvalidFrame(nonfiniteParamFrame());
        assertInvalidFrame(frameWithTrailingByte());
        assertThrows(IllegalArgumentException.class, () -> BridgeValue.floating(Double.NaN));
    }

    /// Rejects Bridge handles at any depth before they can become JVM reference channels.
    @Test
    void rejectsRecursiveBridgeHandles() throws IOException {
        BridgeValue handle = BridgeValue.handle(new BridgeHandle(1L, 1L, "ui.node"));
        BridgeValue nested = BridgeValue.map(orderedMap(
                "nested", BridgeValue.array(List.of(handle))
        ));

        assertThrows(IllegalArgumentException.class,
                () -> new UiFrontendMessage.Request(1L, "ui.mutate", nested));
        assertThrows(IllegalArgumentException.class, () -> new UiFrontendMessage.Result(1L, nested));
        assertInvalidFrame(frame(requestEnvelope(1L, 1L, "ui.mutate", nested)));
    }

    /// Handles fragmented frames, clean EOF, and malformed frame header or body boundaries.
    @Test
    void readsFragmentedFramesAndRejectsIncompleteFrames() throws IOException {
        UiFrontendMessage.Request request = new UiFrontendMessage.Request(1L, "ui.ping", BridgeValue.nullValue());

        assertEquals(request, UiFrontendWireCodec.read(
                new FragmentedInputStream(REQUEST_GOLDEN_FRAME), UiFrontendWireCodec.InboundEndpoint.FRONTEND));
        assertNull(UiFrontendWireCodec.read(new ByteArrayInputStream(new byte[]{}),
                UiFrontendWireCodec.InboundEndpoint.FRONTEND));
        assertThrows(IOException.class, () -> UiFrontendWireCodec.read(new ByteArrayInputStream(new byte[]{0}),
                UiFrontendWireCodec.InboundEndpoint.FRONTEND));
        assertThrows(IOException.class, () -> UiFrontendWireCodec.read(new ByteArrayInputStream(new byte[]{0, 0, 0, 5, 1}),
                UiFrontendWireCodec.InboundEndpoint.FRONTEND));
    }

    /// Rejects an oversized frame and stdout bytes that do not begin at a complete protocol frame.
    @Test
    void rejectsOversizeAndBadStdoutPrefix() {
        assertThrows(IOException.class, () -> UiFrontendWireCodec.read(new ByteArrayInputStream(
                new byte[]{1, 0, 0, 1}), UiFrontendWireCodec.InboundEndpoint.FRONTEND));
        assertThrows(IOException.class, () -> UiFrontendWireCodec.read(new ByteArrayInputStream(
                new byte[]{'l', 'o', 'g', '\n'}), UiFrontendWireCodec.InboundEndpoint.FRONTEND));
    }

    /// Enforces request parity by inbound endpoint while accepting replies to either caller's requests.
    @Test
    void enforcesRequestDirectionButAllowsBothReplyParities() throws IOException {
        UiFrontendMessage.Request parentRequest = new UiFrontendMessage.Request(1L, "ui.ping", BridgeValue.nullValue());
        UiFrontendMessage.Request childRequest = new UiFrontendMessage.Request(2L, "ui.event", BridgeValue.nullValue());
        UiFrontendMessage.Result parentReply = new UiFrontendMessage.Result(1L, BridgeValue.nullValue());
        UiFrontendMessage.Error childReply = new UiFrontendMessage.Error(2L, "operation-failed", "No action was taken");

        assertInvalidFrame(write(parentRequest), UiFrontendWireCodec.InboundEndpoint.LAUNCHER);
        assertInvalidFrame(write(childRequest), UiFrontendWireCodec.InboundEndpoint.FRONTEND);
        assertEquals(parentReply, UiFrontendWireCodec.read(new ByteArrayInputStream(write(parentReply)),
                UiFrontendWireCodec.InboundEndpoint.LAUNCHER));
        assertEquals(childReply, UiFrontendWireCodec.read(new ByteArrayInputStream(write(childReply)),
                UiFrontendWireCodec.InboundEndpoint.FRONTEND));
    }

    /// Serializes one message through the production writer.
    ///
    /// @param message source message
    /// @return one complete framed message
    private static byte[] write(UiFrontendMessage message) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        UiFrontendWireCodec.write(output, message);
        return output.toByteArray();
    }

    /// Encodes one malformed source map with a legitimate Bridge Value v1 body and frame prefix.
    ///
    /// @param envelope source map
    /// @return complete frame
    private static byte[] frame(Map<String, BridgeValue> envelope) throws IOException {
        byte[] body = RuntimeBridgeWireCodec.encode(BridgeValue.map(envelope));
        byte[] frame = new byte[Integer.BYTES + body.length];
        frame[0] = (byte) (body.length >>> 24);
        frame[1] = (byte) (body.length >>> 16);
        frame[2] = (byte) (body.length >>> 8);
        frame[3] = (byte) body.length;
        System.arraycopy(body, 0, frame, Integer.BYTES, body.length);
        return frame;
    }

    /// Adds a duplicate `type` key that a generic Bridge map would otherwise overwrite during decode.
    ///
    /// @return valid Bridge framing with an invalid duplicate UI envelope field
    private static byte[] duplicateTypeFieldFrame() {
        byte[] typePair = new byte[]{
                (byte) 0x92, (byte) 0xdb, 0, 0, 0, 4, 't', 'y', 'p', 'e',
                (byte) 0x92, 0x04, (byte) 0xdb, 0, 0, 0, 7, 'r', 'e', 'q', 'u', 'e', 's', 't'
        };
        byte[] body = Arrays.copyOfRange(REQUEST_GOLDEN_FRAME, Integer.BYTES, REQUEST_GOLDEN_FRAME.length + typePair.length);
        body[Integer.BYTES + 2] = 6;
        System.arraycopy(typePair, 0, body, REQUEST_GOLDEN_FRAME.length - Integer.BYTES, typePair.length);
        return frame(body);
    }

    /// Replaces the method's initial UTF-8 byte with an invalid leading byte.
    ///
    /// @return frame whose Bridge string cannot be decoded as UTF-8
    private static byte[] invalidUtf8Frame() {
        byte[] frame = REQUEST_GOLDEN_FRAME.clone();
        for (int index = Integer.BYTES; index < frame.length - 2; index++) {
            if (frame[index] == 'u' && frame[index + 1] == 'i' && frame[index + 2] == '.') {
                frame[index] = (byte) 0xff;
                return frame;
            }
        }
        throw new AssertionError("Golden frame does not contain its UI method");
    }

    /// Replaces the null parameter with a raw IEEE-754 nonfinite floating-point Bridge value.
    ///
    /// @return frame containing a NaN parameter value
    private static byte[] nonfiniteParamFrame() {
        byte[] nonfinite = new byte[]{
                (byte) 0x92, 0x03, (byte) 0xcb,
                (byte) 0x7f, (byte) 0xf8, 0, 0, 0, 0, 0, 0
        };
        byte[] body = Arrays.copyOfRange(REQUEST_GOLDEN_FRAME, Integer.BYTES,
                REQUEST_GOLDEN_FRAME.length - 3 + nonfinite.length);
        System.arraycopy(nonfinite, 0, body, body.length - nonfinite.length, nonfinite.length);
        return frame(body);
    }

    /// Appends one byte after an otherwise complete Bridge value body.
    ///
    /// @return frame containing trailing Bridge wire bytes
    private static byte[] frameWithTrailingByte() {
        byte[] body = Arrays.copyOfRange(REQUEST_GOLDEN_FRAME, Integer.BYTES, REQUEST_GOLDEN_FRAME.length + 1);
        body[body.length - 1] = 0;
        return frame(body);
    }

    /// Frames an already encoded Bridge Value body.
    ///
    /// @param body complete Bridge value wire bytes
    /// @return four-byte big-endian length prefixed frame
    private static byte[] frame(byte[] body) {
        byte[] frame = new byte[Integer.BYTES + body.length];
        frame[0] = (byte) (body.length >>> 24);
        frame[1] = (byte) (body.length >>> 16);
        frame[2] = (byte) (body.length >>> 8);
        frame[3] = (byte) body.length;
        System.arraycopy(body, 0, frame, Integer.BYTES, body.length);
        return frame;
    }

    /// Creates one canonical ordered request envelope.
    ///
    /// @param version wire schema version
    /// @param requestId direction-scoped request identifier
    /// @param method request method
    /// @param params arbitrary nested parameter value
    /// @return mutable ordered envelope for malformed-wire test fixtures
    private static Map<String, BridgeValue> requestEnvelope(long version, long requestId, String method, BridgeValue params) {
        return orderedMap(
                "schemaVersion", BridgeValue.integer(version),
                "type", BridgeValue.string("request"),
                "requestId", BridgeValue.integer(requestId),
                "method", BridgeValue.string(method),
                "params", params
        );
    }

    /// Asserts that a frontend-bound frame fails closed.
    ///
    /// @param frame malformed frame
    private static void assertInvalidFrame(byte[] frame) {
        assertInvalidFrame(frame, UiFrontendWireCodec.InboundEndpoint.FRONTEND);
    }

    /// Asserts that a frame fails closed for one receiving endpoint.
    ///
    /// @param frame malformed frame
    /// @param endpoint receiving endpoint
    private static void assertInvalidFrame(byte[] frame, UiFrontendWireCodec.InboundEndpoint endpoint) {
        assertThrows(IOException.class,
                () -> UiFrontendWireCodec.read(new ByteArrayInputStream(frame), endpoint));
    }

    /// Creates one insertion-ordered map from alternating immutable entries.
    ///
    /// @param entries alternating string keys and Bridge values
    /// @return insertion-ordered map
    private static Map<String, BridgeValue> orderedMap(Object... entries) {
        Map<String, BridgeValue> values = new LinkedHashMap<>(entries.length / 2);
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], (BridgeValue) entries[index + 1]);
        }
        return values;
    }

    /// Delivers a source array one byte at a time to exercise exact fragmented reads.
    @NotNullByDefault
    private static final class FragmentedInputStream extends InputStream {
        /// Source bytes that remain owned by this test fixture.
        private final byte @Unmodifiable [] source;

        /// Next unread source index.
        private int index;

        /// Creates one single-byte source reader.
        ///
        /// @param source complete source frame
        private FragmentedInputStream(byte[] source) {
            this.source = source.clone();
        }

        /// Reads one byte or reports end of stream.
        ///
        /// @return unsigned source byte or `-1` at EOF
        @Override
        public int read() {
            if (index == source.length) {
                return -1;
            }
            return Byte.toUnsignedInt(source[index++]);
        }
    }
}
