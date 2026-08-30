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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the stable Java plugin callback values shared by every Patch position.
@NotNullByDefault
public final class PluginPatchCallbackContractTest {
    /// Preserves existing Plugin implementations through the new default callback.
    @Test
    public void defaultPluginPatchCallbackReturnsUnchanged() {
        Plugin plugin = lifecycleWithoutPatchOverride();
        PluginPatchInvocation invocation = PluginPatchInvocation.before(
                declaration(PluginPatchDeclaration.PatchType.BEFORE),
                null,
                List.of("original")
        );

        assertSame(PluginPatchResult.unchanged(), plugin.onPatch(invocation));
    }

    /// Copies nullable invocation arguments and exposes no mutable caller state.
    @Test
    public void copyNullableInvocationArguments() {
        List<@Nullable Object> source = new ArrayList<>();
        source.add("original");
        source.add(null);
        PluginPatchInvocation invocation = PluginPatchInvocation.before(
                declaration(PluginPatchDeclaration.PatchType.BEFORE),
                new Object(),
                source
        );

        source.set(0, "changed");
        assertEquals("original", invocation.arguments().get(0));
        assertNull(invocation.arguments().get(1));
        assertThrows(UnsupportedOperationException.class, () -> invocation.arguments().clear());
    }

    /// Distinguishes an explicit nullable replacement result from an unchanged callback.
    @Test
    public void representExplicitNullReturnValue() {
        PluginPatchResult result = PluginPatchResult.returnValue(null);

        assertEquals(PluginPatchResult.Action.RETURN, result.action());
        assertNull(result.returnValue());
        assertThrows(IllegalStateException.class, result::arguments);
    }

    /// Copies nullable replacement arguments without accepting another action's accessor.
    @Test
    public void representCompleteArgumentReplacement() {
        List<@Nullable Object> replacement = new ArrayList<>();
        replacement.add(null);
        replacement.add(4L);
        PluginPatchResult result = PluginPatchResult.arguments(replacement);

        replacement.set(1, 9L);
        assertEquals(PluginPatchResult.Action.ARGUMENTS, result.action());
        assertEquals(java.util.Arrays.asList(null, 4L), result.arguments());
        assertThrows(IllegalStateException.class, result::returnValue);
    }

    /// Rejects factory positions that disagree with the authoritative manifest declaration.
    @Test
    public void rejectMismatchedInvocationFactory() {
        PluginPatchDeclaration after = declaration(PluginPatchDeclaration.PatchType.AFTER);

        assertThrows(IllegalArgumentException.class,
                () -> PluginPatchInvocation.before(after, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> PluginPatchInvocation.replace(after, null, List.of()));
        PluginPatchInvocation invocation = PluginPatchInvocation.after(after, null, List.of(), "result");
        assertEquals("result", invocation.result());
    }

    /// Creates one valid declaration for the requested callback position.
    ///
    /// @param type callback position
    /// @return declaration
    private static PluginPatchDeclaration declaration(PluginPatchDeclaration.PatchType type) {
        return new PluginPatchDeclaration(
                "org.jackhuang.hmcl.Launcher",
                "launch",
                type,
                List.of("java.lang.String")
        );
    }

    /// Creates an existing-style Plugin implementation without a Patch override.
    ///
    /// @return lifecycle implementation
    private static Plugin lifecycleWithoutPatchOverride() {
        return new Plugin() {
            /// Accepts the test context without side effects.
            ///
            /// @param context plugin context
            @Override
            public void onLoad(PluginContext context) {
            }

            /// Enables without side effects.
            @Override
            public void onEnable() {
            }

            /// Disables without side effects.
            @Override
            public void onDisable() {
            }

            /// Has no manifest because this test exercises only the default callback.
            ///
            /// @return never returns normally
            @Override
            public PluginManifest getManifest() {
                throw new UnsupportedOperationException("Callback contract fixture has no manifest");
            }
        };
    }
}
