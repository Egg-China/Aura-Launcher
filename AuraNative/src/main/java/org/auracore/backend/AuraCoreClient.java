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

    /// Reads one instance by identifier.
    ///
    /// @param id the instance identifier
    /// @return the parsed instance object
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> getInstance(String id) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_get_instance(handle(), id, reference);
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

    /// Starts creation of a vanilla instance.
    ///
    /// @param name the display name of the new instance
    /// @param gameVersion the Minecraft version descriptor
    /// @param group the optional group name, or null
    /// @return the parsed creation reply containing the task id
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> createInstance(String name, String gameVersion, @Nullable String group) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_create_instance(handle(), name, gameVersion, group, reference);
            return read(status, handle(), reference);
        });
    }

    /// Cancels a tracked task.
    ///
    /// @param taskId the identifier returned by a starting call
    /// @return the parsed cancel reply
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<Boolean> cancelTask(String taskId) {
        return submit(() -> nativeLibrary.auracore_cancel_task(handle(), taskId)
                == AuraCoreStatusCode.OK.value());
    }

    /// Renames an instance and moves its directory when possible.
    ///
    /// @param id the instance identifier
    /// @param newName the replacement display name
    /// @return the parsed rename reply
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> renameInstance(String id, String newName) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_rename_instance(handle(), id, newName, reference);
            return read(status, handle(), reference);
        });
    }

    /// Stops a running instance process.
    ///
    /// @param id the instance identifier
    /// @return the parsed stop reply
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> stopInstance(String id) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_stop_instance(handle(), id, reference);
            return read(status, handle(), reference);
        });
    }

    /// Deletes an instance directory with its shortcuts and group membership.
    ///
    /// @param id the instance identifier
    /// @return the parsed delete reply
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> deleteInstance(String id) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_delete_instance(handle(), id, reference);
            return read(status, handle(), reference);
        });
    }

    /// Creates and stores an offline account.
    ///
    /// @param username the offline profile name
    /// @return the parsed creation reply
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> addOfflineAccount(String username) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_add_offline_account(handle(), username, reference);
            return read(status, handle(), reference);
        });
    }

    /// Removes an account by profile name.
    ///
    /// @param profileName the stored account profile name
    /// @return the parsed removal reply
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> removeAccount(String profileName) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_remove_account(handle(), profileName, reference);
            return read(status, handle(), reference);
        });
    }

    /// Selects the account used by future launches.
    ///
    /// @param profileName the stored account profile name
    /// @return the parsed selection reply
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> setDefaultAccount(String profileName) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_set_default_account(handle(), profileName, reference);
            return read(status, handle(), reference);
        });
    }

    /// Moves an instance into a group; an empty group clears membership.
    ///
    /// @param id the instance identifier
    /// @param group the group name, or null to clear membership
    /// @return the parsed group reply
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> setInstanceGroup(String id, @Nullable String group) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_set_instance_group(handle(), id, group, reference);
            return read(status, handle(), reference);
        });
    }

    /// Sets the icon key used by launcher UIs.
    ///
    /// @param id the instance identifier
    /// @param iconKey the icon key
    /// @return the parsed icon reply
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> setInstanceIcon(String id, String iconKey) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_set_instance_icon(handle(), id, iconKey, reference);
            return read(status, handle(), reference);
        });
    }

    /// Starts a MultiMC-format zip export of an instance.
    ///
    /// @param id the instance identifier
    /// @param outputPath the destination zip path
    /// @return the parsed export reply containing the task id
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> exportInstance(String id, String outputPath) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_export_instance(handle(), id, outputPath, reference);
            return read(status, handle(), reference);
        });
    }

    /// Starts an import from a local archive path or remote URL.
    ///
    /// @param source the archive path or URL
    /// @param name the display name for the imported instance
    /// @param group the optional group name, or null
    /// @return the parsed import reply containing the task id
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> importInstance(String source, String name, @Nullable String group) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_import_instance(handle(), source, name, group, reference);
            return read(status, handle(), reference);
        });
    }

    /// Starts a Microsoft device-code login.
    ///
    /// @return the parsed reply containing the login task id
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> beginMsaLogin() {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_begin_msa_login(handle(), reference);
            return read(status, handle(), reference);
        });
    }

    /// Reads device-code login information for a task.
    ///
    /// @param taskId the login task identifier
    /// @return the parsed reply carrying the verification URL and user code
    /// @throws AuraCoreException when the native call fails
    public CompletableFuture<JsonElement> msaLoginInfo(String taskId) {
        return submit(() -> {
            final PointerByReference reference = new PointerByReference();
            final int status = nativeLibrary.auracore_msa_login_info(handle(), taskId, reference);
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
