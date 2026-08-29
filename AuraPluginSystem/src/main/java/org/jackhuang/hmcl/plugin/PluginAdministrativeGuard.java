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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/// Rejects launcher-administrative calls made from ordinary plugin execution.
///
/// This is a same-process trust guard, not a JVM or operating-system sandbox. It permits only class loaders frozen as
/// trusted during manager construction and code executing outside plugin lifecycle callbacks. A Mixin can inject
/// bytecode into an HMCL-owned class defined by the application class loader, and unrestricted reflection or `Unsafe`
/// can also bypass Java-level encapsulation; those cases require process or module isolation that the current plugin
/// runtime does not provide.
@NotNullByDefault
final class PluginAdministrativeGuard {
    /// Exact application, platform, bootstrap-adjacent, and isolated-test loaders allowed to administer plugins.
    private final @Unmodifiable Set<ClassLoader> trustedClassLoaders;

    /// Per-thread nesting depth while launcher code invokes plugin-owned lifecycle code.
    private final ThreadLocal<Integer> pluginCallbackDepth = ThreadLocal.withInitial(() -> 0);

    /// Stack walker retaining defining classes so their class-loader ancestry can be checked.
    private final StackWalker stackWalker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /// Creates a guard for one plugin manager.
    ///
    /// Production construction trusts only HMCL's application loader and its ancestors. The package-private isolated
    /// manager constructor additionally freezes loaders already present on its construction stack so Gradle and JUnit
    /// can drive tests without trusting loaders created later by a plugin.
    ///
    /// @param trustConstructionStack whether to trust exact loaders already present on the current stack
    PluginAdministrativeGuard(boolean trustConstructionStack) {
        Set<ClassLoader> trusted = new HashSet<>();
        for (@Nullable ClassLoader current = PluginManager.class.getClassLoader();
                current != null;
                current = current.getParent()) {
            trusted.add(current);
        }
        if (trustConstructionStack) {
            stackWalker.forEach(frame -> {
                @Nullable ClassLoader classLoader = frame.getDeclaringClass().getClassLoader();
                if (classLoader != null) {
                    trusted.add(classLoader);
                }
            });
        }
        trustedClassLoaders = Set.copyOf(trusted);
    }

    /// Rejects a state-changing call when any active frame belongs to ordinary plugin code.
    ///
    /// @throws SecurityException if the call originates from plugin execution
    void checkTrustedCaller() {
        @Nullable Class<?> untrustedClass = stackWalker.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(declaringClass -> isUntrustedClassLoader(declaringClass.getClassLoader()))
                .findFirst()
                .orElse(null));
        if (pluginCallbackDepth.get() > 0 || untrustedClass != null) {
            throw new SecurityException("Plugin code cannot invoke launcher-administrative plugin APIs"
                    + (untrustedClass == null ? " during a lifecycle callback" : " from " + untrustedClass.getName()));
        }
    }

    /// Runs one plugin lifecycle callback while administrative entry points are denied on the current thread.
    ///
    /// @param callback plugin-owned callback
    void runPluginCallback(Runnable callback) {
        callPluginCallback((Supplier<Boolean>) () -> {
            callback.run();
            return Boolean.TRUE;
        });
    }

    /// Calls plugin loading and construction while administrative entry points are denied on the current thread.
    ///
    /// @param callback loader-owned operation
    /// @param <T> loaded value type
    /// @return loaded value
    /// @throws IOException if plugin loading fails
    <T> T callPluginLoadingCallback(PluginLoadingOperation<T> callback) throws IOException {
        int previousDepth = pluginCallbackDepth.get();
        pluginCallbackDepth.set(previousDepth + 1);
        try {
            return callback.run();
        } finally {
            restoreCallbackDepth(previousDepth);
        }
    }

    /// Calls one plugin-owned operation while administrative entry points are denied on the current thread.
    ///
    /// @param callback plugin-owned operation
    /// @param <T> result type
    /// @return callback result
    <T> T callPluginCallback(Supplier<T> callback) {
        int previousDepth = pluginCallbackDepth.get();
        pluginCallbackDepth.set(previousDepth + 1);
        try {
            return callback.get();
        } finally {
            restoreCallbackDepth(previousDepth);
        }
    }

    /// Calls one checked plugin-owned operation while administrative entry points are denied on the current thread.
    ///
    /// @param callback plugin-owned operation
    /// @param <T> result type
    /// @return callback result
    /// @throws Exception if the plugin-owned operation fails
    <T> T callPluginCallback(Callable<T> callback) throws Exception {
        int previousDepth = pluginCallbackDepth.get();
        pluginCallbackDepth.set(previousDepth + 1);
        try {
            return callback.call();
        } finally {
            restoreCallbackDepth(previousDepth);
        }
    }

    /// Restores callback nesting after one plugin-owned operation completes.
    ///
    /// @param previousDepth nesting depth captured before the operation
    private void restoreCallbackDepth(int previousDepth) {
        if (previousDepth == 0) {
            pluginCallbackDepth.remove();
        } else {
            pluginCallbackDepth.set(previousDepth);
        }
    }

    /// Returns whether a frame was defined by a loader absent from the construction-time trust set.
    ///
    /// @param candidate defining class loader, or `null` for bootstrap classes
    /// @return whether the loader is untrusted
    private boolean isUntrustedClassLoader(@Nullable ClassLoader candidate) {
        return candidate != null && !trustedClassLoaders.contains(candidate);
    }

    /// Checked plugin-loading operation executed before the lifecycle instance is registered.
    @FunctionalInterface
    @NotNullByDefault
    interface PluginLoadingOperation<T> {
        /// Loads or constructs one plugin-owned value.
        ///
        /// @return loaded value
        /// @throws IOException if loading fails
        T run() throws IOException;
    }
}
