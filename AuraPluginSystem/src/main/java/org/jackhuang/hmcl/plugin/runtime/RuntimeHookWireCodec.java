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
import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jackhuang.hmcl.plugin.bridge.RuntimeBridgeWireCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Translates launcher Hook envelopes to the canonical language-neutral Runtime Bridge wire format.
///
/// Java capability tokens and [org.jackhuang.hmcl.plugin.PluginSecretAccess] objects never enter this codec. External
/// payloads receive ordinary immutable Hook data only; privileged secret access remains a separate launcher Bridge
/// operation guarded by call-time authority.
@NotNullByDefault
public final class RuntimeHookWireCodec {
    /// Frozen Hook result contract generation.
    private static final long CONTRACT_VERSION = PluginHookEvent.CURRENT_CONTRACT_VERSION;

    /// Exact fields accepted for an unchanged result.
    private static final @Unmodifiable Set<String> UNCHANGED_FIELDS =
            Set.of("contractVersion", "action");

    /// Exact fields accepted for a replacement result.
    private static final @Unmodifiable Set<String> REPLACE_FIELDS =
            Set.of("contractVersion", "action", "data", "protectedSecrets");

    /// Exact fields accepted for a cancellation result.
    private static final @Unmodifiable Set<String> CANCEL_FIELDS =
            Set.of("contractVersion", "action", "reasonCode", "message");

    /// Prevents instantiation of this stateless codec.
    private RuntimeHookWireCodec() {
    }

    /// Returns the canonical payload operation for one Hook point.
    ///
    /// @param point Hook point being dispatched
    /// @return stable payload operation
    public static String operation(PluginHookPoint point) {
        return "hook." + Objects.requireNonNull(point, "point").getId();
    }

    /// Encodes one complete ordinary Hook event without Java authority or secret-access objects.
    ///
    /// @param event immutable launcher Hook event
    /// @return canonical Bridge Value v1 wire bytes
    /// @throws IOException if event data cannot be represented exactly by the Bridge value model
    public static byte[] encodeEvent(PluginHookEvent event) throws IOException {
        PluginHookEvent source = Objects.requireNonNull(event, "event");
        Map<String, BridgeValue> envelope = new LinkedHashMap<>();
        envelope.put("contractVersion", BridgeValue.integer(source.contractVersion()));
        envelope.put("dispatchId", BridgeValue.string(source.dispatchId()));
        envelope.put("point", BridgeValue.string(source.point().getId()));
        envelope.put("occurredAt", BridgeValue.string(source.occurredAt().toString()));
        envelope.put("data", toBridgeObject(source.data()));
        try {
            return RuntimeBridgeWireCodec.encode(BridgeValue.map(envelope));
        } catch (IllegalArgumentException exception) {
            throw malformedEvent(exception);
        }
    }

    /// Decodes one untrusted external Hook result.
    ///
    /// A malformed wire value, unknown field, unsupported value kind, wrong contract generation, or invalid
    /// action-specific payload returns `null` so the shared Hook dispatcher can classify it as malformed Provider
    /// output without accepting a partial transformation.
    ///
    /// @param wire untrusted canonical Bridge bytes returned by a Runtime payload
    /// @return validated Hook result, or `null` when malformed
    public static @Nullable PluginHookResult decodeResult(byte[] wire) {
        Objects.requireNonNull(wire, "wire");
        try {
            BridgeValue decoded = RuntimeBridgeWireCodec.decode(wire);
            Map<String, BridgeValue> envelope = requireMap(decoded);
            if (requireInteger(envelope, "contractVersion") != CONTRACT_VERSION) {
                return null;
            }
            return switch (requireString(envelope, "action")) {
                case "unchanged" -> decodeUnchanged(envelope);
                case "replace" -> decodeReplace(envelope);
                case "cancel" -> decodeCancel(envelope);
                default -> null;
            };
        } catch (IOException | IllegalArgumentException exception) {
            return null;
        }
    }

    /// Decodes an unchanged result after exact-field validation.
    ///
    /// @param envelope untrusted result envelope
    /// @return unchanged result, or `null` for unknown fields
    private static @Nullable PluginHookResult decodeUnchanged(Map<String, BridgeValue> envelope) {
        return hasExactFields(envelope, UNCHANGED_FIELDS) ? PluginHookResult.unchanged() : null;
    }

    /// Decodes a complete replacement result after exact-field validation.
    ///
    /// @param envelope untrusted result envelope
    /// @return replacement result, or `null` for unknown or invalid fields
    /// @throws IOException if ordinary data contains a non-JSON Bridge value
    private static @Nullable PluginHookResult decodeReplace(Map<String, BridgeValue> envelope) throws IOException {
        if (!hasExactFields(envelope, REPLACE_FIELDS)) {
            return null;
        }
        PluginDataObject data = fromBridgeObject(requireMap(require(envelope, "data")));
        Map<String, BridgeValue> encodedSecrets = requireMap(require(envelope, "protectedSecrets"));
        Map<String, String> protectedSecrets = new LinkedHashMap<>();
        for (Map.Entry<String, BridgeValue> entry : encodedSecrets.entrySet()) {
            protectedSecrets.put(entry.getKey(), requireStringValue(entry.getValue()));
        }
        return PluginHookResult.replace(data, protectedSecrets);
    }

    /// Decodes a deliberate cancellation result after exact-field validation.
    ///
    /// @param envelope untrusted result envelope
    /// @return cancellation result, or `null` for unknown fields
    private static @Nullable PluginHookResult decodeCancel(Map<String, BridgeValue> envelope) {
        if (!hasExactFields(envelope, CANCEL_FIELDS)) {
            return null;
        }
        return PluginHookResult.cancel(
                requireString(envelope, "reasonCode"),
                requireString(envelope, "message")
        );
    }

    /// Converts one immutable Hook data object to a Bridge map.
    ///
    /// @param object Hook data object
    /// @return language-neutral Bridge map
    /// @throws IOException if a number has no exact supported Bridge representation
    private static BridgeValue toBridgeObject(PluginDataObject object) throws IOException {
        Map<String, BridgeValue> values = new LinkedHashMap<>();
        for (Map.Entry<String, PluginDataValue> entry : object.values().entrySet()) {
            values.put(entry.getKey(), toBridgeValue(entry.getValue()));
        }
        return BridgeValue.map(values);
    }

    /// Converts one JSON-compatible Hook value to the equivalent Bridge value.
    ///
    /// @param value Hook value
    /// @return equivalent Bridge value
    /// @throws IOException if a number has no exact supported Bridge representation
    private static BridgeValue toBridgeValue(PluginDataValue value) throws IOException {
        if (value instanceof PluginDataValue.NullValue) {
            return BridgeValue.nullValue();
        }
        if (value instanceof PluginDataValue.BooleanValue booleanValue) {
            return BridgeValue.bool(booleanValue.value());
        }
        if (value instanceof PluginDataValue.NumberValue numberValue) {
            return toBridgeNumber(numberValue.value());
        }
        if (value instanceof PluginDataValue.StringValue stringValue) {
            return BridgeValue.string(stringValue.value());
        }
        if (value instanceof PluginDataValue.ArrayValue arrayValue) {
            List<BridgeValue> values = new ArrayList<>(arrayValue.values().size());
            for (PluginDataValue entry : arrayValue.values()) {
                values.add(toBridgeValue(entry));
            }
            return BridgeValue.array(values);
        }
        if (value instanceof PluginDataValue.ObjectValue objectValue) {
            return toBridgeObject(objectValue.value());
        }
        throw malformedEvent(null);
    }

    /// Converts one arbitrary-precision JSON number without silently rounding it.
    ///
    /// Integral values use signed 64-bit Bridge integers. Other values use binary64 only when Java's canonical
    /// decimal representation round-trips to the exact source value.
    ///
    /// @param value arbitrary-precision source number
    /// @return exact supported Bridge number
    /// @throws IOException if the value exceeds the frozen Bridge numeric model
    private static BridgeValue toBridgeNumber(BigDecimal value) throws IOException {
        BigDecimal normalized = value.stripTrailingZeros();
        try {
            return BridgeValue.integer(normalized.longValueExact());
        } catch (ArithmeticException ignored) {
            double floating = normalized.doubleValue();
            if (Double.isFinite(floating) && BigDecimal.valueOf(floating).compareTo(normalized) == 0) {
                return BridgeValue.floating(floating);
            }
            throw malformedEvent(null);
        }
    }

    /// Converts one Bridge map to an immutable Hook data object.
    ///
    /// @param values Bridge map entries
    /// @return immutable Hook data object
    /// @throws IOException if an entry uses a non-JSON Bridge kind
    private static PluginDataObject fromBridgeObject(Map<String, BridgeValue> values) throws IOException {
        Map<String, PluginDataValue> decoded = new LinkedHashMap<>();
        for (Map.Entry<String, BridgeValue> entry : values.entrySet()) {
            decoded.put(entry.getKey(), fromBridgeValue(entry.getValue()));
        }
        return PluginDataObject.of(decoded);
    }

    /// Converts one Bridge value to the closed JSON-compatible Hook value model.
    ///
    /// @param value untrusted Bridge value
    /// @return equivalent Hook value
    /// @throws IOException if bytes, handles, errors, or another unsupported kind is present
    private static PluginDataValue fromBridgeValue(BridgeValue value) throws IOException {
        if (value instanceof BridgeValue.NullValue) {
            return PluginDataValue.nullValue();
        }
        if (value instanceof BridgeValue.BooleanValue booleanValue) {
            return PluginDataValue.bool(booleanValue.value());
        }
        if (value instanceof BridgeValue.IntegerValue integerValue) {
            return PluginDataValue.number(BigDecimal.valueOf(integerValue.value()));
        }
        if (value instanceof BridgeValue.FloatValue floatValue) {
            return PluginDataValue.number(BigDecimal.valueOf(floatValue.value()));
        }
        if (value instanceof BridgeValue.StringValue stringValue) {
            return PluginDataValue.string(stringValue.value());
        }
        if (value instanceof BridgeValue.ArrayValue arrayValue) {
            List<PluginDataValue> decoded = new ArrayList<>(arrayValue.values().size());
            for (BridgeValue entry : arrayValue.values()) {
                decoded.add(fromBridgeValue(entry));
            }
            return PluginDataValue.array(decoded);
        }
        if (value instanceof BridgeValue.MapValue mapValue) {
            return PluginDataValue.object(fromBridgeObject(mapValue.values()));
        }
        throw new IOException("Runtime Hook result contains a non-JSON Bridge value");
    }

    /// Requires one named value from a result envelope.
    ///
    /// @param envelope result envelope
    /// @param field required field name
    /// @return required value
    private static BridgeValue require(Map<String, BridgeValue> envelope, String field) {
        @Nullable BridgeValue value = envelope.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Missing Runtime Hook result field");
        }
        return value;
    }

    /// Requires one Bridge map value.
    ///
    /// @param value candidate Bridge value
    /// @return immutable map entries
    private static @Unmodifiable Map<String, BridgeValue> requireMap(BridgeValue value) {
        if (!(value instanceof BridgeValue.MapValue mapValue)) {
            throw new IllegalArgumentException("Runtime Hook value must be a map");
        }
        return mapValue.values();
    }

    /// Requires one named signed integer.
    ///
    /// @param envelope result envelope
    /// @param field required field name
    /// @return signed integer value
    private static long requireInteger(Map<String, BridgeValue> envelope, String field) {
        BridgeValue value = require(envelope, field);
        if (!(value instanceof BridgeValue.IntegerValue integerValue)) {
            throw new IllegalArgumentException("Runtime Hook field must be an integer");
        }
        return integerValue.value();
    }

    /// Requires one named string.
    ///
    /// @param envelope result envelope
    /// @param field required field name
    /// @return string value
    private static String requireString(Map<String, BridgeValue> envelope, String field) {
        return requireStringValue(require(envelope, field));
    }

    /// Requires one Bridge string value.
    ///
    /// @param value candidate Bridge value
    /// @return string content
    private static String requireStringValue(BridgeValue value) {
        if (!(value instanceof BridgeValue.StringValue stringValue)) {
            throw new IllegalArgumentException("Runtime Hook value must be a string");
        }
        return stringValue.value();
    }

    /// Returns whether an envelope contains exactly the fields allowed by its action.
    ///
    /// @param envelope result envelope
    /// @param expected exact field names
    /// @return whether no field is missing or unknown
    private static boolean hasExactFields(
            Map<String, BridgeValue> envelope,
            @Unmodifiable Set<String> expected
    ) {
        return envelope.keySet().equals(expected);
    }

    /// Creates one redacted event-encoding failure.
    ///
    /// @param cause underlying value-model rejection, or `null` when none exists
    /// @return checked redacted failure
    private static IOException malformedEvent(@Nullable Throwable cause) {
        return cause == null
                ? new IOException("Runtime Hook event cannot be represented by Bridge Value v1")
                : new IOException("Runtime Hook event cannot be represented by Bridge Value v1", cause);
    }
}
