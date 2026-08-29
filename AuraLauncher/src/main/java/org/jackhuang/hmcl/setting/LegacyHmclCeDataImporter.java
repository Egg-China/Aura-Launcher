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

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Transactionally copies a fixed settings whitelist from HMCL CE homes into isolated Aura homes.
@NotNullByDefault
public final class LegacyHmclCeDataImporter {
    /// Relative files accepted from the machine-local HMCL CE home.
    private static final @Unmodifiable List<Path> LOCAL_WHITELIST = List.of(
            Path.of("config", "launcher-settings.json"),
            Path.of("config", "authlib-injector-servers.json"),
            Path.of("config", "game-directories.json"),
            Path.of("config", "game-settings.json"),
            Path.of("config", "accounts.json"),
            Path.of("private", "account-private-data.json")
    );

    /// Relative files accepted from the per-user HMCL CE home.
    private static final @Unmodifiable List<Path> USER_WHITELIST = List.of(
            Path.of("config", "user-settings.json"),
            Path.of("config", "user-game-directories.json"),
            Path.of("config", "user-accounts.json"),
            Path.of("private", "user-account-private-data.json")
    );

    /// Relative location of the successful-import receipt in Aura's local home.
    private static final Path RECEIPT_RELATIVE_PATH = Path.of("state", "hmcl-ce-import-receipt.json");

    /// Prefix that identifies staging directories owned by this importer.
    private static final String STAGING_PREFIX = ".aura-hmcl-ce-import-";

    /// Creates a stateless HMCL CE settings importer.
    public LegacyHmclCeDataImporter() {
    }

    /// Imports all existing allowlisted regular files without reading or modifying plugin state.
    ///
    /// The method validates every selected file as a JSON object before staging anything. Existing
    /// Aura settings are rejected unless replacement is explicitly enabled. Successful replacement
    /// preserves persistent byte-identical backups; any publication failure restores the prior state.
    ///
    /// @param legacyUserHome source per-user HMCL CE home
    /// @param legacyLocalHome source machine-local HMCL CE home
    /// @param auraUserHome destination per-user Aura home
    /// @param auraLocalHome destination machine-local Aura home
    /// @param replaceExisting whether existing Aura setting files may be backed up and replaced
    /// @return immutable import outcome
    public LegacyHmclCeImportResult importData(
            Path legacyUserHome,
            Path legacyLocalHome,
            Path auraUserHome,
            Path auraLocalHome,
            boolean replaceExisting
    ) {
        Path normalizedLegacyUser = normalize(legacyUserHome);
        Path normalizedLegacyLocal = normalize(legacyLocalHome);
        Path normalizedAuraUser = normalize(auraUserHome);
        Path normalizedAuraLocal = normalize(auraLocalHome);
        if (normalizedLegacyUser.equals(normalizedAuraUser)
                || normalizedLegacyLocal.equals(normalizedAuraLocal)) {
            return LegacyHmclCeImportResult.failure("Legacy launcher source homes must be separate from Aura homes");
        }

        Path receipt = receiptPath(normalizedAuraLocal);
        if (!replaceExisting && Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
            return LegacyHmclCeImportResult.skipped();
        }

        List<ImportFile> selected = new ArrayList<>();
        collectSelected(normalizedLegacyLocal, normalizedAuraLocal, LOCAL_WHITELIST, selected);
        collectSelected(normalizedLegacyUser, normalizedAuraUser, USER_WHITELIST, selected);
        if (selected.isEmpty()) {
            return LegacyHmclCeImportResult.skipped();
        }

        try {
            validateSelected(selected);
            if (!replaceExisting) {
                rejectExistingTargets(selected);
            }
            return publish(
                    selected,
                    normalizedLegacyUser,
                    normalizedLegacyLocal,
                    normalizedAuraLocal,
                    receipt,
                    replaceExisting
            );
        } catch (IOException | RuntimeException e) {
            return LegacyHmclCeImportResult.failure(describeFailure(e));
        }
    }

    /// Detects importable settings in the normal HMCL CE homes resolved by [Metadata].
    ///
    /// @return legacy home pair when at least one allowlisted regular file exists
    public static Optional<LegacyHomes> detectLegacyHomes() {
        return detectLegacyHomes(Metadata.LEGACY_HMCL_CE_USER_HOME, Metadata.LEGACY_HMCL_CE_LOCAL_HOME);
    }

    /// Detects importable settings in an explicit pair of HMCL CE homes.
    ///
    /// Plugin packages, caches, dependency downloads, logs, and state files do not count as
    /// importable settings and therefore cannot trigger an import prompt.
    ///
    /// @param legacyUserHome candidate per-user HMCL CE home
    /// @param legacyLocalHome candidate machine-local HMCL CE home
    /// @return normalized legacy home pair when an allowlisted regular file exists
    public static Optional<LegacyHomes> detectLegacyHomes(Path legacyUserHome, Path legacyLocalHome) {
        Path normalizedUser = normalize(legacyUserHome);
        Path normalizedLocal = normalize(legacyLocalHome);
        if (containsManagedFile(normalizedLocal, LOCAL_WHITELIST)
                || containsManagedFile(normalizedUser, USER_WHITELIST)) {
            return Optional.of(new LegacyHomes(normalizedUser, normalizedLocal));
        }
        return Optional.empty();
    }

    /// Returns whether neither managed Aura settings nor an import receipt exists.
    ///
    /// Unrelated logs, caches, plugins, and dependency files do not initialize Aura for import purposes.
    ///
    /// @param auraUserHome per-user Aura home
    /// @param auraLocalHome machine-local Aura home
    /// @return `true` when automatic import may still be offered
    public static boolean isAuraUninitialized(Path auraUserHome, Path auraLocalHome) {
        Path normalizedUser = normalize(auraUserHome);
        Path normalizedLocal = normalize(auraLocalHome);
        if (Files.isRegularFile(receiptPath(normalizedLocal), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return !containsManagedFile(normalizedLocal, LOCAL_WHITELIST)
                && !containsManagedFile(normalizedUser, USER_WHITELIST);
    }

    /// Returns the normalized successful-import receipt path for an Aura local home.
    ///
    /// @param auraLocalHome machine-local Aura home
    /// @return absolute normalized receipt path
    public static Path receiptPath(Path auraLocalHome) {
        return normalize(auraLocalHome).resolve(RECEIPT_RELATIVE_PATH);
    }

    /// Collects existing regular allowlisted source files and their destination paths.
    ///
    /// @param sourceHome normalized legacy source home
    /// @param targetHome normalized Aura target home
    /// @param whitelist immutable relative-file whitelist
    /// @param selected mutable operation-local destination list
    private static void collectSelected(
            Path sourceHome,
            Path targetHome,
            @Unmodifiable List<Path> whitelist,
            List<ImportFile> selected
    ) {
        for (Path relativePath : whitelist) {
            Path source = sourceHome.resolve(relativePath).normalize();
            if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                selected.add(new ImportFile(source, targetHome.resolve(relativePath).normalize(), relativePath));
            }
        }
    }

    /// Validates that every selected source is a non-null JSON object before publication begins.
    ///
    /// @param selected selected source and target mappings
    /// @throws IOException if a source cannot be read
    /// @throws IllegalArgumentException if a source is malformed or has a non-object root
    private static void validateSelected(List<ImportFile> selected) throws IOException {
        for (ImportFile file : selected) {
            @Nullable JsonObject object = JsonUtils.fromJsonFile(file.source(), JsonObject.class);
            if (object == null) {
                throw new IllegalArgumentException("Settings file must contain a JSON object: " + file.source());
            }
        }
    }

    /// Rejects an automatic import before staging when any target already exists.
    ///
    /// @param selected selected source and target mappings
    /// @throws IOException if an existing target would be overwritten
    private static void rejectExistingTargets(List<ImportFile> selected) throws IOException {
        for (ImportFile file : selected) {
            if (Files.exists(file.target(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Aura settings already exist: " + file.target());
            }
        }
    }

    /// Stages, backs up, and publishes one already validated import transaction.
    ///
    /// @param selected selected source and target mappings
    /// @param legacyUserHome normalized per-user source home
    /// @param legacyLocalHome normalized local source home
    /// @param auraLocalHome normalized local destination home
    /// @param receipt normalized receipt path
    /// @param replaceExisting whether existing settings may be replaced
    /// @return successful immutable result
    /// @throws IOException if staging, backup, publication, or rollback fails
    private static LegacyHmclCeImportResult publish(
            List<ImportFile> selected,
            Path legacyUserHome,
            Path legacyLocalHome,
            Path auraLocalHome,
            Path receipt,
            boolean replaceExisting
    ) throws IOException {
        Path stagingParent = requireParent(auraLocalHome);
        Files.createDirectories(stagingParent);
        Path stagingRoot = Files.createTempDirectory(stagingParent, STAGING_PREFIX);
        Map<Path, Path> backups = new LinkedHashMap<>();
        List<Path> published = new ArrayList<>();
        Path receiptBackup = stagingRoot.resolve("previous-receipt.json");
        boolean hadReceipt = Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS);

        try {
            stageSelected(selected, stagingRoot);
            Path stagedReceipt = writeStagedReceipt(
                    stagingRoot, selected, legacyUserHome, legacyLocalHome, auraLocalHome
            );
            if (hadReceipt) {
                Files.copy(receipt, receiptBackup, StandardCopyOption.COPY_ATTRIBUTES);
            }
            if (replaceExisting) {
                createBackups(selected, backups);
            }

            for (ImportFile file : selected) {
                Path staged = stagedPath(stagingRoot, file);
                Files.createDirectories(requireParent(file.target()));
                published.add(file.target());
                movePublished(staged, file.target());
            }

            Files.createDirectories(requireParent(receipt));
            movePublished(stagedReceipt, receipt);
            return LegacyHmclCeImportResult.success(List.copyOf(published), Map.copyOf(backups));
        } catch (IOException | RuntimeException failure) {
            @Nullable IOException rollbackFailure = rollback(published, backups, receipt, receiptBackup, hadReceipt);
            removeFailedBackups(backups);
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            try {
                deleteVerifiedStaging(stagingRoot, stagingParent);
            } catch (IOException cleanupFailure) {
                LOG.warning("Failed to clean legacy launcher import staging directory " + stagingRoot, cleanupFailure);
            }
        }
    }

    /// Copies selected source bytes into the transaction-owned staging tree.
    ///
    /// @param selected selected source and target mappings
    /// @param stagingRoot verified transaction staging root
    /// @throws IOException if a source cannot be staged
    private static void stageSelected(List<ImportFile> selected, Path stagingRoot) throws IOException {
        for (ImportFile file : selected) {
            Path staged = stagedPath(stagingRoot, file);
            Files.createDirectories(requireParent(staged));
            Files.copy(file.source(), staged, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    /// Writes the receipt into staging so it can be published after every setting file.
    ///
    /// @param stagingRoot verified transaction staging root
    /// @param selected selected source and target mappings
    /// @param legacyUserHome normalized per-user source home
    /// @param legacyLocalHome normalized local source home
    /// @param auraLocalHome normalized local destination home
    /// @return staged receipt path
    /// @throws IOException if the staged receipt cannot be written
    private static Path writeStagedReceipt(
            Path stagingRoot,
            List<ImportFile> selected,
            Path legacyUserHome,
            Path legacyLocalHome,
            Path auraLocalHome
    ) throws IOException {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("importedAt", Instant.now().toString());
        receipt.addProperty("legacyUserHome", legacyUserHome.toString());
        receipt.addProperty("legacyLocalHome", legacyLocalHome.toString());
        receipt.addProperty("auraLocalHome", auraLocalHome.toString());
        receipt.add("importedFiles", JsonUtils.GSON.toJsonTree(
                selected.stream().map(file -> file.target().toString()).toList()
        ));
        Path stagedReceipt = stagingRoot.resolve("receipt.json");
        Files.writeString(
                stagedReceipt,
                JsonUtils.GSON.toJson(receipt),
                StandardCharsets.UTF_8
        );
        return stagedReceipt;
    }

    /// Creates unique persistent backups for selected targets that already exist.
    ///
    /// @param selected selected source and target mappings
    /// @param backups mutable target-to-backup map
    /// @throws IOException if a backup cannot be created
    private static void createBackups(List<ImportFile> selected, Map<Path, Path> backups) throws IOException {
        for (ImportFile file : selected) {
            if (!Files.exists(file.target(), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(file.target(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Aura settings target is not a regular file: " + file.target());
            }
            Path backup = uniqueBackupPath(file.target());
            Files.copy(file.target(), backup, StandardCopyOption.COPY_ATTRIBUTES);
            backups.put(file.target(), backup);
        }
    }

    /// Restores settings and receipt state after an incomplete publication.
    ///
    /// @param published target paths whose publication was attempted
    /// @param backups persistent target backups
    /// @param receipt normalized receipt path
    /// @param receiptBackup transaction-local prior receipt copy
    /// @param hadReceipt whether the receipt existed before the transaction
    /// @return first rollback exception, or `null` when rollback completed
    private static @Nullable IOException rollback(
            List<Path> published,
            Map<Path, Path> backups,
            Path receipt,
            Path receiptBackup,
            boolean hadReceipt
    ) {
        @Nullable IOException firstFailure = null;
        List<Path> reversed = new ArrayList<>(published);
        Collections.reverse(reversed);
        for (Path target : reversed) {
            try {
                @Nullable Path backup = backups.get(target);
                if (backup != null) {
                    Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    Files.deleteIfExists(target);
                }
            } catch (IOException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
            }
        }
        try {
            if (hadReceipt && Files.isRegularFile(receiptBackup, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(requireParent(receipt));
                Files.copy(receiptBackup, receipt, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            } else {
                Files.deleteIfExists(receipt);
            }
        } catch (IOException e) {
            if (firstFailure == null) {
                firstFailure = e;
            } else {
                firstFailure.addSuppressed(e);
            }
        }
        return firstFailure;
    }

    /// Deletes backups from a transaction that did not complete successfully.
    ///
    /// @param backups target-to-backup paths created by the transaction
    private static void removeFailedBackups(Map<Path, Path> backups) {
        for (Path backup : backups.values()) {
            try {
                Files.deleteIfExists(backup);
            } catch (IOException ignored) {
            }
        }
    }

    /// Moves a staged file into place atomically when the file system supports it.
    ///
    /// @param source staged file
    /// @param target final destination
    /// @throws IOException if both atomic and ordinary replacement fail
    private static void movePublished(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Resolves the deterministic staging path for one selected source file.
    ///
    /// @param stagingRoot verified transaction staging root
    /// @param file selected source and target mapping
    /// @return staging path below the transaction root
    private static Path stagedPath(Path stagingRoot, ImportFile file) {
        String home = LOCAL_WHITELIST.contains(file.relativePath()) ? "local" : "user";
        return stagingRoot.resolve(home).resolve(file.relativePath());
    }

    /// Creates a unique backup path next to an existing Aura settings file.
    ///
    /// @param target existing Aura settings file
    /// @return unused sibling backup path
    private static Path uniqueBackupPath(Path target) {
        String suffix = ".hmcl-ce-import-backup-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
        return target.resolveSibling(target.getFileName() + suffix);
    }

    /// Deletes a staging tree only after verifying its ownership and direct parent.
    ///
    /// @param stagingRoot staging root created by this transaction
    /// @param expectedParent normalized parent supplied to temporary-directory creation
    /// @throws IOException if cleanup fails
    private static void deleteVerifiedStaging(Path stagingRoot, Path expectedParent) throws IOException {
        Path normalized = normalize(stagingRoot);
        @Nullable Path parent = normalized.getParent();
        if (parent == null
                || !parent.equals(normalize(expectedParent))
                || !normalized.getFileName().toString().startsWith(STAGING_PREFIX)) {
            throw new IOException("Refusing to clean unverified import staging path: " + stagingRoot);
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Collections.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /// Checks whether one Aura home contains an allowlisted regular settings file.
    ///
    /// @param home normalized Aura home
    /// @param whitelist immutable relative-file whitelist
    /// @return `true` when a managed settings file exists
    private static boolean containsManagedFile(Path home, @Unmodifiable List<Path> whitelist) {
        for (Path relativePath : whitelist) {
            if (Files.isRegularFile(home.resolve(relativePath), LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }

    /// Returns the absolute normalized form of a path.
    ///
    /// @param path source path
    /// @return absolute normalized path
    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /// Returns a required parent directory for an absolute path.
    ///
    /// @param path absolute path
    /// @return parent directory
    /// @throws IOException if the path has no parent
    private static Path requireParent(Path path) throws IOException {
        @Nullable Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Path has no parent directory: " + path);
        }
        return parent;
    }

    /// Formats an exception as a stable non-empty result message.
    ///
    /// @param failure import failure
    /// @return human-readable failure detail
    private static String describeFailure(Exception failure) {
        @Nullable String message = failure.getMessage();
        return message != null && !message.isBlank()
                ? message
                : failure.getClass().getSimpleName();
    }

    /// Associates one allowlisted legacy source with its Aura target and relative location.
    ///
    /// @param source absolute normalized legacy source file
    /// @param target absolute normalized Aura target file
    /// @param relativePath allowlisted relative location
    private record ImportFile(Path source, Path target, Path relativePath) {
    }

    /// Groups the two normalized HMCL CE homes used by one optional import.
    ///
    /// @param userHome user-wide HMCL CE settings source
    /// @param localHome launcher-local HMCL CE settings source
    @NotNullByDefault
    public record LegacyHomes(Path userHome, Path localHome) {
    }
}
