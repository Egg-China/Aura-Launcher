package org.auracore.backend;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;

/// Thread-affine Java client for one AuraCore native backend instance.
///
/// The native ABI requires every call on the thread that created the backend, so all calls are
/// marshalled through a single-thread executor owned by this client. Closing the client waits for
/// pending work and destroys the backend on that same thread.
@NotNullByDefault
public final class AuraCoreClient implements AutoCloseable {
    /// The bound native interface.
    private final AuraCoreNative nativeLibrary;

    /// The executor serializing every native call.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        final Thread thread = new Thread(task, "AuraCore-backend");
        thread.setDaemon(true);
        return thread;
    });

    /// The opaque backend handle, null once closed.
    private volatile @Nullable Pointer backend;

    /// Creates a backend bound to `dataDirectory`.
    ///
    /// @param nativeLibrary the loaded native interface
    /// @param dataDirectory the writable backend data directory
    /// @throws AuraCoreException when backend creation fails
    public AuraCoreClient(AuraCoreNative nativeLibrary, Path dataDirectory) {
        this.nativeLibrary = nativeLibrary;
        rawSubmit(() -> {
            try {
                Files.createDirectories(dataDirectory);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_backend_create(dataDirectory.toAbsolutePath().toString(), reference);
            final Pointer created = reference.getValue();
            if (status != AuraCoreStatusCode.OK.value()) {
                throw new AuraCoreException(AuraCoreStatusCode.fromValue(status),
                        created == null ? null : nativeLibrary.auracore_last_error(created));
            }
            backend = created;
            return null;
        });
    }

    /// Lists all discovered instances.
    ///
    /// @return the parsed instance array
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> listInstances() {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_list_instances(handle(), reference);
            return read(status, handle(), reference);
        });
    }

    /// Reads one tracked task's status snapshot.
    ///
    /// @param taskId the identifier returned by a starting call
    /// @return the parsed status object
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> taskStatus(String taskId) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_task_status(handle(), taskId, reference);
            return read(status, handle(), reference);
        });
    }

    /// Pumps the core event loop until a task finishes or the timeout elapses.
    ///
    /// @param taskId the identifier returned by a starting call
    /// @param timeoutMs the maximum wait duration in milliseconds
    /// @return the parsed final status snapshot
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> waitTask(String taskId, int timeoutMs) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_wait_task(handle(), taskId, timeoutMs, reference);
            return read(status, handle(), reference);
        });
    }

    /// Starts the full launch chain for an instance.
    ///
    /// @param id the instance identifier
    /// @param accountProfile the account profile name, or null for the default account
    /// @param offlineName the player name for accountless launches, or null
    /// @return the parsed launch reply containing the task id
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> launchInstance(String id, @Nullable String accountProfile, @Nullable String offlineName) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_launch_instance(handle(), id, accountProfile, offlineName, reference);
            return read(status, handle(), reference);
        });
    }

    /// Reads the newest launch log lines of an instance.
    ///
    /// @param id the instance identifier
    /// @param maxLines the maximum number of lines, or a non-positive value for the default
    /// @return the parsed log object
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> readInstanceLogs(String id, int maxLines) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_read_instance_logs(handle(), id, maxLines, reference);
            return read(status, handle(), reference);
        });
    }

    /// Lists stored accounts.
    ///
    /// @return the parsed account array
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> listAccounts() {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_list_accounts(handle(), reference);
            return read(status, handle(), reference);
        });
    }

    /// Reads one registered core setting.
    ///
    /// @param key the setting key
    /// @return the parsed `{ key, value }` object
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> getSetting(String key) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_get_setting(handle(), key, reference);
            return read(status, handle(), reference);
        });
    }

    /// Writes one registered core setting.
    ///
    /// @param key the setting key
    /// @param value the JSON value to store
    /// @return the parsed `{ key, value }` reply
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> setSetting(String key, JsonElement value) {
        final String payload = "{\"value\":" + value + "}";
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_set_setting(handle(), key, payload, reference);
            return read(status, handle(), reference);
        });
    }

    /// Destroys the backend after pending work completes.
    @Override
    public void close() {
        final Pointer current = backend;
        backend = null;
        if (current != null) {
            rawSubmit(() -> {
                nativeLibrary.auracore_backend_destroy(current);
                return null;
            });
        }
        executor.shutdown();
    }

    /// Runs a unit of work on the backend thread without the lifecycle guard.
    private <T> CompletableFuture<T> rawSubmit(Callable<T> work) {
        return schedule(work);
    }

    /// Runs a guarded unit of work on the backend thread.
    private <T> CompletableFuture<T> submit(Callable<T> work) {
        return schedule(() -> {
            if (backend == null) {
                throw new AuraCoreException(AuraCoreStatusCode.BACKEND, "AuraCore client is closed");
            }
            return work.call();
        });
    }

    /// Bridges a callable onto the backend executor while exposing completion callbacks.
    private <T> CompletableFuture<T> schedule(Callable<T> work) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /// Returns the live backend handle.
    private Pointer handle() {
        final Pointer current = backend;
        if (current == null) {
            throw new AuraCoreException(AuraCoreStatusCode.BACKEND, "AuraCore client is closed");
        }
        return current;
    }

    /// Validates a status code and reports the backend's last error on failure.
    private void check(int status, Pointer backendHandle) {
        if (status != AuraCoreStatusCode.OK.value()) {
            throw new AuraCoreException(AuraCoreStatusCode.fromValue(status), nativeLibrary.auracore_last_error(backendHandle));
        }
    }

    /// Reads a JSON reply, validating its status and freeing the native buffer.
    private JsonElement read(int status, Pointer backendHandle, PointerByReference reference) {
        check(status, backendHandle);
        final Pointer text = reference.getValue();
        try {
            return JsonParser.parseString(text.getString(0, StandardCharsets.UTF_8.name()));
        } finally {
            nativeLibrary.auracore_free(text);
        }
    }
}