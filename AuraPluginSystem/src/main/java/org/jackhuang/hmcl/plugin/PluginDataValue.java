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
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/// Represents one immutable JSON-compatible value in the Runtime-neutral plugin Hook contract.
@NotNullByDefault
public sealed interface PluginDataValue permits PluginDataValue.NullValue,
        PluginDataValue.BooleanValue, PluginDataValue.NumberValue, PluginDataValue.StringValue,
        PluginDataValue.ArrayValue, PluginDataValue.ObjectValue {
    /// Returns the singleton JSON null value.
    ///
    /// @return immutable null value
    static PluginDataValue nullValue() {
        return NullValue.INSTANCE;
    }

    /// Wraps one JSON boolean value.
    ///
    /// @param value boolean value
    /// @return immutable boolean value
    static PluginDataValue bool(boolean value) {
        return new BooleanValue(value);
    }

    /// Wraps one arbitrary-precision JSON number.
    ///
    /// @param value number value
    /// @return immutable number value
    static PluginDataValue number(BigDecimal value) {
        return new NumberValue(value);
    }

    /// Wraps one JSON string.
    ///
    /// @param value string value
    /// @return immutable string value
    static PluginDataValue string(String value) {
        return new StringValue(value);
    }

    /// Copies one ordered JSON array.
    ///
    /// @param values array values
    /// @return immutable array value
    static PluginDataValue array(List<PluginDataValue> values) {
        return new ArrayValue(values);
    }

    /// Wraps one immutable JSON object.
    ///
    /// @param value object value
    /// @return immutable object value
    static PluginDataValue object(PluginDataObject value) {
        return new ObjectValue(value);
    }

    /// Represents the sole JSON null value.
    @NotNullByDefault
    enum NullValue implements PluginDataValue {
        /// Shared immutable null instance.
        INSTANCE
    }

    /// Represents one JSON boolean.
    ///
    /// @param value boolean value
    @NotNullByDefault
    record BooleanValue(boolean value) implements PluginDataValue {
    }

    /// Represents one arbitrary-precision JSON number.
    ///
    /// @param value number value
    @NotNullByDefault
    record NumberValue(BigDecimal value) implements PluginDataValue {
        /// Rejects a null number.
        public NumberValue {
            Objects.requireNonNull(value, "value");
        }
    }

    /// Represents one JSON string.
    ///
    /// @param value string value
    @NotNullByDefault
    record StringValue(String value) implements PluginDataValue {
        /// Rejects a null string.
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    /// Represents one immutable ordered JSON array.
    ///
    /// @param values copied array values
    @NotNullByDefault
    record ArrayValue(@Unmodifiable List<PluginDataValue> values) implements PluginDataValue {
        /// Copies the array and rejects null elements.
        public ArrayValue {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    /// Represents one immutable JSON object.
    ///
    /// @param value object value
    @NotNullByDefault
    record ObjectValue(PluginDataObject value) implements PluginDataValue {
        /// Rejects a null object.
        public ObjectValue {
            Objects.requireNonNull(value, "value");
        }
    }
}
