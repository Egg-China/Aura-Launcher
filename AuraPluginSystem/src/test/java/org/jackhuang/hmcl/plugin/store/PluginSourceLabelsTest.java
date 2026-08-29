/*
 * Copyright 2026 Aura Launcher contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jackhuang.hmcl.plugin.store;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Verifies credential-safe source labels shared by plugin-store presentation and diagnostics.
@NotNullByDefault
public final class PluginSourceLabelsTest {
    /// Keeps an ordinary local alias unchanged.
    @Test
    public void preservesOrdinaryHumanAlias() {
        PluginSource source = new PluginSource(
                "source", "https://plugins.example.test/catalog.json", "Community Plugins", true, false);

        assertEquals("Community Plugins", PluginSourceLabels.displayName(source, "Registry"));
    }

    /// Replaces hostile aliases and remote registry names with a credential-free source URL fallback.
    @Test
    public void hostileLabelsFallBackWithoutCredentialsOrParameters() {
        PluginSource source = new PluginSource(
                "source",
                "https://user:secret@plugins.example.test/catalog.json?token=secret#fragment",
                "https://user:secret@host/catalog?token=secret#fragment",
                true,
                false
        );
        String label = PluginSourceLabels.displayName(
                source,
                "https://user:secret@host/catalog?token=secret#fragment"
        );

        assertEquals("plugins.example.test", label);
        assertFalse(label.contains("secret"));
        assertFalse(label.contains("token"));
        assertFalse(label.contains("?"));
        assertFalse(label.contains("#"));
        assertFalse(label.contains(source.getUrl()));
    }

    /// Never reuses an encoded final path segment as a compact source identity.
    @Test
    public void encodedSensitivePathFallsBackToHostOnlyLabel() {
        PluginSource source = new PluginSource(
                "source",
                "https://plugins.example.test/catalog/user%3Asecret%40host%3Ftoken%3Dprivate",
                null,
                true,
                false
        );

        String label = PluginSourceLabels.displayName(source, null);

        assertEquals("plugins.example.test", label);
        assertEquals("https://plugins.example.test", PluginSourceLabels.diagnosticUrl(source.getUrl()));
        assertFalse(label.contains("secret"));
        assertFalse(label.contains("token"));
        assertFalse(label.contains("%"));
    }

    /// Never exposes arbitrary plain path tokens through compact labels or diagnostics.
    @Test
    public void plainSensitivePathFallsBackToHostOnly() {
        PluginSource source = new PluginSource(
                "source",
                "https://plugins.example.test/hooks/PlainBearerSecret123",
                null,
                true,
                false
        );

        String label = PluginSourceLabels.displayName(source, null);
        String diagnostic = PluginSourceLabels.diagnosticUrl(source.getUrl());

        assertEquals("plugins.example.test", label);
        assertEquals("https://plugins.example.test", diagnostic);
        assertFalse(label.contains("PlainBearerSecret123"));
        assertFalse(diagnostic.contains("PlainBearerSecret123"));
    }
}
