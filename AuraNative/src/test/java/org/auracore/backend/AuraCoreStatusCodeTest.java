package org.auracore.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuraCoreStatusCodeTest {
    @ParameterizedTest
    @CsvSource({
            "0, OK",
            "1, INVALID_ARGUMENT",
            "2, BACKEND",
            "3, OUT_OF_MEMORY",
    })
    void resolvesWireValues(int value, AuraCoreStatusCode expected) {
        assertEquals(expected, AuraCoreStatusCode.fromValue(value));
        assertEquals(value, expected.value());
    }

    @Test
    void rejectsUnknownValues() {
        assertThrows(IllegalArgumentException.class, () -> AuraCoreStatusCode.fromValue(99));
    }
}
