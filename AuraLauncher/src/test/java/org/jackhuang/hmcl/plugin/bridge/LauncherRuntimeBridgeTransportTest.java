/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jackhuang.hmcl.plugin.bridge;

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadContext;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies raw-byte Runtime Host calls enter the authorized launcher Bridge without token serialization.
@NotNullByDefault
final class LauncherRuntimeBridgeTransportTest {
    /// Dispatches canonical wire values and converts portable Bridge failures back to wire values.
    @Test
    void dispatchesWireCallsAndHandleOwnership(@TempDir Path temporaryDirectory) throws Exception {
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                "dev.plugin.rust", "1.0.0", "d".repeat(64));
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        PluginCapabilitySession session = authority.openSession(
                identity,
                PluginExecutionMode.EMBEDDED,
                () -> Set.of(PluginPermission.LAUNCHER_CORE),
                BridgeServiceRegistry.CALLBACK_DOMAIN,
                Duration.ofMinutes(1)
        );
        RuntimePayloadContext context = new RuntimePayloadContext(
                identity,
                temporaryDirectory,
                "payload/plugin.dll",
                PluginExecutionMode.EMBEDDED,
                temporaryDirectory.resolve("data"),
                session::issue
        );
        BridgeServiceRegistry registry = new BridgeServiceRegistry(authority, action -> action.call());
        new CoreBridgeService(registry);
        List<String> handleEvents = new ArrayList<>();
        LauncherRuntimeBridgeTransport transport = new LauncherRuntimeBridgeTransport(
                registry,
                new LauncherRuntimeBridgeTransport.HandleTransport() {
                    /// Records one retained handle.
                    @Override
                    public void retain(RuntimePayloadContext ignored, long objectId, long generation) {
                        handleEvents.add("retain:" + objectId + ":" + generation);
                    }

                    /// Records one released handle.
                    @Override
                    public void release(RuntimePayloadContext ignored, long objectId, long generation) {
                        handleEvents.add("release:" + objectId + ":" + generation);
                    }
                }
        );

        BridgeValue version = RuntimeBridgeWireCodec.decode(transport.invoke(
                context,
                BridgeMethod.CORE_LAUNCHER_VERSION.operation(),
                RuntimeBridgeWireCodec.encode(BridgeValue.nullValue())
        ));
        BridgeValue unknown = RuntimeBridgeWireCodec.decode(transport.invoke(
                context,
                "core.unknown",
                RuntimeBridgeWireCodec.encode(BridgeValue.nullValue())
        ));
        transport.retainHandle(context, 7L, 9L);
        transport.releaseHandle(context, 7L, 9L);

        assertEquals(BridgeValue.string(Metadata.VERSION), version);
        assertEquals(BridgeError.Category.INVALID_ARGUMENT,
                ((BridgeValue.ErrorValue) unknown).value().category());
        assertEquals(List.of("retain:7:9", "release:7:9"), handleEvents);
        session.close();
    }
}
