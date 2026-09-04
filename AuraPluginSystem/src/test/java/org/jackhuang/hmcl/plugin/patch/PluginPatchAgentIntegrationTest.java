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

import org.jackhuang.hmcl.patchfixture.PatchAgentLoadedTarget;
import org.jackhuang.hmcl.patchfixture.PatchAgentFutureTarget;
import org.jackhuang.hmcl.patchfixture.PatchAgentMalformedTarget;
import org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinAgent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Patch retransformation through the real JVM instrumentation and transformer chain.
@NotNullByDefault
public final class PluginPatchAgentIntegrationTest {
    /// Runs the real premain chain and verifies loaded, future, conflict, Mixin, and restoration behavior.
    ///
    /// @param temporaryDirectory isolated agent, class-path, and launcher-home root
    /// @throws Exception if fixture assembly or child execution fails
    @Test
    @Timeout(45)
    public void retransformAndRestoreLoadedTarget(@TempDir Path temporaryDirectory) throws Exception {
        Path isolatedClasses = temporaryDirectory.resolve("isolated-classes");
        copyClass(isolatedClasses, PluginInstrumentation.class);
        copyClass(isolatedClasses, PatchAgentLoadedTarget.class);
        copyClass(isolatedClasses, PatchAgentFutureTarget.class);
        copyClass(isolatedClasses, PatchAgentMalformedTarget.class);
        Path mixinAgentJar = createAgentJar(
                temporaryDirectory.resolve("mixin-agent.jar"),
                PatchMixinFixtureAgent.class,
                true
        );
        Path auraAgentJar = createAgentJar(
                temporaryDirectory.resolve("aura-agent.jar"),
                HmclMixinAgent.class,
                true
        );
        Path launcherHome = Files.createDirectories(temporaryDirectory.resolve("launcher-home"));
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Dhmcl.dir=" + launcherHome);
        command.add("-javaagent:" + mixinAgentJar);
        command.add("-javaagent:" + auraAgentJar);
        command.add("-cp");
        command.add(isolatedClasses + java.io.File.pathSeparator + System.getProperty("java.class.path"));
        command.add(PluginPatchAgentFixtureMain.class.getName());

        Path childOutput = temporaryDirectory.resolve("patch-agent-child.log");
        Process child = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(childOutput.toFile())
                .start();
        boolean completed = false;
        try {
            completed = child.waitFor(30, TimeUnit.SECONDS);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
        String output = Files.readString(childOutput, java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(completed, "Patch agent child JVM timed out\n" + output);
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.lines().anyMatch("PATCH_AGENT_LIFECYCLE_OK"::equals), output);
    }

    /// Copies one exact class resource into the isolated child code source.
    ///
    /// @param root isolated child class-path root
    /// @param type class whose current compiled bytes are copied
    /// @throws IOException if the resource is absent or cannot be copied
    private static void copyClass(Path root, Class<?> type) throws IOException {
        String resourceName = type.getName().replace('.', '/') + ".class";
        Path target = root.resolve(resourceName);
        Files.createDirectories(target.getParent());
        try (@Nullable InputStream input = type.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing class resource: " + resourceName);
            }
            Files.copy(input, target);
        }
    }

    /// Creates a manifest-only agent JAR for a premain class available on the child class path.
    ///
    /// @param target output agent JAR
    /// @param premainClass exact premain class
    /// @param canRetransform whether the agent requires retransformation capability
    /// @return created agent JAR
    /// @throws IOException if the JAR cannot be written
    private static Path createAgentJar(
            Path target,
            Class<?> premainClass,
            boolean canRetransform
    ) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Premain-Class", premainClass.getName());
        attributes.putValue("Can-Redefine-Classes", "false");
        attributes.putValue("Can-Retransform-Classes", Boolean.toString(canRetransform));
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            return target;
        }
    }

    /// Returns the current test JVM's Java executable.
    ///
    /// @return absolute Java executable path
    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath();
    }
}
