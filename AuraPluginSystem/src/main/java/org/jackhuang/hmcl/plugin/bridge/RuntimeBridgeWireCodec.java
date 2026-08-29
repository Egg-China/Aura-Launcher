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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Encodes and decodes the canonical tagged MessagePack Bridge Value v1 representation.
@NotNullByDefault
public final class RuntimeBridgeWireCodec {
    /// Maximum zero-based recursive depth accepted by the Rust SDK codec.
    private static final int MAX_DEPTH = BridgeValue.MAX_DEPTH - 1;

    /// Prevents instantiation of this stateless codec.
    private RuntimeBridgeWireCodec() {
    }

    /// Encodes one already validated closed Bridge value.
    ///
    /// @param value closed Bridge value
    /// @return canonical wire bytes
    /// @throws IOException if encoded size arithmetic fails
    public static byte[] encode(BridgeValue value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            new Encoder(output).writeValue(value, 0);
        }
        return bytes.toByteArray();
    }

    /// Decodes one complete canonical Bridge Value v1 message.
    ///
    /// @param input untrusted wire bytes
    /// @return validated closed Bridge value
    /// @throws IOException if input is malformed, noncanonical, truncated, trailing, or exceeds bounds
    public static BridgeValue decode(byte[] input) throws IOException {
        Decoder decoder = new Decoder(input);
        BridgeValue value = decoder.readValue(0);
        if (decoder.hasRemaining()) {
            throw malformed();
        }
        return value;
    }

    /// Creates a redacted malformed-wire failure.
    ///
    /// @return checked malformed-wire failure
    private static IOException malformed() {
        return new IOException("Runtime Bridge wire value is malformed");
    }

    /// Stateful canonical encoder with aggregate bounds matching the Rust SDK.
    @NotNullByDefault
    private static final class Encoder {
        /// Binary output receiving canonical MessagePack markers and values.
        private final DataOutputStream output;

        /// Aggregate string, map-key, and byte content written so far.
        private int contentBytes;

        /// Aggregate recursively encoded value count.
        private int values;

        /// Creates one encoder targeting an in-memory output.
        ///
        /// @param output binary target
        private Encoder(DataOutputStream output) {
            this.output = output;
        }

        /// Writes one tagged value recursively.
        ///
        /// @param value current value
        /// @param depth zero-based recursive depth
        /// @throws IOException if output or bounds validation fails
        private void writeValue(BridgeValue value, int depth) throws IOException {
            if (depth > MAX_DEPTH || values >= BridgeValue.MAX_TOTAL_VALUES) {
                throw malformed();
            }
            values++;
            output.writeByte(0x92);
            output.writeByte(value.tag().ordinal());
            if (value instanceof BridgeValue.NullValue) {
                output.writeByte(0xc0);
            } else if (value instanceof BridgeValue.BooleanValue bool) {
                output.writeByte(bool.value() ? 0xc3 : 0xc2);
            } else if (value instanceof BridgeValue.IntegerValue integer) {
                output.writeByte(0xd3);
                output.writeLong(integer.value());
            } else if (value instanceof BridgeValue.FloatValue floating) {
                output.writeByte(0xcb);
                output.writeLong(Double.doubleToRawLongBits(floating.value()));
            } else if (value instanceof BridgeValue.StringValue string) {
                writeString(string.value(), BridgeValue.MAX_STRING_UTF8_LENGTH, true);
            } else if (value instanceof BridgeValue.BytesValue bytes) {
                writeBytes(bytes.value());
            } else if (value instanceof BridgeValue.ArrayValue array) {
                writeArray(array.values(), depth);
            } else if (value instanceof BridgeValue.MapValue map) {
                writeMap(map.values(), depth);
            } else if (value instanceof BridgeValue.HandleValue handle) {
                writeHandle(handle.value());
            } else if (value instanceof BridgeValue.ErrorValue error) {
                writeString(error.value().code(), 128, false);
            } else {
                throw malformed();
            }
        }

        /// Writes one bounded byte value.
        ///
        /// @param value byte content
        /// @throws IOException if size bounds or output fails
        private void writeBytes(byte[] value) throws IOException {
            addContent(value.length, BridgeValue.MAX_BYTE_LENGTH);
            output.writeByte(0xc6);
            output.writeInt(value.length);
            output.write(value);
        }

        /// Writes one bounded array and its children.
        ///
        /// @param entries array entries
        /// @param depth parent depth
        /// @throws IOException if bounds or child encoding fails
        private void writeArray(List<BridgeValue> entries, int depth) throws IOException {
            writeContainerLength(entries.size());
            for (BridgeValue entry : entries) {
                writeValue(entry, depth + 1);
            }
        }

        /// Writes one insertion-ordered map and its unique keys.
        ///
        /// @param entries ordered map entries
        /// @param depth parent depth
        /// @throws IOException if bounds or child encoding fails
        private void writeMap(Map<String, BridgeValue> entries, int depth) throws IOException {
            writeContainerLength(entries.size());
            for (Map.Entry<String, BridgeValue> entry : entries.entrySet()) {
                output.writeByte(0x92);
                writeString(entry.getKey(), BridgeValue.MAX_STRING_UTF8_LENGTH, true);
                writeValue(entry.getValue(), depth + 1);
            }
        }

        /// Writes one positive generation-safe object handle.
        ///
        /// @param handle opaque handle
        /// @throws IOException if output fails
        private void writeHandle(BridgeHandle handle) throws IOException {
            output.writeByte(0x93);
            output.writeByte(0xcf);
            output.writeLong(handle.id());
            output.writeByte(0xcf);
            output.writeLong(handle.generation());
            writeString(handle.type(), BridgeHandle.MAX_TYPE_LENGTH, false);
        }

        /// Writes one canonical UTF-8 string.
        ///
        /// @param value source text
        /// @param limit individual encoded limit
        /// @param countContent whether this string counts toward aggregate content
        /// @throws IOException if text or output is invalid
        private void writeString(String value, int limit, boolean countContent) throws IOException {
            byte[] encoded;
            try {
                ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(value));
                encoded = new byte[buffer.remaining()];
                buffer.get(encoded);
            } catch (CharacterCodingException exception) {
                throw malformed();
            }
            if (countContent) {
                addContent(encoded.length, limit);
            } else if (encoded.length > limit) {
                throw malformed();
            }
            output.writeByte(0xdb);
            output.writeInt(encoded.length);
            output.write(encoded);
        }

        /// Writes one canonical array/map length.
        ///
        /// @param length direct entry count
        /// @throws IOException if count exceeds bounds or output fails
        private void writeContainerLength(int length) throws IOException {
            if (length > BridgeValue.MAX_CONTAINER_ENTRIES) {
                throw malformed();
            }
            output.writeByte(0xdd);
            output.writeInt(length);
        }

        /// Adds bounded content length without overflow.
        ///
        /// @param length new content length
        /// @param individualLimit per-value limit
        /// @throws IOException if individual or aggregate bounds are exceeded
        private void addContent(int length, int individualLimit) throws IOException {
            if (length < 0 || length > individualLimit
                    || length > BridgeValue.MAX_TOTAL_CONTENT_LENGTH - contentBytes) {
                throw malformed();
            }
            contentBytes += length;
        }
    }

    /// Stateful strict decoder that rejects alternate MessagePack representations.
    @NotNullByDefault
    private static final class Decoder {
        /// Untrusted input viewed in network byte order.
        private final ByteBuffer input;

        /// Aggregate decoded string, map-key, and byte content.
        private int contentBytes;

        /// Aggregate recursively decoded value count.
        private int values;

        /// Creates one decoder over a defensive read-only view.
        ///
        /// @param input untrusted wire bytes
        private Decoder(byte[] input) {
            this.input = ByteBuffer.wrap(input.clone()).order(ByteOrder.BIG_ENDIAN).asReadOnlyBuffer();
        }

        /// Returns whether bytes remain after decoding one root.
        ///
        /// @return trailing-byte state
        private boolean hasRemaining() {
            return input.hasRemaining();
        }

        /// Reads one canonical tagged value recursively.
        ///
        /// @param depth zero-based recursive depth
        /// @return decoded value
        /// @throws IOException if input is malformed or exceeds bounds
        private BridgeValue readValue(int depth) throws IOException {
            if (depth > MAX_DEPTH || values >= BridgeValue.MAX_TOTAL_VALUES) {
                throw malformed();
            }
            values++;
            expect(0x92);
            int tag = readUnsignedByte();
            return switch (tag) {
                case 0 -> {
                    expect(0xc0);
                    yield BridgeValue.nullValue();
                }
                case 1 -> BridgeValue.bool(readBoolean());
                case 2 -> {
                    expect(0xd3);
                    yield BridgeValue.integer(readLong());
                }
                case 3 -> {
                    expect(0xcb);
                    double value = Double.longBitsToDouble(readLong());
                    if (!Double.isFinite(value)) {
                        throw malformed();
                    }
                    yield BridgeValue.floating(value);
                }
                case 4 -> BridgeValue.string(readString(BridgeValue.MAX_STRING_UTF8_LENGTH, true));
                case 5 -> BridgeValue.bytes(readBytes());
                case 6 -> BridgeValue.array(readArray(depth));
                case 7 -> BridgeValue.map(readMap(depth));
                case 8 -> BridgeValue.handle(readHandle());
                case 9 -> BridgeValue.error(readError());
                default -> throw malformed();
            };
        }

        /// Reads one canonical boolean marker.
        ///
        /// @return decoded boolean
        /// @throws IOException if marker is invalid
        private boolean readBoolean() throws IOException {
            return switch (readUnsignedByte()) {
                case 0xc2 -> false;
                case 0xc3 -> true;
                default -> throw malformed();
            };
        }

        /// Reads one bounded byte sequence after validating its length before allocation.
        ///
        /// @return decoded bytes
        /// @throws IOException if marker, length, or input is invalid
        private byte[] readBytes() throws IOException {
            expect(0xc6);
            int length = readLength(BridgeValue.MAX_BYTE_LENGTH);
            addContent(length, BridgeValue.MAX_BYTE_LENGTH);
            return readExact(length);
        }

        /// Reads one bounded array.
        ///
        /// @param depth parent depth
        /// @return decoded entries
        /// @throws IOException if input or children are invalid
        private List<BridgeValue> readArray(int depth) throws IOException {
            int length = readContainerLength();
            List<BridgeValue> entries = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                entries.add(readValue(depth + 1));
            }
            return entries;
        }

        /// Reads one bounded insertion-ordered map with unique keys.
        ///
        /// @param depth parent depth
        /// @return decoded ordered entries
        /// @throws IOException if input, keys, or children are invalid
        private Map<String, BridgeValue> readMap(int depth) throws IOException {
            int length = readContainerLength();
            Map<String, BridgeValue> entries = new LinkedHashMap<>(length);
            Set<String> keys = new HashSet<>(length);
            for (int index = 0; index < length; index++) {
                expect(0x92);
                String key = readString(BridgeValue.MAX_STRING_UTF8_LENGTH, true);
                if (!keys.add(key)) {
                    throw malformed();
                }
                entries.put(key, readValue(depth + 1));
            }
            return entries;
        }

        /// Reads one positive handle tuple.
        ///
        /// @return decoded opaque handle
        /// @throws IOException if marker or handle fields are invalid
        private BridgeHandle readHandle() throws IOException {
            expect(0x93);
            expect(0xcf);
            long id = readLong();
            expect(0xcf);
            long generation = readLong();
            String type = readString(BridgeHandle.MAX_TYPE_LENGTH, false);
            try {
                return new BridgeHandle(id, generation, type);
            } catch (IllegalArgumentException exception) {
                throw malformed();
            }
        }

        /// Reads one stable redacted error category.
        ///
        /// @return decoded portable error
        /// @throws IOException if the wire code is unknown
        private BridgeError readError() throws IOException {
            String code = readString(128, false);
            for (BridgeError.Category category : BridgeError.Category.values()) {
                if (category.code().equals(code)) {
                    return BridgeError.of(category);
                }
            }
            throw malformed();
        }

        /// Reads one canonical UTF-8 string.
        ///
        /// @param limit individual encoded limit
        /// @param countContent whether content counts toward the aggregate bound
        /// @return decoded text
        /// @throws IOException if marker, bytes, or UTF-8 are invalid
        private String readString(int limit, boolean countContent) throws IOException {
            expect(0xdb);
            int length = readLength(limit);
            if (countContent) {
                addContent(length, limit);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(readExact(length)))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw malformed();
            }
        }

        /// Reads one canonical bounded container length.
        ///
        /// @return direct entry count
        /// @throws IOException if marker or length is invalid
        private int readContainerLength() throws IOException {
            expect(0xdd);
            return readLength(BridgeValue.MAX_CONTAINER_ENTRIES);
        }

        /// Reads one nonnegative unsigned-32 length representable by Java arrays.
        ///
        /// @param limit maximum accepted length
        /// @return validated length
        /// @throws IOException if truncated, negative as signed, or over limit
        private int readLength(int limit) throws IOException {
            int length = readInt();
            if (length < 0 || length > limit) {
                throw malformed();
            }
            return length;
        }

        /// Adds bounded aggregate content without overflow.
        ///
        /// @param length new content length
        /// @param individualLimit individual value limit
        /// @throws IOException if a bound is exceeded
        private void addContent(int length, int individualLimit) throws IOException {
            if (length < 0 || length > individualLimit
                    || length > BridgeValue.MAX_TOTAL_CONTENT_LENGTH - contentBytes) {
                throw malformed();
            }
            contentBytes += length;
        }

        /// Requires one exact byte marker.
        ///
        /// @param expected expected unsigned marker
        /// @throws IOException if input is truncated or differs
        private void expect(int expected) throws IOException {
            if (readUnsignedByte() != expected) {
                throw malformed();
            }
        }

        /// Reads one unsigned byte.
        ///
        /// @return value in `[0, 255]`
        /// @throws IOException if input is truncated
        private int readUnsignedByte() throws IOException {
            if (input.remaining() < Byte.BYTES) {
                throw malformed();
            }
            return Byte.toUnsignedInt(input.get());
        }

        /// Reads one signed 32-bit value.
        ///
        /// @return decoded integer
        /// @throws IOException if input is truncated
        private int readInt() throws IOException {
            if (input.remaining() < Integer.BYTES) {
                throw malformed();
            }
            return input.getInt();
        }

        /// Reads one signed 64-bit value.
        ///
        /// @return decoded integer or preserved unsigned bit pattern
        /// @throws IOException if input is truncated
        private long readLong() throws IOException {
            if (input.remaining() < Long.BYTES) {
                throw malformed();
            }
            return input.getLong();
        }

        /// Copies one exact byte count after checking availability.
        ///
        /// @param length required byte count
        /// @return copied bytes
        /// @throws IOException if input is truncated
        private byte[] readExact(int length) throws IOException {
            if (length < 0 || input.remaining() < length) {
                throw malformed();
            }
            byte[] result = new byte[length];
            input.get(result);
            return result;
        }
    }
}
