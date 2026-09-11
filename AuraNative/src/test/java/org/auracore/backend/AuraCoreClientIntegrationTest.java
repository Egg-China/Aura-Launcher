package org.auracore.backend;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

            final Path exportPath = DATA_DIRECTORY.resolve("jvm-probe-export.zip");
            final JsonElement exported = client.exportInstance("JVM Probe 2", exportPath.toString())
                    .get(30, TimeUnit.SECONDS);
            assertTrue(exported.getAsJsonObject().has("taskId"), "export must return a task id");
            final String exportTask = exported.getAsJsonObject().get("taskId").getAsString();
            client.waitTask(exportTask, 60000).get(70, TimeUnit.SECONDS);
            assertTrue(Files.isRegularFile(exportPath), "export must write the archive");

            client.deleteInstance("JVM Probe 2").get(30, TimeUnit.SECONDS);
            final JsonElement imported = client.importInstance(exportPath.toString(), "JVM Probe Reborn", null)
                    .get(150, TimeUnit.SECONDS);
            assertTrue(imported.getAsJsonObject().has("taskId"), "import must return a task id");
            final String importTask = imported.getAsJsonObject().get("taskId").getAsString();
            client.waitTask(importTask, 180000).get(190, TimeUnit.SECONDS);
            final JsonElement afterImport = client.listInstances().get(30, TimeUnit.SECONDS);
            assertTrue(afterImport.toString().contains("JVM Probe Reborn"), "imported instance must be listed");

            client.deleteInstance("JVM Probe Reborn").get(30, TimeUnit.SECONDS);
            final JsonElement afterDelete = client.listInstances().get(30, TimeUnit.SECONDS);
            assertTrue(!afterDelete.toString().contains("JVM Probe"), "deleted instances must disappear");
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

            client.addOfflineAccount("JVM Second").get(30, TimeUnit.SECONDS);
            client.setDefaultAccount("JVM Second").get(30, TimeUnit.SECONDS);
            final JsonElement accounts = client.listAccounts().get(30, TimeUnit.SECONDS);
            assertTrue(accounts.isJsonArray(), "list_accounts must return a JSON array");
            boolean firstIsDefault = false;
            boolean secondIsDefault = false;
            for (JsonElement entry : accounts.getAsJsonArray()) {
                assertTrue(entry.isJsonObject(), "account entries must be objects");
                final JsonObject account = entry.getAsJsonObject();
                assertTrue(account.has("isDefault"), "account entries must carry the default marker");
                if ("JVM Tester".equals(account.get("profileName").getAsString())) {
                    firstIsDefault = account.get("isDefault").getAsBoolean();
                } else if ("JVM Second".equals(account.get("profileName").getAsString())) {
                    secondIsDefault = account.get("isDefault").getAsBoolean();
                }
            }
            assertFalse(firstIsDefault, "the non-default account must not be flagged");
            assertTrue(secondIsDefault, "the default account must be flagged");

            client.removeAccount("JVM Tester").get(30, TimeUnit.SECONDS);
            client.removeAccount("JVM Second").get(30, TimeUnit.SECONDS);
            final JsonElement afterRemove = client.listAccounts().get(30, TimeUnit.SECONDS);
            assertTrue(!afterRemove.toString().contains("JVM Tester"), "removed account must disappear");
        }
    }
}
