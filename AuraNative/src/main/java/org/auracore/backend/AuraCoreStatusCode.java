package org.auracore.backend;

import org.jetbrains.annotations.NotNullByDefault;

/// Mirrors the `auracore_status` values of the native backend C ABI.
///
/// The numeric values must stay in sync with `corebackend/include/auracore/backend.h`.
@NotNullByDefault
public enum AuraCoreStatusCode {
    /// The call completed and produced its result.
    OK(0),

    /// A caller supplied a null or malformed argument.
    INVALID_ARGUMENT(1),

    /// The backend rejected the operation or hit an internal failure.
    BACKEND(2),

    /// The backend failed to allocate memory for the reply.
    OUT_OF_MEMORY(3);

    /// The wire value used by the C ABI.
    private final int value;

    /// Creates a status code from its wire value.
    ///
    /// @param value the numeric value used by the C ABI
    AuraCoreStatusCode(int value) {
        this.value = value;
    }

    /// Returns the wire value of this status.
    ///
    /// @return the numeric value used by the C ABI
    public int value() {
        return value;
    }

    /// Resolves a wire value into a status code.
    ///
    /// @param value the numeric status returned by the native library
    /// @return the matching status code
    /// @throws IllegalArgumentException when the value is not part of the ABI
    public static AuraCoreStatusCode fromValue(int value) {
        for (AuraCoreStatusCode status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown AuraCore status value: " + value);
    }
}