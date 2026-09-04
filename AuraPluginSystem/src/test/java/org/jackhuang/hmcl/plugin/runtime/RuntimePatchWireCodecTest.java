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

import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jackhuang.hmcl.plugin.PluginPatchInvocation;
import org.jackhuang.hmcl.plugin.PluginPatchResult;
import org.jackhuang.hmcl.plugin.bridge.BridgeHandle;
import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jackhuang.hmcl.plugin.bridge.RuntimeBridgeWireCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the canonical Bridge Value v1 contract used by external Runtime Patch callbacks.
@NotNullByDefault
public final class RuntimePatchWireCodecTest {
    /// Encodes the complete request envelope in its one accepted field order.
    ///
    /// @throws Exception if the codec rejects the valid fixture
    @Test
    public void encodeBeforeInvocationAsCanonicalBridgeValueV1() throws Exception {
        PluginPatchDeclaration declaration = declaration(
                PluginPatchDeclaration.PatchType.BEFORE,
                List.of("java.lang.String", "long")
        );
        PluginPatchInvocation invocation = PluginPatchInvocation.before(
                declaration,
                null,
                List.of("value", 4L)
        );

        try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
            BridgeValue decoded = RuntimeBridgeWireCodec.decode(codec.encodeInvocation(invocation));
            Map<String, BridgeValue> expected = new LinkedHashMap<>();
            expected.put("schemaVersion", BridgeValue.integer(1L));
            expected.put("target", BridgeValue.string("org.jackhuang.hmcl.Launcher"));
            expected.put("method", BridgeValue.string("launch"));
            expected.put("parameters", BridgeValue.array(List.of(
                    BridgeValue.string("java.lang.String"),
                    BridgeValue.string("long")
            )));
            expected.put("type", BridgeValue.string("before"));
            expected.put("receiver", BridgeValue.nullValue());
            expected.put("arguments", BridgeValue.array(List.of(
                    BridgeValue.string("value"),
                    BridgeValue.integer(4L)
            )));
            expected.put("result", BridgeValue.nullValue());

            assertEquals(BridgeValue.map(expected), decoded);
        }
    }

    /// Locks the exact canonical Bridge Value v1 bytes and ordered request fields.
    ///
    /// @throws Exception if the codec rejects the valid fixture
    @Test
    public void encodeMinimalInvocationAsGoldenWireBytes() throws Exception {
        assertArrayEquals(
                hex("""
                        92 07 dd 00 00 00 08
                        92 db 00 00 00 0d 73 63 68 65 6d 61 56 65 72 73 69 6f 6e
                           92 02 d3 00 00 00 00 00 00 00 01
                        92 db 00 00 00 06 74 61 72 67 65 74
                           92 04 db 00 00 00 1b 6f 72 67 2e 6a 61 63 6b 68 75 61 6e 67 2e 68 6d 63 6c 2e 4c 61 75 6e 63 68 65 72
                        92 db 00 00 00 06 6d 65 74 68 6f 64
                           92 04 db 00 00 00 06 6c 61 75 6e 63 68
                        92 db 00 00 00 0a 70 61 72 61 6d 65 74 65 72 73
                           92 06 dd 00 00 00 00
                        92 db 00 00 00 04 74 79 70 65
                           92 04 db 00 00 00 06 62 65 66 6f 72 65
                        92 db 00 00 00 08 72 65 63 65 69 76 65 72
                           92 00 c0
                        92 db 00 00 00 09 61 72 67 75 6d 65 6e 74 73
                           92 06 dd 00 00 00 00
                        92 db 00 00 00 06 72 65 73 75 6c 74
                           92 00 c0
                        """),
                encode(emptyBeforeInvocation())
        );
    }

    /// Converts every scalar representation and one invocation-local reference handle back to Java values.
    ///
    /// @throws Exception if the valid replacement cannot be encoded or decoded
    @Test
    public void decodeArgumentReplacementWithDeclaredJvmTypes() throws Exception {
        Object reference = new StringBuilder("original");
        PluginPatchDeclaration declaration = declaration(
                PluginPatchDeclaration.PatchType.BEFORE,
                List.of(
                        "boolean", "byte", "char", "short", "int", "long", "float", "double",
                        "java.lang.String", "byte[]", "java.lang.StringBuilder"
                )
        );
        PluginPatchInvocation invocation = PluginPatchInvocation.before(
                declaration,
                null,
                List.of(
                        true, (byte) 1, 'a', (short) 2, 3, 4L, 1.25F, 2.5D,
                        "before", new byte[]{1, 2}, reference
                )
        );

        try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
            BridgeValue.MapValue request = request(codec, invocation);
            BridgeValue.ArrayValue requestArguments = (BridgeValue.ArrayValue) request.values().get("arguments");
            BridgeValue referenceHandle = requestArguments.values().get(10);
            byte[] response = argumentsResponse(List.of(
                    BridgeValue.bool(false),
                    BridgeValue.integer(-2L),
                    BridgeValue.integer('Z'),
                    BridgeValue.integer(32000L),
                    BridgeValue.integer(42L),
                    BridgeValue.integer(Long.MIN_VALUE),
                    BridgeValue.floating(0.5D),
                    BridgeValue.floating(0.25D),
                    BridgeValue.string("after"),
                    BridgeValue.bytes(new byte[]{9, 8}),
                    referenceHandle
            ));

            PluginPatchResult result = codec.decodeResult(response, invocation);

            assertEquals(PluginPatchResult.Action.ARGUMENTS, result.action());
            assertEquals(false, result.arguments().get(0));
            assertEquals((byte) -2, result.arguments().get(1));
            assertEquals('Z', result.arguments().get(2));
            assertEquals((short) 32000, result.arguments().get(3));
            assertEquals(42, result.arguments().get(4));
            assertEquals(Long.MIN_VALUE, result.arguments().get(5));
            assertEquals(0.5F, result.arguments().get(6));
            assertEquals(0.25D, result.arguments().get(7));
            assertEquals("after", result.arguments().get(8));
            assertArrayEquals(new byte[]{9, 8}, (byte[]) result.arguments().get(9));
            assertSame(reference, result.arguments().get(10));
        }
    }

    /// Preserves explicit nulls and decodes a returned input handle only for the active invocation.
    ///
    /// @throws Exception if the valid handle exchange fails
    @Test
    public void decodeReturnHandleAndInvalidateInvocationTable() throws Exception {
        Object resultReference = new ArrayList<>();
        PluginPatchDeclaration declaration = declaration(
                PatchTarget.class.getName(),
                "objectResult",
                PluginPatchDeclaration.PatchType.AFTER,
                List.of("java.lang.Object")
        );
        PluginPatchInvocation invocation = PluginPatchInvocation.after(
                declaration,
                null,
                Collections.singletonList((@Nullable Object) null),
                resultReference
        );
        BridgeHandle returnedHandle;

        try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
            BridgeValue.MapValue request = request(codec, invocation);
            returnedHandle = ((BridgeValue.HandleValue) request.values().get("result")).value();
            PluginPatchResult result = codec.decodeResult(
                    returnResponse(BridgeValue.handle(returnedHandle)), invocation);

            assertEquals(PluginPatchResult.Action.RETURN, result.action());
            assertSame(resultReference, result.returnValue());
            assertThrows(IOException.class, () -> codec.decodeResult(unchangedResponse(), invocation));
        }

        try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
            codec.encodeInvocation(invocation);
            BridgeHandle staleHandle = returnedHandle;
            assertThrows(IOException.class, () -> codec.decodeResult(
                    returnResponse(BridgeValue.handle(staleHandle)), invocation));
        }
    }

    /// Invalidates invocation-local state when response decoding is attempted with another invocation object.
    ///
    /// @throws Exception if fixture encoding unexpectedly fails
    @Test
    public void invalidateAfterWrongInvocationDecodeAttempt() throws Exception {
        PluginPatchInvocation invocation = emptyReplaceInvocation();
        PluginPatchInvocation wrongInvocation = PluginPatchInvocation.replace(
                invocation.declaration(), null, List.of());

        try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
            codec.encodeInvocation(invocation);

            assertThrows(IOException.class, () -> codec.decodeResult(unchangedResponse(), wrongInvocation));
            assertThrows(IOException.class, () -> codec.decodeResult(unchangedResponse(), invocation));
        }
    }

    /// Decodes the minimal unchanged response without accepting extra payload fields.
    ///
    /// @throws Exception if the valid unchanged response fails
    @Test
    public void decodeMinimalUnchangedResponse() throws Exception {
        PluginPatchInvocation invocation = PluginPatchInvocation.replace(
                declaration(PluginPatchDeclaration.PatchType.REPLACE, List.of()),
                null,
                List.of()
        );
        try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
            codec.encodeInvocation(invocation);
            assertEquals(
                    PluginPatchResult.Action.UNCHANGED,
                    codec.decodeResult(unchangedResponse(), invocation).action()
            );
        }
    }

    /// Narrows Bridge numeric scalars to the exact boxed JVM return type resolved from the target method.
    ///
    /// @throws Exception if target resolution or valid response decoding fails
    @Test
    public void decodePrimitiveReplacementUsingResolvedTargetReturnType() throws Exception {
        PluginPatchInvocation integerInvocation = PluginPatchInvocation.replace(
                declaration(
                        PatchTarget.class.getName(),
                        "integerResult",
                        PluginPatchDeclaration.PatchType.REPLACE,
                        List.of()
                ),
                null,
                List.of()
        );
        try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
            codec.encodeInvocation(integerInvocation);
            assertEquals(42, codec.decodeResult(
                    returnResponse(BridgeValue.integer(42L)), integerInvocation).returnValue());
        }

        PluginPatchInvocation floatInvocation = PluginPatchInvocation.replace(
                declaration(
                        PatchTarget.class.getName(),
                        "floatResult",
                        PluginPatchDeclaration.PatchType.REPLACE,
                        List.of()
                ),
                null,
                List.of()
        );
        try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
            codec.encodeInvocation(floatInvocation);
            assertEquals(0.5F, codec.decodeResult(
                    returnResponse(BridgeValue.floating(0.5D)), floatInvocation).returnValue());
        }
    }

    /// Encodes a void `after` result as null and rejects attempts to replace the nonexistent return value.
    ///
    /// @throws Exception if valid request or response encoding fails
    @Test
    public void supportVoidAfterOnlyWithUnchangedResult() throws Exception {
        PluginPatchInvocation invocation = PluginPatchInvocation.after(
                declaration(
                        PatchTarget.class.getName(),
                        "voidResult",
                        PluginPatchDeclaration.PatchType.AFTER,
                        List.of()
                ),
                null,
                List.of(),
                null
        );

        try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
            BridgeValue.MapValue request = request(codec, invocation);
            assertEquals(BridgeValue.nullValue(), request.values().get("result"));
            assertEquals(
                    PluginPatchResult.Action.UNCHANGED,
                    codec.decodeResult(unchangedResponse(), invocation).action()
            );
        }

        assertMalformedResponse(invocation, returnResponse(BridgeValue.nullValue()));
    }

    /// Rejects response maps whose members are missing, unknown, or not in canonical order.
    ///
    /// @throws Exception if malformed fixture construction fails
    @Test
    public void rejectUnknownMissingOrOutOfOrderResponseMembers() throws Exception {
        PluginPatchInvocation invocation = emptyBeforeInvocation();
        Map<String, BridgeValue> outOfOrder = new LinkedHashMap<>();
        outOfOrder.put("action", BridgeValue.string("unchanged"));
        outOfOrder.put("schemaVersion", BridgeValue.integer(1L));
        assertMalformedResponse(invocation, RuntimeBridgeWireCodec.encode(BridgeValue.map(outOfOrder)));

        Map<String, BridgeValue> missing = new LinkedHashMap<>();
        missing.put("schemaVersion", BridgeValue.integer(1L));
        assertMalformedResponse(invocation, RuntimeBridgeWireCodec.encode(BridgeValue.map(missing)));

        Map<String, BridgeValue> unknown = new LinkedHashMap<>();
        unknown.put("schemaVersion", BridgeValue.integer(1L));
        unknown.put("action", BridgeValue.string("unchanged"));
        unknown.put("extra", BridgeValue.nullValue());
        assertMalformedResponse(invocation, RuntimeBridgeWireCodec.encode(BridgeValue.map(unknown)));
    }

    /// Rejects duplicate map keys, non-finite numbers, and byte values beyond Bridge Value limits.
    ///
    /// @throws Exception if malformed fixture construction fails
    @Test
    public void rejectDuplicateKeysNonFiniteAndOversizedValues() throws Exception {
        assertMalformedResponse(emptyBeforeInvocation(), duplicateSchemaVersionResponse());

        byte[] nonFinite = returnResponse(BridgeValue.floating(0.0D));
        ByteBuffer.wrap(nonFinite, nonFinite.length - Long.BYTES, Long.BYTES)
                .putLong(Double.doubleToRawLongBits(Double.NaN));
        assertMalformedResponse(emptyReplaceInvocation(), nonFinite);

        assertMalformedResponse(emptyReplaceInvocation(), oversizedReturnBytesResponse());
    }

    /// Rejects actions that do not apply at the declaration position and malformed replacement arguments.
    ///
    /// @throws Exception if fixture encoding fails
    @Test
    public void rejectWrongActionArgumentCountAndTypes() throws Exception {
        PluginPatchInvocation before = PluginPatchInvocation.before(
                declaration(PluginPatchDeclaration.PatchType.BEFORE, List.of("int")),
                null,
                List.of(1)
        );
        assertMalformedResponse(before, returnResponse(BridgeValue.integer(1L)));
        assertMalformedResponse(before, argumentsResponse(List.of()));
        assertMalformedResponse(before, argumentsResponse(List.of(BridgeValue.string("wrong"))));

        PluginPatchInvocation replace = emptyReplaceInvocation();
        assertMalformedResponse(replace, argumentsResponse(List.of()));
    }

    /// Rejects a handle when the referenced input object is not assignable to the target parameter type.
    ///
    /// @throws Exception if fixture encoding fails
    @Test
    public void rejectHandleWithWrongDeclaredJvmType() throws Exception {
        PluginPatchDeclaration declaration = declaration(
                PluginPatchDeclaration.PatchType.BEFORE,
                List.of("java.lang.StringBuilder", "java.util.List")
        );
        PluginPatchInvocation invocation = PluginPatchInvocation.before(
                declaration,
                null,
                List.of(new StringBuilder("value"), new ArrayList<>())
        );

        try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
            BridgeValue.ArrayValue arguments = (BridgeValue.ArrayValue) request(codec, invocation)
                    .values().get("arguments");
            assertThrows(IOException.class, () -> codec.decodeResult(
                    argumentsResponse(List.of(arguments.values().get(1), arguments.values().get(0))),
                    invocation
            ));
        }
    }

    /// Rejects malformed invocation shape and classifies incompatible Java values before Provider entry.
    @Test
    public void rejectWrongInputCountTypesNullPrimitiveAndNonFiniteNumber() {
        assertThrows(IOException.class, () -> encode(PluginPatchInvocation.before(
                declaration(PluginPatchDeclaration.PatchType.BEFORE, List.of("int")),
                null,
                List.of()
        )));
        assertThrows(RuntimePatchWireCodec.TypeMismatchException.class,
                () -> encode(PluginPatchInvocation.before(
                declaration(PluginPatchDeclaration.PatchType.BEFORE, List.of("int")),
                null,
                List.of(1L)
        )));
        assertThrows(RuntimePatchWireCodec.TypeMismatchException.class,
                () -> encode(PluginPatchInvocation.before(
                declaration(PluginPatchDeclaration.PatchType.BEFORE, List.of("int")),
                null,
                Collections.singletonList((@Nullable Object) null)
        )));
        assertThrows(IOException.class, () -> encode(PluginPatchInvocation.before(
                declaration(PluginPatchDeclaration.PatchType.BEFORE, List.of("double")),
                null,
                List.of(Double.POSITIVE_INFINITY)
        )));
    }

    /// Creates one validated declaration against the stable test target.
    ///
    /// @param type callback position
    /// @param parameters ordered Java parameter names
    /// @return validated declaration
    private static PluginPatchDeclaration declaration(
            PluginPatchDeclaration.PatchType type,
            List<String> parameters
    ) {
        return declaration("org.jackhuang.hmcl.Launcher", "launch", type, parameters);
    }

    /// Creates one validated declaration against a caller-selected target method.
    ///
    /// @param target binary target class name
    /// @param method target method name
    /// @param type callback position
    /// @param parameters ordered Java parameter names
    /// @return validated declaration
    private static PluginPatchDeclaration declaration(
            String target,
            String method,
            PluginPatchDeclaration.PatchType type,
            List<String> parameters
    ) {
        return new PluginPatchDeclaration(
                target,
                method,
                type,
                parameters
        );
    }

    /// Creates one no-argument before invocation.
    ///
    /// @return immutable invocation
    private static PluginPatchInvocation emptyBeforeInvocation() {
        return PluginPatchInvocation.before(
                declaration(PluginPatchDeclaration.PatchType.BEFORE, List.of()),
                null,
                List.of()
        );
    }

    /// Creates one no-argument replacement invocation.
    ///
    /// @return immutable invocation
    private static PluginPatchInvocation emptyReplaceInvocation() {
        return PluginPatchInvocation.replace(
                declaration(PluginPatchDeclaration.PatchType.REPLACE, List.of()),
                null,
                List.of()
        );
    }

    /// Encodes one invocation with a fresh invocation-local codec.
    ///
    /// @param invocation callback input
    /// @return canonical request bytes
    /// @throws IOException if the invocation is malformed
    private static byte[] encode(PluginPatchInvocation invocation) throws IOException {
        try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
            return codec.encodeInvocation(invocation);
        }
    }

    /// Encodes and decodes one request as an ordered Bridge map.
    ///
    /// @param codec active invocation codec
    /// @param invocation callback input
    /// @return decoded request map
    /// @throws IOException if encoding fails
    private static BridgeValue.MapValue request(
            RuntimePatchWireCodec codec,
            PluginPatchInvocation invocation
    ) throws IOException {
        return (BridgeValue.MapValue) RuntimeBridgeWireCodec.decode(codec.encodeInvocation(invocation));
    }

    /// Asserts that one untrusted response fails against a freshly encoded invocation.
    ///
    /// @param invocation callback input
    /// @param response malformed response bytes
    /// @throws IOException if request encoding unexpectedly fails
    private static void assertMalformedResponse(
            PluginPatchInvocation invocation,
            byte[] response
    ) throws IOException {
        try (RuntimePatchWireCodec codec = new RuntimePatchWireCodec()) {
            codec.encodeInvocation(invocation);
            assertThrows(IOException.class, () -> codec.decodeResult(response, invocation));
        }
    }

    /// Encodes the canonical unchanged response.
    ///
    /// @return response bytes
    /// @throws IOException if Bridge encoding fails
    private static byte[] unchangedResponse() throws IOException {
        Map<String, BridgeValue> response = new LinkedHashMap<>();
        response.put("schemaVersion", BridgeValue.integer(1L));
        response.put("action", BridgeValue.string("unchanged"));
        return RuntimeBridgeWireCodec.encode(BridgeValue.map(response));
    }

    /// Encodes the canonical complete-argument replacement response.
    ///
    /// @param arguments replacement values
    /// @return response bytes
    /// @throws IOException if Bridge encoding fails
    private static byte[] argumentsResponse(List<BridgeValue> arguments) throws IOException {
        Map<String, BridgeValue> response = new LinkedHashMap<>();
        response.put("schemaVersion", BridgeValue.integer(1L));
        response.put("action", BridgeValue.string("arguments"));
        response.put("arguments", BridgeValue.array(arguments));
        return RuntimeBridgeWireCodec.encode(BridgeValue.map(response));
    }

    /// Encodes the canonical return-value response.
    ///
    /// @param result replacement return value
    /// @return response bytes
    /// @throws IOException if Bridge encoding fails
    private static byte[] returnResponse(BridgeValue result) throws IOException {
        Map<String, BridgeValue> response = new LinkedHashMap<>();
        response.put("schemaVersion", BridgeValue.integer(1L));
        response.put("action", BridgeValue.string("return"));
        response.put("result", result);
        return RuntimeBridgeWireCodec.encode(BridgeValue.map(response));
    }

    /// Builds a raw response with a duplicate key that cannot be represented by `BridgeValue.MapValue`.
    ///
    /// @return malformed response bytes
    /// @throws IOException if in-memory fixture encoding fails
    private static byte[] duplicateSchemaVersionResponse() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeMapHeader(output, 3);
            writeMapEntry(output, "schemaVersion", RuntimeBridgeWireCodec.encode(BridgeValue.integer(1L)));
            writeMapEntry(output, "schemaVersion", RuntimeBridgeWireCodec.encode(BridgeValue.integer(1L)));
            writeMapEntry(output, "action", RuntimeBridgeWireCodec.encode(BridgeValue.string("unchanged")));
        }
        return bytes.toByteArray();
    }

    /// Builds a raw response whose declared byte length exceeds the Bridge Value v1 limit.
    ///
    /// @return malformed response bytes
    /// @throws IOException if in-memory fixture encoding fails
    private static byte[] oversizedReturnBytesResponse() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeMapHeader(output, 3);
            writeMapEntry(output, "schemaVersion", RuntimeBridgeWireCodec.encode(BridgeValue.integer(1L)));
            writeMapEntry(output, "action", RuntimeBridgeWireCodec.encode(BridgeValue.string("return")));
            ByteArrayOutputStream valueBytes = new ByteArrayOutputStream();
            try (DataOutputStream value = new DataOutputStream(valueBytes)) {
                value.writeByte(0x92);
                value.writeByte(0x05);
                value.writeByte(0xc6);
                value.writeInt(BridgeValue.MAX_BYTE_LENGTH + 1);
            }
            writeMapEntry(output, "result", valueBytes.toByteArray());
        }
        return bytes.toByteArray();
    }

    /// Writes the canonical Bridge map root and direct-entry count.
    ///
    /// @param output fixture output
    /// @param size direct entry count
    /// @throws IOException if writing fails
    private static void writeMapHeader(DataOutputStream output, int size) throws IOException {
        output.writeByte(0x92);
        output.writeByte(0x07);
        output.writeByte(0xdd);
        output.writeInt(size);
    }

    /// Writes one raw ordered Bridge map entry.
    ///
    /// @param output fixture output
    /// @param key UTF-8 map key
    /// @param encodedValue complete encoded Bridge value
    /// @throws IOException if writing fails
    private static void writeMapEntry(
            DataOutputStream output,
            String key,
            byte[] encodedValue
    ) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        output.writeByte(0x92);
        output.writeByte(0xdb);
        output.writeInt(keyBytes.length);
        output.write(keyBytes);
        output.write(encodedValue);
    }

    /// Parses a whitespace-separated hand-derived hexadecimal wire fixture.
    ///
    /// @param value hexadecimal bytes
    /// @return parsed bytes
    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value.replaceAll("\\s+", ""));
    }

    /// Real reflective target signatures used to verify return-value narrowing without executing target code.
    @NotNullByDefault
    public static final class PatchTarget {
        /// Returns its reference argument unchanged.
        ///
        /// @param value input reference
        /// @return identical reference
        public static Object objectResult(Object value) {
            return value;
        }

        /// Returns one integer fixture value.
        ///
        /// @return integer fixture
        public static int integerResult() {
            return 0;
        }

        /// Returns one floating-point fixture value.
        ///
        /// @return float fixture
        public static float floatResult() {
            return 0.0F;
        }

        /// Performs a no-op for void-result Patch verification.
        public static void voidResult() {
        }
    }
}
