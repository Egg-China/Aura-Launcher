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

/// Launcher-owned bytecode fixture with overload, native, array, and bridge method shapes.
@NotNullByDefault
public final class PatchTargetFixture implements PatchValueSink<String> {
    /// Joins a string and integer in declaration order.
    ///
    /// @param value string value
    /// @param count integer value
    /// @return joined value
    public String join(String value, int count) {
        return value + count;
    }

    /// Joins the same values in the opposite overload order.
    ///
    /// @param count integer value
    /// @param value string value
    /// @return joined value
    public String join(int count, String value) {
        return count + value;
    }

    /// Accepts a nested reference array.
    ///
    /// @param values nested reference array
    /// @return outer array length
    public int arrayLength(String[][] values) {
        return values.length;
    }

    /// Returns the implementation class name of one launcher-owned interface value.
    ///
    /// @param value launcher-owned interface value
    /// @return implementation binary name
    public String sinkType(PatchValueSink<String> value) {
        return value.getClass().getName();
    }

    /// Declares a native body that the safe Patch engine must reject.
    ///
    /// @return native value
    public native String nativeValue();

    /// Implements a generic sink and causes javac to add a unique `accept(Object)` bridge method.
    ///
    /// @param value accepted value
    @Override
    public void accept(String value) {
    }

    /// Declares an abstract method with no transformable body.
    @NotNullByDefault
    public abstract static class AbstractTarget {
        /// Returns a value without providing a method body.
        ///
        /// @return abstract value
        public abstract String abstractValue();
    }

    /// Uses a generated-class marker that the safe target policy rejects.
    @NotNullByDefault
    public static final class Generated$$Target {
        /// Returns a fixed fixture value.
        ///
        /// @return fixture value
        public String value() {
            return "generated";
        }
    }
}
