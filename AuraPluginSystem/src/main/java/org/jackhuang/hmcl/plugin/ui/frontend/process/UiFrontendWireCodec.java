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

import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jackhuang.hmcl.plugin.bridge.RuntimeBridgeWireCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Encodes and decodes bounded `aura.ui.v1` messages for isolated UI frontend processes.
@NotNullByDefault
public final class UiFrontendWireCodec {
    /// Fixed protocol identifier for this UI frontend wire codec.
    public static final String PROTOCOL = "aura.ui.v1";

    /// Frozen UI wire schema generation.
    static final long SCHEMA_VERSION = 1L;

    /// Maximum accepted length-prefixed Bridge Value body size.
    static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    /// Prevents construction of this stateless codec.
    private UiFrontendWireCodec() {
    }

    /// Identifies the endpoint that is receiving a decoded wire frame.
    @NotNullByDefault
    public enum InboundEndpoint {
        /// The launcher JVM receives child-originated frames.
        LAUNCHER,

        /// The isolated UI frontend receives launcher-originated frames.
        FRONTEND
    }

    /// Reads one complete UI frame, returning `null` only for clean EOF before a frame header.
    ///
    /// @param input untrusted process stdout or stdin stream
    /// @param endpoint receiving endpoint used only for request direction validation
    /// @return decoded message or `null` for clean EOF
    /// @throws IOException if framing, Bridge value encoding, strict field order, direction, or values are invalid
    public static @Nullable UiFrontendMessage read(InputStream input, InboundEndpoint endpoint) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(endpoint, "endpoint");
        int first = input.read();
        if (first < 0) {
            return null;
        }
        byte[] remainder = readExact(input, Integer.BYTES - 1, "truncated frame header");
        int length = (first << 24)
                | (Byte.toUnsignedInt(remainder[0]) << 16)
                | (Byte.toUnsignedInt(remainder[1]) << 8)
                | Byte.toUnsignedInt(remainder[2]);
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw invalid("frame length is outside bounds");
        }
        byte[] body = readExact(input, length, "truncated frame body");
        BridgeValue value;
        try {
            value = RuntimeBridgeWireCodec.decode(body);
        } catch (IllegalArgumentException exception) {
            throw invalid("Bridge value is invalid", exception);
        }
        return decodeMessage(value, endpoint);
    }

    /// Writes one canonical UI message with a four-byte big-endian body length prefix.
    ///
    /// @param output process stdin or stdout stream
    /// @param message locally validated UI message
    /// @throws IOException if the message cannot be represented within the protocol bounds or output fails
    public static void write(OutputStream output, UiFrontendMessage message) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(message, "message");
        BridgeValue envelope = encodeMessage(message);
        byte[] body = RuntimeBridgeWireCodec.encode(envelope);
        if (body.length == 0 || body.length > MAX_FRAME_BYTES) {
            throw invalid("frame length is outside bounds");
        }
        output.write((body.length >>> 24) & 0xff);
        output.write((body.length >>> 16) & 0xff);
        output.write((body.length >>> 8) & 0xff);
        output.write(body.length & 0xff);
        output.write(body);
    }

    /// Decodes one strict ordered UI message envelope.
    ///
    /// @param value decoded Bridge root value
    /// @param endpoint receiving endpoint
    /// @return immutable UI message model
    /// @throws IOException if the envelope is not an exact supported UI message
    private static UiFrontendMessage decodeMessage(BridgeValue value, InboundEndpoint endpoint) throws IOException {
        @Unmodifiable Map<String, BridgeValue> prefix = orderedPrefix(value, "schemaVersion", "type", "requestId");
        long version = integer(field(prefix, "schemaVersion"));
        if (version != SCHEMA_VERSION) {
            throw invalid("unsupported schema version");
        }
        long requestId = positive(field(prefix, "requestId"));
        String type = string(field(prefix, "type"));
        try {
            return switch (type) {
                case "request" -> decodeRequest(value, endpoint, requestId);
                case "result" -> new UiFrontendMessage.Result(requestId,
                        field(orderedMap(value, "schemaVersion", "type", "requestId", "value"), "value"));
                case "error" -> {
                    @Unmodifiable Map<String, BridgeValue> error = orderedMap(value,
                            "schemaVersion", "type", "requestId", "code", "message");
                    yield new UiFrontendMessage.Error(requestId,
                            string(field(error, "code")), string(field(error, "message")));
                }
                default -> throw invalid("unsupported message type");
            };
        } catch (IllegalArgumentException exception) {
            throw invalid("message fields are invalid", exception);
        }
    }

    /// Decodes one request and applies its inbound request-ID parity rule.
    ///
    /// @param value decoded Bridge root value
    /// @param endpoint receiving endpoint
    /// @param requestId positive candidate identifier
    /// @return immutable request message
    /// @throws IOException if fields or request direction are invalid
    private static UiFrontendMessage.Request decodeRequest(
            BridgeValue value, InboundEndpoint endpoint, long requestId) throws IOException {
        @Unmodifiable Map<String, BridgeValue> request = orderedMap(value,
                "schemaVersion", "type", "requestId", "method", "params");
        boolean expectedEven = endpoint == InboundEndpoint.LAUNCHER;
        if (((requestId & 1L) == 0L) != expectedEven) {
            throw invalid("request ID belongs to the other endpoint");
        }
        return new UiFrontendMessage.Request(requestId,
                string(field(request, "method")), field(request, "params"));
    }

    /// Encodes one UI message into its canonical type-specific ordered envelope.
    ///
    /// @param message source message
    /// @return canonical Bridge map
    /// @throws IOException if an unsupported implementation is supplied
    private static BridgeValue encodeMessage(UiFrontendMessage message) throws IOException {
        Map<String, BridgeValue> envelope;
        if (message instanceof UiFrontendMessage.Request request) {
            envelope = map(
                    "schemaVersion", BridgeValue.integer(SCHEMA_VERSION),
                    "type", BridgeValue.string("request"),
                    "requestId", BridgeValue.integer(request.requestId()),
                    "method", BridgeValue.string(request.method()),
                    "params", request.params()
            );
        } else if (message instanceof UiFrontendMessage.Result result) {
            envelope = map(
                    "schemaVersion", BridgeValue.integer(SCHEMA_VERSION),
                    "type", BridgeValue.string("result"),
                    "requestId", BridgeValue.integer(result.requestId()),
                    "value", result.value()
            );
        } else if (message instanceof UiFrontendMessage.Error error) {
            envelope = map(
                    "schemaVersion", BridgeValue.integer(SCHEMA_VERSION),
                    "type", BridgeValue.string("error"),
                    "requestId", BridgeValue.integer(error.requestId()),
                    "code", BridgeValue.string(error.code()),
                    "message", BridgeValue.string(error.message())
            );
        } else {
            throw invalid("unsupported message model");
        }
        try {
            return BridgeValue.map(envelope);
        } catch (IllegalArgumentException exception) {
            throw invalid("message exceeds Bridge value bounds", exception);
        }
    }

    /// Requires an ordered map with exactly the named field sequence.
    ///
    /// @param value candidate Bridge value
    /// @param names exact ordered field names
    /// @return immutable ordered map entries
    /// @throws IOException if type, count, names, or their order differs
    private static @Unmodifiable Map<String, BridgeValue> orderedMap(BridgeValue value, String... names)
            throws IOException {
        if (!(value instanceof BridgeValue.MapValue map)) {
            throw invalid("message value must be a map");
        }
        @Unmodifiable Map<String, BridgeValue> fields = map.values();
        if (fields.size() != names.length) {
            throw invalid("message has unknown, duplicate, or missing fields");
        }
        Iterator<String> actual = fields.keySet().iterator();
        for (String name : names) {
            if (!actual.hasNext() || !name.equals(actual.next())) {
                throw invalid("message fields are out of order or unsupported");
            }
        }
        return fields;
    }

    /// Requires a map beginning with exactly the named ordered field prefix.
    ///
    /// @param value candidate Bridge value
    /// @param names required ordered field prefix
    /// @return immutable ordered map entries
    /// @throws IOException if type, prefix names, or their order differs
    private static @Unmodifiable Map<String, BridgeValue> orderedPrefix(BridgeValue value, String... names)
            throws IOException {
        if (!(value instanceof BridgeValue.MapValue map)) {
            throw invalid("message value must be a map");
        }
        @Unmodifiable Map<String, BridgeValue> fields = map.values();
        if (fields.size() < names.length) {
            throw invalid("message is missing required fields");
        }
        Iterator<String> actual = fields.keySet().iterator();
        for (String name : names) {
            if (!actual.hasNext() || !name.equals(actual.next())) {
                throw invalid("message fields are out of order or unsupported");
            }
        }
        return fields;
    }

    /// Returns one required field from an already exact ordered map.
    ///
    /// @param fields exact ordered map
    /// @param name field name
    /// @return required field value
    /// @throws IOException if a field is unexpectedly absent
    private static BridgeValue field(Map<String, BridgeValue> fields, String name) throws IOException {
        @Nullable BridgeValue value = fields.get(name);
        if (value == null) {
            throw invalid("message is missing a required field");
        }
        return value;
    }

    /// Requires one signed 64-bit Bridge integer.
    ///
    /// @param value candidate field value
    /// @return integer content
    /// @throws IOException if the field has another type
    private static long integer(BridgeValue value) throws IOException {
        if (value instanceof BridgeValue.IntegerValue integer) {
            return integer.value();
        }
        throw invalid("message field must be an integer");
    }

    /// Requires one positive signed 64-bit Bridge integer.
    ///
    /// @param value candidate field value
    /// @return positive identifier
    /// @throws IOException if the field is not positive
    private static long positive(BridgeValue value) throws IOException {
        long requestId = integer(value);
        if (requestId <= 0L) {
            throw invalid("request ID must be positive");
        }
        return requestId;
    }

    /// Requires one Bridge string value.
    ///
    /// @param value candidate field value
    /// @return text content
    /// @throws IOException if the field has another type
    private static String string(BridgeValue value) throws IOException {
        if (value instanceof BridgeValue.StringValue string) {
            return string.value();
        }
        throw invalid("message field must be a string");
    }

    /// Reads exactly one bounded byte sequence.
    ///
    /// @param input source process stream
    /// @param length required byte length
    /// @param failure stable failure detail
    /// @return complete byte sequence
    /// @throws IOException if EOF arrives early or reading fails
    private static byte[] readExact(InputStream input, int length, String failure) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw invalid(failure);
        }
        return bytes;
    }

    /// Builds one insertion-ordered map from alternating key and value entries.
    ///
    /// @param entries alternating string keys and Bridge values
    /// @return ordered map consumed immediately by the immutable Bridge value factory
    private static Map<String, BridgeValue> map(Object... entries) {
        Map<String, BridgeValue> values = new LinkedHashMap<>(entries.length / 2);
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], (BridgeValue) entries[index + 1]);
        }
        return values;
    }

    /// Creates a checked UI wire protocol validation failure.
    ///
    /// @param detail stable local failure detail
    /// @return checked wire failure
    private static IOException invalid(String detail) {
        return new IOException("Invalid aura.ui.v1 frontend wire message: " + detail);
    }

    /// Creates a checked UI wire protocol validation failure with a local cause.
    ///
    /// @param detail stable local failure detail
    /// @param cause local validation cause
    /// @return checked wire failure
    private static IOException invalid(String detail, Throwable cause) {
        return new IOException("Invalid aura.ui.v1 frontend wire message: " + detail, cause);
    }
}
