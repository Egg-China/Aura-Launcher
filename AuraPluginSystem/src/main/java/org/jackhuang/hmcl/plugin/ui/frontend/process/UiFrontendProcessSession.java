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
package org.jackhuang.hmcl.plugin.ui.frontend.process;

import org.jackhuang.hmcl.plugin.bridge.BridgeValue;
import org.jackhuang.hmcl.plugin.ui.frontend.UiFrontendCoordinator;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/// Supervises one strict, bounded, token-free `aura.ui.v1` native frontend session.
///
/// Incoming and outgoing requests share exactly 32 active slots and one 128-entry FIFO waiting queue. A request
/// remains active through its matching response (and, for child commands, through response flush). Queue time is
/// included in the ordinary ten-second deadline. The session authenticates no package or permission state; callers
/// must supply only already inventoried paths and a handler that performs launcher-owned checks per command.
@NotNullByDefault
public final class UiFrontendProcessSession implements AutoCloseable, UiFrontendCoordinator.SupervisedSession {
    /// Maximum number of active incoming and outgoing requests combined.
    private static final int MAX_INFLIGHT = 32;

    /// Maximum number of waiting incoming and outgoing requests combined.
    private static final int MAX_QUEUED = 128;

    /// Maximum retained child stderr tail size.
    private static final int STDERR_TAIL_BYTES = 64 * 1024;

    /// Fixed JVM-to-child request methods available after readiness.
    private static final @Unmodifiable Set<String> OUTGOING_METHODS = Set.of(
            "ui.snapshot.replace", "ui.navigate", "ui.notify", "ui.shutdown");

    /// Fixed child-to-JVM command methods delegated to the launcher handler.
    private static final @Unmodifiable Set<String> INCOMING_METHODS = Set.of(
            "core.snapshot.get", "core.instance.select", "core.instance.launch", "core.account.select",
            "core.asset.get", "core.ui.use-javafx", "core.app.shutdown");

    /// Exact ordinary inherited environment keys.
    private static final @Unmodifiable Set<String> ENVIRONMENT_KEYS = Set.of(
            "PATH", "PATHEXT", "SystemRoot", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP", "TMPDIR",
            "LANG", "DISPLAY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS");

    /// Mutable state monitor; user code is never invoked while held.
    private final Object lock = new Object();

    /// Native child process owned by this session.
    private final Process process;

    /// Serialized child stdin target.
    private final OutputStream childInput;

    /// Launcher command handler invoked off transport threads.
    private final UiFrontendCommandHandler handler;

    /// Production or test timing policy.
    private final Timing timing;

    /// Single bounded serialized protocol writer.
    private final ExecutorService writer;

    /// Bounded pool that invokes arbitrary asynchronous handlers.
    private final ExecutorService handlers;

    /// Bounded dispatcher that isolates public future continuations from transport and state monitors.
    private final ExecutorService completions;

    /// Bounded executor reserved exclusively for acknowledged launcher-owned after-response actions.
    private final ExecutorService afterResponses;

    /// Bounded transport pool for reader, stderr drainer, and process exit waiter.
    private final ExecutorService transports;

    /// Dedicated bounded cleanup executor that never waits on itself.
    private final ExecutorService cleanup;

    /// Shared FIFO queue after the 32 active request slots are occupied.
    private final ArrayDeque<Work> waiting = new ArrayDeque<>(MAX_QUEUED);

    /// Current outgoing requests keyed by their exact odd identifier.
    private final Map<Long, OutgoingWork> pending = new HashMap<>();

    /// Every active request, used for deadline checks and cancellation.
    private final List<Work> active = new ArrayList<>(MAX_INFLIGHT);

    /// Descendants observed before or during shutdown, keyed by process identifier and guarded by `lock`.
    private final Map<Long, ProcessHandle> capturedDescendants = new LinkedHashMap<>();

    /// Completion visible after process exit, streams, and owned threads have been cleaned.
    private final CompletableFuture<Termination> termination = new CompletableFuture<>();

    /// Wakes the deadline supervisor when admissions or state change.
    private final Object deadlineSignal = new Object();

    /// Records whether termination cleanup has been scheduled.
    private final AtomicBoolean terminationStarted = new AtomicBoolean();

    /// Records whether a graceful close has been initiated.
    private final AtomicBoolean closeStarted = new AtomicBoolean();

    /// Bounded child stderr tail accumulator.
    private final StderrTail stderrTail = new StderrTail();

    /// Current handshake or lifecycle state guarded by `lock`.
    private State state = State.HELLO;

    /// Next odd JVM request identifier guarded by `lock`.
    private long nextRequestId = 1L;

    /// Number of active shared request slots guarded by `lock`.
    private int activeCount;

    /// Records whether forced termination was required.
    private volatile boolean forced;

    /// First terminal failure, retained across close and startup-admission races.
    private volatile @Nullable UiFrontendProcessException terminalFailure;

    /// Creates a launched session and starts its transport supervisors.
    ///
    /// @param process owned native process
    /// @param handler launcher command handler
    /// @param timing timeout policy
    private UiFrontendProcessSession(Process process, UiFrontendCommandHandler handler, Timing timing) {
        this.process = process;
        this.childInput = process.getOutputStream();
        this.handler = handler;
        this.timing = timing;
        writer = executor("aura-ui-writer", 1, 64);
        handlers = executor("aura-ui-handler", 4, MAX_INFLIGHT);
        completions = executor("aura-ui-completion", 1, MAX_INFLIGHT + MAX_QUEUED + 4);
        afterResponses = executor("aura-ui-after-response", 4, MAX_INFLIGHT);
        transports = executor("aura-ui-transport", 3, 3);
        cleanup = executor("aura-ui-cleanup", 1, 1);
        transports.execute(this::readLoop);
        transports.execute(this::drainStderr);
        transports.execute(this::waitForExit);
        Thread deadlines = daemonThread("aura-ui-deadlines", this::deadlineLoop);
        deadlines.start();
    }

    /// Starts a production child and blocks until hello, initial snapshot, and first-ready all succeed.
    ///
    /// Process launch and all protocol IO occur on owned non-FX workers. The command is exactly the canonical
    /// executable followed by `--stdio`; no shell is involved.
    ///
    /// @param executable already inventoried native executable
    /// @param packageRoot already inventoried package root
    /// @param initialSnapshot redacted initial UI state
    /// @param handler launcher command handler
    /// @return ready supervised session
    /// @throws UiFrontendProcessException if validation, launch, handshake, or readiness fails
    public static UiFrontendProcessSession start(
            Path executable, Path packageRoot, BridgeValue initialSnapshot, UiFrontendCommandHandler handler)
            throws UiFrontendProcessException {
        return start(executable, packageRoot, initialSnapshot, handler, ProcessBuilder::start, Timing.PRODUCTION);
    }

    /// Starts a child with package-private process and timing injection for deterministic transport tests.
    ///
    /// @param executable already inventoried native executable
    /// @param packageRoot already inventoried package root
    /// @param initialSnapshot redacted initial UI state
    /// @param handler launcher command handler
    /// @param launcher process launch boundary
    /// @param timing timeout policy
    /// @return ready supervised session
    /// @throws UiFrontendProcessException if validation, launch, handshake, or readiness fails
    static UiFrontendProcessSession start(
            Path executable, Path packageRoot, BridgeValue initialSnapshot, UiFrontendCommandHandler handler,
            ProcessLauncher launcher, Timing timing) throws UiFrontendProcessException {
        return start(executable, packageRoot, initialSnapshot, handler, launcher, timing,
                System.getenv(), isWindows());
    }

    /// Starts a child with package-private environment-source injection for process-boundary tests.
    ///
    /// @param executable already inventoried native executable
    /// @param packageRoot already inventoried package root
    /// @param initialSnapshot redacted initial UI state
    /// @param handler launcher command handler
    /// @param launcher process launch boundary
    /// @param timing timeout policy
    /// @param sourceEnvironment inherited environment candidate map
    /// @param windows whether environment names use Windows case rules
    /// @return ready supervised session
    /// @throws UiFrontendProcessException if validation, launch, handshake, or readiness fails
    static UiFrontendProcessSession start(
            Path executable, Path packageRoot, BridgeValue initialSnapshot, UiFrontendCommandHandler handler,
            ProcessLauncher launcher, Timing timing, Map<String, String> sourceEnvironment, boolean windows)
            throws UiFrontendProcessException {
        Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(launcher, "launcher");
        Objects.requireNonNull(timing, "timing");
        Objects.requireNonNull(sourceEnvironment, "sourceEnvironment");
        Path canonicalExecutable = validatePaths(executable, packageRoot);
        ProcessBuilder builder = new ProcessBuilder(canonicalExecutable.toString(), "--stdio");
        builder.directory(packageRoot.toAbsolutePath().normalize().toFile());
        builder.environment().clear();
        builder.environment().putAll(filterEnvironment(sourceEnvironment, windows));

        long startupDeadline = deadline(timing.startupBudget(), timing.nanoClock());
        ExecutorService starter = executor("aura-ui-starter", 1, 1);
        CompletableFuture<Process> launchFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return launcher.start(builder);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, starter);
        Process process;
        try {
            process = Objects.requireNonNull(launchFuture.get(
                    Math.max(1L, startupDeadline - timing.nanoClock().nanoTime()), TimeUnit.NANOSECONDS),
                    "launched process");
        } catch (java.util.concurrent.TimeoutException exception) {
            launchFuture.thenAccept(UiFrontendProcessSession::destroyLateProcess);
            throw failure(UiFrontendProcessException.Category.TIMEOUT,
                    "Native UI process launch exceeded the startup deadline", exception);
        } catch (InterruptedException exception) {
            launchFuture.thenAccept(UiFrontendProcessSession::destroyLateProcess);
            Thread.currentThread().interrupt();
            throw failure(UiFrontendProcessException.Category.STARTUP,
                    "Native UI process launch was interrupted", exception);
        } catch (java.util.concurrent.ExecutionException | NullPointerException exception) {
            launchFuture.thenAccept(UiFrontendProcessSession::destroyLateProcess);
            throw failure(UiFrontendProcessException.Category.STARTUP,
                    "Native UI process could not be started", unwrap(exception));
        } finally {
            starter.shutdownNow();
        }

        UiFrontendProcessSession session = new UiFrontendProcessSession(process, handler, timing);
        try {
            BridgeValue hello = awaitUntil(
                    session.handshakeRequest("ui.hello", helloValue(), State.SNAPSHOT), startupDeadline,
                    timing.nanoClock());
            if (!isValidHello(hello)) {
                throw failure(UiFrontendProcessException.Category.PROTOCOL,
                        "Native UI hello acknowledgement was invalid", null);
            }
            BridgeValue snapshot = awaitUntil(
                    session.handshakeRequest("ui.snapshot.replace", initialSnapshot, State.WAITING_READY), startupDeadline,
                    timing.nanoClock());
            if (!(snapshot instanceof BridgeValue.NullValue)) {
                throw failure(UiFrontendProcessException.Category.PROTOCOL,
                        "Native UI snapshot acknowledgement was invalid", null);
            }
            awaitUntil(session.readyFuture(), startupDeadline, timing.nanoClock());
            return session;
        } catch (UiFrontendProcessException exception) {
            session.fail(exception);
            session.awaitTerminationQuietly();
            @Nullable UiFrontendProcessException established = session.terminalFailure;
            throw established == null ? exception : established;
        }
    }

    /// Filters inherited environment candidates to documented child process keys.
    ///
    /// @param source candidate environment
    /// @param windows whether names use Windows case rules
    /// @return immutable admitted entries retaining source spelling and values
    static @Unmodifiable Map<String, String> filterEnvironment(Map<String, String> source, boolean windows) {
        Map<String, String> filtered = new LinkedHashMap<>();
        source.forEach((name, value) -> {
            if (isAllowedEnvironmentName(name, windows)) {
                filtered.put(name, value);
            }
        });
        return Map.copyOf(filtered);
    }

    /// Applies platform-correct name matching to the fixed environment allowlist.
    ///
    /// @param name candidate environment name
    /// @param windows whether names use Windows case rules
    /// @return whether the existing allowlist admits the name
    private static boolean isAllowedEnvironmentName(String name, boolean windows) {
        boolean fixed = ENVIRONMENT_KEYS.stream()
                .anyMatch(allowed -> windows ? allowed.equalsIgnoreCase(name) : allowed.equals(name));
        boolean locale = windows
                ? name.regionMatches(true, 0, "LC_", 0, 3)
                : name.startsWith("LC_");
        return fixed || locale;
    }

    /// Identifies Windows environment-name semantics.
    ///
    /// @return whether the current operating system is Windows
    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    /// Sends one supported UI request after readiness.
    ///
    /// @param method one of the four fixed `ui.*` methods
    /// @param params token-free request parameters
    /// @return future completed by exactly one matching current child response
    public CompletableFuture<BridgeValue> request(String method, BridgeValue params) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(params, "params");
        synchronized (lock) {
            if (state != State.READY || closeStarted.get() || terminationStarted.get()) {
                return failedFuture(failure(UiFrontendProcessException.Category.CLOSED,
                        "Native UI session is closed", null));
            }
        }
        if (!OUTGOING_METHODS.contains(method)) {
            return failedFuture(failure(UiFrontendProcessException.Category.PROTOCOL,
                    "Unsupported native UI request method", null));
        }
        OutgoingWork work = new OutgoingWork(method, params, deadline(timing.requestDeadline()), State.READY);
        admit(work, false);
        return work.future;
    }

    /// Returns a read-only completion view that resolves only after all owned resources are cleaned.
    ///
    /// @return terminal completion
    public CompletionStage<Termination> termination() {
        return termination.minimalCompletionStage();
    }

    /// Invalidates the session, attempts an acknowledged UI shutdown, and joins bounded cleanup.
    ///
    /// Calls from an owned reader, writer, handler, or cleanup worker initiate shutdown without synchronously waiting
    /// on that same worker.
    @Override
    public void close() {
        beginClose();
        if (!Thread.currentThread().getName().startsWith("aura-ui-")) {
            awaitTerminationQuietly();
        }
    }

    /// Initiates one idempotent graceful close sequence.
    private void beginClose() {
        if (!closeStarted.compareAndSet(false, true)) {
            return;
        }
        cancelRequests(failure(UiFrontendProcessException.Category.CLOSED, "Native UI session is closed", null));
        synchronized (lock) {
            if (state == State.TERMINATED) {
                return;
            }
            state = State.CLOSING;
        }
        if (!process.isAlive()) {
            terminate();
            return;
        }
        captureDescendants();
        OutgoingWork shutdown = new OutgoingWork(
                "ui.shutdown", BridgeValue.nullValue(), deadline(timing.requestDeadline()), State.CLOSING);
        activateDirect(shutdown);
    }

    /// Creates and admits one internal handshake request.
    ///
    /// @param method handshake method
    /// @param params handshake parameters
    /// @param nextState state installed atomically when its matching result arrives
    /// @return response future
    private CompletableFuture<BridgeValue> handshakeRequest(String method, BridgeValue params, State nextState) {
        OutgoingWork work = new OutgoingWork(method, params, deadline(timing.startupBudget()), state, nextState);
        admit(work, true);
        return work.future;
    }

    /// Returns the ready completion guarded by the startup budget.
    ///
    /// @return ready completion
    private CompletableFuture<BridgeValue> readyFuture() {
        synchronized (lock) {
            return ready;
        }
    }

    /// Exact ordered hello map required in both directions.
    ///
    /// @return canonical hello value
    private static BridgeValue helloValue() {
        Map<String, BridgeValue> values = new LinkedHashMap<>();
        values.put("protocol", BridgeValue.string(UiFrontendWireCodec.PROTOCOL));
        values.put("abi", BridgeValue.integer(1L));
        return BridgeValue.map(values);
    }

    /// Validates the exact ordered hello result independently from its request builder.
    ///
    /// @param value candidate hello acknowledgement
    /// @return whether it has exactly the required ordered fields, types, and values
    private static boolean isValidHello(BridgeValue value) {
        if (!(value instanceof BridgeValue.MapValue map) || map.values().size() != 2) {
            return false;
        }
        Iterator<Map.Entry<String, BridgeValue>> entries = map.values().entrySet().iterator();
        Map.Entry<String, BridgeValue> protocol = entries.next();
        Map.Entry<String, BridgeValue> abi = entries.next();
        return "protocol".equals(protocol.getKey())
                && protocol.getValue() instanceof BridgeValue.StringValue text
                && UiFrontendWireCodec.PROTOCOL.equals(text.value())
                && "abi".equals(abi.getKey())
                && abi.getValue() instanceof BridgeValue.IntegerValue integer
                && integer.value() == 1L;
    }

    /// Admits one request against the shared active and waiting bounds.
    ///
    /// @param work request work
    /// @param startup whether startup state rather than ready state is required
    private void admit(Work work, boolean startup) {
        boolean activate = false;
        @Nullable UiFrontendProcessException rejection = null;
        synchronized (lock) {
            boolean permitted = startup ? state == work.requiredState : state == State.READY;
            if (!permitted || terminationStarted.get() || closeStarted.get()) {
                rejection = failure(UiFrontendProcessException.Category.CLOSED,
                        "Native UI session is closed", null);
            } else if (isExpired(work)) {
                rejection = timeoutFailure();
            } else if (activeCount < MAX_INFLIGHT) {
                activeCount++;
                active.add(work);
                work.active = true;
                activate = true;
            } else if (waiting.size() < MAX_QUEUED) {
                waiting.addLast(work);
            } else {
                rejection = failure(UiFrontendProcessException.Category.OVERLOAD,
                        "Native UI request capacity is exhausted", null);
            }
        }
        if (rejection != null) {
            work.reject(rejection);
            if (rejection.category() == UiFrontendProcessException.Category.TIMEOUT) {
                fail(rejection);
            }
            return;
        }
        wakeDeadlines();
        if (activate) {
            work.activate();
        }
    }

    /// Activates a close-only request after all ordinary work has been cancelled.
    ///
    /// @param work direct shutdown request
    private void activateDirect(OutgoingWork work) {
        synchronized (lock) {
            activeCount++;
            active.add(work);
            work.active = true;
        }
        wakeDeadlines();
        work.activate();
    }

    /// Completes one active slot and activates queued FIFO work outside the monitor.
    ///
    /// @param work completed work
    private void completeWork(Work work) {
        @Nullable Work next = null;
        @Nullable UiFrontendProcessException expiration = null;
        synchronized (lock) {
            if (!work.active || work.done) {
                return;
            }
            work.done = true;
            work.active = false;
            active.remove(work);
            activeCount--;
            if (!waiting.isEmpty() && state == State.READY && !closeStarted.get()) {
                Work candidate = waiting.getFirst();
                if (isExpired(candidate)) {
                    expiration = timeoutFailure();
                } else {
                    next = waiting.removeFirst();
                    next.active = true;
                    active.add(next);
                    activeCount++;
                }
            }
        }
        wakeDeadlines();
        if (expiration != null) {
            fail(expiration);
            return;
        }
        if (next != null) {
            next.activate();
        }
    }

    /// Activates one outgoing request and preserves its pending slot through the matching response.
    ///
    /// @param work outgoing request
    private void activateOutgoing(OutgoingWork work) {
        long requestId = 0L;
        @Nullable UiFrontendProcessException activationFailure = null;
        synchronized (lock) {
            if (work.done || !work.active || terminationStarted.get()) {
                return;
            }
            if (isExpired(work)) {
                activationFailure = timeoutFailure();
            } else if (nextRequestId <= 0L || nextRequestId > Long.MAX_VALUE - 2L) {
                activationFailure = failure(UiFrontendProcessException.Category.PROTOCOL,
                        "Native UI request identifiers are exhausted", null);
            } else {
                requestId = nextRequestId;
                nextRequestId += 2L;
                work.requestId = requestId;
                pending.put(requestId, work);
            }
        }
        if (activationFailure != null) {
            fail(activationFailure);
            return;
        }
        write(new UiFrontendMessage.Request(requestId, work.method, work.params), work, null,
                () -> { }, exception -> fail(transportFailure(exception)));
    }

    /// Continuously decodes child frames until EOF or terminal failure.
    private void readLoop() {
        try {
            while (!terminationStarted.get()) {
                @Nullable UiFrontendMessage message = UiFrontendWireCodec.read(
                        process.getInputStream(), UiFrontendWireCodec.InboundEndpoint.LAUNCHER);
                if (message == null) {
                    if (!closeStarted.get()) {
                        fail(failure(UiFrontendProcessException.Category.TRANSPORT,
                                "Native UI stdout ended unexpectedly", null));
                    }
                    return;
                }
                if (message instanceof UiFrontendMessage.Request request) {
                    receiveRequest(request);
                } else {
                    receiveResponse(message);
                }
            }
        } catch (IOException exception) {
            if (!terminationStarted.get()) {
                fail(failure(UiFrontendProcessException.Category.PROTOCOL,
                        "Native UI emitted an invalid protocol frame", exception));
            }
        } catch (RuntimeException exception) {
            fail(failure(UiFrontendProcessException.Category.PROTOCOL,
                    "Native UI protocol processing failed", exception));
        }
    }

    /// Processes one child request without invoking its handler on the reader.
    ///
    /// @param request child request
    private void receiveRequest(UiFrontendMessage.Request request) {
        State current;
        boolean acknowledgeReady = false;
        @Nullable UiFrontendProcessException protocolFailure = null;
        synchronized (lock) {
            current = state;
            if ("ui.ready".equals(request.method())) {
                if (current != State.WAITING_READY || request.requestId() <= 0L || readySeen) {
                    protocolFailure = failure(UiFrontendProcessException.Category.PROTOCOL,
                            "Native UI ready request was premature or duplicated", null);
                } else if (!(request.params() instanceof BridgeValue.NullValue)) {
                    protocolFailure = failure(UiFrontendProcessException.Category.PROTOCOL,
                            "Native UI ready parameters were invalid", null);
                } else {
                    readySeen = true;
                    acknowledgeReady = true;
                }
            }
        }
        if (protocolFailure != null) {
            fail(protocolFailure);
            return;
        }
        if (acknowledgeReady) {
            write(new UiFrontendMessage.Result(request.requestId(), BridgeValue.nullValue()), null,
                    () -> {
                        synchronized (lock) {
                            state = State.READY;
                        }
                        completeFuture(ready, BridgeValue.nullValue());
                    }, exception -> fail(transportFailure(exception)));
            return;
        }
        if (current != State.READY || !INCOMING_METHODS.contains(request.method())) {
            fail(failure(UiFrontendProcessException.Category.PROTOCOL,
                    "Native UI invoked an unsupported or premature command", null));
            return;
        }
        IncomingWork work = new IncomingWork(request, deadline(timing.requestDeadline()));
        admit(work, false);
        if (work.rejection != null) {
            write(new UiFrontendMessage.Error(request.requestId(), "overloaded", "Launcher request capacity exhausted"),
                    null, () -> { }, exception -> fail(transportFailure(exception)));
        }
    }

    /// Matches one result or error to a currently pending outgoing request.
    ///
    /// @param response child response
    private void receiveResponse(UiFrontendMessage response) {
        @Nullable OutgoingWork work = null;
        @Nullable UiFrontendProcessException protocolFailure = null;
        synchronized (lock) {
            @Nullable OutgoingWork matched = pending.remove(response.requestId());
            if (matched == null || matched.done) {
                protocolFailure = failure(UiFrontendProcessException.Category.PROTOCOL,
                        "Native UI response did not match a current request", null);
            } else {
                work = matched;
                if (response instanceof UiFrontendMessage.Result && work.nextState != null) {
                    state = work.nextState;
                }
            }
        }
        if (protocolFailure != null) {
            fail(protocolFailure);
            return;
        }
        if (work == null) {
            return;
        }
        if (response instanceof UiFrontendMessage.Result result) {
            completeFuture(work.future, result.value());
        } else if (response instanceof UiFrontendMessage.Error) {
            failFuture(work.future, failure(UiFrontendProcessException.Category.REMOTE_ERROR,
                    "Native UI request returned an error", null));
        }
        if (work.requiredState == State.CLOSING) {
            terminate();
        }
        completeWork(work);
    }

    /// Invokes one accepted child command on the bounded handler pool.
    ///
    /// @param work child request work
    private void invokeHandler(IncomingWork work) {
        if (isExpired(work)) {
            fail(timeoutFailure());
            return;
        }
        try {
            handlers.execute(() -> {
                if (isExpired(work)) {
                    fail(timeoutFailure());
                    return;
                }
                CompletionStage<UiFrontendCommandHandler.Reply> stage;
                try {
                    stage = Objects.requireNonNull(handler.handle(work.request.method(), work.request.params()),
                            "handler completion");
                } catch (Throwable throwable) {
                    replyHandlerFailure(work);
                    return;
                }
                stage.whenComplete((reply, exception) -> {
                    if (exception != null || reply == null) {
                        replyHandlerFailure(work);
                    } else {
                        write(new UiFrontendMessage.Result(work.request.requestId(), reply.value()), work,
                                reply.afterResponseAction(), () -> completeWork(work),
                                failure -> fail(transportFailure(failure)));
                    }
                });
            });
        } catch (RejectedExecutionException exception) {
            fail(failure(UiFrontendProcessException.Category.OVERLOAD,
                    "Native UI handler capacity is exhausted", exception));
        }
    }

    /// Sends a stable handler failure without reflecting exception detail.
    ///
    /// @param work failed child request
    private void replyHandlerFailure(IncomingWork work) {
        write(new UiFrontendMessage.Error(work.request.requestId(), "handler-failed",
                        "Launcher command failed"), work, null, () -> completeWork(work),
                exception -> fail(transportFailure(exception)));
    }

    /// Serializes and flushes one frame, then performs completion and optional launcher action.
    ///
    /// @param message response or request frame
    /// @param afterResponse action allowed only after a successful flush
    /// @param completion internal completion after flush
    /// @param failure transport failure consumer
    private void write(
            UiFrontendMessage message, @Nullable Runnable afterResponse, Runnable completion,
            java.util.function.Consumer<Throwable> failure) {
        write(message, null, afterResponse, completion, failure);
    }

    /// Serializes a deadline-bound frame and preserves action-before-promotion ordering.
    ///
    /// @param message response or request frame
    /// @param deadlineWork request whose deadline and active state must still be valid, or `null`
    /// @param afterResponse action allowed only after a successful flush
    /// @param completion internal completion after flush and action
    /// @param failure transport failure consumer
    private void write(
            UiFrontendMessage message, @Nullable Work deadlineWork, @Nullable Runnable afterResponse,
            Runnable completion, java.util.function.Consumer<Throwable> failure) {
        try {
            writer.execute(() -> {
                try {
                    if (deadlineWork != null && !canWrite(deadlineWork)) {
                        return;
                    }
                    if (deadlineWork != null && isExpired(deadlineWork)) {
                        fail(timeoutFailure());
                        return;
                    }
                    UiFrontendWireCodec.write(childInput, message);
                    childInput.flush();
                    if (afterResponse != null) {
                        afterResponses.execute(() -> {
                            try {
                                afterResponse.run();
                            } finally {
                                completion.run();
                            }
                        });
                    } else {
                        completion.run();
                    }
                } catch (Throwable exception) {
                    failure.accept(exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            failure.accept(exception);
        }
    }

    /// Checks whether deadline-bound work is still active before a queued writer touches the transport.
    ///
    /// @param work deadline-bound work
    /// @return whether it remains active
    private boolean canWrite(Work work) {
        synchronized (lock) {
            return work.active && !work.done && !terminationStarted.get();
        }
    }

    /// Supervises ordinary request deadlines including time spent in the shared queue.
    private void deadlineLoop() {
        while (!termination.isDone()) {
            long remaining = TimeUnit.MILLISECONDS.toNanos(100L);
            @Nullable Work expired = null;
            long now = timing.nanoClock().nanoTime();
            synchronized (lock) {
                for (Work work : active) {
                    if (!work.done && work.deadlineNanos <= now) {
                        expired = work;
                        break;
                    }
                    remaining = Math.min(remaining, Math.max(1L, work.deadlineNanos - now));
                }
                if (expired == null) {
                    for (Work work : waiting) {
                        if (work.deadlineNanos <= now) {
                            expired = work;
                            break;
                        }
                        remaining = Math.min(remaining, Math.max(1L, work.deadlineNanos - now));
                    }
                }
            }
            if (expired != null) {
                UiFrontendProcessException exception = failure(UiFrontendProcessException.Category.TIMEOUT,
                        "Native UI request deadline expired", null);
                fail(exception);
                continue;
            }
            synchronized (deadlineSignal) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(deadlineSignal, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /// Wakes the bounded deadline supervisor.
    private void wakeDeadlines() {
        synchronized (deadlineSignal) {
            deadlineSignal.notifyAll();
        }
    }

    /// Tests one request deadline at its current execution boundary.
    ///
    /// @param work admitted request work
    /// @return whether its deadline has elapsed
    private boolean isExpired(Work work) {
        return timing.nanoClock().nanoTime() >= work.deadlineNanos;
    }

    /// Creates the stable deadline failure used at every queue and execution boundary.
    ///
    /// @return timeout failure
    private static UiFrontendProcessException timeoutFailure() {
        return failure(UiFrontendProcessException.Category.TIMEOUT,
                "Native UI request deadline expired", null);
    }

    /// Completes a future on the bounded continuation-isolation executor.
    ///
    /// @param future future to complete
    /// @param value successful value
    /// @param <T> value type
    private <T> void completeFuture(CompletableFuture<T> future, T value) {
        executeCompletion(() -> future.complete(value));
    }

    /// Fails a future on the bounded continuation-isolation executor.
    ///
    /// @param future future to fail
    /// @param exception stable failure
    private void failFuture(CompletableFuture<?> future, UiFrontendProcessException exception) {
        executeCompletion(() -> future.completeExceptionally(exception));
    }

    /// Submits one completion without invoking arbitrary dependents on transport or under `lock`.
    ///
    /// A saturated completion executor cannot recover by submitting another completion from its own rejection
    /// path; doing so recursively exhausts the calling stack. The rejection is therefore completed inline on the
    /// bounded caller after the terminal failure has been established, while normal completions stay isolated.
    ///
    /// @param completion future state transition
    private void executeCompletion(Runnable completion) {
        try {
            completions.execute(completion);
        } catch (RejectedExecutionException exception) {
            @Nullable UiFrontendProcessException established = terminalFailure;
            if (established == null && !termination.isDone()) {
                established = failure(UiFrontendProcessException.Category.OVERLOAD,
                        "Native UI completion capacity is exhausted", exception);
            }
            if (established == null) {
                return;
            }
            closeStarted.set(true);
            cancelRequests(established);
            terminate();
            completion.run();
        }
    }

    /// Independently drains child stderr into its fixed-size tail.
    private void drainStderr() {
        byte[] buffer = new byte[8192];
        try (InputStream error = process.getErrorStream()) {
            int count;
            while ((count = error.read(buffer)) >= 0) {
                if (count > 0) {
                    stderrTail.append(buffer, count);
                }
            }
        } catch (IOException ignored) {
            // Process termination commonly closes stderr; the terminal category is established elsewhere.
        }
    }

    /// Observes unexpected child exit independently from stdout framing.
    private void waitForExit() {
        try {
            int exitCode = process.waitFor();
            if (!terminationStarted.get()) {
                if (closeStarted.get()) {
                    terminate();
                } else {
                    fail(failure(UiFrontendProcessException.Category.TRANSPORT,
                            "Native UI process exited unexpectedly with code " + exitCode, null));
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /// Initiates immediate idempotent failure termination.
    ///
    /// @param exception terminal failure
    private void fail(UiFrontendProcessException exception) {
        UiFrontendProcessException established;
        synchronized (lock) {
            if (terminalFailure == null) {
                terminalFailure = exception;
            }
            established = terminalFailure;
            if (state != State.TERMINATED) {
                state = State.CLOSING;
            }
        }
        closeStarted.set(true);
        failFuture(ready, established);
        cancelRequests(established);
        terminate();
    }

    /// Cancels every queued and active request future without running user code under the monitor.
    ///
    /// @param exception cancellation reason
    private void cancelRequests(UiFrontendProcessException exception) {
        List<Work> cancelled;
        synchronized (lock) {
            cancelled = new ArrayList<>(waiting.size() + active.size());
            cancelled.addAll(waiting);
            cancelled.addAll(active);
            waiting.clear();
            active.clear();
            pending.clear();
            activeCount = 0;
            for (Work work : cancelled) {
                work.done = true;
                work.active = false;
            }
        }
        cancelled.forEach(work -> work.reject(exception));
        wakeDeadlines();
    }

    /// Schedules process-tree termination and publishes only after actual exit and worker cleanup.
    private void terminate() {
        if (!terminationStarted.compareAndSet(false, true)) {
            return;
        }
        @Unmodifiable List<ProcessHandle> descendants = captureDescendants();
        cleanup.execute(() -> {
            if (terminalFailure != null && process.isAlive()) {
                destroyDescendants(descendants, false);
                process.destroy();
            }
            if (!waitForProcessTreeExit(descendants, timing.shutdownGrace())) {
                forced = true;
                destroyDescendants(descendants, true);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            waitForActualProcessExit();
            waitForDescendantsExit(descendants);
            writer.shutdown();
            awaitExecutorFully(writer);
            closeQuietly(childInput);
            handlers.shutdownNow();
            awaitExecutorFully(handlers);
            afterResponses.shutdown();
            awaitExecutorFully(afterResponses);
            transports.shutdown();
            awaitExecutorFully(transports);
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            int exitCode = exitValue(process);
            synchronized (lock) {
                state = State.TERMINATED;
            }
            termination.complete(new Termination(exitCode, forced, stderrTail.snapshot(), terminalFailure));
            wakeDeadlines();
            completions.shutdown();
            cleanup.shutdown();
        });
    }

    /// Captures all descendants visible before process-tree shutdown begins.
    ///
    /// @return immutable cumulative descendant snapshot
    private @Unmodifiable List<ProcessHandle> captureDescendants() {
        try {
            @Unmodifiable List<ProcessHandle> observed = process.toHandle().descendants().toList();
            synchronized (lock) {
                for (ProcessHandle handle : observed) {
                    capturedDescendants.put(handle.pid(), handle);
                }
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // A custom Process or restricted platform may not expose descendants.
        }
        synchronized (lock) {
            return List.copyOf(capturedDescendants.values());
        }
    }

    /// Attempts to terminate every captured descendant.
    ///
    /// @param descendants immutable captured descendants
    /// @param forcibly whether to use forcible termination
    private static void destroyDescendants(
            @Unmodifiable List<ProcessHandle> descendants, boolean forcibly) {
        for (ProcessHandle handle : descendants) {
            if (forcibly) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        }
    }

    /// Waits for the child and captured descendants within one shared graceful-exit budget.
    ///
    /// @param descendants immutable captured descendants
    /// @param duration total process-tree grace
    /// @return whether the entire captured process tree exited before the grace elapsed
    private boolean waitForProcessTreeExit(
            @Unmodifiable List<ProcessHandle> descendants, Duration duration) {
        long finish = deadline(duration, System::nanoTime);
        boolean interrupted = false;
        while (process.isAlive() || hasLiveDescendant(descendants)) {
            long remaining = finish - System.nanoTime();
            if (remaining <= 0L || interrupted) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
            try {
                if (process.isAlive()) {
                    process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10L)), TimeUnit.NANOSECONDS);
                } else {
                    TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10L)));
                }
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return true;
    }

    /// Reports whether any captured descendant remains alive.
    ///
    /// @param descendants immutable captured descendants
    /// @return whether at least one descendant is alive
    private static boolean hasLiveDescendant(@Unmodifiable List<ProcessHandle> descendants) {
        return descendants.stream().anyMatch(ProcessHandle::isAlive);
    }

    /// Waits without a false timeout until the owned child has actually exited.
    private void waitForActualProcessExit() {
        boolean interrupted = false;
        while (process.isAlive()) {
            try {
                process.waitFor();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /// Waits without a false timeout until every captured descendant has actually exited.
    ///
    /// @param descendants immutable captured descendants
    private static void waitForDescendantsExit(@Unmodifiable List<ProcessHandle> descendants) {
        boolean interrupted = Thread.interrupted();
        for (ProcessHandle handle : descendants) {
            while (handle.isAlive()) {
                try {
                    handle.onExit().get();
                } catch (InterruptedException exception) {
                    interrupted = true;
                } catch (java.util.concurrent.ExecutionException exception) {
                    handle.destroyForcibly();
                    Thread.yield();
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /// Waits for terminal cleanup while preserving interruption.
    private void awaitTerminationQuietly() {
        try {
            termination.join();
        } catch (CompletionException ignored) {
            // The terminal value carries the inspected failure rather than failing this join.
        }
    }

    /// Validates canonical containment and rejects every symbolic path component.
    ///
    /// @param executable candidate executable
    /// @param packageRoot candidate package root
    /// @return canonical executable
    /// @throws UiFrontendProcessException if either path is invalid
    private static Path validatePaths(Path executable, Path packageRoot) throws UiFrontendProcessException {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(packageRoot, "packageRoot");
        try {
            Path root = packageRoot.toAbsolutePath().normalize();
            Path child = executable.toAbsolutePath().normalize();
            rejectSymbolicComponents(root);
            rejectSymbolicComponents(child);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(UiFrontendProcessException.Category.PATH,
                        "Native UI package paths are not regular contained paths", null);
            }
            Path canonicalRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path canonicalChild = child.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!canonicalChild.startsWith(canonicalRoot)) {
                throw failure(UiFrontendProcessException.Category.PATH,
                        "Native UI executable is outside its package root", null);
            }
            return canonicalChild;
        } catch (UiFrontendProcessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(UiFrontendProcessException.Category.PATH,
                    "Native UI package paths could not be validated", exception);
        }
    }

    /// Rejects symbolic links in an absolute path and all existing ancestors.
    ///
    /// @param path absolute normalized path
    /// @throws UiFrontendProcessException if any component is symbolic
    private static void rejectSymbolicComponents(Path path) throws UiFrontendProcessException {
        @Nullable Path current = path.getRoot();
        for (Path component : path) {
            current = current == null ? component : current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw failure(UiFrontendProcessException.Category.PATH,
                        "Native UI package paths must not contain symbolic links", null);
            }
        }
    }

    /// Waits only until the shared startup deadline.
    ///
    /// @param future startup stage
    /// @param deadline absolute monotonic deadline
    /// @param <T> result type
    /// @return completed result
    /// @throws UiFrontendProcessException if the stage fails or times out
    private static <T> T awaitUntil(
            CompletableFuture<T> future, long deadline, NanoClock nanoClock) throws UiFrontendProcessException {
        long remaining = deadline - nanoClock.nanoTime();
        if (remaining <= 0L) {
            throw failure(UiFrontendProcessException.Category.TIMEOUT,
                    "Native UI startup deadline expired", null);
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw failure(UiFrontendProcessException.Category.TIMEOUT,
                    "Native UI startup deadline expired", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(UiFrontendProcessException.Category.STARTUP,
                    "Native UI startup was interrupted", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = unwrap(exception);
            if (cause instanceof UiFrontendProcessException processException) {
                throw processException;
            }
            throw failure(UiFrontendProcessException.Category.STARTUP,
                    "Native UI startup failed", cause);
        }
    }

    /// Creates one fixed-size executor with a bounded work queue.
    ///
    /// @param name thread-name prefix
    /// @param threads fixed worker count
    /// @param queueCapacity maximum waiting tasks
    /// @return bounded executor
    private static ExecutorService executor(String name, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), daemonFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /// Creates daemon threads with stable diagnostics-only names.
    ///
    /// @param prefix name prefix
    /// @return thread factory
    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> daemonThread(prefix + '-' + sequence.incrementAndGet(), runnable);
    }

    /// Creates one daemon thread.
    ///
    /// @param name thread name
    /// @param runnable work
    /// @return unstarted daemon thread
    private static Thread daemonThread(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /// Returns an absolute monotonic deadline.
    ///
    /// @param duration allowed duration
    /// @return monotonic deadline
    private long deadline(Duration duration) {
        return deadline(duration, timing.nanoClock());
    }

    /// Returns an absolute monotonic deadline from an injected clock.
    ///
    /// @param duration allowed duration
    /// @param nanoClock monotonic clock
    /// @return monotonic deadline
    private static long deadline(Duration duration, NanoClock nanoClock) {
        long now = nanoClock.nanoTime();
        long nanos = duration.toNanos();
        return nanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + nanos;
    }

    /// Unwraps asynchronous wrapper failures for inspectable local causes.
    ///
    /// @param throwable wrapper or cause
    /// @return deepest ordinary asynchronous cause
    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /// Converts a write failure to a stable transport failure.
    ///
    /// @param cause local write cause
    /// @return stable session exception
    private static UiFrontendProcessException transportFailure(Throwable cause) {
        return failure(UiFrontendProcessException.Category.TRANSPORT,
                "Native UI transport write failed", cause);
    }

    /// Creates one stable session failure.
    ///
    /// @param category stable category
    /// @param message redacted message
    /// @param cause inspectable local cause
    /// @return session exception
    private static UiFrontendProcessException failure(
            UiFrontendProcessException.Category category, String message, @Nullable Throwable cause) {
        return new UiFrontendProcessException(category, message, cause);
    }

    /// Creates an already failed request future.
    ///
    /// @param exception failure
    /// @return exceptional future
    private static CompletableFuture<BridgeValue> failedFuture(UiFrontendProcessException exception) {
        return CompletableFuture.failedFuture(exception);
    }

    /// Closes a resource without changing the established terminal outcome.
    ///
    /// @param resource resource to close
    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // Cleanup remains best effort after termination has already been decided.
        }
    }

    /// Forcibly cleans a child returned after its launch caller has already failed.
    ///
    /// @param process late process result
    private static void destroyLateProcess(Process process) {
        try {
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // A custom Process or restricted platform may not expose descendants.
        }
        process.destroyForcibly();
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
    }

    /// Waits without a false timeout until an executor has released every owned worker.
    ///
    /// @param executor executor being cleaned
    private static void awaitExecutorFully(ExecutorService executor) {
        boolean interrupted = Thread.interrupted();
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /// Returns a stable process exit code even when the process implementation cannot expose one.
    ///
    /// @param process terminated process
    /// @return exit code or `-1` when unavailable
    private static int exitValue(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException exception) {
            return -1;
        }
    }

    /// Launches one configured process; package-private for real-process test injection.
    @FunctionalInterface
    @NotNullByDefault
    interface ProcessLauncher {
        /// Starts one exact configured process.
        ///
        /// @param builder exact process builder
        /// @return started process
        /// @throws IOException if the operating system rejects launch
        Process start(ProcessBuilder builder) throws IOException;
    }

    /// Supplies monotonic time for package-private deterministic deadline tests.
    @FunctionalInterface
    @NotNullByDefault
    interface NanoClock {
        /// Returns the current monotonic time in nanoseconds.
        ///
        /// @return monotonic nanoseconds
        long nanoTime();
    }

    /// Holds production deadlines with package-private shortening for deterministic tests.
    ///
    /// @param startupBudget one total launch-through-ready budget
    /// @param requestDeadline ordinary request deadline including queue wait
    /// @param shutdownGrace graceful process-exit grace before force
    /// @param nanoClock monotonic deadline clock
    @NotNullByDefault
    record Timing(Duration startupBudget, Duration requestDeadline, Duration shutdownGrace, NanoClock nanoClock) {
        /// Production timing required by the native frontend contract.
        private static final Timing PRODUCTION = new Timing(
                Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofMillis(250), System::nanoTime);

        /// Creates timing that uses the production monotonic clock.
        ///
        /// @param startupBudget one total launch-through-ready budget
        /// @param requestDeadline ordinary request deadline including queue wait
        /// @param shutdownGrace graceful process-exit grace before force
        Timing(Duration startupBudget, Duration requestDeadline, Duration shutdownGrace) {
            this(startupBudget, requestDeadline, shutdownGrace, System::nanoTime);
        }

        /// Validates positive bounded durations.
        Timing {
            requirePositive(startupBudget, "startupBudget");
            requirePositive(requestDeadline, "requestDeadline");
            requirePositive(shutdownGrace, "shutdownGrace");
            Objects.requireNonNull(nanoClock, "nanoClock");
        }

        /// Rejects a zero or negative duration.
        ///
        /// @param duration candidate duration
        /// @param name field name
        private static void requirePositive(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }

    /// Immutable terminal process outcome with a defensive stderr tail.
    @NotNullByDefault
    public static final class Termination {
        /// Child exit code, or `-1` if unavailable after bounded cleanup.
        private final int exitCode;

        /// Whether the child required forcible termination.
        private final boolean forced;

        /// Final bounded diagnostic bytes.
        private final byte @Unmodifiable [] stderrTail;

        /// Terminal failure, or `null` for normal close.
        private final @Nullable UiFrontendProcessException failure;

        /// Creates one immutable terminal outcome.
        ///
        /// @param exitCode child exit code
        /// @param forced whether force was required
        /// @param stderrTail final diagnostic bytes
        /// @param failure terminal failure, or `null`
        private Termination(
                int exitCode, boolean forced, byte[] stderrTail, @Nullable UiFrontendProcessException failure) {
            this.exitCode = exitCode;
            this.forced = forced;
            this.stderrTail = stderrTail.clone();
            this.failure = failure;
        }

        /// Returns the child exit code or `-1` when unavailable.
        ///
        /// @return exit code
        public int exitCode() {
            return exitCode;
        }

        /// Returns whether forcible termination was required.
        ///
        /// @return forced state
        public boolean forced() {
            return forced;
        }

        /// Returns a defensive copy of the final 64 KiB stderr tail.
        ///
        /// @return bounded diagnostic bytes
        public byte @Unmodifiable [] stderrTail() {
            return stderrTail.clone();
        }

        /// Returns the terminal failure or `null` for a normal close.
        ///
        /// @return terminal failure
        public @Nullable UiFrontendProcessException failure() {
            return failure;
        }
    }

    /// Models lifecycle and strict handshake ordering.
    @NotNullByDefault
    private enum State {
        /// Waiting for the exact hello acknowledgement.
        HELLO,

        /// Waiting for the initial snapshot acknowledgement.
        SNAPSHOT,

        /// Waiting for the child's one ready request.
        WAITING_READY,

        /// Ordinary bidirectional request traffic is accepted.
        READY,

        /// New requests are rejected while shutdown proceeds.
        CLOSING,

        /// Process and owned resources have been cleaned.
        TERMINATED
    }

    /// Completion for the child's unique ready request.
    private final CompletableFuture<BridgeValue> ready = new CompletableFuture<>();

    /// Whether the child ready request has already been accepted.
    private boolean readySeen;

    /// Represents one request counted by shared admission.
    @NotNullByDefault
    private abstract class Work {
        /// Absolute ordinary or startup request deadline.
        protected final long deadlineNanos;

        /// State required at admission.
        protected final State requiredState;

        /// Whether this work currently consumes one active slot.
        protected boolean active;

        /// Whether this work can no longer complete normally.
        protected boolean done;

        /// Immediate admission rejection, if any.
        protected @Nullable UiFrontendProcessException rejection;

        /// Creates one admitted request candidate.
        ///
        /// @param deadlineNanos absolute deadline
        /// @param requiredState required session state
        private Work(long deadlineNanos, State requiredState) {
            this.deadlineNanos = deadlineNanos;
            this.requiredState = requiredState;
        }

        /// Starts active work outside the state monitor.
        protected abstract void activate();

        /// Rejects or cancels this work.
        ///
        /// @param exception stable reason
        protected final void reject(UiFrontendProcessException exception) {
            rejection = exception;
            rejected(exception);
        }

        /// Propagates rejection to the direction-specific completion.
        ///
        /// @param exception stable reason
        protected abstract void rejected(UiFrontendProcessException exception);
    }

    /// Represents one JVM-originated request pending an exact odd-ID response.
    @NotNullByDefault
    private final class OutgoingWork extends Work {
        /// Fixed outgoing method.
        private final String method;

        /// Token-free outgoing parameters.
        private final BridgeValue params;

        /// Request completion returned to the caller or startup sequence.
        private final CompletableFuture<BridgeValue> future = new CompletableFuture<>();

        /// Optional handshake state installed before response completion.
        private final @Nullable State nextState;

        /// Assigned odd request identifier, or zero while queued.
        private long requestId;

        /// Creates one ordinary outgoing request.
        ///
        /// @param method method
        /// @param params parameters
        /// @param deadlineNanos absolute deadline
        /// @param requiredState required state
        private OutgoingWork(String method, BridgeValue params, long deadlineNanos, State requiredState) {
            this(method, params, deadlineNanos, requiredState, null);
        }

        /// Creates one outgoing request with an optional handshake transition.
        ///
        /// @param method method
        /// @param params parameters
        /// @param deadlineNanos absolute deadline
        /// @param requiredState required state
        /// @param nextState response transition, or `null`
        private OutgoingWork(
                String method, BridgeValue params, long deadlineNanos, State requiredState, @Nullable State nextState) {
            super(deadlineNanos, requiredState);
            this.method = method;
            this.params = params;
            this.nextState = nextState;
        }

        /// Writes this request through the serialized writer.
        @Override
        protected void activate() {
            activateOutgoing(this);
        }

        /// Completes the public request exceptionally.
        ///
        /// @param exception stable reason
        @Override
        protected void rejected(UiFrontendProcessException exception) {
            failFuture(future, exception);
        }
    }

    /// Represents one child-originated command through response flush.
    @NotNullByDefault
    private final class IncomingWork extends Work {
        /// Exact child request.
        private final UiFrontendMessage.Request request;

        /// Creates one incoming command.
        ///
        /// @param request child request
        /// @param deadlineNanos absolute deadline
        private IncomingWork(UiFrontendMessage.Request request, long deadlineNanos) {
            super(deadlineNanos, State.READY);
            this.request = request;
        }

        /// Dispatches the handler outside transport and state monitors.
        @Override
        protected void activate() {
            invokeHandler(this);
        }

        /// Records admission rejection for a same-ID overload response.
        ///
        /// @param exception stable reason
        @Override
        protected void rejected(UiFrontendProcessException exception) {
            // The reader sends the required same-ID overload response after admission returns.
        }
    }

    /// Retains only the final fixed number of child diagnostic bytes.
    @NotNullByDefault
    private static final class StderrTail {
        /// Circular diagnostic storage.
        private final byte[] bytes = new byte[STDERR_TAIL_BYTES];

        /// Next write position in circular storage.
        private int position;

        /// Current number of retained bytes.
        private int size;

        /// Appends bytes, discarding the oldest prefix after capacity.
        ///
        /// @param source read buffer
        /// @param count valid byte count
        private synchronized void append(byte[] source, int count) {
            for (int index = 0; index < count; index++) {
                bytes[position] = source[index];
                position = (position + 1) % bytes.length;
                size = Math.min(bytes.length, size + 1);
            }
        }

        /// Returns retained bytes in chronological order.
        ///
        /// @return defensive tail snapshot
        private synchronized byte @Unmodifiable [] snapshot() {
            ByteArrayOutputStream output = new ByteArrayOutputStream(size);
            int start = (position - size + bytes.length) % bytes.length;
            for (int index = 0; index < size; index++) {
                output.write(bytes[(start + index) % bytes.length]);
            }
            return output.toByteArray();
        }
    }
}
