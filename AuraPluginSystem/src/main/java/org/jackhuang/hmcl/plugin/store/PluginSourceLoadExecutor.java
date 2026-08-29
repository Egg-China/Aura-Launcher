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
package org.jackhuang.hmcl.plugin.store;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/// Limits process-wide plugin source network loads without owning any page or aggregator executor lifecycle.
@NotNullByDefault
public final class PluginSourceLoadExecutor {
    /// Maximum number of source network and manifest loads active process-wide.
    public static final int MAX_CONCURRENCY = 4;

    /// Shared permits spanning aggregate, preview, and manually tested source loads.
    private static final Semaphore PERMITS = new Semaphore(MAX_CONCURRENCY);

    /// Runs one source load while holding one process-wide permit.
    ///
    /// @param task source network and manifest load
    /// @param <T> successful load result type
    /// @return task result
    /// @throws Exception if the source load fails or is interrupted
    public static <T> T call(Callable<T> task) throws Exception {
        PERMITS.acquire();
        try {
            return task.call();
        } finally {
            PERMITS.release();
        }
    }

    /// Prevents construction of the shared source-load limiter.
    private PluginSourceLoadExecutor() {
    }
}
