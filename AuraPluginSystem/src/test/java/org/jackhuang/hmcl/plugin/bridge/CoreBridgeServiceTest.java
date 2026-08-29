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
package org.jackhuang.hmcl.plugin.bridge;

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadContext;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Freezes the stable Core Bridge method table, permission checks, and payload-scoped directory results.
@NotNullByDefault
final class CoreBridgeServiceTest {
    /// Temporary package and private-data roots used by runtime payload contexts.
    @TempDir
    private Path temporaryDirectory;

    /// Exposes launcher metadata and exact payload paths through both stable dispatch keys.
    @Test
    void exposesCoreValuesByNumericIdAndCanonicalOperation() {
        try (Fixture fixture = fixture(Set.of(PluginPermission.LAUNCHER_CORE))) {
            assertEquals(1L, BridgeMethod.CORE_LAUNCHER_VERSION.id());
            assertEquals("core.launcher.version", BridgeMethod.CORE_LAUNCHER_VERSION.operation());
            assertEquals(2L, BridgeMethod.CORE_PACKAGE_DIRECTORY.id());
            assertEquals("core.plugin.package-directory", BridgeMethod.CORE_PACKAGE_DIRECTORY.operation());
            assertEquals(3L, BridgeMethod.CORE_DATA_DIRECTORY.id());
            assertEquals("core.plugin.data-directory", BridgeMethod.CORE_DATA_DIRECTORY.operation());

            assertEquals(BridgeValue.string(Metadata.VERSION), fixture.registry().invoke(
                    fixture.context(), BridgeMethod.CORE_LAUNCHER_VERSION.id(), BridgeValue.nullValue()));
            assertEquals(BridgeValue.string(fixture.context().packagePath().toString()), fixture.registry().invoke(
                    fixture.context(), BridgeMethod.CORE_PACKAGE_DIRECTORY.operation(), BridgeValue.nullValue()));
            assertEquals(BridgeValue.string(fixture.context().dataDirectory().toString()), fixture.registry().invoke(
                    fixture.context(), BridgeMethod.CORE_DATA_DIRECTORY.operation(), BridgeValue.nullValue()));
        }
    }

    /// Rejects undeclared or denied launcher-core authority before executing a Core handler.
    @Test
    void requiresLauncherCorePermission() {
        try (Fixture fixture = fixture(Set.of(PluginPermission.LAUNCHER_UI))) {
            assertCategory(BridgeError.Category.PERMISSION_DENIED, () -> fixture.registry().invoke(
                    fixture.context(), BridgeMethod.CORE_LAUNCHER_VERSION.operation(), BridgeValue.nullValue()));
        }
    }

    /// Rejects unknown methods and values outside each method's frozen argument schema.
    @Test
    void rejectsUnknownMethodsAndInvalidInputSchemas() {
        try (Fixture fixture = fixture(Set.of(PluginPermission.LAUNCHER_CORE))) {
            assertCategory(BridgeError.Category.INVALID_ARGUMENT,
                    () -> fixture.registry().invoke(fixture.context(), 999_999L, BridgeValue.nullValue()));
            assertCategory(BridgeError.Category.INVALID_ARGUMENT,
                    () -> fixture.registry().invoke(fixture.context(), "core.missing", BridgeValue.nullValue()));
            assertCategory(BridgeError.Category.INVALID_ARGUMENT, () -> fixture.registry().invoke(
                    fixture.context(), BridgeMethod.CORE_LAUNCHER_VERSION.operation(), BridgeValue.string("bad")));
        }
    }

    /// Refuses a second handler for an already registered method ID and operation.
    @Test
    void refusesDuplicateServiceRegistration() {
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        BridgeServiceRegistry registry = new BridgeServiceRegistry(authority);
        new CoreBridgeService(registry);

        assertThrows(IllegalStateException.class, () -> new CoreBridgeService(registry));
    }

    /// Creates one exact runtime payload fixture with a dynamically issued permission session.
    ///
    /// @param permissions effective payload grants
    /// @return closeable Bridge fixture
    private Fixture fixture(Set<PluginPermission> permissions) {
        @Unmodifiable Set<PluginPermission> effectivePermissions = Set.copyOf(permissions);
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                "test.core-bridge", "1.0.0", "a".repeat(64));
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        PluginCapabilitySession session = authority.openSession(
                identity,
                PluginExecutionMode.EMBEDDED,
                () -> effectivePermissions,
                BridgeServiceRegistry.CALLBACK_DOMAIN,
                Duration.ofMinutes(1)
        );
        Path packageDirectory = temporaryDirectory.resolve("package");
        Path dataDirectory = temporaryDirectory.resolve("data");
        RuntimePayloadContext context = new RuntimePayloadContext(
                identity,
                packageDirectory,
                "payload/plugin.dll",
                PluginExecutionMode.EMBEDDED,
                dataDirectory,
                session::issue
        );
        BridgeServiceRegistry registry = new BridgeServiceRegistry(authority);
        new CoreBridgeService(registry);
        return new Fixture(registry, context, session);
    }

    /// Verifies one Bridge invocation fails with the expected portable category.
    ///
    /// @param expected expected failure category
    /// @param invocation failing invocation
    private static void assertCategory(BridgeError.Category expected, Runnable invocation) {
        BridgeError error = assertThrows(BridgeError.class, invocation::run);
        assertEquals(expected, error.category());
    }

    /// Owns the registry, payload context, and capability session for one test.
    ///
    /// @param registry configured Core registry
    /// @param context exact payload context
    /// @param session closeable capability session
    @NotNullByDefault
    private record Fixture(
            BridgeServiceRegistry registry,
            RuntimePayloadContext context,
            PluginCapabilitySession session
    ) implements AutoCloseable {
        /// Revokes every token issued by this fixture.
        @Override
        public void close() {
            session.close();
        }
    }
}
