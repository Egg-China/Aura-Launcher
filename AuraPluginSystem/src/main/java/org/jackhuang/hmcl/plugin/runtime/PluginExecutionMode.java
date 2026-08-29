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
package org.jackhuang.hmcl.plugin.runtime;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines whether a plugin executes inside the launcher process or through an isolated runtime boundary.
@NotNullByDefault
public enum PluginExecutionMode {
    /// Executes through the launcher's embedded runtime bridge.
    EMBEDDED("embedded"),

    /// Executes through a provider-owned isolated runtime boundary.
    ISOLATED("isolated");

    /// Stable serialized identifier used in `plugin.json`.
    private final String id;

    /// Creates one execution mode with its serialized identifier.
    ///
    /// @param id stable serialized identifier
    PluginExecutionMode(String id) {
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
