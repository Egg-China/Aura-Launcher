/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.setting;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Describes the observable outcome of one HMCL CE settings import attempt.
@NotNullByDefault
public final class LegacyHmclCeImportResult {
    /// Whether the import completed without an error.
    private final boolean successful;

    /// Whether the importer intentionally performed no file publication.
    private final boolean skipped;

    /// Human-readable failure detail, or `null` after a successful operation.
    private final @Nullable String failureMessage;

    /// Absolute Aura setting paths published by the operation.
    private final @Unmodifiable List<Path> importedFiles;

    /// Persistent byte-for-byte backups keyed by the Aura paths they protected.
    private final @Unmodifiable Map<Path, Path> backupFiles;

    /// Creates an immutable import outcome.
    ///
    /// @param successful whether the operation completed without error
    /// @param skipped whether no settings were published intentionally
    /// @param failureMessage failure detail, or `null` for success
    /// @param importedFiles absolute paths published by the operation
    /// @param backupFiles backup paths keyed by the replaced Aura paths
    private LegacyHmclCeImportResult(
            boolean successful,
            boolean skipped,
            @Nullable String failureMessage,
            @Unmodifiable List<Path> importedFiles,
            @Unmodifiable Map<Path, Path> backupFiles
    ) {
        this.successful = successful;
        this.skipped = skipped;
        this.failureMessage = failureMessage;
        this.importedFiles = List.copyOf(importedFiles);
        this.backupFiles = Map.copyOf(backupFiles);
    }

    /// Creates a successful result for published settings.
    ///
    /// @param importedFiles absolute paths published by the operation
    /// @param backupFiles backup paths keyed by the replaced Aura paths
    /// @return immutable successful result
    static LegacyHmclCeImportResult success(
            @Unmodifiable List<Path> importedFiles,
            @Unmodifiable Map<Path, Path> backupFiles
    ) {
        return new LegacyHmclCeImportResult(true, false, null, importedFiles, backupFiles);
    }

    /// Creates a successful result for an intentionally skipped operation.
    ///
    /// @return immutable skipped result
    static LegacyHmclCeImportResult skipped() {
        return new LegacyHmclCeImportResult(true, true, null, List.of(), Map.of());
    }

    /// Creates a failed result that published no lasting changes.
    ///
    /// @param failureMessage human-readable failure detail
    /// @return immutable failed result
    static LegacyHmclCeImportResult failure(String failureMessage) {
        return new LegacyHmclCeImportResult(false, false, failureMessage, List.of(), Map.of());
    }

    /// Returns whether the operation completed without an error.
    public boolean isSuccessful() {
        return successful;
    }

    /// Returns whether the operation intentionally published no settings.
    public boolean isSkipped() {
        return skipped;
    }

    /// Returns the failure detail, or `null` after a successful operation.
    public @Nullable String getFailureMessage() {
        return failureMessage;
    }

    /// Returns the immutable list of absolute Aura setting paths that were published.
    public @Unmodifiable List<Path> getImportedFiles() {
        return importedFiles;
    }

    /// Returns immutable backup paths keyed by the absolute Aura paths they protected.
    public @Unmodifiable Map<Path, Path> getBackupFiles() {
        return backupFiles;
    }
}
