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

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Publishes exact Agent snapshots for lifecycle gate tests outside the bootstrap package.
@NotNullByDefault
public final class PluginAgentSnapshotTestSupport {
    /// Prevents instantiation of the test bridge.
    private PluginAgentSnapshotTestSupport() {
    }

    /// Publishes one exact artifact registration that owns the supplied already-loaded test class.
    ///
    /// @param identity exact package artifact
    /// @param mixinConfigurations ordered Mixin configuration declaration
    /// @param ownedClass system-loaded class whose code source represents the Agent class path
    /// @throws URISyntaxException if the test code source cannot be converted to a path
    public static void publish(
            PluginArtifactIdentity identity,
            @Unmodifiable List<String> mixinConfigurations,
            Class<?> ownedClass
    ) throws URISyntaxException {
        Path codeSource = Path.of(Objects.requireNonNull(
                ownedClass.getProtectionDomain().getCodeSource()
        ).getLocation().toURI()).toAbsolutePath().normalize();
        PluginAgentSnapshot.publish(List.of(PluginAgentSnapshot.registration(
                identity,
                PluginAgentSnapshot.calculateMixinConfigurationDigest(mixinConfigurations),
                List.of(codeSource)
        )));
    }

    /// Clears every test registration and restores the fail-closed empty snapshot.
    public static void clear() {
        PluginAgentSnapshot.clear();
    }
}
