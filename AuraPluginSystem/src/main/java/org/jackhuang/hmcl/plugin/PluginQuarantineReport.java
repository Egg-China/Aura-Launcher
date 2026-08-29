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

import org.jackhuang.hmcl.plugin.protector.PluginRecoveryRecord;
import org.jackhuang.hmcl.plugin.protector.ProtectorStage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/// Secret-free recovery summary published after every installed third-party plugin is durably quarantined.
///
/// @param failureTimestampEpochMillis wall-clock failure time in Unix epoch milliseconds
/// @param failureCategory stable broad failure category
/// @param failureReason controlled startup failure reason
/// @param lastStage last authenticated startup stage
/// @param lastHeartbeatMonotonicNanos last authenticated protector heartbeat from the failed startup
/// @param activeProviderId active Runtime Provider at failure, or `null`
/// @param activePluginId active ordinary plugin at failure, or `null`
/// @param launcherLogReference safe launcher-local relative log reference, or `null`
/// @param diagnosticDumpReference safe launcher-local relative diagnostic reference, or `null`
/// @param quarantinedPluginIds immutable sorted IDs quarantined for this recovery record
/// @param pluginPackagesRetained whether installed plugin package files were retained
/// @param pluginConfigurationRetained whether extracted plugin configuration files were retained
/// @param pluginDataRetained whether persistent plugin data files were retained
@NotNullByDefault
public record PluginQuarantineReport(
        long failureTimestampEpochMillis,
        PluginRecoveryRecord.FailureCategory failureCategory,
        PluginRecoveryRecord.FailureReason failureReason,
        ProtectorStage lastStage,
        long lastHeartbeatMonotonicNanos,
        @Nullable String activeProviderId,
        @Nullable String activePluginId,
        @Nullable String launcherLogReference,
        @Nullable String diagnosticDumpReference,
        @Unmodifiable Set<String> quarantinedPluginIds,
        boolean pluginPackagesRetained,
        boolean pluginConfigurationRetained,
        boolean pluginDataRetained
) {
    /// Validates immutable report data and captures quarantine IDs in deterministic order.
    public PluginQuarantineReport {
        new PluginRecoveryRecord(
                failureTimestampEpochMillis,
                failureCategory,
                failureReason,
                lastStage,
                lastHeartbeatMonotonicNanos,
                activeProviderId,
                activePluginId,
                launcherLogReference,
                diagnosticDumpReference
        );
        Objects.requireNonNull(quarantinedPluginIds, "quarantinedPluginIds");
        if (!pluginPackagesRetained || !pluginConfigurationRetained || !pluginDataRetained) {
            throw new IllegalArgumentException("Quarantine report must retain all plugin-owned files");
        }
        LinkedHashSet<String> sortedIds = new LinkedHashSet<>();
        quarantinedPluginIds.stream().sorted().forEach(pluginId -> {
            if (!PluginManifest.isValidId(pluginId)) {
                throw new IllegalArgumentException("Invalid quarantined plugin ID: " + pluginId);
            }
            sortedIds.add(pluginId);
        });
        quarantinedPluginIds = Collections.unmodifiableSet(sortedIds);
    }

    /// Creates a retained-files report from one strict recovery record and the exact quarantined package IDs.
    ///
    /// @param recoveryRecord consumed strict recovery record
    /// @param quarantinedPluginIds exact installed third-party IDs quarantined for the record
    /// @return immutable secret-free recovery report
    static PluginQuarantineReport fromRecovery(
            PluginRecoveryRecord recoveryRecord,
            @Unmodifiable Set<String> quarantinedPluginIds
    ) {
        return new PluginQuarantineReport(
                recoveryRecord.failureTimestampEpochMillis(),
                recoveryRecord.failureCategory(),
                recoveryRecord.failureReason(),
                recoveryRecord.lastStage(),
                recoveryRecord.lastHeartbeatMonotonicNanos(),
                recoveryRecord.activeProviderId(),
                recoveryRecord.activePluginId(),
                recoveryRecord.launcherLogReference(),
                recoveryRecord.diagnosticDumpReference(),
                quarantinedPluginIds,
                true,
                true,
                true
        );
    }
}
