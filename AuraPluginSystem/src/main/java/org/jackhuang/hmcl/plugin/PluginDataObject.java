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
package org.jackhuang.hmcl.plugin;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Stores one immutable string-keyed JSON object with copy-on-write replacement helpers.
@NotNullByDefault
public final class PluginDataObject {
    /// Shared empty object.
    private static final PluginDataObject EMPTY = new PluginDataObject(Map.of());

    /// Immutable values in stable insertion order.
    private final @Unmodifiable Map<String, PluginDataValue> values;

    /// Copies and validates one object value map.
    ///
    /// @param values source values
    private PluginDataObject(Map<String, PluginDataValue> values) {
        Objects.requireNonNull(values, "values");
        Map<String, PluginDataValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, PluginDataValue> entry : values.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "Object key"),
                    Objects.requireNonNull(entry.getValue(), "Object value")
            );
        }
        this.values = Collections.unmodifiableMap(copy);
    }

    /// Copies one object value map.
    ///
    /// @param values source values
    /// @return immutable object
    public static PluginDataObject of(Map<String, PluginDataValue> values) {
        return values.isEmpty() ? empty() : new PluginDataObject(values);
    }

    /// Returns the shared empty object.
    ///
    /// @return empty immutable object
    public static PluginDataObject empty() {
        return EMPTY;
    }

    /// Returns the immutable value map.
    ///
    /// @return immutable values in insertion order
    public @Unmodifiable Map<String, PluginDataValue> values() {
        return values;
    }

    /// Returns one value when present.
    ///
    /// @param key object key
    /// @return value or `null` when absent
    public @Nullable PluginDataValue get(String key) {
        return values.get(Objects.requireNonNull(key, "key"));
    }

    /// Requires one boolean property.
    ///
    /// @param key object key
    /// @return boolean value
    public boolean requireBoolean(String key) {
        return requireValue(key, PluginDataValue.BooleanValue.class, "boolean").value();
    }

    /// Requires one number property.
    ///
    /// @param key object key
    /// @return arbitrary-precision number
    public BigDecimal requireNumber(String key) {
        return requireValue(key, PluginDataValue.NumberValue.class, "number").value();
    }

    /// Requires one string property.
    ///
    /// @param key object key
    /// @return string value
    public String requireString(String key) {
        return requireValue(key, PluginDataValue.StringValue.class, "string").value();
    }

    /// Requires one object property.
    ///
    /// @param key object key
    /// @return immutable object value
    public PluginDataObject requireObject(String key) {
        return requireValue(key, PluginDataValue.ObjectValue.class, "object").value();
    }

    /// Requires one array property.
    ///
    /// @param key object key
    /// @return immutable ordered values
    public @Unmodifiable List<PluginDataValue> requireArray(String key) {
        return requireValue(key, PluginDataValue.ArrayValue.class, "array").values();
    }

    /// Returns a copy with one property added or replaced.
    ///
    /// @param key object key
    /// @param value replacement value
    /// @return updated immutable object
    public PluginDataObject with(String key, PluginDataValue value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (value.equals(values.get(key))) {
            return this;
        }
        Map<String, PluginDataValue> copy = new LinkedHashMap<>(values);
        copy.put(key, value);
        return new PluginDataObject(copy);
    }

    /// Returns a copy without one property.
    ///
    /// @param key object key
    /// @return updated immutable object, or this object when the key is absent
    public PluginDataObject without(String key) {
        Objects.requireNonNull(key, "key");
        if (!values.containsKey(key)) {
            return this;
        }
        Map<String, PluginDataValue> copy = new LinkedHashMap<>(values);
        copy.remove(key);
        return copy.isEmpty() ? empty() : new PluginDataObject(copy);
    }

    /// Requires one property to have the requested value wrapper type.
    ///
    /// @param key object key
    /// @param type expected wrapper type
    /// @param expected human-readable expected kind
    /// @return typed wrapper
    /// @param <T> wrapper type
    private <T extends PluginDataValue> T requireValue(String key, Class<T> type, String expected) {
        Objects.requireNonNull(key, "key");
        @Nullable PluginDataValue value = values.get(key);
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Plugin data property '" + key + "' must be " + expected);
        }
        return type.cast(value);
    }

    /// Compares object values structurally.
    ///
    /// @param other candidate value
    /// @return whether both objects contain equal mappings
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || other instanceof PluginDataObject that && values.equals(that.values);
    }

    /// Returns the structural object hash.
    ///
    /// @return value-map hash
    @Override
    public int hashCode() {
        return values.hashCode();
    }

    /// Returns the ordinary JSON-like object representation.
    ///
    /// @return object representation
    @Override
    public String toString() {
        return values.toString();
    }
}
