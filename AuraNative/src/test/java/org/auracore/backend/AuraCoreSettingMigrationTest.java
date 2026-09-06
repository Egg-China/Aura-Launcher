package org.auracore.backend;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuraCoreSettingMigrationTest {
    @Test
    void allowListIsImmutable() {
        Map<String, String> allowList = AuraCoreSettingMigration.allowList();
        assertThrows(UnsupportedOperationException.class, () -> allowList.put("forbidden", "Nope"));
    }

    @Test
    void metaUrlOverrideIsAllowListed() {
        assertTrue(AuraCoreSettingMigration.isAllowed("download.source.meta"));
        assertEquals("MetaURLOverride", AuraCoreSettingMigration.backendKey("download.source.meta"));
    }

    @Test
    void pluginKeysAreNeverAllowListed() {
        assertFalse(AuraCoreSettingMigration.isAllowed("plugin.security"));
        assertFalse(AuraCoreSettingMigration.isAllowed("accounts.token"));
        assertNull(AuraCoreSettingMigration.backendKey("plugin.security"));
    }

    @Test
    void launcherKeysStayOrdered() {
        assertEquals("download.source.meta", AuraCoreSettingMigration.launcherKeys().get(0));
        assertThrows(UnsupportedOperationException.class, () -> AuraCoreSettingMigration.launcherKeys().add("more"));
    }
}