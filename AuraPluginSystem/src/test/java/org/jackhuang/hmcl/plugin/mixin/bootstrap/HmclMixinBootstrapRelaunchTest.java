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
package org.jackhuang.hmcl.plugin.mixin.bootstrap;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies that the Mixin relaunch keeps a self-patched JavaFX runtime available in the child JVM.
@NotNullByDefault
public final class HmclMixinBootstrapRelaunchTest {
    /// Adds the cached JavaFX modules to the child process before premain can load UI-dependent Mixins.
    @Test
    public void addSelfPatchedJavaFxModulesToAgentProcess() {
        List<String> command = new ArrayList<>(List.of("java"));
        List<Path> modulePath = List.of(
                Path.of("cache", "javafx-base.jar"),
                Path.of("cache", "javafx-graphics.jar"),
                Path.of("cache", "javafx-controls.jar")
        );

        HmclMixinBootstrap.appendJavaFxRuntimeArguments(command, modulePath);

        assertEquals(List.of(
                "java",
                "--module-path",
                String.join(File.pathSeparator, modulePath.stream().map(Path::toString).toList()),
                "--add-modules",
                "javafx.base,javafx.graphics,javafx.controls"
        ), command);
    }

    /// Leaves the child command untouched when HMCL used JavaFX supplied by the selected Java runtime.
    @Test
    public void omitModuleArgumentsWithoutSelfPatchedJavaFx() {
        List<String> command = new ArrayList<>(List.of("java"));

        HmclMixinBootstrap.appendJavaFxRuntimeArguments(command, List.of());

        assertEquals(List.of("java"), command);
    }
}
