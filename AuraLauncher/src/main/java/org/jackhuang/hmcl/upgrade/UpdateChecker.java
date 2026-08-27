/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.upgrade;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.*;
import javafx.beans.value.ObservableBooleanValue;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.Lang.*;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Coordinates Aura Launcher update checks and exposes their observable state to the UI.
public final class UpdateChecker {
    /// Prevents construction of the process-wide update coordinator.
    private UpdateChecker() {
    }

    /// Most recent remote release returned by a successful check, or `null` before one succeeds.
    private static final ObjectProperty<@Nullable RemoteVersion> latestVersion = new SimpleObjectProperty<>();
    /// Whether the current launcher version is older than the latest applicable remote release.
    private static final BooleanBinding outdated = Bindings.createBooleanBinding(
            () -> {
                @Nullable RemoteVersion latest = latestVersion.get();
                if (latest == null || isDevelopmentVersion(Metadata.VERSION)) {
                    return false;
                } else if (latest.force()
                        || Metadata.isNightly()
                        || latest.channel() == UpdateChannel.NIGHTLY
                        || latest.channel() != UpdateChannel.getChannel()) {
                    return !latest.version().equals(Metadata.VERSION);
                } else {
                    return VersionNumber.compare(Metadata.VERSION, latest.version()) < 0;
                }
            },
            latestVersion);
    /// Whether an asynchronous update request is currently active.
    private static final ReadOnlyBooleanWrapper checkingUpdate = new ReadOnlyBooleanWrapper(false);

    /// Starts the configured automatic update check unless private-build updates are disabled.
    public static void init() {
        if (Metadata.AURA_UPDATE_URL.isBlank()) {
            LOG.info("Automatic updates are disabled because Aura Launcher has no configured release endpoint");
            return;
        }
        requestCheckUpdate(UpdateChannel.getChannel(), settings().acceptPreviewUpdateProperty().get());
    }

    /// Returns the latest known remote release, or `null` before a successful check.
    public static @Nullable RemoteVersion getLatestVersion() {
        return latestVersion.get();
    }

    /// Returns the observable latest-release property, whose value may initially be `null`.
    public static ReadOnlyObjectProperty<@Nullable RemoteVersion> latestVersionProperty() {
        return latestVersion;
    }

    /// Returns whether the latest known release supersedes the running launcher.
    public static boolean isOutdated() {
        return outdated.get();
    }

    /// Returns the observable outdated-state binding.
    public static ObservableBooleanValue outdatedProperty() {
        return outdated;
    }

    /// Returns whether an update request is currently active.
    public static boolean isCheckingUpdate() {
        return checkingUpdate.get();
    }

    /// Returns the read-only observable update-request state.
    public static ReadOnlyBooleanProperty checkingUpdateProperty() {
        return checkingUpdate.getReadOnlyProperty();
    }

    /// Fetches and validates the latest release for one update channel.
    private static RemoteVersion checkUpdate(UpdateChannel channel, boolean preview) throws IOException {
        if (!IntegrityChecker.DISABLE_SELF_INTEGRITY_CHECK && !IntegrityChecker.isSelfVerified()) {
            throw new IOException("Self verification failed");
        }

        var query = new LinkedHashMap<String, String>();
        query.put("version", Metadata.VERSION);
        query.put("channel", preview ? channel.channelName + "-preview" : channel.channelName);

        String url = Metadata.GITHUB_RELEASES_API_URL.equals(Metadata.AURA_UPDATE_URL)
                ? Metadata.AURA_UPDATE_URL
                : NetworkUtils.withQuery(Metadata.AURA_UPDATE_URL, query);
        return RemoteVersion.fetch(channel, preview, url);
    }

    /// Returns whether a version string identifies a local development build.
    private static boolean isDevelopmentVersion(String version) {
        return version.contains("@") || // eg. @develop@
                version.contains("SNAPSHOT"); // eg. 3.5.SNAPSHOT
    }

    /// Schedules one asynchronous update request when no other request is active.
    public static void requestCheckUpdate(UpdateChannel channel, boolean preview) {
        if (Metadata.AURA_UPDATE_URL.isBlank()) {
            return;
        }
        Platform.runLater(() -> {
            if (isCheckingUpdate())
                return;
            checkingUpdate.set(true);

            thread(() -> {
                @Nullable RemoteVersion result = null;
                try {
                    result = checkUpdate(channel, preview);
                    LOG.info("Latest version (" + channel + ", preview=" + preview + ") is " + result);
                } catch (Throwable e) {
                    LOG.warning("Failed to check for update", e);
                }

                @Nullable RemoteVersion finalResult = result;
                Platform.runLater(() -> {
                    if (finalResult != null) {
                        latestVersion.set(finalResult);
                    }
                    checkingUpdate.set(false);
                });
            }, "Update Checker", true);
        });
    }
}
