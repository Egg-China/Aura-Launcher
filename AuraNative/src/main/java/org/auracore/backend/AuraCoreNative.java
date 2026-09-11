package org.auracore.backend;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/// Raw JNA mapping of the AuraCore native backend C ABI.
///
/// All strings cross the boundary as UTF-8 and every JSON reply is returned through a
/// [PointerByReference] that callers must release with [AuraCoreNative#free]. Hosts must keep
/// the backing Qt event loop on one thread; [AuraCoreClient] owns that discipline.
@NotNullByDefault
public interface AuraCoreNative extends Library {
    /// Loads the native library from an absolute path with UTF-8 string mapping.
    ///
    /// @param libraryPath absolute path of `auracore_backend` shared library
    /// @return the bound native interface
    static AuraCoreNative load(String libraryPath) {
        return Native.load(libraryPath, AuraCoreNative.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
    }

    /// Creates a backend bound to a writable data directory.
    ///
    /// @param dataPath the backend data directory, created when missing
    /// @param outBackend receives the opaque backend handle
    /// @return the ABI status of the call
    int auracore_backend_create(String dataPath, PointerByReference outBackend);

    /// Destroys a backend created by [AuraCoreNative#backend_create].
    ///
    /// @param backend the backend handle
    void auracore_backend_destroy(Pointer backend);

    /// Returns the last error text recorded on the backend.
    ///
    /// @param backend the backend handle
    /// @return the error text, or an empty string when no failure was recorded
    String auracore_last_error(Pointer backend);

    /// Lists all discovered instances as a JSON array.
    int auracore_list_instances(Pointer backend, PointerByReference outJson);

    /// Reads one instance by identifier as a JSON object.
    int auracore_get_instance(Pointer backend, String id, PointerByReference outJson);

    /// Lists detected Java candidates as a JSON array.
    int auracore_detect_java(Pointer backend, PointerByReference outJson);

    /// Lists registered component lists as a JSON object.
    int auracore_list_component_lists(Pointer backend, PointerByReference outJson);

    /// Lists one component's versions as a JSON object.
    int auracore_list_component_versions(Pointer backend, String uid, PointerByReference outJson);

    /// Probes detected Java candidates through JavaCheck as a JSON array.
    int auracore_probe_java(Pointer backend, PointerByReference outJson);

    /// Refreshes the global metadata index online.
    int auracore_refresh_metadata(Pointer backend, PointerByReference outJson);

    /// Refreshes one component's version list online.
    int auracore_refresh_component(Pointer backend, String uid, PointerByReference outJson);

    /// Starts vanilla instance creation and returns a task id.
    int auracore_create_instance(Pointer backend, String name, String gameVersion, @Nullable String group, PointerByReference outJson);

    /// Reads one tracked task's status snapshot.
    int auracore_task_status(Pointer backend, String taskId, PointerByReference outJson);

    /// Pumps the core event loop until a task finishes or the timeout elapses.
    int auracore_wait_task(Pointer backend, String taskId, int timeoutMs, PointerByReference outJson);

    /// Requests cancellation of a tracked task.
    int auracore_cancel_task(Pointer backend, String taskId);

    /// Renames an instance, moving its directory when possible.
    int auracore_rename_instance(Pointer backend, String id, String newName, PointerByReference outJson);

    /// Moves an instance into a group; an empty group clears membership.
    int auracore_set_instance_group(Pointer backend, String id, @Nullable String group, PointerByReference outJson);

    /// Sets the icon key used by launcher UIs.
    int auracore_set_instance_icon(Pointer backend, String id, String iconKey, PointerByReference outJson);

    /// Deletes an instance directory with its shortcuts and group membership.
    int auracore_delete_instance(Pointer backend, String id, PointerByReference outJson);

    /// Starts a MultiMC-format zip export of an instance.
    int auracore_export_instance(Pointer backend, String id, String outputPath, PointerByReference outJson);

    /// Starts an import from a local archive path or remote URL.
    int auracore_import_instance(Pointer backend, String source, String name, @Nullable String group, PointerByReference outJson);

    /// Lists stored accounts as a JSON array.
    int auracore_list_accounts(Pointer backend, PointerByReference outJson);

    /// Creates and stores an offline profile.
    int auracore_add_offline_account(Pointer backend, String username, PointerByReference outJson);

    /// Removes an account by profile name.
    int auracore_remove_account(Pointer backend, String profileName, PointerByReference outJson);

    /// Selects the account used by future launches.
    int auracore_set_default_account(Pointer backend, String profileName, PointerByReference outJson);

    /// Starts a Microsoft device-code login.
    int auracore_begin_msa_login(Pointer backend, PointerByReference outJson);

    /// Reads device-code login information for a task.
    int auracore_msa_login_info(Pointer backend, String taskId, PointerByReference outJson);

    /// Starts the full launch chain for an instance.
    int auracore_launch_instance(Pointer backend, String id, @Nullable String accountProfile, @Nullable String offlineName, PointerByReference outJson);

    /// Asks a running instance process to terminate.
    int auracore_stop_instance(Pointer backend, String id, PointerByReference outJson);

    /// Reads the newest launch log lines of an instance.
    int auracore_read_instance_logs(Pointer backend, String id, int maxLines, PointerByReference outJson);

    /// Reads one registered core setting.
    int auracore_get_setting(Pointer backend, String key, PointerByReference outJson);

    /// Writes one registered core setting.
    int auracore_set_setting(Pointer backend, String key, String jsonValue, PointerByReference outJson);

    /// Frees a string produced by any query above.
    ///
    /// @param text the pointer returned through an output reference
    void auracore_free(Pointer text);
}
