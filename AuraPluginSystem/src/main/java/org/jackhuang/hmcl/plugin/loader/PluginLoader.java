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
package org.jackhuang.hmcl.plugin.loader;

import org.jackhuang.hmcl.plugin.Plugin;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;

/// Creates a lifecycle implementation from an extracted plugin package.
@NotNullByDefault
public interface PluginLoader {
    /// Loads one validated plugin package.
    ///
    /// @param manifest validated plugin manifest
    /// @param pluginPackage exact verified package inventory
    /// @param nplFile original package path
    /// @return loaded lifecycle implementation
    /// @throws IOException if loading fails
    Plugin load(PluginManifest manifest, VerifiedPluginPackage pluginPackage, Path nplFile) throws IOException;
}
