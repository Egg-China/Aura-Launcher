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
package org.jackhuang.hmcl.plugin.patch;

import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the one canonical conversion from schema-v5 Java parameter names to JVM method identities.
@NotNullByDefault
public final class PluginPatchMethodTest {
    /// Converts every supported scalar, reference, nested-class, and array form without changing manifest order.
    @Test
    public void convertCanonicalParameterNamesToJvmDescriptor() {
        PluginPatchMethod method = PluginPatchMethod.from(new PluginPatchDeclaration(
                "org.jackhuang.hmcl.Launcher",
                "launch",
                PluginPatchDeclaration.PatchType.BEFORE,
                List.of(
                        "boolean", "byte", "char", "short", "int", "long", "float", "double",
                        "java.lang.String", "java.util.Map$Entry", "int[]", "java.lang.String[][]"
                )
        ));

        assertEquals("org.jackhuang.hmcl.Launcher", method.target());
        assertEquals("launch", method.name());
        assertEquals("(ZBCSIJFDLjava/lang/String;Ljava/util/Map$Entry;[I[[Ljava/lang/String;)",
                method.parameterDescriptor());
        assertEquals(List.of(
                "boolean", "byte", "char", "short", "int", "long", "float", "double",
                "java.lang.String", "java.util.Map$Entry", "int[]", "java.lang.String[][]"
        ), method.parameterNames());
    }

    /// Matches overloads by method name and argument descriptor while ignoring their return type.
    @Test
    public void matchExactMethodArgumentsIndependentOfReturnType() {
        PluginPatchMethod method = PluginPatchMethod.from(new PluginPatchDeclaration(
                "org.jackhuang.hmcl.Launcher",
                "launch",
                PluginPatchDeclaration.PatchType.AFTER,
                List.of("java.lang.String", "int")
        ));

        assertTrue(method.matches("launch", "(Ljava/lang/String;I)V"));
        assertTrue(method.matches("launch", "(Ljava/lang/String;I)Ljava/lang/String;"));
        assertFalse(method.matches("start", "(Ljava/lang/String;I)V"));
        assertFalse(method.matches("launch", "(ILjava/lang/String;)V"));
        assertFalse(method.matches("launch", "not-a-method-descriptor"));
    }

    /// Retains an exact resolved descriptor only when it belongs to the declared overload.
    @Test
    public void bindOnlyMatchingResolvedMethodDescriptor() {
        PluginPatchMethod unresolved = PluginPatchMethod.from(new PluginPatchDeclaration(
                "org.jackhuang.hmcl.Launcher",
                "launch",
                PluginPatchDeclaration.PatchType.REPLACE,
                List.of("long")
        ));

        assertThrows(IllegalStateException.class, unresolved::descriptor);
        PluginPatchMethod resolved = unresolved.withDescriptor("(J)Ljava/lang/String;");
        assertEquals("(J)Ljava/lang/String;", resolved.descriptor());
        assertThrows(IllegalArgumentException.class, () -> unresolved.withDescriptor("(I)V"));
    }
}
