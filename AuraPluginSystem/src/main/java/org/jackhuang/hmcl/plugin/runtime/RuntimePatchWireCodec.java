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
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/// Maps one JVM Patch invocation to the strict `aura.patch.v1` Bridge Value v1 exchange.
///
/// Each instance is single-use and owns one invocation-local opaque handle generation. Decoding a response or
/// closing the codec invalidates every handle, so a Runtime payload cannot retain JVM references across callbacks.
@NotNullByDefault
public final class RuntimePatchWireCodec implements AutoCloseable {
    /// Current external Runtime Patch envelope version.
    private static final long SCHEMA_VERSION = 1L;

    /// Canonical language-neutral type carried by every invocation-local JVM reference handle.
    private static final String HANDLE_TYPE = "patch-reference";

    /// Process-local monotonic source preventing handle reuse across adjacent callback invocations.
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();

    /// Unique positive generation owned by this codec instance.
    private final long generation;

    /// Live reference objects indexed by their invocation-local transport handles.
    private final Map<BridgeHandle, Object> references = new LinkedHashMap<>();

    /// Identity-based reverse index which emits one handle when the same reference appears more than once.
    private final IdentityHashMap<Object, BridgeHandle> handles = new IdentityHashMap<>();

    /// Exact invocation instance bound by the request encoder, or `null` before encoding.
    private @Nullable PluginPatchInvocation boundInvocation;

    /// Next positive handle slot within this invocation generation.
    private long nextHandleId = 1L;

    /// Whether this invocation-local table has been invalidated.
    private boolean closed;

    /// Creates one empty single-use invocation codec with a fresh process-local handle generation.
    public RuntimePatchWireCodec() {
        generation = NEXT_GENERATION.incrementAndGet();
        if (generation <= 0L) {
            throw new IllegalStateException("Runtime Patch handle generation exhausted");
        }
    }

    /// Encodes one invocation as the canonical ordered `aura.patch.v1` request map.
    ///
    /// @param invocation exact immutable Patch invocation
    /// @return canonical Bridge Value v1 bytes
    /// @throws IOException if the invocation count, values, types, or target signature are malformed
    public byte[] encodeInvocation(PluginPatchInvocation invocation) throws IOException {
        if (closed || boundInvocation != null) {
            throw malformed();
        }
        PluginPatchInvocation current = Objects.requireNonNull(invocation, "invocation");
        boundInvocation = current;
        try {
            PluginPatchDeclaration declaration = current.declaration();
            declaration.validate();
            @Unmodifiable List<String> parameterNames = declaration.getParameters();
            @Unmodifiable List<@Nullable Object> arguments = current.arguments();
            if (parameterNames.size() != arguments.size()) {
                throw malformed();
            }

            List<BridgeValue> encodedParameters = new ArrayList<>(parameterNames.size());
            List<BridgeValue> encodedArguments = new ArrayList<>(arguments.size());
            for (int index = 0; index < parameterNames.size(); index++) {
                String parameterName = parameterNames.get(index);
                Class<?> parameterType = resolveJavaType(parameterName);
                encodedParameters.add(BridgeValue.string(parameterName));
                encodedArguments.add(encodeValue(arguments.get(index), parameterType));
            }

            BridgeValue receiver = current.receiver() == null
                    ? BridgeValue.nullValue()
                    : encodeValue(current.receiver(), resolveJavaType(declaration.getTarget()));
            BridgeValue result = BridgeValue.nullValue();
            if (declaration.getType() == PluginPatchDeclaration.PatchType.AFTER) {
                Class<?> returnType = resolveReturnType(declaration);
                if (returnType != void.class) {
                    result = encodeValue(current.result(), returnType);
                }
            }
            Map<String, BridgeValue> request = new LinkedHashMap<>();
            request.put("schemaVersion", BridgeValue.integer(SCHEMA_VERSION));
            request.put("target", BridgeValue.string(declaration.getTarget()));
            request.put("method", BridgeValue.string(declaration.getMethod()));
            request.put("parameters", BridgeValue.array(encodedParameters));
            request.put("type", BridgeValue.string(wirePatchType(declaration.getType())));
            request.put("receiver", receiver);
            request.put("arguments", BridgeValue.array(encodedArguments));
            request.put("result", result);
            return RuntimeBridgeWireCodec.encode(BridgeValue.map(request));
        } catch (IOException exception) {
            close();
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            close();
            throw malformed(exception);
        }
    }

    /// Decodes one canonical Runtime response and invalidates the invocation-local handle table.
    ///
    /// @param response untrusted Bridge Value v1 response bytes
    /// @param invocation exact invocation previously supplied to [encodeInvocation]
    /// @return validated transactional Patch result
    /// @throws IOException if the response or invocation identity is malformed
    public PluginPatchResult decodeResult(
            byte[] response,
            PluginPatchInvocation invocation
    ) throws IOException {
        try {
            requireBoundInvocation(invocation);
            BridgeValue decoded = RuntimeBridgeWireCodec.decode(
                    Objects.requireNonNull(response, "response"));
            if (!(decoded instanceof BridgeValue.MapValue responseMap)) {
                throw malformed();
            }
            Map<String, BridgeValue> values = responseMap.values();
            requireSchemaVersion(values);
            String action = requireString(values.get("action"));
            PluginPatchDeclaration declaration = invocation.declaration();
            return switch (action) {
                case "unchanged" -> {
                    requireKeys(values, List.of("schemaVersion", "action"));
                    yield PluginPatchResult.unchanged();
                }
                case "arguments" -> {
                    if (declaration.getType() != PluginPatchDeclaration.PatchType.BEFORE) {
                        throw malformed();
                    }
                    requireKeys(values, List.of("schemaVersion", "action", "arguments"));
                    BridgeValue.ArrayValue replacements = requireArray(values.get("arguments"));
                    yield PluginPatchResult.arguments(decodeArguments(
                            replacements.values(), declaration.getParameters()));
                }
                case "return" -> {
                    if (declaration.getType() == PluginPatchDeclaration.PatchType.BEFORE) {
                        throw malformed();
                    }
                    requireKeys(values, List.of("schemaVersion", "action", "result"));
                    Class<?> returnType = resolveReturnType(declaration);
                    if (returnType == void.class) {
                        throw malformed();
                    }
                    yield PluginPatchResult.returnValue(decodeValue(values.get("result"), returnType));
                }
                default -> throw malformed();
            };
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            throw malformed(exception);
        } finally {
            close();
        }
    }

    /// Invalidates all invocation-local handles and prevents subsequent encoding or decoding.
    @Override
    public void close() {
        closed = true;
        boundInvocation = null;
        references.clear();
        handles.clear();
    }

    /// Encodes one Java value after checking the exact declared JVM type.
    ///
    /// @param value nullable boxed primitive or reference value
    /// @param expectedType exact JVM type
    /// @return bounded Bridge value
    /// @throws IOException if the value does not match the declared type or is not transportable
    private BridgeValue encodeValue(
            @Nullable Object value,
            Class<?> expectedType
    ) throws IOException {
        if (value == null) {
            if (expectedType.isPrimitive()) {
                throw typeMismatch();
            }
            return BridgeValue.nullValue();
        }
        Class<?> boxedType = boxed(expectedType);
        if (!boxedType.isInstance(value)) {
            throw typeMismatch();
        }
        if (value instanceof Boolean booleanValue) {
            return BridgeValue.bool(booleanValue);
        }
        if (value instanceof Byte byteValue) {
            return BridgeValue.integer(byteValue.longValue());
        }
        if (value instanceof Character characterValue) {
            return BridgeValue.integer(characterValue);
        }
        if (value instanceof Short shortValue) {
            return BridgeValue.integer(shortValue.longValue());
        }
        if (value instanceof Integer integerValue) {
            return BridgeValue.integer(integerValue.longValue());
        }
        if (value instanceof Long longValue) {
            return BridgeValue.integer(longValue);
        }
        if (value instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) {
                throw malformed();
            }
            return BridgeValue.floating(floatValue.doubleValue());
        }
        if (value instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) {
                throw malformed();
            }
            return BridgeValue.floating(doubleValue);
        }
        if (value instanceof String stringValue) {
            return BridgeValue.string(stringValue);
        }
        if (value instanceof byte[] bytesValue) {
            return BridgeValue.bytes(bytesValue);
        }
        return BridgeValue.handle(retainReference(value));
    }

    /// Decodes a complete replacement argument list against the declaration's exact JVM parameter names.
    ///
    /// @param values untrusted ordered Bridge values
    /// @param parameterNames authoritative ordered JVM parameter names
    /// @return immutable-compatible nullable Java values
    /// @throws IOException if count, type, range, or handle validation fails
    private @Unmodifiable List<@Nullable Object> decodeArguments(
            List<BridgeValue> values,
            List<String> parameterNames
    ) throws IOException {
        if (values.size() != parameterNames.size()) {
            throw malformed();
        }
        List<@Nullable Object> arguments = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            arguments.add(decodeValue(values.get(index), resolveJavaType(parameterNames.get(index))));
        }
        return java.util.Collections.unmodifiableList(arguments);
    }

    /// Decodes one Bridge scalar or invocation-local reference for an exact JVM type.
    ///
    /// @param value untrusted Bridge value
    /// @param expectedType exact JVM type
    /// @return validated nullable Java value
    /// @throws IOException if the value is incompatible, out of range, structured, or stale
    private @Nullable Object decodeValue(
            @Nullable BridgeValue value,
            Class<?> expectedType
    ) throws IOException {
        if (value == null) {
            throw malformed();
        }
        if (value instanceof BridgeValue.NullValue) {
            if (expectedType.isPrimitive()) {
                throw typeMismatch();
            }
            return null;
        }
        Class<?> boxedType = boxed(expectedType);
        @Nullable Object decoded;
        if (value instanceof BridgeValue.BooleanValue booleanValue) {
            decoded = booleanValue.value();
        } else if (value instanceof BridgeValue.IntegerValue integerValue) {
            decoded = decodeInteger(integerValue.value(), boxedType);
        } else if (value instanceof BridgeValue.FloatValue floatValue) {
            decoded = decodeFloat(floatValue.value(), boxedType);
        } else if (value instanceof BridgeValue.StringValue stringValue) {
            decoded = stringValue.value();
        } else if (value instanceof BridgeValue.BytesValue bytesValue) {
            decoded = bytesValue.value();
        } else if (value instanceof BridgeValue.HandleValue handleValue) {
            decoded = resolveReference(handleValue.value(), expectedType);
        } else {
            throw malformed();
        }
        if (!boxedType.isInstance(decoded)) {
            throw typeMismatch();
        }
        return decoded;
    }

    /// Narrows one Bridge signed integer to the exact primitive wrapper or a safe assignable `Long`.
    ///
    /// @param value signed Bridge integer
    /// @param boxedType boxed expected JVM type
    /// @return exact boxed value
    /// @throws IOException if the value is out of range or incompatible
    private static Object decodeInteger(long value, Class<?> boxedType) throws IOException {
        if (boxedType == Byte.class) {
            if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                throw typeMismatch();
            }
            return (byte) value;
        }
        if (boxedType == Character.class) {
            if (value < Character.MIN_VALUE || value > Character.MAX_VALUE) {
                throw typeMismatch();
            }
            return (char) value;
        }
        if (boxedType == Short.class) {
            if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                throw typeMismatch();
            }
            return (short) value;
        }
        if (boxedType == Integer.class) {
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw typeMismatch();
            }
            return (int) value;
        }
        if (boxedType == Long.class || boxedType.isAssignableFrom(Long.class)) {
            return value;
        }
        throw typeMismatch();
    }

    /// Narrows one finite Bridge double to `Float` or preserves it as an assignable `Double`.
    ///
    /// @param value finite Bridge floating-point value
    /// @param boxedType boxed expected JVM type
    /// @return exact boxed value
    /// @throws IOException if narrowing is non-finite or the type is incompatible
    private static Object decodeFloat(double value, Class<?> boxedType) throws IOException {
        if (!Double.isFinite(value)) {
            throw typeMismatch();
        }
        if (boxedType == Float.class) {
            float narrowed = (float) value;
            if (!Float.isFinite(narrowed)) {
                throw typeMismatch();
            }
            return narrowed;
        }
        if (boxedType == Double.class || boxedType.isAssignableFrom(Double.class)) {
            return value;
        }
        throw typeMismatch();
    }

    /// Returns or allocates the sole handle for one reference identity in this invocation.
    ///
    /// @param value non-null reference value
    /// @return invocation-local handle
    /// @throws IOException if the handle slot range is exhausted
    private BridgeHandle retainReference(Object value) throws IOException {
        @Nullable BridgeHandle existing = handles.get(value);
        if (existing != null) {
            return existing;
        }
        if (nextHandleId <= 0L || nextHandleId == Long.MAX_VALUE) {
            throw malformed();
        }
        BridgeHandle handle = new BridgeHandle(nextHandleId++, generation, HANDLE_TYPE);
        handles.put(value, handle);
        references.put(handle, value);
        return handle;
    }

    /// Resolves one live handle and verifies reference assignability to the destination JVM type.
    ///
    /// @param handle untrusted Runtime-supplied handle
    /// @param expectedType exact destination JVM type
    /// @return original invocation-local object
    /// @throws IOException if the handle is stale, unknown, mistyped, or incompatible
    private Object resolveReference(BridgeHandle handle, Class<?> expectedType) throws IOException {
        if (handle.generation() != generation || !HANDLE_TYPE.equals(handle.type())) {
            throw malformed();
        }
        @Nullable Object value = references.get(handle);
        if (value == null) {
            throw malformed();
        }
        if (expectedType.isPrimitive() || !expectedType.isInstance(value)) {
            throw typeMismatch();
        }
        return value;
    }

    /// Resolves an exact Java source-style primitive, binary class, or array name without initialization.
    ///
    /// @param javaType source-style JVM type name
    /// @return resolved class
    /// @throws IOException if the type cannot be resolved
    private static Class<?> resolveJavaType(String javaType) throws IOException {
        String componentName = Objects.requireNonNull(javaType, "javaType");
        int dimensions = 0;
        while (componentName.endsWith("[]")) {
            dimensions++;
            componentName = componentName.substring(0, componentName.length() - 2);
        }
        Class<?> componentType = switch (componentName) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "char" -> char.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            default -> loadClass(componentName);
        };
        if (dimensions == 0) {
            return componentType;
        }
        try {
            return Array.newInstance(componentType, new int[dimensions]).getClass();
        } catch (IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    /// Resolves the exact declared return type of one Patch target overload without class initialization.
    ///
    /// @param declaration authoritative target declaration
    /// @return exact JVM return class
    /// @throws IOException if the target or overload cannot be resolved
    private static Class<?> resolveReturnType(PluginPatchDeclaration declaration) throws IOException {
        Class<?> targetType = resolveJavaType(declaration.getTarget());
        @Unmodifiable List<String> parameterNames = declaration.getParameters();
        Class<?>[] parameterTypes = new Class<?>[parameterNames.size()];
        for (int index = 0; index < parameterNames.size(); index++) {
            parameterTypes[index] = resolveJavaType(parameterNames.get(index));
        }
        try {
            Method method = targetType.getDeclaredMethod(declaration.getMethod(), parameterTypes);
            return method.getReturnType();
        } catch (NoSuchMethodException | SecurityException exception) {
            throw malformed(exception);
        }
    }

    /// Loads one binary class through the exact launcher Plugin System class loader without initialization.
    ///
    /// @param className binary class name
    /// @return resolved class
    /// @throws IOException if resolution fails
    private static Class<?> loadClass(String className) throws IOException {
        try {
            return Class.forName(className, false, RuntimePatchWireCodec.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError exception) {
            throw malformed(exception);
        }
    }

    /// Returns the wrapper used by Patch callbacks for one primitive JVM type.
    ///
    /// @param type primitive or reference class
    /// @return wrapper for primitives, otherwise the input class
    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return Void.class;
    }

    /// Returns the stable lower-case wire code for one declaration position.
    ///
    /// @param type declaration position
    /// @return canonical wire code
    private static String wirePatchType(PluginPatchDeclaration.PatchType type) {
        return switch (type) {
            case BEFORE -> "before";
            case AFTER -> "after";
            case REPLACE -> "replace";
        };
    }

    /// Requires the response schema marker to be the first field and equal to version one.
    ///
    /// @param values ordered response map
    /// @throws IOException if the marker is absent, reordered, or invalid
    private static void requireSchemaVersion(Map<String, BridgeValue> values) throws IOException {
        if (values.isEmpty()
                || !"schemaVersion".equals(values.keySet().iterator().next())
                || !(values.get("schemaVersion") instanceof BridgeValue.IntegerValue version)
                || version.value() != SCHEMA_VERSION) {
            throw malformed();
        }
    }

    /// Requires exact response field membership and insertion order.
    ///
    /// @param values ordered response map
    /// @param expectedKeys sole accepted ordered keys
    /// @throws IOException if a field is missing, unknown, or reordered
    private static void requireKeys(
            Map<String, BridgeValue> values,
            List<String> expectedKeys
    ) throws IOException {
        if (!List.copyOf(values.keySet()).equals(expectedKeys)) {
            throw malformed();
        }
    }

    /// Returns one required Bridge string.
    ///
    /// @param value candidate value
    /// @return decoded string
    /// @throws IOException if the value is absent or has another tag
    private static String requireString(@Nullable BridgeValue value) throws IOException {
        if (!(value instanceof BridgeValue.StringValue stringValue)) {
            throw malformed();
        }
        return stringValue.value();
    }

    /// Returns one required Bridge array.
    ///
    /// @param value candidate value
    /// @return decoded array
    /// @throws IOException if the value is absent or has another tag
    private static BridgeValue.ArrayValue requireArray(@Nullable BridgeValue value) throws IOException {
        if (!(value instanceof BridgeValue.ArrayValue arrayValue)) {
            throw malformed();
        }
        return arrayValue;
    }

    /// Requires a live codec bound to the exact same invocation object supplied during encoding.
    ///
    /// @param invocation candidate invocation
    /// @throws IOException if the codec is closed, unbound, or bound to another invocation
    private void requireBoundInvocation(PluginPatchInvocation invocation) throws IOException {
        if (closed || boundInvocation != Objects.requireNonNull(invocation, "invocation")) {
            throw malformed();
        }
    }

    /// Creates one stable redacted malformed-value failure.
    ///
    /// @return malformed-value exception
    private static IOException malformed() {
        return new IOException("Malformed Runtime Patch Bridge Value");
    }

    /// Creates one stable redacted malformed-value failure with an internal cause.
    ///
    /// @param cause internal validation cause
    /// @return malformed-value exception
    private static IOException malformed(Throwable cause) {
        return new IOException("Malformed Runtime Patch Bridge Value", cause);
    }

    /// Creates one stable redacted type-mismatch failure.
    ///
    /// @return type-mismatch exception
    private static TypeMismatchException typeMismatch() {
        return new TypeMismatchException();
    }

    /// Distinguishes structurally valid Bridge values that are incompatible with the declared JVM type.
    @NotNullByDefault
    static final class TypeMismatchException extends IOException {
        /// Creates one redacted type-mismatch exception.
        private TypeMismatchException() {
            super("Runtime Patch Bridge Value type mismatch");
        }
    }
}
