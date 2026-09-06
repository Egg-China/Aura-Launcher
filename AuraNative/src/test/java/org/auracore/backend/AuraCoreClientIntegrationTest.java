package org.auracore.backend;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AuraCoreClientIntegrationTest {
    /// Shared data directory kept between runs so metadata caching survives.
    private static final Path DATA_DIRECTORY = resolveDataDirectory();

    /// Resolves the persistent backend data directory under the JVM temp root.
    ///
    /// @return the created directory
    private static Path resolveDataDirectory() {
        try {
            return Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "auracore-jvm-it"));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create AuraCore test data directory", failure);
        }
    }

    /// Creates a client against the real backend when the library is configured.
    ///
    /// @throws Exception when the native backend rejects the call
    @Test
    void listsInstancesThroughNativeBackend() throws Exception {
        final String override = System.getenv(AuraCoreLibraryLoader.LIBRARY_PATH_PROPERTY);
        Assumptions.assumeTrue(override != null && !override.isBlank(),
                "AuraCore native backend is not configured");
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(override)),
                "Configured AuraCore native backend does not exist");

        final AuraCoreNative nativeLibrary = AuraCoreNative.load(override);
        try (AuraCoreClient client = new AuraCoreClient(nativeLibrary, DATA_DIRECTORY)) {
            final JsonElement instances = client.listInstances().get(30, TimeUnit.SECONDS);
            assertTrue(instances.isJsonArray(), "list_instances must return a JSON array");
        }
    }

    /// Creates, waits for, and deletes a vanilla instance through the backend.
    ///
    /// @throws Exception when any native stage fails
    @Test
    void managesInstanceLifecycle() throws Exception {
        final String override = System.getenv(AuraCoreLibraryLoader.LIBRARY_PATH_PROPERTY);
        Assumptions.assumeTrue(override != null && !override.isBlank(),
                "AuraCore native backend is not configured");
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(override)),
                "Configured AuraCore native backend does not exist");

        final AuraCoreNative nativeLibrary = AuraCoreNative.load(override);
        try (AuraCoreClient client = new AuraCoreClient(nativeLibrary, DATA_DIRECTORY)) {
            final JsonElement created = client.createInstance("JVM Probe", "1.20.1", null)
                    .get(150, TimeUnit.SECONDS);
            assertTrue(created.isJsonObject(), "create_instance must return an object");
            assertTrue(created.getAsJsonObject().has("taskId"), "create_instance must return a task id");
            final String taskId = created.getAsJsonObject().get("taskId").getAsString();

            final JsonElement waited = client.waitTask(taskId, 180000).get(190, TimeUnit.SECONDS);
            assertTrue(waited.isJsonObject(), "wait_task must return an object");

            final JsonElement instances = client.listInstances().get(30, TimeUnit.SECONDS);
            assertTrue(instances.toString().contains("JVM Probe"), "created instance must be listed");

            client.renameInstance("JVM Probe", "JVM Probe 2").get(30, TimeUnit.SECONDS);
            client.deleteInstance("JVM Probe 2").get(30, TimeUnit.SECONDS);
            final JsonElement afterDelete = client.listInstances().get(30, TimeUnit.SECONDS);
            assertTrue(!afterDelete.toString().contains("JVM Probe"), "deleted instance must disappear");
        }
    }
    /// Adds, selects, and removes an offline account through the backend.
    ///
    /// @throws Exception when any native stage fails
    @Test
    void managesOfflineAccount() throws Exception {
        final String override = System.getenv(AuraCoreLibraryLoader.LIBRARY_PATH_PROPERTY);
        Assumptions.assumeTrue(override != null && !override.isBlank(),
                "AuraCore native backend is not configured");
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(override)),
                "Configured AuraCore native backend does not exist");

        final AuraCoreNative nativeLibrary = AuraCoreNative.load(override);
        try (AuraCoreClient client = new AuraCoreClient(nativeLibrary, DATA_DIRECTORY)) {
            final JsonElement added = client.addOfflineAccount("JVM Tester").get(30, TimeUnit.SECONDS);
            assertTrue(added.isJsonObject(), "add_offline_account must return an object");
            assertTrue(added.getAsJsonObject().has("profileName"), "account reply must carry the profile name");

            client.setDefaultAccount("JVM Tester").get(30, TimeUnit.SECONDS);
            final JsonElement accounts = client.listAccounts().get(30, TimeUnit.SECONDS);
            assertTrue(accounts.toString().contains("JVM Tester"), "account list must contain the profile");

            client.removeAccount("JVM Tester").get(30, TimeUnit.SECONDS);
            final JsonElement afterRemove = client.listAccounts().get(30, TimeUnit.SECONDS);
            assertTrue(!afterRemove.toString().contains("JVM Tester"), "removed account must disappear");
        }
    }
}
