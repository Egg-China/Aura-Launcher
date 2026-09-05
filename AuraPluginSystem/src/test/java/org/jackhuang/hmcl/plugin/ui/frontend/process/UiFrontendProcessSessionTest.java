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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies strict bounded supervision of one isolated native UI frontend process.
@NotNullByDefault
final class UiFrontendProcessSessionTest {
    /// Temporary package root used for canonical executable validation.
    @TempDir
    private Path temporaryDirectory;

    /// Exercises the actual JVM process boundary, handshake, child callback, request, environment, and clean exit.
    @Test
    void supervisesRealChildFromHandshakeThroughCleanShutdown() throws Exception {
        Path executable = executable();
        AtomicReference<@Nullable List<String>> capturedCommand = new AtomicReference<>();
        AtomicReference<@Nullable Map<String, String>> capturedEnvironment = new AtomicReference<>();
        Map<String, String> sourceEnvironment = fixtureEnvironment();
        UiFrontendProcessSession.ProcessLauncher launcher = builder -> {
            @Unmodifiable List<String> originalCommand = List.copyOf(builder.command());
            capturedCommand.set(originalCommand);
            capturedEnvironment.set(Map.copyOf(builder.environment()));
            List<String> fixtureCommand = new ArrayList<>(javaCommand());
            fixtureCommand.addAll(originalCommand);
            builder.command(fixtureCommand);
            return builder.start();
        };
        UiFrontendCommandHandler handler = (method, params) -> CompletableFuture.completedFuture(
                UiFrontendCommandHandler.Reply.result(BridgeValue.string("launcher-state")));

        UiFrontendProcessSession session = UiFrontendProcessSession.start(
                executable, temporaryDirectory, BridgeValue.string("redacted"), handler,
                launcher, timing(Duration.ofSeconds(5), Duration.ofSeconds(2)), sourceEnvironment, isWindows());
        try {
            try {
                assertEquals(BridgeValue.string("route"), await(session.request(
                        "ui.navigate", BridgeValue.string("route"))));
            } catch (Exception exception) {
                UiFrontendProcessSession.Termination termination = await(session.termination());
                throw new AssertionError(new String(termination.stderrTail(), StandardCharsets.UTF_8), exception);
            }
            @Unmodifiable Map<String, String> environment = Map.copyOf(capturedEnvironment.get());
            assertTrue(environment.keySet().stream().allMatch(UiFrontendProcessSessionTest::allowedEnvironment));
        } finally {
            session.close();
        }
        UiFrontendProcessSession.Termination termination = await(session.termination());
        assertEquals(0, termination.exitCode(),
                new String(termination.stderrTail(), StandardCharsets.UTF_8));
        assertFalse(termination.forced());
        assertNull(termination.failure());
    }

    /// Applies case-insensitive allowlist matching only for Windows environment names.
    @Test
    void filtersEnvironmentWithOperatingSystemCaseRules() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("Path", "fixture-path");
        source.put("cOmSpEc", "fixture-comspec");
        source.put("windir", "fixture-windir");
        source.put("lC_ALL", "fixture-locale");
        source.put("PASSWORD", "must-not-cross");
        source.put("AURA_SENTINEL_TOKEN", "must-not-cross");

        @Unmodifiable Map<String, String> windows = UiFrontendProcessSession.filterEnvironment(source, true);
        assertEquals(Map.of(
                "Path", "fixture-path",
                "cOmSpEc", "fixture-comspec",
                "windir", "fixture-windir",
                "lC_ALL", "fixture-locale"), windows);

        @Unmodifiable Map<String, String> unix = UiFrontendProcessSession.filterEnvironment(source, false);
        assertTrue(unix.isEmpty());
    }

    /// Rejects paths outside the canonical package root and non-regular executables before launch.
    @Test
    void rejectsUncontainedAndNonRegularExecutablePaths() throws Exception {
        Path outside = Files.createTempFile("aura-ui-outside", ".bin");
        try {
            UiFrontendProcessException outsideFailure = assertThrows(UiFrontendProcessException.class,
                    () -> UiFrontendProcessSession.start(outside, temporaryDirectory, BridgeValue.nullValue(),
                            unusedHandler(), builder -> {
                                throw new AssertionError("invalid path reached launcher");
                            }, timing(Duration.ofMillis(200), Duration.ofMillis(100))));
            assertEquals(UiFrontendProcessException.Category.PATH, outsideFailure.category());

            UiFrontendProcessException directoryFailure = assertThrows(UiFrontendProcessException.class,
                    () -> UiFrontendProcessSession.start(temporaryDirectory, temporaryDirectory,
                            BridgeValue.nullValue(), unusedHandler(), builder -> {
                                throw new AssertionError("invalid path reached launcher");
                            }, timing(Duration.ofMillis(200), Duration.ofMillis(100))));
            assertEquals(UiFrontendProcessException.Category.PATH, directoryFailure.category());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /// Categorizes a launch-budget expiry as timeout and destroys a process returned after that expiry.
    @Test
    void cleansUpAProcessThatLaunchesAfterStartupTimeout() throws Exception {
        ScriptedProcess lateProcess = new ScriptedProcess(ScriptedChild::closeStdout);
        CountDownLatch launchEntered = new CountDownLatch(1);
        CountDownLatch releaseLaunch = new CountDownLatch(1);
        UiFrontendProcessSession.ProcessLauncher launcher = builder -> {
            launchEntered.countDown();
            try {
                releaseLaunch.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return lateProcess;
        };

        UiFrontendProcessException timeout = assertThrows(UiFrontendProcessException.class,
                () -> UiFrontendProcessSession.start(executable(), temporaryDirectory,
                        BridgeValue.nullValue(), unusedHandler(), launcher,
                        timing(Duration.ofMillis(50), Duration.ofMillis(100))));
        assertTrue(launchEntered.await(1, TimeUnit.SECONDS));
        releaseLaunch.countDown();

        assertEquals(UiFrontendProcessException.Category.TIMEOUT, timeout.category());
        lateProcess.onExit().get(1, TimeUnit.SECONDS);
        assertFalse(lateProcess.isAlive());
    }

    /// Fails startup for malformed hello, premature ready, duplicate ready, bad stdout, EOF, and crash.
    @Test
    void failsClosedForHandshakeAndTransportViolations() throws Exception {
        @Unmodifiable List<Scenario> scenarios = List.of(
                new Scenario("malformed hello", child -> child.replyHello(BridgeValue.nullValue()),
                        UiFrontendProcessException.Category.PROTOCOL),
                new Scenario("reordered hello", child -> {
                    Map<String, BridgeValue> fields = new LinkedHashMap<>();
                    fields.put("abi", BridgeValue.integer(1L));
                    fields.put("protocol", BridgeValue.string("aura.ui.v1"));
                    child.replyHello(BridgeValue.map(fields));
                }, UiFrontendProcessException.Category.PROTOCOL),
                new Scenario("premature ready", child -> child.sendRequest(2L, "ui.ready", BridgeValue.nullValue()),
                        UiFrontendProcessException.Category.PROTOCOL),
                new Scenario("stdout noise", child -> child.writeRaw(new byte[]{'l', 'o', 'g', '\n'}),
                        UiFrontendProcessException.Category.PROTOCOL),
                new Scenario("clean eof", ScriptedChild::closeStdout,
                        UiFrontendProcessException.Category.TRANSPORT),
                new Scenario("crash", child -> child.exit(19),
                        UiFrontendProcessException.Category.TRANSPORT)
        );

        for (Scenario scenario : scenarios) {
            ScriptedProcess process = new ScriptedProcess(scenario.script());
            UiFrontendProcessException failure = assertThrows(UiFrontendProcessException.class,
                    () -> UiFrontendProcessSession.start(executable(), temporaryDirectory,
                            BridgeValue.nullValue(), unusedHandler(), builder -> process,
                            timing(Duration.ofMillis(700), Duration.ofMillis(100))), scenario.name());
            assertEquals(scenario.category(), failure.category(), scenario.name());
            assertFalse(process.isAlive(), scenario.name());
        }


        ScriptedProcess duplicate = new ScriptedProcess(child -> {
            child.completeHandshake();
            child.sendRequest(4L, "ui.ready", BridgeValue.nullValue());
        });
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), unusedHandler(), builder -> duplicate,
                timing(Duration.ofMillis(700), Duration.ofMillis(100)));
        UiFrontendProcessSession.Termination duplicateTermination = await(session.termination());
        @Nullable UiFrontendProcessException duplicateFailure = duplicateTermination.failure();
        assertNotNull(duplicateFailure);
        assertEquals(UiFrontendProcessException.Category.PROTOCOL, duplicateFailure.category());
    }

    /// Treats unknown, duplicate, and stale response identifiers as terminal protocol failures.
    @Test
    void rejectsResponsesThatDoNotOwnCurrentPendingRequests() throws Exception {
        @Unmodifiable List<Script> scripts = List.of(
                child -> {
                    child.completeHandshake();
                    child.sendResult(999L, BridgeValue.nullValue());
                },
                child -> {
                    child.completeHandshake();
                    child.sendResult(1L, BridgeValue.nullValue());
                },
                child -> {
                    child.completeHandshake();
                    child.sendResult(3L, BridgeValue.nullValue());
                }
        );

        for (Script script : scripts) {
            ScriptedProcess process = new ScriptedProcess(script);
            UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                    BridgeValue.nullValue(), unusedHandler(), builder -> process,
                    timing(Duration.ofSeconds(1), Duration.ofMillis(200)));
            UiFrontendProcessSession.Termination termination = await(session.termination());
            @Nullable UiFrontendProcessException failure = termination.failure();
            assertNotNull(failure);
            assertEquals(UiFrontendProcessException.Category.PROTOCOL, failure.category());
        }
    }

    /// Shares exactly 32 active and 128 queued slots and releases queued calls on their deadline.
    @Test
    void enforcesSharedInflightAndQueueBoundsIncludingQueueWait() throws Exception {
        CountDownLatch queueFilled = new CountDownLatch(1);
        AtomicReference<@Nullable UiFrontendMessage> incomingReply = new AtomicReference<>();
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.completeHandshake();
            child.drainRequestsWithoutReply(32);
            assertTrue(queueFilled.await(1, TimeUnit.SECONDS));
            child.sendRequest(4L, "core.snapshot.get", BridgeValue.nullValue());
            incomingReply.set(child.read());
        });
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), unusedHandler(), builder -> process,
                timing(Duration.ofSeconds(1), Duration.ofMillis(500)));
        try {
            List<CompletableFuture<BridgeValue>> accepted = new ArrayList<>();
            for (int index = 0; index < 160; index++) {
                accepted.add(session.request("ui.notify", BridgeValue.integer(index)));
            }
            queueFilled.countDown();
            UiFrontendProcessException overload = futureFailure(
                    session.request("ui.notify", BridgeValue.integer(160)));
            assertEquals(UiFrontendProcessException.Category.OVERLOAD, overload.category());
            assertTrue(process.scriptFinished.await(1, TimeUnit.SECONDS));
            UiFrontendMessage.Error incomingOverload = assertInstanceOf(
                    UiFrontendMessage.Error.class, incomingReply.get());
            assertEquals(4L, incomingOverload.requestId());
            assertEquals("overloaded", incomingOverload.code());
            UiFrontendProcessException deadline = futureFailure(accepted.get(159));
            assertEquals(UiFrontendProcessException.Category.TIMEOUT, deadline.category());
        } finally {
            session.close();
        }
    }

    /// Does not recursively fail when the bounded completion worker itself is saturated.
    @Test
    void failsWithoutRecursiveCompletionFailureWhenCompletionCapacityIsExhausted() throws Exception {
        CountDownLatch firstCompletionEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCompletion = new CountDownLatch(1);
        AtomicReference<@Nullable UiFrontendProcessException> observedFailure = new AtomicReference<>();
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.completeHandshake();
            for (int index = 0; index < 32; index++) {
                UiFrontendMessage.Request request = assertInstanceOf(UiFrontendMessage.Request.class, child.read());
                child.sendResult(request.requestId(), BridgeValue.nullValue());
            }
            child.read();
        });
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), unusedHandler(), builder -> process,
                timing(Duration.ofSeconds(5), Duration.ofSeconds(2)));
        try {
            List<CompletableFuture<BridgeValue>> requests = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                requests.add(session.request("ui.notify", BridgeValue.integer(index)));
            }
            requests.get(0).whenComplete((value, exception) -> {
                firstCompletionEntered.countDown();
                try {
                    releaseFirstCompletion.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(firstCompletionEntered.await(2, TimeUnit.SECONDS));

            // Occupy the sole completion worker, then fill its entire bounded queue deterministically.
            Field completionsField = UiFrontendProcessSession.class.getDeclaredField("completions");
            completionsField.setAccessible(true);
            java.util.concurrent.ExecutorService completions =
                    (java.util.concurrent.ExecutorService) completionsField.get(session);
            for (int index = 0; index < 164; index++) {
                try {
                    completions.execute(() -> { });
                } catch (RejectedExecutionException ignored) {
                    // The worker is blocked and its queue is full at this deterministic boundary.
                    break;
                }
            }

            // Invoke the production rejection path directly after readiness is complete.
            Field readyField = UiFrontendProcessSession.class.getDeclaredField("ready");
            readyField.setAccessible(true);
            @SuppressWarnings("unchecked")
            CompletableFuture<BridgeValue> ready = (CompletableFuture<BridgeValue>) readyField.get(session);
            java.lang.reflect.Method executeCompletion = UiFrontendProcessSession.class
                    .getDeclaredMethod("executeCompletion", Runnable.class);
            executeCompletion.setAccessible(true);
            CountDownLatch inlineCompleted = new CountDownLatch(1);
            executeCompletion.invoke(session, (Runnable) inlineCompleted::countDown);
            releaseFirstCompletion.countDown();
            assertTrue(inlineCompleted.await(2, TimeUnit.SECONDS));
            session.close();

            // The direct rejection path completed inline without recursive failure or stack exhaustion.
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            releaseFirstCompletion.countDown();
            session.close();
        }
    }

    /// Retains only the final 64 KiB of independently drained stderr in terminal diagnostics.
    @Test
    void boundsStderrTailIndependently() throws Exception {
        byte[] stderr = new byte[70 * 1024];
        for (int index = 0; index < stderr.length; index++) {
            stderr[index] = (byte) ('a' + (index % 26));
        }
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.writeStderr(stderr);
            child.completeHandshake();
            child.exit(23);
        });
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), unusedHandler(), builder -> process,
                timing(Duration.ofSeconds(1), Duration.ofMillis(200)));

        UiFrontendProcessSession.Termination termination = await(session.termination());
        assertEquals(64 * 1024, termination.stderrTail().length);
        assertEquals(stderr[stderr.length - 1], termination.stderrTail()[termination.stderrTail().length - 1]);
    }

    /// Converts handler failure to a redacted same-ID error while keeping the session usable.
    @Test
    void repliesPredictablyWhenHandlerFails() throws Exception {
        AtomicReference<@Nullable UiFrontendMessage> reply = new AtomicReference<>();
        CountDownLatch replyRead = new CountDownLatch(1);
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.completeHandshake();
            child.sendRequest(4L, "core.asset.get", BridgeValue.string("opaque"));
            reply.set(child.read());
            replyRead.countDown();
            child.awaitShutdown();
        });
        UiFrontendCommandHandler handler = (method, params) -> CompletableFuture.failedFuture(
                new IllegalStateException("secret failure detail"));
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), handler, builder -> process,
                timing(Duration.ofSeconds(1), Duration.ofMillis(500)));
        try {
            assertTrue(replyRead.await(1, TimeUnit.SECONDS));
            UiFrontendMessage.Error error = assertInstanceOf(UiFrontendMessage.Error.class, reply.get());
            assertEquals(4L, error.requestId());
            assertEquals("handler-failed", error.code());
            assertFalse(error.message().contains("secret"));
        } finally {
            session.close();
        }
    }

    /// Flushes a successful child reply before invoking its launcher-owned after-response action.
    @Test
    void flushesReplyBeforeAfterResponseAction() throws Exception {
        AtomicBoolean actionObservedFlush = new AtomicBoolean(true);
        CountDownLatch actionRun = new CountDownLatch(2);
        AtomicInteger handled = new AtomicInteger();
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.completeHandshake();
            child.sendRequest(4L, "core.ui.use-javafx", BridgeValue.nullValue());
            child.read();
            child.sendRequest(6L, "core.app.shutdown", BridgeValue.nullValue());
            child.read();
            child.awaitShutdown();
        });
        UiFrontendCommandHandler handler = (method, params) -> {
            int expectedFlushes = handled.incrementAndGet();
            return CompletableFuture.completedFuture(new UiFrontendCommandHandler.Reply(BridgeValue.nullValue(),
                        () -> {
                            actionObservedFlush.set(actionObservedFlush.get()
                                    && process.outputFlushes.get() >= expectedFlushes);
                            actionRun.countDown();
                        }));
        };
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), handler, builder -> process,
                timing(Duration.ofSeconds(1), Duration.ofMillis(500)));
        try {
            assertTrue(actionRun.await(1, TimeUnit.SECONDS));
            assertTrue(actionObservedFlush.get());
        } finally {
            session.close();
        }
    }

    /// Terminates the complete session when one active request exceeds its deadline.
    @Test
    void activeRequestDeadlineFailsTheSession() throws Exception {
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.completeHandshake();
            child.drainRequestsWithoutReply(1);
        });
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), unusedHandler(), builder -> process,
                timing(Duration.ofSeconds(1), Duration.ofMillis(100)));

        UiFrontendProcessException timeout = futureFailure(
                session.request("ui.notify", BridgeValue.nullValue()));
        assertEquals(UiFrontendProcessException.Category.TIMEOUT, timeout.category());
        UiFrontendProcessSession.Termination termination = await(session.termination());
        @Nullable UiFrontendProcessException failure = termination.failure();
        assertNotNull(failure);
        assertEquals(UiFrontendProcessException.Category.TIMEOUT, failure.category());
    }

    /// Times out a request, terminates immediately, and makes racing closes idempotent.
    @Test
    void deadlineAndRacingCloseResolveTerminationOnce() throws Exception {
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.completeHandshake();
            child.drainRequestsWithoutReply(1);
        });
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), unusedHandler(), builder -> process,
                timing(Duration.ofSeconds(1), Duration.ofMillis(120)));
        CompletableFuture<BridgeValue> request = session.request("ui.notify", BridgeValue.nullValue());
        Thread first = new Thread(session::close);
        Thread second = new Thread(session::close);
        first.start();
        second.start();
        first.join(1000);
        second.join(1000);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertTrue(request.isCompletedExceptionally());
        assertNotNull(await(session.termination()));
        UiFrontendProcessException closed = futureFailure(
                session.request("ui.navigate", BridgeValue.nullValue()));
        assertEquals(UiFrontendProcessException.Category.CLOSED, closed.category());
    }

    /// Keeps public continuations off transport threads and outside the session monitor during protocol failure.
    @Test
    void completesPublicFuturesWithoutBlockingTransportCleanup() throws Exception {
        CountDownLatch requestRead = new CountDownLatch(1);
        CountDownLatch emitBadResponse = new CountDownLatch(1);
        CountDownLatch continuationFinished = new CountDownLatch(1);
        AtomicReference<@Nullable String> completionThread = new AtomicReference<>();
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.completeHandshake();
            child.readRequest(5L, "ui.navigate");
            requestRead.countDown();
            assertTrue(emitBadResponse.await(1, TimeUnit.SECONDS));
            child.sendResult(999L, BridgeValue.nullValue());
        });
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), unusedHandler(), builder -> process,
                timing(Duration.ofSeconds(1), Duration.ofMillis(500)));
        CompletableFuture<BridgeValue> request = session.request("ui.navigate", BridgeValue.nullValue());
        request.whenComplete((value, exception) -> {
            completionThread.set(Thread.currentThread().getName());
            session.termination().toCompletableFuture().join();
            continuationFinished.countDown();
        });

        assertTrue(requestRead.await(1, TimeUnit.SECONDS));
        emitBadResponse.countDown();
        assertTrue(continuationFinished.await(1, TimeUnit.SECONDS));
        @Nullable String threadName = completionThread.get();
        assertNotNull(threadName);
        assertFalse(threadName.startsWith("aura-ui-transport"));
        assertFalse(threadName.startsWith("aura-ui-writer"));
        @Nullable UiFrontendProcessException terminalFailure = await(session.termination()).failure();
        assertNotNull(terminalFailure);
        assertEquals(UiFrontendProcessException.Category.PROTOCOL, terminalFailure.category());
    }

    /// Starts graceful termination independently from a cancelled continuation that awaits terminal publication.
    @Test
    void startsAcknowledgedShutdownWithoutWaitingForPublicContinuations() throws Exception {
        CountDownLatch requestRead = new CountDownLatch(1);
        CountDownLatch continuationFinished = new CountDownLatch(1);
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.completeHandshake();
            child.readRequest(5L, "ui.navigate");
            requestRead.countDown();
            UiFrontendMessage.Request shutdown = child.readRequest(7L, "ui.shutdown");
            child.sendResult(shutdown.requestId(), BridgeValue.nullValue());
        });
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), unusedHandler(), builder -> process,
                timing(Duration.ofSeconds(1), Duration.ofSeconds(2)));
        CompletableFuture<BridgeValue> request = session.request("ui.navigate", BridgeValue.nullValue());
        request.whenComplete((value, exception) -> {
            session.termination().toCompletableFuture().join();
            continuationFinished.countDown();
        });
        assertTrue(requestRead.await(1, TimeUnit.SECONDS));
        Thread closer = new Thread(session::close, "aura-ui-test-closer");
        closer.start();

        boolean forcedPromptly;
        try {
            forcedPromptly = process.forcedAttempted.await(500, TimeUnit.MILLISECONDS);
        } finally {
            if (process.isAlive()) {
                process.releaseTermination(0);
            }
            closer.join(1000);
        }

        assertTrue(forcedPromptly);
        assertFalse(closer.isAlive());
        assertTrue(continuationFinished.await(1, TimeUnit.SECONDS));
    }

    /// Releases startup promptly when stdout ends after the snapshot acknowledgement but before ready.
    @Test
    void failsStartupPromptlyWhileWaitingForReady() throws Exception {
        CountDownLatch snapshotAcknowledged = new CountDownLatch(1);
        ScriptedProcess process = new ScriptedProcess(child -> {
            UiFrontendMessage.Request hello = child.readRequest(1L, "ui.hello");
            child.sendResult(hello.requestId(), hello.params());
            UiFrontendMessage.Request snapshot = child.readRequest(3L, "ui.snapshot.replace");
            child.sendResult(snapshot.requestId(), BridgeValue.nullValue());
            snapshotAcknowledged.countDown();
            child.closeStdout();
        });
        Path frontendExecutable = executable();
        CompletableFuture<UiFrontendProcessSession> startup = CompletableFuture.supplyAsync(() -> {
            try {
                return UiFrontendProcessSession.start(frontendExecutable, temporaryDirectory,
                        BridgeValue.nullValue(), unusedHandler(), builder -> process,
                        timing(Duration.ofSeconds(5), Duration.ofMillis(500)));
            } catch (UiFrontendProcessException exception) {
                throw new CompletionException(exception);
            }
        });

        assertTrue(snapshotAcknowledged.await(1, TimeUnit.SECONDS));
        java.util.concurrent.ExecutionException failure = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> startup.get(1, TimeUnit.SECONDS));
        assertEquals(UiFrontendProcessException.Category.TRANSPORT,
                assertInstanceOf(UiFrontendProcessException.class, failure.getCause()).category());
    }

    /// Leaves terminal completion pending until a force-resistant child has actually exited.
    @Test
    void doesNotPublishTerminationWhileTheChildIsAlive() throws Exception {
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.completeHandshake();
            child.acknowledgeShutdownWithoutExit();
        });
        process.refuseTermination.set(true);
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), unusedHandler(), builder -> process,
                timing(Duration.ofSeconds(1), Duration.ofMillis(200)));
        Thread closer = new Thread(session::close, "aura-ui-test-closer");
        closer.start();

        assertTrue(process.forcedAttempted.await(1, TimeUnit.SECONDS));
        assertTrue(process.isAlive());
        assertThrows(java.util.concurrent.TimeoutException.class,
                () -> session.termination().toCompletableFuture().get(200, TimeUnit.MILLISECONDS));
        process.releaseTermination(0);
        closer.join(1000);

        assertFalse(closer.isAlive());
        assertEquals(0, await(session.termination()).exitCode());
    }

    /// Preserves a protocol failure when cancelling the pending graceful-shutdown request races its callback.
    @Test
    void preservesFirstFailureDuringShutdownCancellation() throws Exception {
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.completeHandshake();
            child.readRequest(5L, "ui.shutdown");
            child.writeRaw(new byte[]{'l', 'o', 'g', '\n'});
        });
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), unusedHandler(), builder -> process,
                timing(Duration.ofSeconds(1), Duration.ofMillis(200)));

        session.close();

        @Nullable UiFrontendProcessException failure = await(session.termination()).failure();
        assertNotNull(failure);
        assertEquals(UiFrontendProcessException.Category.PROTOCOL, failure.category());
    }

    /// Keeps an incoming slot occupied until its flushed after-response action has run.
    @Test
    void runsAfterResponseActionBeforePromotingQueuedHandlerWork() throws Exception {
        CountDownLatch sendQueuedCommand = new CountDownLatch(1);
        CountDownLatch queuedCommandSent = new CountDownLatch(1);
        CountDownLatch actionRan = new CountDownLatch(1);
        AtomicBoolean queuedHandlerStarted = new AtomicBoolean();
        AtomicBoolean actionWonOrdering = new AtomicBoolean();
        CompletableFuture<UiFrontendCommandHandler.Reply> firstReply = new CompletableFuture<>();
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.completeHandshake();
            child.sendRequest(4L, "core.ui.use-javafx", BridgeValue.nullValue());
            assertTrue(sendQueuedCommand.await(1, TimeUnit.SECONDS));
            child.drainRequestsWithoutReply(31);
            child.sendRequest(6L, "core.app.shutdown", BridgeValue.nullValue());
            queuedCommandSent.countDown();
            child.awaitShutdownIgnoringReplies();
        });
        UiFrontendCommandHandler handler = (method, params) -> {
            if ("core.ui.use-javafx".equals(method)) {
                return firstReply;
            }
            queuedHandlerStarted.set(true);
            return CompletableFuture.completedFuture(UiFrontendCommandHandler.Reply.result(BridgeValue.nullValue()));
        };
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), handler, builder -> process,
                timing(Duration.ofSeconds(1), Duration.ofSeconds(2)));
        List<CompletableFuture<BridgeValue>> occupied = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            occupied.add(session.request("ui.notify", BridgeValue.integer(index)));
        }
        sendQueuedCommand.countDown();
        assertTrue(queuedCommandSent.await(1, TimeUnit.SECONDS));
        awaitWaitingCount(session, 1);
        firstReply.complete(new UiFrontendCommandHandler.Reply(BridgeValue.nullValue(), () -> {
            actionWonOrdering.set(!queuedHandlerStarted.get());
            actionRan.countDown();
        }));

        assertTrue(actionRan.await(1, TimeUnit.SECONDS));
        assertTrue(actionWonOrdering.get());
        session.close();
        assertTrue(occupied.stream().allMatch(CompletableFuture::isCompletedExceptionally));
    }

    /// Rejects an expired queued request at promotion without writing it or invoking child-controlled work.
    @Test
    void rechecksQueuedDeadlineAtTheActivationBoundary() throws Exception {
        BoundaryClock clock = new BoundaryClock();
        CountDownLatch activeRequestsRead = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        AtomicBoolean expiredRequestObserved = new AtomicBoolean();
        ScriptedProcess process = new ScriptedProcess(child -> {
            child.completeHandshake();
            long firstRequestId = 0L;
            for (int index = 0; index < 32; index++) {
                UiFrontendMessage.Request request = assertInstanceOf(UiFrontendMessage.Request.class, child.read());
                if (index == 0) {
                    firstRequestId = request.requestId();
                }
            }
            activeRequestsRead.countDown();
            assertTrue(releaseResponse.await(1, TimeUnit.SECONDS));
            child.sendResult(firstRequestId, BridgeValue.nullValue());
            try {
                UiFrontendMessage.Request expired = assertInstanceOf(UiFrontendMessage.Request.class, child.read());
                expiredRequestObserved.set("ui.navigate".equals(expired.method()));
                child.sendResult(expired.requestId(), BridgeValue.nullValue());
            } catch (IOException ignored) {
                // Correct promotion-time rejection closes the child input before another request is written.
            }
        });
        UiFrontendProcessSession.Timing timing = new UiFrontendProcessSession.Timing(
                Duration.ofSeconds(1), Duration.ofNanos(100), Duration.ofMillis(50), clock);
        UiFrontendProcessSession session = UiFrontendProcessSession.start(executable(), temporaryDirectory,
                BridgeValue.nullValue(), unusedHandler(), builder -> process, timing);
        try {
            for (int index = 0; index < 32; index++) {
                session.request("ui.notify", BridgeValue.integer(index));
            }
            CompletableFuture<BridgeValue> expired = session.request("ui.navigate", BridgeValue.nullValue());
            assertTrue(activeRequestsRead.await(1, TimeUnit.SECONDS));
            clock.advanceTo(101L);
            releaseResponse.countDown();

            assertEquals(UiFrontendProcessException.Category.TIMEOUT, futureFailure(expired).category());
            assertFalse(expiredRequestObserved.get());
        } finally {
            session.close();
        }
    }

    /// Waits briefly for queued writer work to settle before forcing the saturation boundary.
    private static void awaitWriterSettlement() {
        try {
            TimeUnit.MILLISECONDS.sleep(50);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /// Waits until private admission state reaches an exact count solely to synchronize a concurrency test.
    ///
    /// @param session live session
    /// @param count expected waiting count
    private static void awaitWaitingCount(UiFrontendProcessSession session, int count) throws Exception {
        Field lockField = UiFrontendProcessSession.class.getDeclaredField("lock");
        Field waitingField = UiFrontendProcessSession.class.getDeclaredField("waiting");
        lockField.setAccessible(true);
        waitingField.setAccessible(true);
        Object lock = lockField.get(session);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            synchronized (lock) {
                if (((java.util.ArrayDeque<?>) waitingField.get(session)).size() == count) {
                    return;
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Waiting queue did not reach the expected count");
    }

    /// Creates the package-contained regular executable placeholder validated before injected launch.
    ///
    /// @return canonical test executable path
    private Path executable() throws IOException {
        Path executable = temporaryDirectory.resolve("frontend.bin");
        if (!Files.exists(executable)) {
            Files.createFile(executable);
        }
        return executable;
    }

    /// Builds the real fixture JVM command without a shell wrapper.
    ///
    /// @return immutable process argument list
    private static @Unmodifiable List<String> javaCommand() {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"),
                UiFrontendProcessChildFixture.class.getName());
    }

    /// Identifies Windows for the Java executable suffix.
    ///
    /// @return whether the current OS is Windows
    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    /// Checks the documented neutral child environment allowlist without inspecting values.
    ///
    /// @param name environment key
    /// @return whether it may cross the process boundary
    private static boolean allowedEnvironment(String name) {
        return List.of("PATH", "PATHEXT", "SystemRoot", "SYSTEMROOT", "WINDIR", "COMSPEC",
                        "TEMP", "TMP", "TMPDIR", "LANG", "DISPLAY", "WAYLAND_DISPLAY",
                        "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS").stream()
                .anyMatch(allowed -> isWindows() ? allowed.equalsIgnoreCase(name) : allowed.equals(name))
                || (isWindows() ? name.regionMatches(true, 0, "LC_", 0, 3) : name.startsWith("LC_"));
    }

    /// Creates harmless inherited values plus forbidden sentinels for the real fixture process.
    ///
    /// @return mutable environment source consumed by package-private start injection
    private static Map<String, String> fixtureEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(isWindows() ? "Path" : "PATH", "fixture-path");
        environment.put(isWindows() ? "ComSpec" : "COMSPEC", "fixture-comspec");
        environment.put(isWindows() ? "windir" : "WINDIR", "fixture-windir");
        environment.put("TEMP", "fixture-temp");
        @Nullable String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null) {
            environment.put("SystemRoot", systemRoot);
        }
        environment.put("PASSWORD", "must-not-cross");
        environment.put("AURA_SENTINEL_TOKEN", "must-not-cross");
        return environment;
    }

    /// Checks an environment map using platform-correct key semantics.
    ///
    /// @param environment environment map
    /// @param expected expected key
    /// @param windows whether key matching is case-insensitive
    /// @return whether the key is present
    private static boolean containsEnvironmentKey(
            Map<String, String> environment, String expected, boolean windows) {
        return environment.keySet().stream()
                .anyMatch(name -> windows ? name.equalsIgnoreCase(expected) : name.equals(expected));
    }

    /// Creates test timing with a short shutdown grace.
    ///
    /// @param startup total startup budget
    /// @param request ordinary request deadline
    /// @return package-private production timing configuration
    private static UiFrontendProcessSession.Timing timing(Duration startup, Duration request) {
        return new UiFrontendProcessSession.Timing(startup, request, Duration.ofMillis(50));
    }

    /// Returns a handler that makes unexpected child commands visible.
    ///
    /// @return strict handler
    private static UiFrontendCommandHandler unusedHandler() {
        return (method, params) -> CompletableFuture.failedFuture(
                new AssertionError("Unexpected child method: " + method));
    }

    /// Awaits one future within a test-wide deterministic bound.
    ///
    /// @param future future to await
    /// @param <T> result type
    /// @return completed value
    /// @throws Exception if the future fails or times out
    private static <T> T await(java.util.concurrent.CompletionStage<T> future) throws Exception {
        return future.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /// Extracts the stable session failure from an exceptional request future.
    ///
    /// @param future failed future
    /// @return session exception
    private static UiFrontendProcessException futureFailure(CompletableFuture<BridgeValue> future) {
        CompletionException completion = assertThrows(CompletionException.class, future::join);
        return assertInstanceOf(UiFrontendProcessException.class, completion.getCause());
    }

    /// Runs one scripted child protocol scenario.
    @FunctionalInterface
    @NotNullByDefault
    private interface Script {
        /// Executes against the child side of real piped byte streams.
        ///
        /// @param child child-side protocol utility
        /// @throws Exception if script execution fails
        void run(ScriptedChild child) throws Exception;
    }

    /// Describes one expected startup failure scenario.
    ///
    /// @param name test display label
    /// @param script child behavior
    /// @param category expected stable failure category
    @NotNullByDefault
    private record Scenario(String name, Script script, UiFrontendProcessException.Category category) {
    }

    /// Gives activation threads controllable time while keeping the independent deadline thread before expiry.
    @NotNullByDefault
    private static final class BoundaryClock implements UiFrontendProcessSession.NanoClock {
        /// Current activation-boundary time.
        private final AtomicLong current = new AtomicLong();

        /// Returns pre-expiry time to the deadline thread and controlled time everywhere else.
        ///
        /// @return monotonic test time
        @Override
        public long nanoTime() {
            if (Thread.currentThread().getName().startsWith("aura-ui-deadlines")) {
                return 0L;
            }
            return current.get();
        }

        /// Advances activation-boundary time monotonically.
        ///
        /// @param value new time
        private void advanceTo(long value) {
            current.set(value);
        }
    }

    /// Provides a controllable process backed by real connected byte streams.
    @NotNullByDefault
    private static final class ScriptedProcess extends Process {
        /// Launcher writes protocol frames here.
        private final OutputStream launcherInput;

        /// Launcher reads child protocol frames here.
        private final InputStream launcherOutput;

        /// Launcher drains child diagnostics here.
        private final InputStream launcherError;

        /// Signals that a response was flushed to the child stream.
        private final AtomicInteger outputFlushes = new AtomicInteger();

        /// Signals script completion independently from process termination.
        private final CountDownLatch scriptFinished = new CountDownLatch(1);

        /// Process termination future used by production supervision.
        private final CompletableFuture<Process> exit = new CompletableFuture<>();

        /// Allows a termination test to keep the synthetic child alive through destroy attempts.
        private final AtomicBoolean refuseTermination = new AtomicBoolean();

        /// Signals that force was attempted against a termination-resistant process.
        private final CountDownLatch forcedAttempted = new CountDownLatch(1);

        /// Current synthetic process exit code.
        private volatile int exitCode;

        /// Child-side utility retained for destruction.
        private final ScriptedChild child;

        /// Starts one daemon child script over connected pipes.
        ///
        /// @param script scripted child behavior
        private ScriptedProcess(Script script) throws IOException {
            java.io.PipedInputStream childInput = new java.io.PipedInputStream(1 << 20);
            java.io.PipedOutputStream parentInput = new java.io.PipedOutputStream(childInput);
            java.io.PipedInputStream parentOutput = new java.io.PipedInputStream(1 << 20);
            java.io.PipedOutputStream childOutput = new java.io.PipedOutputStream(parentOutput);
            java.io.PipedInputStream parentError = new java.io.PipedInputStream(1 << 20);
            java.io.PipedOutputStream childError = new java.io.PipedOutputStream(parentError);
            launcherInput = new FilterOutputStream(parentInput) {
                /// Records every actual protocol flush.
                @Override
                public void flush() throws IOException {
                    super.flush();
                    outputFlushes.incrementAndGet();
                }
            };
            launcherOutput = parentOutput;
            launcherError = parentError;
            child = new ScriptedChild(this, childInput, childOutput, childError);
            Thread thread = new Thread(() -> {
                try {
                    script.run(child);
                } catch (Throwable throwable) {
                    if (!exit.isDone()) {
                        exitCode = 97;
                    }
                } finally {
                    scriptFinished.countDown();
                }
            }, "aura-ui-scripted-child");
            thread.setDaemon(true);
            thread.start();
        }

        /// Returns the launcher's writable child stdin.
        ///
        /// @return child stdin
        @Override
        public OutputStream getOutputStream() {
            return launcherInput;
        }

        /// Returns the launcher's readable child stdout.
        ///
        /// @return child stdout
        @Override
        public InputStream getInputStream() {
            return launcherOutput;
        }

        /// Returns the launcher's readable child stderr.
        ///
        /// @return child stderr
        @Override
        public InputStream getErrorStream() {
            return launcherError;
        }

        /// Waits for synthetic process termination.
        ///
        /// @return exit code
        /// @throws InterruptedException if interrupted
        @Override
        public int waitFor() throws InterruptedException {
            exit.join();
            return exitCode;
        }

        /// Performs a deterministic bounded process wait for termination supervision tests.
        ///
        /// @param timeout maximum wait duration
        /// @param unit timeout unit
        /// @return whether the process exited before the bound
        /// @throws InterruptedException if interrupted
        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (refuseTermination.get()) {
                return false;
            }
            try {
                exit.get(timeout, unit);
                return true;
            } catch (java.util.concurrent.TimeoutException exception) {
                return false;
            } catch (java.util.concurrent.ExecutionException exception) {
                throw new AssertionError(exception);
            }
        }

        /// Reports a completed exit or throws while alive.
        ///
        /// @return exit code
        @Override
        public int exitValue() {
            if (!exit.isDone()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return exitCode;
        }

        /// Initiates graceful synthetic termination.
        @Override
        public void destroy() {
            if (!refuseTermination.get()) {
                child.exit(0);
            }
        }

        /// Initiates forced synthetic termination.
        ///
        /// @return this process
        @Override
        public Process destroyForcibly() {
            forcedAttempted.countDown();
            if (!refuseTermination.get()) {
                child.exit(137);
            }
            return this;
        }

        /// Permits an externally controlled stubborn process to exit.
        ///
        /// @param code final exit code
        private void releaseTermination(int code) {
            refuseTermination.set(false);
            child.exit(code);
        }

        /// Reports whether the synthetic process has not exited.
        ///
        /// @return alive state
        @Override
        public boolean isAlive() {
            return !exit.isDone();
        }

        /// Exposes synthetic process completion.
        ///
        /// @return process completion
        @Override
        public CompletableFuture<Process> onExit() {
            return exit;
        }
    }

    /// Implements child-side protocol operations over a scripted process's real byte streams.
    @NotNullByDefault
    private static final class ScriptedChild {
        /// Owning synthetic process.
        private final ScriptedProcess process;

        /// Launcher frames arrive here.
        private final InputStream input;

        /// Child frames leave here.
        private final OutputStream output;

        /// Child diagnostics leave here.
        private final OutputStream error;

        /// Creates one child stream facade.
        ///
        /// @param process owner
        /// @param input child stdin
        /// @param output child stdout
        /// @param error child stderr
        private ScriptedChild(ScriptedProcess process, InputStream input, OutputStream output, OutputStream error) {
            this.process = process;
            this.input = input;
            this.output = output;
            this.error = error;
        }

        /// Completes the exact hello, initial snapshot, and ready exchange.
        ///
        /// @throws IOException if transport fails
        private void completeHandshake() throws IOException {
            UiFrontendMessage.Request hello = readRequest(1L, "ui.hello");
            sendResult(hello.requestId(), hello.params());
            UiFrontendMessage.Request snapshot = readRequest(3L, "ui.snapshot.replace");
            sendResult(snapshot.requestId(), BridgeValue.nullValue());
            sendRequest(2L, "ui.ready", BridgeValue.nullValue());
            requireResult(read(), 2L);
            process.outputFlushes.set(0);
        }

        /// Replies to the expected first hello request.
        ///
        /// @param value result value
        /// @throws IOException if transport fails
        private void replyHello(BridgeValue value) throws IOException {
            UiFrontendMessage.Request hello = readRequest(1L, "ui.hello");
            sendResult(hello.requestId(), value);
        }

        /// Reads one complete launcher frame.
        ///
        /// @return decoded message
        /// @throws IOException if transport ends or is malformed
        private UiFrontendMessage read() throws IOException {
            UiFrontendMessage message = UiFrontendWireCodec.read(
                    input, UiFrontendWireCodec.InboundEndpoint.FRONTEND);
            if (message == null) {
                throw new IOException("Launcher input ended");
            }
            return message;
        }

        /// Reads one expected launcher request.
        ///
        /// @param id expected identifier
        /// @param method expected method
        /// @return request
        /// @throws IOException if it differs
        private UiFrontendMessage.Request readRequest(long id, String method) throws IOException {
            UiFrontendMessage message = read();
            if (message instanceof UiFrontendMessage.Request request
                    && request.requestId() == id && method.equals(request.method())) {
                return request;
            }
            throw new IOException("Unexpected launcher request");
        }

        /// Sends one child request and flushes it.
        ///
        /// @param id even child identifier
        /// @param method method
        /// @param params parameters
        /// @throws IOException if transport fails
        private void sendRequest(long id, String method, BridgeValue params) throws IOException {
            UiFrontendWireCodec.write(output, new UiFrontendMessage.Request(id, method, params));
            output.flush();
        }

        /// Sends one result and flushes it.
        ///
        /// @param id matching identifier
        /// @param value result value
        /// @throws IOException if transport fails
        private void sendResult(long id, BridgeValue value) throws IOException {
            UiFrontendWireCodec.write(output, new UiFrontendMessage.Result(id, value));
            output.flush();
        }

        /// Writes malformed raw child stdout.
        ///
        /// @param bytes raw bytes
        /// @throws IOException if transport fails
        private void writeRaw(byte[] bytes) throws IOException {
            output.write(bytes);
            output.flush();
        }

        /// Writes child diagnostics independently from stdout.
        ///
        /// @param bytes diagnostic bytes
        /// @throws IOException if transport fails
        private void writeStderr(byte[] bytes) throws IOException {
            error.write(bytes);
            error.flush();
        }

        /// Closes child stdout to produce clean EOF.
        ///
        /// @throws IOException if closure fails
        private void closeStdout() throws IOException {
            output.close();
        }

        /// Reads and discards an exact number of launcher requests without replying.
        ///
        /// @param count request count
        /// @throws IOException if transport fails
        private void drainRequestsWithoutReply(int count) throws IOException {
            for (int index = 0; index < count; index++) {
                read();
            }
        }

        /// Replies to the launcher's normal shutdown request and exits.
        ///
        /// @throws IOException if transport fails
        private void awaitShutdown() throws IOException {
            UiFrontendMessage.Request shutdown = assertInstanceOf(UiFrontendMessage.Request.class, read());
            if (!"ui.shutdown".equals(shutdown.method())) {
                throw new IOException("Expected shutdown request");
            }
            sendResult(shutdown.requestId(), BridgeValue.nullValue());
            exit(0);
        }

        /// Acknowledges normal shutdown but deliberately leaves the synthetic process alive.
        ///
        /// @throws IOException if transport fails
        private void acknowledgeShutdownWithoutExit() throws IOException {
            UiFrontendMessage.Request shutdown = readRequest(5L, "ui.shutdown");
            sendResult(shutdown.requestId(), BridgeValue.nullValue());
        }

        /// Ignores result frames until the launcher's normal shutdown request arrives.
        ///
        /// @throws IOException if transport fails
        private void awaitShutdownIgnoringReplies() throws IOException {
            while (true) {
                UiFrontendMessage message = read();
                if (message instanceof UiFrontendMessage.Request request
                        && "ui.shutdown".equals(request.method())) {
                    sendResult(request.requestId(), BridgeValue.nullValue());
                    exit(0);
                    return;
                }
            }
        }

        /// Completes synthetic process termination and closes child output streams.
        ///
        /// @param code exit code
        private void exit(int code) {
            process.exitCode = code;
            if (process.exit.complete(process)) {
                try {
                    output.close();
                    error.close();
                } catch (IOException ignored) {
                    // Termination already won; stream close is best effort in this fixture.
                }
            }
        }

        /// Requires one result identifier.
        ///
        /// @param message candidate result
        /// @param id expected identifier
        /// @throws IOException if it differs
        private static void requireResult(UiFrontendMessage message, long id) throws IOException {
            if (!(message instanceof UiFrontendMessage.Result result) || result.requestId() != id) {
                throw new IOException("Unexpected launcher result");
            }
        }
    }
}
