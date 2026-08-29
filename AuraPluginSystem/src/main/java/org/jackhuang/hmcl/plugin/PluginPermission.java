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

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;

/// Declares a sensitive launcher capability that a schema-v3 plugin intends to use.
@NotNullByDefault
public enum PluginPermission {
    /// Reads or writes files outside the plugin's packaged resources and private data directory.
    @SerializedName("filesystem")
    FILESYSTEM("filesystem"),

    /// Opens network connections or otherwise communicates with remote services.
    @SerializedName("network")
    NETWORK("network"),

    /// Starts, inspects, or controls operating-system processes.
    @SerializedName("process")
    PROCESS("process"),

    /// Reads or acts on launcher account information and authentication state.
    @SerializedName("account")
    ACCOUNT("account"),

    /// Participates in or modifies the Minecraft launch process.
    @SerializedName("game-launch")
    GAME_LAUNCH("game-launch"),

    /// Registers or modifies launcher user-interface elements.
    @SerializedName("launcher-ui")
    LAUNCHER_UI("launcher-ui"),

    /// Reads from or writes to the system clipboard.
    @SerializedName("clipboard")
    CLIPBOARD("clipboard"),

    /// Transforms launcher classes through startup-time SpongePowered Mixin configurations.
    @SerializedName("mixin")
    MIXIN("mixin"),

    /// Loads native libraries or invokes native code.
    @SerializedName("native-code")
    NATIVE_CODE("native-code"),

    /// Subscribes to launcher lifecycle hook points such as download, login, or game launch.
    @SerializedName("launcher-hook")
    LAUNCHER_HOOK("launcher-hook"),

    /// Applies declarative before, after, or replace patches to launcher core methods.
    @SerializedName("launcher-patch")
    LAUNCHER_PATCH("launcher-patch"),

    /// Invokes privileged launcher Core services through the stable language-neutral Bridge.
    @SerializedName("launcher-core")
    LAUNCHER_CORE("launcher-core"),

    /// Obtains controlled raw JVM, JNI, JVMTI, Instrumentation, or direct Java object access.
    @SerializedName("jvm-raw")
    JVM_RAW("jvm-raw"),

    /// Executes commands through an operating-system shell.
    @SerializedName("shell")
    SHELL("shell");

    /// Stable kebab-case identifier used in `plugin.json`.
    private final String id;

    /// Creates one permission with its stable serialized identifier.
    ///
    /// @param id stable JSON identifier
    PluginPermission(String id) {
        this.id = id;
    }

    /// Returns the stable kebab-case identifier used in manifests and permission prompts.
    ///
    /// @return stable JSON identifier
    public String getId() {
        return id;
    }

    /// Returns whether this capability belongs exclusively to the schema-v5 runtime platform.
    ///
    /// @return whether manifests and Store entries before schema v5 must reject this permission
    public boolean isSchemaFiveOnly() {
        return this == LAUNCHER_HOOK
                || this == LAUNCHER_PATCH
                || this == LAUNCHER_CORE
                || this == JVM_RAW
                || this == SHELL;
    }

    /// Returns the stable JSON identifier so HMCL's enum adapter preserves kebab-case values.
    ///
    /// @return stable JSON identifier
    @Override
    public String toString() {
        return id;
    }
}
