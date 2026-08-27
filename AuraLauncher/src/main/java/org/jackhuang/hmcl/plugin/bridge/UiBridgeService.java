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
package org.jackhuang.hmcl.plugin.bridge;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.PluginUIRegistry;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadContext;
import org.jackhuang.hmcl.ui.Controllers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Registers and owns language-neutral declarative JavaFX contributions for external Runtime payloads.
@NotNullByDefault
public final class UiBridgeService implements LauncherRuntimeBridgeTransport.HandleTransport {
    /// Opaque handle type for one sidebar action contribution.
    private static final String SIDEBAR_HANDLE_TYPE = "ui.sidebar";

    /// Opaque handle type for one declarative page.
    private static final String PAGE_HANDLE_TYPE = "ui.page";

    /// Opaque handle type for one named declarative node.
    private static final String NODE_HANDLE_TYPE = "ui.node";

    /// Canonical declarative node identifier grammar.
    private static final Pattern NODE_ID_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");

    /// Maximum displayed title length.
    private static final int MAX_TITLE_LENGTH = 256;

    /// Tracks asynchronous external callbacks for cancellation during owner cleanup.
    private final BridgeDispatcher callbackDispatcher;

    /// Invokes one provider-owned callback through its Runtime transport.
    private final CallbackInvoker callbackInvoker;

    /// Launcher-owned authority used for callback admission and handle owner verification.
    private final PluginPermissionAuthority permissionAuthority;

    /// Launcher UI adapter used for contribution registration and navigation.
    private final Backend backend;

    /// Generation-safe handles for sidebar items, pages, and named nodes.
    private final BridgeHandleRegistry<PluginCapabilityToken> handles;

    /// Current exact artifact identity keyed by an owner with live UI state.
    private final Map<String, PluginArtifactIdentity> artifactIdentities = new LinkedHashMap<>();

    /// Current execution mode keyed by an owner with live UI state.
    private final Map<String, PluginExecutionMode> executionModes = new LinkedHashMap<>();

    /// Registers stable UI handlers using the launcher's production contribution backend.
    ///
    /// @param registry target Bridge service registry
    /// @param permissionAuthority launcher-owned capability authority
    /// @param callbackDispatcher external callback lifecycle dispatcher
    /// @param callbackInvoker provider callback adapter
    public UiBridgeService(
            BridgeServiceRegistry registry,
            PluginPermissionAuthority permissionAuthority,
            BridgeDispatcher callbackDispatcher,
            CallbackInvoker callbackInvoker
    ) {
        this(registry, permissionAuthority, callbackDispatcher, callbackInvoker, new LauncherBackend());
    }

    /// Registers stable UI handlers with an explicit contribution backend.
    ///
    /// Package visibility confines backend substitution to deterministic Bridge tests.
    ///
    /// @param registry target Bridge service registry
    /// @param permissionAuthority launcher-owned capability authority
    /// @param callbackDispatcher external callback lifecycle dispatcher
    /// @param callbackInvoker provider callback adapter
    /// @param backend launcher UI adapter
    UiBridgeService(
            BridgeServiceRegistry registry,
            PluginPermissionAuthority permissionAuthority,
            BridgeDispatcher callbackDispatcher,
            CallbackInvoker callbackInvoker,
            Backend backend
    ) {
        BridgeServiceRegistry targetRegistry = Objects.requireNonNull(registry, "registry");
        PluginPermissionAuthority authority = Objects.requireNonNull(permissionAuthority, "permissionAuthority");
        this.callbackDispatcher = Objects.requireNonNull(callbackDispatcher, "callbackDispatcher");
        this.callbackInvoker = Objects.requireNonNull(callbackInvoker, "callbackInvoker");
        this.permissionAuthority = authority;
        this.backend = Objects.requireNonNull(backend, "backend");
        this.handles = new BridgeHandleRegistry<>(authority.ownerVerifier(
                this::artifactIdentityForOwner,
                this::executionModeForOwner,
                PluginPermission.LAUNCHER_UI,
                BridgeServiceRegistry.CALLBACK_DOMAIN
        ));

        targetRegistry.register(BridgeMethod.UI_REGISTER_SIDEBAR_ACTION, this::registerSidebarAction);
        targetRegistry.register(BridgeMethod.UI_REGISTER_PAGE, this::registerPage);
        targetRegistry.register(BridgeMethod.UI_SET_PROPERTY, this::setProperty);
        targetRegistry.register(BridgeMethod.UI_NAVIGATE, this::navigate);
        targetRegistry.register(BridgeMethod.UI_UNREGISTER_OWNER, this::unregisterOwner);
    }

    /// Retains one UI handle after resolving fresh call-scoped authority from its exact payload context.
    ///
    /// @param context exact Java-owned payload context
    /// @param objectId launcher-owned object slot
    /// @param generation exact live generation
    /// @throws BridgeError if permission, ownership, or generation validation fails
    @Override
    public void retain(RuntimePayloadContext context, long objectId, long generation) throws BridgeError {
        withHandleAuthority(context, token -> handles.retain(token, objectId, generation));
    }

    /// Releases one UI handle after resolving fresh call-scoped authority from its exact payload context.
    ///
    /// @param context exact Java-owned payload context
    /// @param objectId launcher-owned object slot
    /// @param generation exact live generation
    /// @throws BridgeError if permission, ownership, generation, or cleanup validation fails
    @Override
    public void release(RuntimePayloadContext context, long objectId, long generation) throws BridgeError {
        withHandleAuthority(context, token -> handles.release(token, objectId, generation));
    }

    /// Executes one handle operation under a fresh short-lived capability token and always revokes it.
    ///
    /// @param context exact Java-owned payload context
    /// @param operation token-scoped handle operation
    /// @throws BridgeError if authority issuance or operation validation fails
    private void withHandleAuthority(RuntimePayloadContext context, HandleOperation operation) throws BridgeError {
        RuntimePayloadContext payloadContext = Objects.requireNonNull(context, "context");
        @Nullable PluginCapabilityToken token = null;
        try {
            token = Objects.requireNonNull(
                    payloadContext.capabilityTokenSupplier().get(), "capabilityTokenSupplier result");
            operation.run(token);
        } catch (BridgeError error) {
            throw error;
        } catch (RuntimeException | Error exception) {
            throw BridgeError.of(BridgeError.Category.PERMISSION_DENIED);
        } finally {
            if (token != null) {
                permissionAuthority.revoke(token);
            }
        }
    }

    /// Performs one token-scoped UI handle operation.
    @FunctionalInterface
    @NotNullByDefault
    private interface HandleOperation {
        /// Executes under one current short-lived token.
        ///
        /// @param token current payload authority
        void run(PluginCapabilityToken token);
    }

    /// Registers one sidebar action and returns its generation-safe owner handle.
    ///
    /// @param invocation verified payload invocation
    /// @param input action request map
    /// @return sidebar contribution handle
    private BridgeValue registerSidebarAction(
            BridgeServiceRegistry.Invocation invocation,
            BridgeValue input
    ) {
        BridgeValue.MapValue request = requireMap(input);
        requireExactKeys(request.values(), Set.of("title", "callback"));
        String title = requireTitle(requireString(request.values(), "title"));
        long callbackId = requireCallbackId(requireInteger(request.values(), "callback"));
        boolean newlyBound = bindOwner(invocation);

        String ownerPluginId = invocation.ownerPluginId();
        Runnable action = () -> dispatchCallback(invocation.context(), callbackId);
        @Nullable Object contribution = null;
        try {
            contribution = Objects.requireNonNull(
                    backend.registerSidebarAction(ownerPluginId, title, action), "sidebar contribution");
            Object registeredContribution = contribution;
            BridgeHandle handle = handles.register(
                    ownerPluginId,
                    SIDEBAR_HANDLE_TYPE,
                    registeredContribution,
                    () -> backend.unregister(registeredContribution)
            );
            return BridgeValue.handle(handle);
        } catch (RuntimeException | Error exception) {
            if (contribution != null) {
                backend.unregister(contribution);
            }
            rollbackOwnerBinding(ownerPluginId, invocation.context().artifactIdentity(), newlyBound);
            throw exception;
        }
    }

    /// Registers one declarative page and returns its page plus named-node handles.
    ///
    /// @param invocation verified payload invocation
    /// @param input page request map
    /// @return map containing `page` and `nodes`
    private BridgeValue registerPage(BridgeServiceRegistry.Invocation invocation, BridgeValue input) {
        BridgeValue.MapValue request = requireMap(input);
        requireExactKeys(request.values(), Set.of("title", "root"));
        String title = requireTitle(requireString(request.values(), "title"));
        Map<String, DeclarativeNode> namedNodes = new LinkedHashMap<>();
        DeclarativeNode root = parseNode(
                invocation.context(),
                requireMap(requireValue(request.values(), "root")),
                namedNodes,
                new ParseBudget(BridgeValue.MAX_CONTAINER_ENTRIES)
        );
        boolean newlyBound = bindOwner(invocation);

        String ownerPluginId = invocation.ownerPluginId();
        DeclarativePage page = new DeclarativePage(root, backend);
        try {
            Object contribution = Objects.requireNonNull(
                    backend.registerSidebarPage(ownerPluginId, title, page::materialize), "page contribution");
            page.attachContribution(contribution);
            BridgeHandle pageHandle = handles.register(ownerPluginId, PAGE_HANDLE_TYPE, page, page::release);

            Map<String, BridgeValue> nodeHandles = new LinkedHashMap<>();
            namedNodes.forEach((nodeId, node) -> nodeHandles.put(
                    nodeId,
                    BridgeValue.handle(handles.register(ownerPluginId, NODE_HANDLE_TYPE, node))
            ));

            Map<String, BridgeValue> result = new LinkedHashMap<>();
            result.put("page", BridgeValue.handle(pageHandle));
            result.put("nodes", BridgeValue.map(nodeHandles));
            return BridgeValue.map(result);
        } catch (RuntimeException | Error exception) {
            page.release();
            rollbackOwnerBinding(ownerPluginId, invocation.context().artifactIdentity(), newlyBound);
            throw exception;
        }
    }

    /// Mutates one supported property on an owned logical node and its materialized JavaFX node when present.
    ///
    /// @param invocation verified payload invocation
    /// @param input property request map
    /// @return Bridge null
    private BridgeValue setProperty(BridgeServiceRegistry.Invocation invocation, BridgeValue input) {
        BridgeValue.MapValue request = requireMap(input);
        requireExactKeys(request.values(), Set.of("node", "property", "value"));
        BridgeHandle handle = requireHandle(request.values(), "node");
        String property = requireString(request.values(), "property");
        BridgeValue value = requireValue(request.values(), "value");
        DeclarativeNode node = (DeclarativeNode) handles.resolve(invocation.token(), handle, NODE_HANDLE_TYPE);
        node.setProperty(property, value);
        return BridgeValue.nullValue();
    }

    /// Materializes and navigates to one owned declarative page.
    ///
    /// @param invocation verified payload invocation
    /// @param input page handle
    /// @return Bridge null
    private BridgeValue navigate(BridgeServiceRegistry.Invocation invocation, BridgeValue input) {
        BridgeHandle handle = ((BridgeValue.HandleValue) input).value();
        DeclarativePage page = (DeclarativePage) handles.resolve(invocation.token(), handle, PAGE_HANDLE_TYPE);
        backend.navigate(page.materialize());
        return BridgeValue.nullValue();
    }

    /// Cancels callbacks and removes every handle, contribution, page, and logical node owned by the caller.
    ///
    /// @param invocation verified payload invocation
    /// @param input validated null input
    /// @return Bridge null
    private BridgeValue unregisterOwner(BridgeServiceRegistry.Invocation invocation, BridgeValue input) {
        closeOwner(invocation.ownerPluginId());
        return BridgeValue.nullValue();
    }

    /// Cancels callbacks and removes all Bridge handles and UI contributions for one plugin lifecycle owner.
    ///
    /// This launcher-side path is idempotent and does not require a payload token because PluginManager supplies the
    /// already authenticated lifecycle owner rather than accepting an external Runtime request. Every cleanup stage is
    /// attempted and failures are logged so lifecycle state persistence and class-loader teardown can continue.
    ///
    /// @param ownerPluginId canonical plugin lifecycle owner
    public void closeOwner(String ownerPluginId) {
        synchronized (this) {
            artifactIdentities.remove(ownerPluginId);
            executionModes.remove(ownerPluginId);
        }
        try {
            callbackDispatcher.cancelOwner(ownerPluginId);
        } catch (RuntimeException | Error exception) {
            LOG.warning("Failed to cancel Runtime UI callbacks for " + ownerPluginId, exception);
        }
        try {
            handles.revokeOwner(ownerPluginId);
        } catch (RuntimeException | Error exception) {
            LOG.warning("Failed to release Runtime UI handles for " + ownerPluginId, exception);
        }
        try {
            backend.unregisterOwner(ownerPluginId);
        } catch (RuntimeException | Error exception) {
            LOG.warning("Failed to unregister Runtime UI contributions for " + ownerPluginId, exception);
        }
    }

    /// Binds one owner to the exact artifact and mode used by subsequent handle verification.
    ///
    /// @param invocation verified payload invocation
    /// @return whether this call created the owner binding
    private synchronized boolean bindOwner(BridgeServiceRegistry.Invocation invocation) {
        String ownerPluginId = invocation.ownerPluginId();
        PluginArtifactIdentity artifactIdentity = invocation.context().artifactIdentity();
        PluginExecutionMode executionMode = invocation.context().executionMode();
        @Nullable PluginArtifactIdentity existingIdentity = artifactIdentities.get(ownerPluginId);
        @Nullable PluginExecutionMode existingMode = executionModes.get(ownerPluginId);
        if (existingIdentity != null && !existingIdentity.equals(artifactIdentity)
                || existingMode != null && existingMode != executionMode) {
            throw BridgeError.of(BridgeError.Category.PERMISSION_DENIED);
        }
        artifactIdentities.put(ownerPluginId, artifactIdentity);
        executionModes.put(ownerPluginId, executionMode);
        return existingIdentity == null && existingMode == null;
    }

    /// Removes a newly created owner binding when registration failed before publishing any handle.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @param artifactIdentity exact artifact selected by the failed registration
    /// @param newlyBound whether this registration created the owner binding
    private void rollbackOwnerBinding(
            String ownerPluginId,
            PluginArtifactIdentity artifactIdentity,
            boolean newlyBound
    ) {
        if (!newlyBound || handles.liveCount(ownerPluginId) != 0) {
            return;
        }
        synchronized (this) {
            if (artifactIdentity.equals(artifactIdentities.get(ownerPluginId))) {
                artifactIdentities.remove(ownerPluginId);
                executionModes.remove(ownerPluginId);
            }
        }
    }

    /// Resolves the exact currently bound artifact for one handle owner.
    ///
    /// @param ownerPluginId expected handle owner
    /// @return bound artifact, or `null` when the owner has no live UI state
    private synchronized @Nullable PluginArtifactIdentity artifactIdentityForOwner(String ownerPluginId) {
        return artifactIdentities.get(ownerPluginId);
    }

    /// Resolves the exact currently bound execution mode for one handle owner.
    ///
    /// @param ownerPluginId expected handle owner
    /// @return bound execution mode, or `null` when the owner has no live UI state
    private synchronized @Nullable PluginExecutionMode executionModeForOwner(String ownerPluginId) {
        return executionModes.get(ownerPluginId);
    }

    /// Revalidates current UI authority and atomically admits one external callback against owner cleanup.
    ///
    /// @param context exact payload context captured by the contribution
    /// @param callbackId payload-local callback ID
    private synchronized void dispatchCallback(
            RuntimePayloadContext context,
            long callbackId
    ) {
        String ownerPluginId = context.artifactIdentity().getPluginId();
        if (!context.artifactIdentity().equals(artifactIdentities.get(ownerPluginId))) {
            return;
        }

        @Nullable PluginCapabilityToken token = null;
        try {
            token = Objects.requireNonNull(
                    context.capabilityTokenSupplier().get(), "capabilityTokenSupplier result");
            permissionAuthority.requirePermission(
                    token,
                    ownerPluginId,
                    context.artifactIdentity(),
                    context.executionMode(),
                    PluginPermission.LAUNCHER_UI,
                    BridgeServiceRegistry.CALLBACK_DOMAIN
            );
            callbackDispatcher.dispatch(
                    ownerPluginId,
                    "ui.callback",
                    cancellation -> callbackInvoker.invoke(
                            ownerPluginId,
                            callbackId,
                            BridgeValue.map(Map.of("event", BridgeValue.string("action"))),
                            cancellation
                    )
            );
        } catch (SecurityException | IllegalStateException | NullPointerException exception) {
            // Permission rotation, suspension, and unload close event admission without surfacing secrets to JavaFX.
        } finally {
            if (token != null) {
                permissionAuthority.revoke(token);
            }
        }
    }

    /// Parses and validates one declarative node tree while collecting unique named nodes.
    ///
    /// @param context exact page payload context
    /// @param value node request map
    /// @param namedNodes mutable unique node index
    /// @param budget remaining page-local node budget
    /// @return validated logical node
    private DeclarativeNode parseNode(
            RuntimePayloadContext context,
            BridgeValue.MapValue value,
            Map<String, DeclarativeNode> namedNodes,
            ParseBudget budget
    ) {
        budget.consume();
        requireAllowedKeys(value.values(), Set.of("type", "id", "properties", "events", "children"));
        String type = requireString(value.values(), "type");
        @Nullable String nodeId = optionalString(value.values(), "id");
        if (nodeId != null && !NODE_ID_PATTERN.matcher(nodeId).matches()) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        Map<String, BridgeValue> properties = optionalMap(value.values(), "properties");
        Map<String, BridgeValue> events = optionalMap(value.values(), "events");
        @Nullable Long actionCallback = parseActionEvent(type, events);
        List<DeclarativeNode> children = new ArrayList<>();
        for (BridgeValue child : optionalArray(value.values(), "children")) {
            children.add(parseNode(context, requireMap(child), namedNodes, budget));
        }

        DeclarativeNode node = new DeclarativeNode(
                type,
                properties,
                actionCallback,
                children,
                callbackId -> dispatchCallback(context, callbackId)
        );
        if (nodeId != null && namedNodes.putIfAbsent(nodeId, node) != null) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return node;
    }

    /// Parses an optional action callback and validates that its node type can emit actions.
    ///
    /// @param type declarative node type
    /// @param events event request map
    /// @return action callback ID, or `null` when absent
    private static @Nullable Long parseActionEvent(String type, Map<String, BridgeValue> events) {
        requireAllowedKeys(events, Set.of("action"));
        @Nullable BridgeValue action = events.get("action");
        if (action == null) {
            return null;
        }
        if (!Set.of("button", "text-field", "check-box").contains(type)) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        if (!(action instanceof BridgeValue.IntegerValue integer)) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return requireCallbackId(integer.value());
    }

    /// Requires one exact non-blank bounded title.
    ///
    /// @param title candidate title
    /// @return validated title
    private static String requireTitle(String title) {
        if (title.isBlank() || title.length() > MAX_TITLE_LENGTH || !title.equals(title.trim())) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return title;
    }

    /// Requires one positive callback ID.
    ///
    /// @param callbackId candidate callback ID
    /// @return validated callback ID
    private static long requireCallbackId(long callbackId) {
        if (callbackId <= 0L) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return callbackId;
    }

    /// Requires one map root.
    ///
    /// @param value candidate value
    /// @return map value
    private static BridgeValue.MapValue requireMap(BridgeValue value) {
        if (!(value instanceof BridgeValue.MapValue map)) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return map;
    }

    /// Requires one present map entry.
    ///
    /// @param values source map
    /// @param key required key
    /// @return present value
    private static BridgeValue requireValue(Map<String, BridgeValue> values, String key) {
        @Nullable BridgeValue value = values.get(key);
        if (value == null) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return value;
    }

    /// Requires one string map entry.
    ///
    /// @param values source map
    /// @param key required key
    /// @return string content
    private static String requireString(Map<String, BridgeValue> values, String key) {
        BridgeValue value = requireValue(values, key);
        if (!(value instanceof BridgeValue.StringValue string)) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return string.value();
    }

    /// Returns one optional string map entry.
    ///
    /// @param values source map
    /// @param key optional key
    /// @return string content, or `null` when absent
    private static @Nullable String optionalString(Map<String, BridgeValue> values, String key) {
        @Nullable BridgeValue value = values.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof BridgeValue.StringValue string)) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return string.value();
    }

    /// Requires one integer map entry.
    ///
    /// @param values source map
    /// @param key required key
    /// @return integer content
    private static long requireInteger(Map<String, BridgeValue> values, String key) {
        BridgeValue value = requireValue(values, key);
        if (!(value instanceof BridgeValue.IntegerValue integer)) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return integer.value();
    }

    /// Requires one opaque handle map entry.
    ///
    /// @param values source map
    /// @param key required key
    /// @return handle content
    private static BridgeHandle requireHandle(Map<String, BridgeValue> values, String key) {
        BridgeValue value = requireValue(values, key);
        if (!(value instanceof BridgeValue.HandleValue handle)) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return handle.value();
    }

    /// Returns one optional map entry or an empty map.
    ///
    /// @param values source map
    /// @param key optional key
    /// @return immutable map content
    private static @Unmodifiable Map<String, BridgeValue> optionalMap(
            Map<String, BridgeValue> values,
            String key
    ) {
        @Nullable BridgeValue value = values.get(key);
        if (value == null) {
            return Map.of();
        }
        return requireMap(value).values();
    }

    /// Returns one optional array entry or an empty list.
    ///
    /// @param values source map
    /// @param key optional key
    /// @return immutable array content
    private static @Unmodifiable List<BridgeValue> optionalArray(
            Map<String, BridgeValue> values,
            String key
    ) {
        @Nullable BridgeValue value = values.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof BridgeValue.ArrayValue array)) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
        return array.values();
    }

    /// Requires a map to contain exactly the supplied keys.
    ///
    /// @param values source map
    /// @param expected exact key set
    private static void requireExactKeys(Map<String, BridgeValue> values, Set<String> expected) {
        if (!values.keySet().equals(expected)) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
    }

    /// Requires a map to contain no keys outside the supplied set.
    ///
    /// @param values source map
    /// @param allowed allowed key set
    private static void requireAllowedKeys(Map<String, BridgeValue> values, Set<String> allowed) {
        if (!allowed.containsAll(values.keySet())) {
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }
    }

    /// Bounds declarative page complexity independently of the more general Bridge tree limit.
    @NotNullByDefault
    private static final class ParseBudget {
        /// Remaining logical nodes accepted by the current page parse.
        private int remaining;

        /// Creates one positive page-local node budget.
        ///
        /// @param maximumNodes maximum logical nodes
        private ParseBudget(int maximumNodes) {
            if (maximumNodes <= 0) {
                throw new IllegalArgumentException("Declarative node budget must be positive");
            }
            remaining = maximumNodes;
        }

        /// Consumes one logical node or rejects a page that exceeds its complexity bound.
        private void consume() {
            if (remaining == 0) {
                throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            }
            remaining--;
        }
    }

    /// Invokes one external Runtime callback for a JavaFX event.
    @FunctionalInterface
    @NotNullByDefault
    public interface CallbackInvoker {
        /// Invokes one callback through the owning Runtime transport.
        ///
        /// @param ownerPluginId canonical payload owner
        /// @param callbackId payload-local callback ID
        /// @param event immutable declarative event value
        /// @param cancellation cooperative unload cancellation signal
        /// @return callback result, ignored by action events
        /// @throws Exception if transport or callback execution fails
        BridgeValue invoke(
                String ownerPluginId,
                long callbackId,
                BridgeValue event,
                BridgeDispatcher.Cancellation cancellation
        ) throws Exception;
    }

    /// Adapts Bridge-owned contributions to the launcher's concrete UI registry and navigation controller.
    @NotNullByDefault
    public interface Backend {
        /// Registers one sidebar action.
        ///
        /// @param ownerPluginId canonical owner plugin ID
        /// @param title displayed title
        /// @param action selection action
        /// @return exact backend contribution token
        Object registerSidebarAction(String ownerPluginId, String title, Runnable action);

        /// Registers one lazy sidebar page.
        ///
        /// @param ownerPluginId canonical owner plugin ID
        /// @param title displayed title
        /// @param pageSupplier lazy JavaFX page supplier
        /// @return exact backend contribution token
        Object registerSidebarPage(String ownerPluginId, String title, Supplier<? extends Node> pageSupplier);

        /// Removes one exact backend contribution.
        ///
        /// @param contribution exact token returned by a registration method
        void unregister(Object contribution);

        /// Removes every contribution owned by one plugin after handle cleanup.
        ///
        /// @param ownerPluginId canonical owner plugin ID
        void unregisterOwner(String ownerPluginId);

        /// Navigates to one materialized declarative page.
        ///
        /// @param page JavaFX page root
        void navigate(Node page);
    }

    /// Production backend using the process-wide plugin UI registry and main-window controller.
    @NotNullByDefault
    private static final class LauncherBackend implements Backend {
        /// Registers one launcher sidebar action.
        @Override
        public Object registerSidebarAction(String ownerPluginId, String title, Runnable action) {
            return PluginUIRegistry.registerSidebarItem(ownerPluginId, title, action);
        }

        /// Registers one launcher sidebar page.
        @Override
        public Object registerSidebarPage(
                String ownerPluginId,
                String title,
                Supplier<? extends Node> pageSupplier
        ) {
            return PluginUIRegistry.registerSidebarPage(ownerPluginId, title, pageSupplier);
        }

        /// Removes one exact launcher sidebar contribution.
        @Override
        public void unregister(Object contribution) {
            PluginUIRegistry.unregisterSidebarItem((PluginUIRegistry.SidebarItem) contribution);
        }

        /// Removes every launcher sidebar contribution owned by one plugin.
        @Override
        public void unregisterOwner(String ownerPluginId) {
            PluginUIRegistry.unregisterAll(ownerPluginId);
        }

        /// Navigates the main window to one declarative page.
        @Override
        public void navigate(Node page) {
            Controllers.navigate(page);
        }
    }

    /// Owns one lazy declarative page and its exact backend contribution.
    @NotNullByDefault
    private static final class DeclarativePage {
        /// Logical root retained before and after JavaFX materialization.
        private final DeclarativeNode root;

        /// Backend which owns this page's sidebar contribution.
        private final Backend backend;

        /// Exact backend contribution, or `null` before successful attachment and after release.
        private @Nullable Object contribution;

        /// Creates one unattached logical page.
        ///
        /// @param root logical page root
        /// @param backend owning UI backend
        private DeclarativePage(DeclarativeNode root, Backend backend) {
            this.root = root;
            this.backend = backend;
        }

        /// Attaches the exact contribution returned by backend registration.
        ///
        /// @param contribution exact backend contribution
        private void attachContribution(Object contribution) {
            if (this.contribution != null) {
                throw new IllegalStateException("Declarative page contribution is already attached");
            }
            this.contribution = Objects.requireNonNull(contribution, "contribution");
        }

        /// Materializes or returns the cached JavaFX page root.
        ///
        /// @return JavaFX page root
        private Node materialize() {
            if (!Platform.isFxApplicationThread()) {
                throw new IllegalStateException("Declarative pages must materialize on JavaFX");
            }
            return root.materialize();
        }

        /// Removes the exact backend contribution once.
        private void release() {
            @Nullable Object attached = contribution;
            contribution = null;
            if (attached != null) {
                backend.unregister(attached);
            }
        }
    }

    /// Represents one mutable logical node whose JavaFX object is created only when displayed.
    @NotNullByDefault
    private static final class DeclarativeNode {
        /// Stable declarative node type.
        private final String type;

        /// Current validated property values.
        private final Map<String, BridgeValue> properties = new LinkedHashMap<>();

        /// Optional action callback ID.
        private final @Nullable Long actionCallback;

        /// Immutable logical child list.
        private final @Unmodifiable List<DeclarativeNode> children;

        /// Dispatches action callback IDs without exposing JavaFX objects.
        private final java.util.function.LongConsumer actionDispatcher;

        /// Cached JavaFX object, or `null` before first display.
        private @Nullable Node materialized;

        /// Creates one validated logical declarative node.
        ///
        /// @param type stable declarative node type
        /// @param properties initial property snapshot
        /// @param actionCallback optional action callback ID
        /// @param children logical child nodes
        /// @param actionDispatcher external callback dispatcher
        private DeclarativeNode(
                String type,
                Map<String, BridgeValue> properties,
                @Nullable Long actionCallback,
                List<DeclarativeNode> children,
                java.util.function.LongConsumer actionDispatcher
        ) {
            this.type = requireNodeType(type);
            this.actionCallback = actionCallback;
            this.children = List.copyOf(children);
            this.actionDispatcher = actionDispatcher;
            validateChildren();
            properties.forEach(this::setProperty);
        }

        /// Validates and updates one property, applying it immediately when already materialized.
        ///
        /// @param property stable property name
        /// @param value closed Bridge property value
        private void setProperty(String property, BridgeValue value) {
            requireProperty(property, value);
            properties.put(property, value);
            @Nullable Node node = materialized;
            if (node != null) {
                applyProperty(node, property, value);
            }
        }

        /// Materializes this node tree once and returns the cached JavaFX object.
        ///
        /// @return JavaFX node
        private Node materialize() {
            @Nullable Node current = materialized;
            if (current != null) {
                return current;
            }
            Node created = createNode();
            attachChildren(created);
            attachAction(created);
            properties.forEach((property, value) -> applyProperty(created, property, value));
            materialized = created;
            return created;
        }

        /// Creates the concrete JavaFX object for this logical node type.
        ///
        /// @return unconfigured JavaFX node
        private Node createNode() {
            return switch (type) {
                case "vbox" -> new VBox();
                case "hbox" -> new HBox();
                case "stack-pane" -> new StackPane();
                case "label" -> new Label();
                case "button" -> new Button();
                case "text-field" -> new TextField();
                case "check-box" -> new CheckBox();
                case "progress-bar" -> new ProgressBar();
                case "separator" -> new Separator();
                case "scroll-pane" -> new ScrollPane();
                default -> throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            };
        }

        /// Attaches logical children according to this node's container contract.
        ///
        /// @param node newly created JavaFX node
        private void attachChildren(Node node) {
            if (node instanceof Pane pane) {
                pane.getChildren().setAll(children.stream().map(DeclarativeNode::materialize).toList());
            } else if (node instanceof ScrollPane scrollPane && !children.isEmpty()) {
                scrollPane.setContent(children.get(0).materialize());
            }
        }

        /// Attaches the optional action event without exposing the JavaFX event object.
        ///
        /// @param node newly created JavaFX node
        private void attachAction(Node node) {
            @Nullable Long callbackId = actionCallback;
            if (callbackId == null) {
                return;
            }
            if (node instanceof ButtonBase button) {
                button.setOnAction(event -> actionDispatcher.accept(callbackId));
            } else if (node instanceof TextField textField) {
                textField.setOnAction(event -> actionDispatcher.accept(callbackId));
            }
        }

        /// Validates the child count and container capability of this node.
        private void validateChildren() {
            if (Set.of("vbox", "hbox", "stack-pane").contains(type)) {
                return;
            }
            if ("scroll-pane".equals(type) && children.size() <= 1) {
                return;
            }
            if (!children.isEmpty()) {
                throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            }
        }

        /// Applies one already validated property to a JavaFX node.
        ///
        /// @param node target JavaFX node
        /// @param property stable property name
        /// @param value property value
        private static void applyProperty(Node node, String property, BridgeValue value) {
            switch (property) {
                case "visible" -> node.setVisible(booleanValue(value));
                case "disabled" -> node.setDisable(booleanValue(value));
                case "managed" -> node.setManaged(booleanValue(value));
                case "opacity" -> node.setOpacity(numberValue(value));
                case "style" -> node.setStyle(stringValue(value));
                case "style-classes" -> node.getStyleClass().setAll(stringArray(value));
                case "text" -> setText(node, stringValue(value));
                case "prompt-text" -> ((TextInputControl) node).setPromptText(stringValue(value));
                case "selected" -> ((CheckBox) node).setSelected(booleanValue(value));
                case "progress" -> ((ProgressBar) node).setProgress(numberValue(value));
                case "spacing" -> setSpacing(node, numberValue(value));
                case "alignment" -> setAlignment(node, Pos.valueOf(
                        stringValue(value).toUpperCase(Locale.ROOT).replace('-', '_')));
                case "pref-width" -> ((Region) node).setPrefWidth(numberValue(value));
                case "pref-height" -> ((Region) node).setPrefHeight(numberValue(value));
                default -> throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            }
        }

        /// Validates one property against this logical node type without mutating state.
        ///
        /// @param property stable property name
        /// @param value candidate property value
        private void requireProperty(String property, BridgeValue value) {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(value, "value");
            switch (property) {
                case "visible", "disabled", "managed" -> booleanValue(value);
                case "opacity" -> requireRange(numberValue(value), 0.0, 1.0);
                case "style" -> stringValue(value);
                case "style-classes" -> stringArray(value);
                case "text" -> {
                    if (!Set.of("label", "button", "text-field", "check-box").contains(type)) {
                        throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
                    }
                    stringValue(value);
                }
                case "prompt-text" -> {
                    requireType("text-field");
                    stringValue(value);
                }
                case "selected" -> {
                    requireType("check-box");
                    booleanValue(value);
                }
                case "progress" -> {
                    requireType("progress-bar");
                    requireRange(numberValue(value), -1.0, 1.0);
                }
                case "spacing" -> {
                    requireOneOfTypes(Set.of("vbox", "hbox"));
                    requireMinimum(numberValue(value), 0.0);
                }
                case "alignment" -> {
                    requireOneOfTypes(Set.of("vbox", "hbox", "stack-pane"));
                    try {
                        Pos.valueOf(stringValue(value).toUpperCase(Locale.ROOT).replace('-', '_'));
                    } catch (IllegalArgumentException exception) {
                        throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
                    }
                }
                case "pref-width", "pref-height" -> {
                    requireMinimum(numberValue(value), -1.0);
                }
                default -> throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            }
        }

        /// Requires this node to have one exact declarative type.
        ///
        /// @param expected expected node type
        private void requireType(String expected) {
            if (!type.equals(expected)) {
                throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            }
        }

        /// Requires this node type to belong to one allowed set.
        ///
        /// @param allowed allowed node types
        private void requireOneOfTypes(Set<String> allowed) {
            if (!allowed.contains(type)) {
                throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            }
        }

        /// Requires one supported declarative node type.
        ///
        /// @param type candidate node type
        /// @return validated node type
        private static String requireNodeType(String type) {
            if (!Set.of(
                    "vbox", "hbox", "stack-pane", "label", "button", "text-field",
                    "check-box", "progress-bar", "separator", "scroll-pane"
            ).contains(type)) {
                throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            }
            return type;
        }

        /// Assigns text to either a Labeled or text-input node.
        ///
        /// @param node target JavaFX node
        /// @param text new text
        private static void setText(Node node, String text) {
            if (node instanceof Labeled labeled) {
                labeled.setText(text);
            } else {
                ((TextInputControl) node).setText(text);
            }
        }

        /// Assigns spacing to either a vertical or horizontal box.
        ///
        /// @param node target JavaFX box
        /// @param spacing non-negative spacing
        private static void setSpacing(Node node, double spacing) {
            if (node instanceof VBox vbox) {
                vbox.setSpacing(spacing);
            } else {
                ((HBox) node).setSpacing(spacing);
            }
        }

        /// Assigns alignment to one supported layout pane.
        ///
        /// @param node target layout pane
        /// @param alignment JavaFX alignment
        private static void setAlignment(Node node, Pos alignment) {
            if (node instanceof VBox vbox) {
                vbox.setAlignment(alignment);
            } else if (node instanceof HBox hbox) {
                hbox.setAlignment(alignment);
            } else {
                ((StackPane) node).setAlignment(alignment);
            }
        }

        /// Extracts one boolean property value.
        ///
        /// @param value candidate value
        /// @return boolean content
        private static boolean booleanValue(BridgeValue value) {
            if (!(value instanceof BridgeValue.BooleanValue bool)) {
                throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            }
            return bool.value();
        }

        /// Extracts one integer or finite floating-point property value.
        ///
        /// @param value candidate value
        /// @return numeric content
        private static double numberValue(BridgeValue value) {
            if (value instanceof BridgeValue.IntegerValue integer) {
                return integer.value();
            }
            if (value instanceof BridgeValue.FloatValue floating) {
                return floating.value();
            }
            throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
        }

        /// Extracts one string property value.
        ///
        /// @param value candidate value
        /// @return string content
        private static String stringValue(BridgeValue value) {
            if (!(value instanceof BridgeValue.StringValue string)) {
                throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            }
            return string.value();
        }

        /// Extracts one immutable array of string property values.
        ///
        /// @param value candidate value
        /// @return immutable strings
        private static @Unmodifiable List<String> stringArray(BridgeValue value) {
            if (!(value instanceof BridgeValue.ArrayValue array)) {
                throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            }
            return array.values().stream().map(DeclarativeNode::stringValue).toList();
        }

        /// Requires one number to remain inside an inclusive range.
        ///
        /// @param value candidate number
        /// @param minimum inclusive minimum
        /// @param maximum inclusive maximum
        private static void requireRange(double value, double minimum, double maximum) {
            if (value < minimum || value > maximum) {
                throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            }
        }

        /// Requires one number to meet an inclusive lower bound.
        ///
        /// @param value candidate number
        /// @param minimum inclusive minimum
        private static void requireMinimum(double value, double minimum) {
            if (value < minimum) {
                throw BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);
            }
        }
    }
}
