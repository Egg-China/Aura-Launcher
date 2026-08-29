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

/// Distinguishes ordinary plugins from plugins that publish runtime implementations.
@NotNullByDefault
public enum PluginKind {
    /// An ordinary plugin that consumes, but does not publish, a runtime implementation.
    NORMAL("normal"),

    /// A Java bootstrap plugin that publishes one or more runtime implementation declarations.
    RUNTIME_PROVIDER("runtime-provider");

    /// Stable serialized identifier used in `plugin.json`.
    private final String id;

    /// Creates a plugin kind with its serialized identifier.
    ///
    /// @param id stable serialized identifier
    PluginKind(String id) {
        this.id = id;
    }

    /// Returns the stable serialized identifier.
    ///
    /// @return serialized identifier
    public String getId() {
        return id;
    }

    /// Returns the stable serialized identifier for Gson's lower-case enum adapter.
    ///
    /// @return serialized identifier
    @Override
    public String toString() {
        return id;
    }
}
