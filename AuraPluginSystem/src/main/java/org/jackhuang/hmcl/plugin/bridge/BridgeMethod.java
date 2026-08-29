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
package org.jackhuang.hmcl.plugin.bridge;

import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jetbrains.annotations.NotNullByDefault;

/// Defines the frozen numeric and textual dispatch contract for stable Runtime Bridge operations.
@NotNullByDefault
public enum BridgeMethod {
    /// Returns the launcher version embedded in the running artifact.
    CORE_LAUNCHER_VERSION(
            1L,
            "core.launcher.version",
            PluginPermission.LAUNCHER_CORE,
            ValueSchema.NULL,
            ValueSchema.STRING,
            ThreadPolicy.CALLER
    ),

    /// Returns the exact read-only package directory of the calling payload.
    CORE_PACKAGE_DIRECTORY(
            2L,
            "core.plugin.package-directory",
            PluginPermission.LAUNCHER_CORE,
            ValueSchema.NULL,
            ValueSchema.STRING,
            ThreadPolicy.CALLER
    ),

    /// Returns the persistent private data directory of the calling payload.
    CORE_DATA_DIRECTORY(
            3L,
            "core.plugin.data-directory",
            PluginPermission.LAUNCHER_CORE,
            ValueSchema.NULL,
            ValueSchema.STRING,
            ThreadPolicy.CALLER
    ),

    /// Registers one sidebar action backed by an external Runtime callback.
    UI_REGISTER_SIDEBAR_ACTION(
            1_001L,
            "ui.sidebar.register-action",
            PluginPermission.LAUNCHER_UI,
            ValueSchema.MAP,
            ValueSchema.HANDLE,
            ThreadPolicy.FX_APPLICATION
    ),

    /// Registers one declarative page and returns its page and named-node handles.
    UI_REGISTER_PAGE(
            1_002L,
            "ui.page.register",
            PluginPermission.LAUNCHER_UI,
            ValueSchema.MAP,
            ValueSchema.MAP,
            ThreadPolicy.FX_APPLICATION
    ),

    /// Changes one supported property on an owned declarative node.
    UI_SET_PROPERTY(
            1_003L,
            "ui.node.set-property",
            PluginPermission.LAUNCHER_UI,
            ValueSchema.MAP,
            ValueSchema.NULL,
            ThreadPolicy.FX_APPLICATION
    ),

    /// Materializes and navigates to an owned declarative page.
    UI_NAVIGATE(
            1_004L,
            "ui.page.navigate",
            PluginPermission.LAUNCHER_UI,
            ValueSchema.HANDLE,
            ValueSchema.NULL,
            ThreadPolicy.FX_APPLICATION
    ),

    /// Removes every callback, handle, sidebar contribution, and declarative node owned by the caller.
    UI_UNREGISTER_OWNER(
            1_005L,
            "ui.owner.unregister",
            PluginPermission.LAUNCHER_UI,
            ValueSchema.NULL,
            ValueSchema.NULL,
            ThreadPolicy.FX_APPLICATION
    );

    /// Stable positive numeric method ID used by generated and binary protocols.
    private final long id;

    /// Canonical operation name used by the Rust ABI v1 Host table.
    private final String operation;

    /// Permission required immediately before each invocation.
    private final PluginPermission permission;

    /// Root Bridge value schema accepted as input.
    private final ValueSchema inputSchema;

    /// Root Bridge value schema required from the handler.
    private final ValueSchema resultSchema;

    /// Thread on which the registered handler must execute.
    private final ThreadPolicy threadPolicy;

    /// Creates one frozen method descriptor.
    ///
    /// @param id stable numeric method ID
    /// @param operation canonical ABI operation
    /// @param permission required launcher permission
    /// @param inputSchema accepted root input schema
    /// @param resultSchema required root result schema
    /// @param threadPolicy required execution thread
    BridgeMethod(
            long id,
            String operation,
            PluginPermission permission,
            ValueSchema inputSchema,
            ValueSchema resultSchema,
            ThreadPolicy threadPolicy
    ) {
        this.id = id;
        this.operation = operation;
        this.permission = permission;
        this.inputSchema = inputSchema;
        this.resultSchema = resultSchema;
        this.threadPolicy = threadPolicy;
    }

    /// Returns the stable positive numeric ID.
    ///
    /// @return numeric method ID
    public long id() {
        return id;
    }

    /// Returns the canonical Runtime ABI operation.
    ///
    /// @return canonical operation name
    public String operation() {
        return operation;
    }

    /// Returns the launcher permission required by this method.
    ///
    /// @return required permission
    public PluginPermission permission() {
        return permission;
    }

    /// Returns the accepted root input schema.
    ///
    /// @return input schema
    public ValueSchema inputSchema() {
        return inputSchema;
    }

    /// Returns the required root result schema.
    ///
    /// @return result schema
    public ValueSchema resultSchema() {
        return resultSchema;
    }

    /// Returns the handler execution policy.
    ///
    /// @return thread policy
    public ThreadPolicy threadPolicy() {
        return threadPolicy;
    }

    /// Defines the root value shape of one method argument or result.
    @NotNullByDefault
    public enum ValueSchema {
        /// Bridge null singleton.
        NULL(BridgeValue.Tag.NULL),

        /// UTF-8 string scalar.
        STRING(BridgeValue.Tag.STRING),

        /// Ordered string-keyed map.
        MAP(BridgeValue.Tag.MAP),

        /// Owner-scoped opaque handle.
        HANDLE(BridgeValue.Tag.HANDLE);

        /// Required Bridge value tag.
        private final BridgeValue.Tag tag;

        /// Creates one root schema for an exact Bridge tag.
        ///
        /// @param tag accepted or required value tag
        ValueSchema(BridgeValue.Tag tag) {
            this.tag = tag;
        }

        /// Returns whether one value has this schema's exact root tag.
        ///
        /// @param value candidate Bridge value
        /// @return whether the root tag matches
        public boolean accepts(BridgeValue value) {
            return value.tag() == tag;
        }
    }

    /// Defines the execution thread required by one Bridge handler.
    @NotNullByDefault
    public enum ThreadPolicy {
        /// Executes synchronously on the invoking thread.
        CALLER,

        /// Executes synchronously on JavaFX, or on the deterministic caller path before toolkit startup.
        FX_APPLICATION
    }
}
