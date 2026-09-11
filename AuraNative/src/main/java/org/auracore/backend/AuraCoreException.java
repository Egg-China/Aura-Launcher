package org.auracore.backend;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Reports a failed native backend call together with its ABI status.
@NotNullByDefault
public final class AuraCoreException extends RuntimeException {
    /// The ABI status returned by the failed call.
    private final AuraCoreStatusCode status;

    /// Creates an exception for a failed call.
    ///
    /// @param status the ABI status returned by the native library
    /// @param message the backend error text, or a generic description when absent
    AuraCoreException(AuraCoreStatusCode status, @Nullable String message) {
        super(message == null || message.isBlank() ? "AuraCore backend call failed: " + status : message);
        this.status = status;
    }

    /// Returns the ABI status of the failed call.
    ///
    /// @return the status code reported by the native library
    public AuraCoreStatusCode status() {
        return status;
    }
}
