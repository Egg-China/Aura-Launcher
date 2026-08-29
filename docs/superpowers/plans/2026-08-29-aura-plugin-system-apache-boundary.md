# Aura Plugin System Apache License Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the user-owned Aura plugin system into a top-level Apache-2.0 source directory while preserving the GPL launcher, package compatibility, runtime behavior, and distributable artifact identity.

**Architecture:** Keep `AuraPluginSystem` in the existing `AuraLauncher` Java compilation through additional source-set roots, because current plugin and launcher code have bidirectional dependencies. The root GPL license remains the default; `AuraPluginSystem/LICENSE` overrides it for that directory, and dedicated Checkstyle tasks enforce Apache headers without weakening GPL checks elsewhere.

**Tech Stack:** Java 17, Gradle Kotlin DSL, JUnit 5, Checkstyle, Shadow JAR, PowerShell 5.1-compatible verification

**Spec:** `docs/superpowers/specs/2026-08-29-aura-plugin-system-apache-boundary-design.md`

## Global Constraints

- Preserve all `org.jackhuang.hmcl` and `dev.hmclce` compatibility identifiers.
- Keep every embedded version suffixed with `-next` exactly once.
- Keep the distributable name `Aura-Launcher-<version>.jar` and its matching `Implementation-Version`.
- Do not modify `schema-v4` or any external Runtime Host repository in this change.
- Keep inherited launcher files and launcher integration points under their existing GPL notices.
- License only user-confirmed Aura plugin-system files under Apache License 2.0.
- Use `Copyright 2026 Aura Launcher contributors` in Apache notices.
- Preserve `@NotNullByDefault`, explicit `@Nullable`, immutability annotations, and `///` documentation requirements in every touched Java file.
- Preserve the current uncommitted bilingual `README.md` and `docs/assets/aura-launcher.png`; finish and commit them with the license documentation task.
- Use ordinary commits and pushes only; never rewrite pushed history or force push.

## File Map

- `LICENSE`: unchanged GPL repository-default license.
- `AuraPluginSystem/LICENSE`: canonical, unmodified Apache License 2.0 text.
- `AuraPluginSystem/NOTICE`: plugin-system copyright notice.
- `AuraPluginSystem/README.md`: directory scope, reuse terms, and combined-distribution explanation.
- `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/**`: plugin core, Bridge, loaders, Protector, Runtime, Store, and Trust code.
- `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/ui/main/Plugin*.java`: eight standalone plugin UI files verified as user-owned.
- `AuraPluginSystem/src/main/resources/META-INF/services/org.spongepowered.asm.service.*`: plugin Mixin service registrations.
- `AuraPluginSystem/src/test/**`: plugin core and UI tests plus plugin-specific resources.
- `AuraPluginSystem/docs/`: the four live plugin architecture/contract documents.
- `AuraLauncher/build.gradle.kts`: adds source roots and separate GPL/Apache Checkstyle tasks.
- `config/checkstyle/license-header-apache.txt`: exact Apache Java header pattern.
- `AuraLauncher/src/test/java/org/jackhuang/hmcl/LicenseBoundaryTest.java`: repository-level license boundary regression test; remains GPL as a launcher integration test.
- `README.md`: bilingual PCL-style license list and updated plugin-document links.

---

### Task 1: Establish The Apache Directory Contract

**Files:**
- Create: `AuraPluginSystem/LICENSE`
- Create: `AuraPluginSystem/NOTICE`
- Create: `AuraPluginSystem/README.md`
- Create: `AuraLauncher/src/test/java/org/jackhuang/hmcl/LicenseBoundaryTest.java`

**Interfaces:**
- Consumes: Root `LICENSE` as the GPL default and the approved licensing spec.
- Produces: `LicenseBoundaryTest.repositoryRoot()` and a canonical Apache directory contract used by later tasks.

- [ ] **Step 1: Capture the pre-move plugin JAR entry baseline**

Run:

```powershell
.\gradlew.bat :AuraLauncher:shadowJar --no-daemon --stacktrace
$jar = Get-ChildItem 'AuraLauncher\build\libs\Aura-Launcher-*.jar' |
    Where-Object { $_.Name -notmatch '\.(?:sha1|sha256|sha512)$' } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $entries = $zip.Entries.FullName |
        Where-Object { $_ -like 'org/jackhuang/hmcl/plugin/*' -or $_ -like 'META-INF/services/org.spongepowered.asm.service.*' } |
        Sort-Object
    New-Item -ItemType Directory -Force 'build\license-baseline' | Out-Null
    [IO.File]::WriteAllLines(
        (Join-Path (Resolve-Path 'build\license-baseline') 'plugin-entries.txt'),
        $entries,
        [Text.UTF8Encoding]::new($false))
} finally {
    $zip.Dispose()
}
```

Expected: `build/license-baseline/plugin-entries.txt` is non-empty and remains untracked.

- [ ] **Step 2: Write the failing directory-license test**

Create a GPL-headered, fully documented `LicenseBoundaryTest` with this behavior:

```java
/// Verifies the repository's directory-specific license policy.
@NotNullByDefault
public final class LicenseBoundaryTest {
    /// Expected Apache license directory.
    private static final Path PLUGIN_SYSTEM_ROOT = repositoryRoot().resolve("AuraPluginSystem");

    /// Locates the repository root from Gradle or an IDE working directory.
    private static Path repositoryRoot() {
        @Nullable Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate Aura Launcher repository root");
    }

    /// Requires the plugin-system directory to carry its own canonical license files.
    @Test
    public void pluginSystemDeclaresApacheLicenseBoundary() throws IOException {
        Path license = PLUGIN_SYSTEM_ROOT.resolve("LICENSE");
        Path notice = PLUGIN_SYSTEM_ROOT.resolve("NOTICE");
        Path readme = PLUGIN_SYSTEM_ROOT.resolve("README.md");

        assertTrue(Files.isRegularFile(license));
        assertTrue(Files.readString(license).startsWith("                                 Apache License\n"));
        assertTrue(Files.readString(license).contains("Version 2.0, January 2004"));
        assertTrue(Files.readString(notice).contains("Copyright 2026 Aura Launcher contributors"));
        assertTrue(Files.readString(readme).contains("Apache License 2.0"));
        assertTrue(Files.readString(readme).contains("combined Aura Launcher distribution remains GPL"));
    }
}
```

- [ ] **Step 3: Run the test to verify RED**

Run:

```powershell
.\gradlew.bat :AuraLauncher:test --tests "org.jackhuang.hmcl.LicenseBoundaryTest" --no-daemon --stacktrace
```

Expected: FAIL because `AuraPluginSystem/LICENSE` does not exist.

- [ ] **Step 4: Add the canonical license files**

Create `AuraPluginSystem/LICENSE` from the unmodified Apache License 2.0 text. Verify it against the reference bytes:

```text
SHA-256: 449f6e5e053b2e3c419553ef0625fc33b89f0d2c22598e81804fa4ff4d70719b
Size: 11513 bytes (LF line endings)
```

Create `AuraPluginSystem/NOTICE` exactly as:

```text
Aura Plugin System
Copyright 2026 Aura Launcher contributors

This product includes software developed by Aura Launcher contributors.
```

Create `AuraPluginSystem/README.md` with these exact legal points:

```markdown
# Aura Plugin System

This directory contains the Aura-authored plugin system used by Aura Launcher.

The files in this directory are licensed under the [Apache License 2.0](LICENSE), unless a file states otherwise. When these files are compiled and distributed as part of Aura Launcher, the combined Aura Launcher distribution remains GPL-covered because it also contains GPL launcher code.

Java package names and protocol identifiers retain their compatibility spellings and do not change the product name, Aura Launcher.
```

- [ ] **Step 5: Run the test to verify GREEN**

Run the focused `LicenseBoundaryTest` command from Step 3.

Expected: PASS.

- [ ] **Step 6: Commit the directory contract**

```powershell
git add -- AuraPluginSystem AuraLauncher/src/test/java/org/jackhuang/hmcl/LicenseBoundaryTest.java
git commit -m "docs: establish Apache plugin license boundary"
```

### Task 2: Compile And Check One Apache-Licensed Plugin Source

**Files:**
- Create: `config/checkstyle/license-header-apache.txt`
- Modify: `AuraLauncher/build.gradle.kts`
- Move: `AuraLauncher/src/main/java/org/jackhuang/hmcl/plugin/PluginKind.java` to `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/PluginKind.java`
- Modify: `AuraLauncher/src/test/java/org/jackhuang/hmcl/LicenseBoundaryTest.java`

**Interfaces:**
- Consumes: `AuraPluginSystem` directory contract from Task 1.
- Produces: Additional main/test/resource source roots plus `checkstylePluginMain` and `checkstylePluginTest` tasks.

- [ ] **Step 1: Extend the boundary test and verify RED**

Add a documented test that walks `AuraPluginSystem/src/main/java`, requires at least one `.java` file, requires the Apache marker, and rejects the GPL marker:

```java
/// Requires at least one plugin source and enforces its Apache-only header.
@Test
public void pluginSourcesUseOnlyApacheHeaders() throws IOException {
    Path sourceRoot = PLUGIN_SYSTEM_ROOT.resolve("src/main/java");
    try (Stream<Path> files = Files.walk(sourceRoot)) {
        @Unmodifiable List<Path> javaFiles = files
                .filter(path -> path.toString().endsWith(".java"))
                .toList();
        assertFalse(javaFiles.isEmpty());
        for (Path file : javaFiles) {
            String source = Files.readString(file);
            assertTrue(source.contains("Licensed under the Apache License, Version 2.0"), file.toString());
            assertFalse(source.contains("GNU General Public License"), file.toString());
        }
    }
}
```

Run the focused test. Expected: FAIL because the Apache source root is empty.

- [ ] **Step 2: Add the exact Apache Checkstyle header**

Create `config/checkstyle/license-header-apache.txt` with regex lines matching this header:

```java
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
```

- [ ] **Step 3: Add source roots and dedicated Checkstyle tasks**

Import `org.gradle.api.file.Directory`, `org.gradle.api.file.FileCollection`, and
`org.gradle.api.plugins.quality.Checkstyle`, then add:

```kotlin
val pluginSystemDirectory = rootProject.layout.projectDirectory.dir("AuraPluginSystem")

sourceSets {
    main {
        java.srcDir(pluginSystemDirectory.dir("src/main/java"))
        resources.srcDir(pluginSystemDirectory.dir("src/main/resources"))
    }
    test {
        java.srcDir(pluginSystemDirectory.dir("src/test/java"))
        resources.srcDir(pluginSystemDirectory.dir("src/test/resources"))
    }
}

tasks.checkstyleMain {
    source = fileTree("src/main/java") {
        exclude("**/org/jackhuang/hmcl/ui/image/apng/**")
    }
}
tasks.checkstyleTest {
    source = fileTree("src/test/java")
}

fun Checkstyle.configurePluginSystemCheckstyle(sourceDirectory: Directory, sourceClasspath: FileCollection) {
    source = fileTree(sourceDirectory) { include("**/*.java") }
    classpath = sourceClasspath
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    setConfigProperties("licenseHeaderFile" to rootProject.file("config/checkstyle/license-header-apache.txt"))
}

val checkstylePluginMain = tasks.register<Checkstyle>("checkstylePluginMain") {
    configurePluginSystemCheckstyle(
        pluginSystemDirectory.dir("src/main/java"),
        sourceSets.main.get().compileClasspath)
}
val checkstylePluginTest = tasks.register<Checkstyle>("checkstylePluginTest") {
    configurePluginSystemCheckstyle(
        pluginSystemDirectory.dir("src/test/java"),
        sourceSets.test.get().compileClasspath)
}
tasks.named("checkstyle") {
    dependsOn(checkstylePluginMain, checkstylePluginTest)
}
```

Keep the existing APNG exclusion exactly as shown.

- [ ] **Step 4: Move and relicense the canary file**

Move `PluginKind.java` to the new source root with `Move-Item -LiteralPath`. Replace only its leading GPL comment with the exact Apache header from Step 2. Do not change its package, imports, declaration, documentation, or line endings.

- [ ] **Step 5: Run focused build checks**

```powershell
.\gradlew.bat :AuraLauncher:compileJava :AuraLauncher:checkstyleMain :AuraLauncher:checkstylePluginMain :AuraLauncher:test --tests "org.jackhuang.hmcl.LicenseBoundaryTest" --no-daemon --stacktrace
```

Expected: all tasks PASS; `PluginKind.class` is compiled from `AuraPluginSystem`.

- [ ] **Step 6: Commit the build boundary**

```powershell
git add -- config/checkstyle/license-header-apache.txt AuraLauncher/build.gradle.kts AuraLauncher/src/test/java/org/jackhuang/hmcl/LicenseBoundaryTest.java AuraLauncher/src/main/java/org/jackhuang/hmcl/plugin/PluginKind.java AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/PluginKind.java
git commit -m "build: compile Apache plugin source tree"
```

### Task 3: Move The Plugin Core And Tests

**Files:**
- Move: remaining `AuraLauncher/src/main/java/org/jackhuang/hmcl/plugin/**` to `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/**`
- Move: `AuraLauncher/src/test/java/org/jackhuang/hmcl/plugin/**` to `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/**`
- Modify: all moved Java headers only
- Modify: `AuraLauncher/src/test/java/org/jackhuang/hmcl/LicenseBoundaryTest.java`

**Interfaces:**
- Consumes: Additional source roots and Apache Checkstyle tasks from Task 2.
- Produces: 151 Apache production files and 81 Apache test files under the new directory.

- [ ] **Step 1: Strengthen the count assertions and verify RED**

Refactor the header scan into a documented helper `assertApacheJavaTree(Path sourceRoot, long minimumFiles)` and require at least 151 production and 81 test files. Add a documented test that scans `AuraLauncher/src/**.java` and rejects the Apache marker. Run `LicenseBoundaryTest`.

Expected: FAIL because only the canary production file has moved.

- [ ] **Step 2: Validate move targets before moving**

Resolve all source and destination paths and assert that they remain under the repository root:

```powershell
$repo = (Resolve-Path '.').Path
$paths = @(
    'AuraLauncher\src\main\java\org\jackhuang\hmcl\plugin',
    'AuraLauncher\src\test\java\org\jackhuang\hmcl\plugin',
    'AuraPluginSystem\src\main\java\org\jackhuang\hmcl\plugin',
    'AuraPluginSystem\src\test\java\org\jackhuang\hmcl\plugin'
)
foreach ($path in $paths) {
    $resolved = [IO.Path]::GetFullPath((Join-Path $repo $path))
    if (-not $resolved.StartsWith($repo + [IO.Path]::DirectorySeparatorChar)) {
        throw "Path escapes repository: $resolved"
    }
}
```

- [ ] **Step 3: Move the remaining sources in the same PowerShell process**

Create the test destination directory. Move each child from the old production package into the existing production destination, then move the complete test package contents:

```powershell
$mainSource = 'AuraLauncher\src\main\java\org\jackhuang\hmcl\plugin'
$mainDestination = 'AuraPluginSystem\src\main\java\org\jackhuang\hmcl\plugin'
Get-ChildItem -LiteralPath $mainSource | ForEach-Object {
    Move-Item -LiteralPath $_.FullName -Destination $mainDestination
}

$testSource = 'AuraLauncher\src\test\java\org\jackhuang\hmcl\plugin'
$testParent = 'AuraPluginSystem\src\test\java\org\jackhuang\hmcl'
New-Item -ItemType Directory -Force -Path $testParent | Out-Null
Move-Item -LiteralPath $testSource -Destination (Join-Path $testParent 'plugin')
```

Do not delete recursively and do not modify package names.

- [ ] **Step 4: Mechanically replace only owned GPL headers**

For every moved `.java` file:

1. Read UTF-8 without changing the body.
2. If the first comment already contains the Apache marker, leave it unchanged.
3. Otherwise require the first comment to contain `GNU General Public License`; abort on any other header.
4. Detect CRLF versus LF from the file.
5. Replace bytes from the opening `/*` through the first `*/` with the exact Apache header.
6. Write UTF-8 without BOM and preserve the detected newline style.

Run a diff audit that rejects any moved file whose non-header body differs from its previous path.

- [ ] **Step 5: Run core license and behavior tests**

```powershell
.\gradlew.bat checkstyle :AuraLauncher:test --tests "org.jackhuang.hmcl.LicenseBoundaryTest" --tests "org.jackhuang.hmcl.plugin.*" --no-daemon --stacktrace
```

Expected: both Checkstyle profiles PASS; all plugin tests PASS; old plugin source directories contain no tracked files.

- [ ] **Step 6: Commit the core move**

```powershell
git add -A -- AuraLauncher/src/main/java/org/jackhuang/hmcl/plugin AuraLauncher/src/test/java/org/jackhuang/hmcl/plugin AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin AuraLauncher/src/test/java/org/jackhuang/hmcl/LicenseBoundaryTest.java
git commit -m "refactor: separate Apache plugin core sources"
```

### Task 4: Move Verified Plugin UI And Service Resources

**Files:**
- Move: eight `AuraLauncher/src/main/java/org/jackhuang/hmcl/ui/main/Plugin*.java` files listed below
- Move: five corresponding UI tests listed below
- Move: two Mixin service registration files
- Move: `AuraLauncher/src/test/resources/mixins.dev.hmclce.test.host-classpath.json`
- Modify: moved Java headers and `LicenseBoundaryTest`

**Interfaces:**
- Consumes: Compiling Apache source and resource roots.
- Produces: Complete standalone plugin UI and Mixin bootstrap resources under the Apache boundary.

- [ ] **Step 1: Audit the exact ownership list**

Compare each file with local source history and confirm no inherited or third-party body. The approved production list is:

```text
PluginDialogs.java
PluginManagementPage.java
PluginPermissionManagementPage.java
PluginPermissionPane.java
PluginPermissionRequest.java
PluginRecoveryPage.java
PluginSourceManagementPage.java
PluginStorePage.java
```

The approved test list is:

```text
PluginInstallPlanPresentationTest.java
PluginManagementPageTest.java
PluginRecoveryPageTest.java
PluginSourceManagementPageTest.java
PluginStorePageTest.java
```

If any body is not fully user-owned, leave that file in `AuraLauncher` under GPL and lower the corresponding count expectation. Do not infer ownership from commit authorship alone.

- [ ] **Step 2: Raise count expectations and verify RED**

Update `LicenseBoundaryTest` to require at least 159 Apache production Java files and 86 Apache test Java files. Run the focused test.

Expected: FAIL before the UI files move.

- [ ] **Step 3: Move and relicense the exact Java files**

Move the listed files to the same package paths under `AuraPluginSystem/src/main/java` and `src/test/java`. Apply the Apache header algorithm from Task 3 without changing file bodies.

- [ ] **Step 4: Move only plugin-owned service resources**

Move:

```text
META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService
META-INF/services/org.spongepowered.asm.service.IMixinService
mixins.dev.hmclce.test.host-classpath.json
```

Keep `META-INF/services/java.net.spi.URLStreamHandlerProvider` in `AuraLauncher`; it registers a non-plugin launcher utility.

- [ ] **Step 5: Run UI, Mixin, and resource tests**

```powershell
.\gradlew.bat checkstyle :AuraLauncher:test --tests "org.jackhuang.hmcl.LicenseBoundaryTest" --tests "org.jackhuang.hmcl.ui.main.Plugin*" --tests "org.jackhuang.hmcl.plugin.mixin.bootstrap.*" --no-daemon --stacktrace
```

Expected: PASS, including service discovery and Mixin bootstrap tests.

- [ ] **Step 6: Commit the UI and resource move**

```powershell
git add -A -- AuraLauncher/src/main/java/org/jackhuang/hmcl/ui/main AuraLauncher/src/test/java/org/jackhuang/hmcl/ui/main AuraLauncher/src/main/resources/META-INF/services AuraLauncher/src/test/resources AuraPluginSystem/src AuraLauncher/src/test/java/org/jackhuang/hmcl/LicenseBoundaryTest.java
git commit -m "refactor: move plugin UI into Apache boundary"
```

### Task 5: Move Plugin Documentation And Finish The Bilingual README

**Files:**
- Move: `docs/PLUGIN_SYSTEM.md` to `AuraPluginSystem/docs/PLUGIN_SYSTEM.md`
- Move: `docs/PLUGIN_CONTRACT.md` to `AuraPluginSystem/docs/PLUGIN_CONTRACT.md`
- Move: `docs/NEXT_PLUGIN_ARCHITECTURE.md` to `AuraPluginSystem/docs/NEXT_PLUGIN_ARCHITECTURE.md`
- Move: `docs/COMPANION_IPC.md` to `AuraPluginSystem/docs/COMPANION_IPC.md`
- Create: forwarding stubs at the four old paths
- Modify: `README.md`, `docs/README.md`, and `docs/README_zh.md`
- Add: `docs/assets/aura-launcher.png` from the already verified README work

**Interfaces:**
- Consumes: Final Apache directory layout and the current uncommitted bilingual README/logo.
- Produces: PCL-style bilingual licensing documentation with no broken live links.

- [ ] **Step 1: Move the four user-owned live documents**

Move their complete content to `AuraPluginSystem/docs/`. Preserve filenames and internal links between `PLUGIN_SYSTEM.md` and `PLUGIN_CONTRACT.md`.

At each old path, create a short GPL-bound forwarding document, for example:

```markdown
# Plugin System Documentation

This document moved to [`AuraPluginSystem/docs/PLUGIN_SYSTEM.md`](../AuraPluginSystem/docs/PLUGIN_SYSTEM.md).
```

Use the corresponding title and target for each file.

- [ ] **Step 2: Update live inbound documentation links**

Update `README.md`, `docs/README.md`, and `docs/README_zh.md` to target
`AuraPluginSystem/docs/...` using paths relative to each file. Do not rewrite archived
`docs/superpowers/plans/**` line references; forwarding stubs preserve those historical links.

- [ ] **Step 3: Replace the README single-license wording**

Keep the GPL badge because complete distributions remain GPL. In both Chinese and English, replace the single-license feature/closing statement with an explicit list equivalent to:

```markdown
### 许可证

- [`AuraPluginSystem/`](AuraPluginSystem/) 使用 [Apache License 2.0](AuraPluginSystem/LICENSE)。
- 其余所有目录使用根目录的 [GNU General Public License v3.0](LICENSE)，除非文件另有说明。

完整 Aura Launcher 发行物包含 GPL 代码，因此整体仍须遵守 GPLv3。
```

```markdown
### License

- [`AuraPluginSystem/`](AuraPluginSystem/) uses the [Apache License 2.0](AuraPluginSystem/LICENSE).
- All other directories use the root [GNU General Public License v3.0](LICENSE), unless a file states otherwise.

The complete Aura Launcher distribution contains GPL-covered code and therefore remains subject to GPLv3.
```

- [ ] **Step 4: Verify README assets, links, and branding**

Run `git diff --check`, verify all relative README links exist, submit `README.md` to GitHub's Markdown render API, and scan visible README text for forbidden legacy launcher branding. Verify `docs/assets/aura-launcher.png` remains a 512x512 PNG with SHA-256:

```text
77e585a02f7d60b84069110fcfe3804f4c3c78512e9af5623690ea5ae031e39c
```

- [ ] **Step 5: Commit documentation and branding**

```powershell
git add -A -- README.md docs/assets/aura-launcher.png docs/PLUGIN_SYSTEM.md docs/PLUGIN_CONTRACT.md docs/NEXT_PLUGIN_ARCHITECTURE.md docs/COMPANION_IPC.md docs/README.md docs/README_zh.md AuraPluginSystem/docs AuraPluginSystem/README.md
git commit -m "docs: document Aura source license boundaries"
```

### Task 6: Certify Behavior, Packaging, And License Boundaries

**Files:**
- Modify only if verification exposes a defect in an in-scope file.
- Inspect: `AuraLauncher/build/libs/Aura-Launcher-*.jar`
- Inspect: `build/license-baseline/plugin-entries.txt`

**Interfaces:**
- Consumes: All migration tasks.
- Produces: Fresh evidence that source relocation changed neither behavior nor package identity.

- [ ] **Step 1: Run the full required gate**

```powershell
.\gradlew.bat checkstyle checkTranslations test shadowJar --no-daemon --stacktrace
```

Expected: `BUILD SUCCESSFUL`, zero failed tests, and both plugin Checkstyle tasks executed.

- [ ] **Step 2: Compare packaged plugin entries with the baseline**

Read the newest Shadow JAR with `System.IO.Compression.ZipFile`, select the same plugin class/service patterns as Task 1, sort them, and compare them to `build/license-baseline/plugin-entries.txt` with `Compare-Object`.

Expected: no differences.

- [ ] **Step 3: Verify version and artifact identity**

Verify:

```text
Gradle project version: 27.1-next
Shadow JAR: Aura-Launcher-27.1-next.jar
Implementation-Version: 27.1-next
```

Reject any value with a missing suffix or more than one `-next` occurrence.

- [ ] **Step 4: Run final source-license scans**

Require:

```text
AuraPluginSystem Java files with Apache marker: at least 245 (159 main + 86 test)
AuraPluginSystem Java files with GPL marker: 0
AuraLauncher Java files with Apache marker: 0
Root LICENSE unchanged
AuraPluginSystem/LICENSE SHA-256: 449f6e5e053b2e3c419553ef0625fc33b89f0d2c22598e81804fa4ff4d70719b
```

- [ ] **Step 5: Review the complete diff and repository status**

Run:

```powershell
git diff --check HEAD~5..HEAD
git status --short --branch
git log -7 --oneline
```

Confirm Git recognizes source changes as renames where practical, compatibility package paths inside the JAR are unchanged, no unrelated user changes were included, and no secrets or temporary baseline files are tracked.

- [ ] **Step 6: Fix only verified in-scope defects and rerun the affected gate**

For every defect, add a focused regression assertion first, reproduce the failure, apply the smallest correction, rerun the focused command, then rerun Step 1. Commit corrections with a message describing the actual defect. If no defect is found, create no empty commit.
