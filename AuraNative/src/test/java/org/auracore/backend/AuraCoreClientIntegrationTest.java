package org.auracore.backend;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AuraCoreClientIntegrationTest {
    /// Shared data directory; deleted after the test JVM releases the backend.
    private static final Path DATA_DIRECTORY = createDataDirectory();

    /// Creates the isolated backend data directory.
    ///
    /// @return the created directory
    private static Path createDataDirectory() {
        try {
            return Files.createTempDirectory("auracore-jvm-it");
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create AuraCore test data directory", failure);
        }
    }

    /// Deletes the data directory when the test class finished.
    ///
    /// @throws IOException when tree deletion fails
    @AfterAll
    static void cleanUp() throws IOException {
        // The DLL stays loaded for the JVM lifetime on Windows, so deletion is
        // best effort and may keep the metadata skeleton on locked machines.
        try (var files = Files.walk(DATA_DIRECTORY)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort only; locked native files are acceptable.
                }
            });
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
}