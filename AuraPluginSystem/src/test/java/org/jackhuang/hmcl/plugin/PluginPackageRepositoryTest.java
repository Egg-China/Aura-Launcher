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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies that package discovery never follows caller-controlled filesystem links.
@NotNullByDefault
public final class PluginPackageRepositoryTest {
    /// Fails the complete installed-manifest snapshot when an archive exists but cannot be read.
    ///
    /// @param temporaryDirectory isolated plugin directory
    /// @throws IOException if the damaged package fixture cannot be created
    @Test
    public void damagedInstalledPackageFailsSnapshot(@TempDir Path temporaryDirectory) throws IOException {
        Path packageFile = temporaryDirectory.resolve("damaged.npl");
        Files.writeString(packageFile, "not a zip archive");
        PluginPackageRepository repository = new PluginPackageRepository(temporaryDirectory);

        assertThrows(IOException.class, () -> repository.readInstalledManifests(List.of()));
    }

    /// Rejects a symbolic `.npl` source before manifest or archive bytes are consumed.
    ///
    /// @param temporaryDirectory isolated source directory
    /// @throws IOException if the regular target fixture cannot be created
    @Test
    public void rejectSymbolicPackage(@TempDir Path temporaryDirectory) throws IOException {
        Path target = temporaryDirectory.resolve("target.npl");
        Files.write(target, new byte[] {0});
        Path link = temporaryDirectory.resolve("linked.npl");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        assertThrows(IOException.class, () -> PluginPackageRepository.validateLocalPackage(link));
    }
}
