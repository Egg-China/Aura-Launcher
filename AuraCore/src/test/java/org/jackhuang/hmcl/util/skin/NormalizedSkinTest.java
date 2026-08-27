/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.util.skin;

import javafx.scene.image.Image;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies normalized skin models distinguish wide and slim built-in fixtures.
@NotNullByDefault
public class NormalizedSkinTest {
    /// Loads one built-in skin fixture through the AuraCore resource tree.
    private static NormalizedSkin getSkin(String name, boolean slim) throws InvalidSkinException {
        String path = Paths.get(String.format("../AuraCore/src/main/resources/assets/img/skin/%s/%s.png", slim ? "slim" : "wide", name)).normalize().toAbsolutePath().toUri().toString();
        return new NormalizedSkin(new Image(path));
    }

    /// Checks every paired fixture reports its expected slim-model state.
    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    public void testIsSlim() throws Exception {
        String[] names = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};

        for (String skin : names) {
            assertTrue(getSkin(skin, true).isSlim());
            assertFalse(getSkin(skin, false).isSlim());
        }
    }
}
