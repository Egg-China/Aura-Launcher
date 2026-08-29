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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bounded and caller-owned plugin runtime-state persistence.
@NotNullByDefault
public final class PluginStateStoreTest {
    /// Treats an oversized state document as empty without retaining stale caller state.
    ///
    /// @param temporaryDirectory isolated launcher-local directory
    /// @throws Exception if the test document cannot be written
    @Test
    public void rejectOversizedStateDocument(@TempDir Path temporaryDirectory) throws Exception {
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        byte @Unmodifiable [] oversizedState = new byte[1024 * 1024 + 1];
        Files.write(stateFile, oversizedState);
        Set<String> enabled = new HashSet<>(Set.of("dev.hmclce.test.enabled"));
        Set<String> pendingUninstall = new HashSet<>(Set.of("dev.hmclce.test.pending"));
        Set<String> quarantined = new HashSet<>(Set.of("dev.hmclce.test.quarantined"));
        PluginStateStore store = new PluginStateStore(stateFile, new PluginMutationLock(temporaryDirectory));

        assertTrue(store.load(enabled, pendingUninstall, quarantined).isEmpty());

        assertTrue(enabled.isEmpty());
        assertTrue(pendingUninstall.isEmpty());
        assertTrue(quarantined.isEmpty());
    }

    /// Preserves the caller's last known-good snapshot when strict loading cannot verify the state document.
    ///
    /// @param temporaryDirectory isolated launcher-local directory
    /// @throws Exception if the oversized test document cannot be written
    @Test
    public void strictLoadPreservesCallerStateOnFailure(@TempDir Path temporaryDirectory) throws Exception {
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        byte @Unmodifiable [] oversizedState = new byte[1024 * 1024 + 1];
        Files.write(stateFile, oversizedState);
        Set<String> enabled = new HashSet<>(Set.of("dev.hmclce.test.enabled"));
        Set<String> pendingUninstall = new HashSet<>(Set.of("dev.hmclce.test.pending"));
        Set<String> quarantined = new HashSet<>(Set.of("dev.hmclce.test.quarantined"));
        PluginStateStore store = new PluginStateStore(stateFile, new PluginMutationLock(temporaryDirectory));

        assertThrows(IOException.class, () -> store.loadStrict(enabled, pendingUninstall, quarantined));

        assertEquals(Set.of("dev.hmclce.test.enabled"), enabled);
        assertEquals(Set.of("dev.hmclce.test.pending"), pendingUninstall);
        assertEquals(Set.of("dev.hmclce.test.quarantined"), quarantined);
    }

    /// Loads legacy state documents without a quarantine field as an empty quarantine.
    ///
    /// @param temporaryDirectory isolated launcher-local directory
    /// @throws Exception if the test document cannot be written
    @Test
    public void loadLegacyStateWithoutQuarantine(@TempDir Path temporaryDirectory) throws Exception {
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        Files.writeString(stateFile, """
                {
                  "enabled": ["dev.hmclce.test.enabled"],
                  "pendingUninstall": ["dev.hmclce.test.pending"]
                }
                """);
        Set<String> enabled = new HashSet<>();
        Set<String> pendingUninstall = new HashSet<>();
        Set<String> quarantined = new HashSet<>(Set.of("dev.hmclce.test.stale"));
        PluginStateStore store = new PluginStateStore(stateFile, new PluginMutationLock(temporaryDirectory));

        assertTrue(store.load(enabled, pendingUninstall, quarantined).isEmpty());

        assertEquals(Set.of("dev.hmclce.test.enabled"), enabled);
        assertEquals(Set.of("dev.hmclce.test.pending"), pendingUninstall);
        assertTrue(quarantined.isEmpty());
    }

    /// Persists deterministic quarantine state and filters malformed IDs during reload.
    ///
    /// @param temporaryDirectory isolated launcher-local directory
    /// @throws Exception if strict state publication or fixture rewriting fails
    @Test
    public void persistSortedQuarantineAndFilterMalformedIds(@TempDir Path temporaryDirectory) throws Exception {
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        PluginStateStore store = new PluginStateStore(stateFile, new PluginMutationLock(temporaryDirectory));
        store.saveStrict(
                Set.of("dev.hmclce.test.enabled"),
                Set.of("dev.hmclce.test.pending"),
                Set.of("dev.hmclce.test.quarantine-z", "dev.hmclce.test.quarantine-a"),
                null
        );

        String persisted = Files.readString(stateFile);
        assertTrue(persisted.indexOf("dev.hmclce.test.quarantine-a")
                < persisted.indexOf("dev.hmclce.test.quarantine-z"));

        Files.writeString(stateFile, persisted.replace(
                "\"dev.hmclce.test.quarantine-z\"",
                "\"dev.hmclce.test.quarantine-z\", \"INVALID ID\""
        ));
        Set<String> enabled = new HashSet<>();
        Set<String> pendingUninstall = new HashSet<>();
        Set<String> quarantined = new HashSet<>();
        assertTrue(store.load(enabled, pendingUninstall, quarantined).isEmpty());

        assertEquals(Set.of("dev.hmclce.test.enabled"), enabled);
        assertEquals(Set.of("dev.hmclce.test.pending"), pendingUninstall);
        assertEquals(Set.of(
                "dev.hmclce.test.quarantine-a",
                "dev.hmclce.test.quarantine-z"
        ), quarantined);
        assertFalse(quarantined.contains("INVALID ID"));
    }

    /// Rejects a malformed persisted quarantine report from strict startup state loading.
    ///
    /// @param temporaryDirectory isolated launcher-local directory
    /// @throws Exception if the malformed test document cannot be written
    @Test
    public void rejectMalformedQuarantineReportDuringStrictLoad(@TempDir Path temporaryDirectory) throws Exception {
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        Files.writeString(stateFile, """
                {
                  "enabled": [],
                  "pendingUninstall": [],
                  "quarantined": ["dev.hmclce.test.quarantined"],
                  "quarantineReport": {
                    "failureTimestampEpochMillis": 0,
                    "failureCategory": "CRASH",
                    "failureReason": "CHILD_CRASH",
                    "lastStage": "JVM_STARTED",
                    "quarantinedPluginIds": ["dev.hmclce.test.quarantined"],
                    "pluginPackagesRetained": true,
                    "pluginConfigurationRetained": true,
                    "pluginDataRetained": true
                  }
                }
                """);
        Set<String> enabled = new HashSet<>();
        Set<String> pendingUninstall = new HashSet<>();
        Set<String> quarantined = new HashSet<>();
        PluginStateStore store = new PluginStateStore(stateFile, new PluginMutationLock(temporaryDirectory));

        assertThrows(IOException.class, () -> store.loadStrict(enabled, pendingUninstall, quarantined));

        assertTrue(enabled.isEmpty());
        assertTrue(pendingUninstall.isEmpty());
        assertTrue(quarantined.isEmpty());
    }

    /// Rejects secret-bearing or non-local report references from strict startup state loading.
    ///
    /// @param temporaryDirectory isolated launcher-local directory
    /// @throws Exception if the unsafe test document cannot be written
    @Test
    public void rejectUnsafeQuarantineReportReferenceDuringStrictLoad(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        Files.writeString(stateFile, """
                {
                  "enabled": [],
                  "pendingUninstall": [],
                  "quarantined": ["dev.hmclce.test.quarantined"],
                  "quarantineReport": {
                    "failureTimestampEpochMillis": 1777000000000,
                    "failureCategory": "CRASH",
                    "failureReason": "CHILD_CRASH",
                    "lastStage": "JVM_STARTED",
                    "launcherLogReference": "logs/hmcl.log?access_token=secret",
                    "quarantinedPluginIds": ["dev.hmclce.test.quarantined"],
                    "pluginPackagesRetained": true,
                    "pluginConfigurationRetained": true,
                    "pluginDataRetained": true
                  }
                }
                """);
        Set<String> enabled = new HashSet<>();
        Set<String> pendingUninstall = new HashSet<>();
        Set<String> quarantined = new HashSet<>();
        PluginStateStore store = new PluginStateStore(stateFile, new PluginMutationLock(temporaryDirectory));

        assertThrows(IOException.class, () -> store.loadStrict(enabled, pendingUninstall, quarantined));

        assertTrue(enabled.isEmpty());
        assertTrue(pendingUninstall.isEmpty());
        assertTrue(quarantined.isEmpty());
    }

    /// Rejects persisted reports that contradict the quarantine file-retention guarantee.
    ///
    /// @param temporaryDirectory isolated launcher-local directory
    /// @throws Exception if the contradictory test document cannot be written
    @Test
    public void rejectQuarantineReportWithoutRetainedFilesDuringStrictLoad(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        Files.writeString(stateFile, """
                {
                  "enabled": [],
                  "pendingUninstall": [],
                  "quarantined": ["dev.hmclce.test.quarantined"],
                  "quarantineReport": {
                    "failureTimestampEpochMillis": 1777000000000,
                    "failureCategory": "CRASH",
                    "failureReason": "CHILD_CRASH",
                    "lastStage": "JVM_STARTED",
                    "quarantinedPluginIds": ["dev.hmclce.test.quarantined"],
                    "pluginPackagesRetained": true,
                    "pluginConfigurationRetained": false,
                    "pluginDataRetained": true
                  }
                }
                """);
        Set<String> enabled = new HashSet<>();
        Set<String> pendingUninstall = new HashSet<>();
        Set<String> quarantined = new HashSet<>();
        PluginStateStore store = new PluginStateStore(stateFile, new PluginMutationLock(temporaryDirectory));

        assertThrows(IOException.class, () -> store.loadStrict(enabled, pendingUninstall, quarantined));

        assertTrue(enabled.isEmpty());
        assertTrue(pendingUninstall.isEmpty());
        assertTrue(quarantined.isEmpty());
    }
}
