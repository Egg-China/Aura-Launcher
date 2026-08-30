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
package org.jackhuang.hmcl.patchfixture;

import org.jetbrains.annotations.NotNullByDefault;

/// Exercises Patch transformation for every JVM value category and normal exit shape.
@NotNullByDefault
public final class PatchTransformerFixture {
    /// Stable exception instance used to prove original exception identity survives transformation.
    public static final IllegalStateException FAILURE = new IllegalStateException("fixture failure");

    /// Adds wide and narrow static arguments.
    ///
    /// @param left long argument
    /// @param middle double argument
    /// @param right integer argument
    /// @return arithmetic sum
    public static long wide(long left, double middle, int right) {
        return left + (long) middle + right;
    }

    /// Returns the reference argument after accepting every primitive category and an array.
    ///
    /// @param booleanValue boolean argument
    /// @param byteValue byte argument
    /// @param charValue char argument
    /// @param shortValue short argument
    /// @param intValue integer argument
    /// @param longValue long argument
    /// @param floatValue float argument
    /// @param doubleValue double argument
    /// @param text reference argument
    /// @param values reference array argument
    /// @return text argument
    public String allArguments(
            boolean booleanValue,
            byte byteValue,
            char charValue,
            short shortValue,
            int intValue,
            long longValue,
            float floatValue,
            double doubleValue,
            String text,
            String[] values
    ) {
        if (booleanValue && byteValue + charValue + shortValue + intValue
                + longValue + floatValue + doubleValue == Long.MIN_VALUE) {
            return values[0];
        }
        return text;
    }

    /// Returns a boolean value.
    ///
    /// @return false
    public boolean booleanValue() {
        return false;
    }

    /// Returns a byte value.
    ///
    /// @return one
    public byte byteValue() {
        return 1;
    }

    /// Returns a character value.
    ///
    /// @return `a`
    public char charValue() {
        return 'a';
    }

    /// Returns a short value.
    ///
    /// @return two
    public short shortValue() {
        return 2;
    }

    /// Returns an integer value.
    ///
    /// @return three
    public int intValue() {
        return 3;
    }

    /// Returns a long value.
    ///
    /// @return four
    public long longValue() {
        return 4L;
    }

    /// Returns a float value.
    ///
    /// @return five
    public float floatValue() {
        return 5.0f;
    }

    /// Returns a double value.
    ///
    /// @return six
    public double doubleValue() {
        return 6.0d;
    }

    /// Returns the outer length of a nested reference array.
    ///
    /// @param values nested reference array
    /// @return outer length
    public int arrayLength(String[][] values) {
        return values.length;
    }

    /// Appends the original-body marker to a mutable sink.
    ///
    /// @param sink marker sink
    public void appendOriginal(StringBuilder sink) {
        sink.append("original");
    }

    /// Returns a value that a replacement callback may bypass.
    ///
    /// @return original value
    public String replaceMe() {
        return "original";
    }

    /// Throws the stable fixture failure without a normal return.
    ///
    /// @return never returns normally
    public String throwsOriginal() {
        throw FAILURE;
    }
}
