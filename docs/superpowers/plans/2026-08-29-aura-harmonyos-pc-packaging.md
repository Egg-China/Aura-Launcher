# Aura HarmonyOS PC Packaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a distinct experimental `harmonyos-arm64` plugin target and a reproducible, unsigned-by-default HarmonyOS PC Stage HAP that starts the exact Aura Launcher `27.1-next` JAR through a separately installed BiSheng JDK 17 HNP.

**Architecture:** Keep Aura Launcher core on its existing Linux-compatible paths and introduce HarmonyOS only at the schema-v5 plugin-platform boundary. Extend Store selection with a one-way `linux-arm64` fallback, then package the unchanged Shadow JAR in a private Aura HNP embedded in a small Stage HAP whose ArkTS UI calls a fixed ARM64 N-API process bridge. Keep SDK, Store, and real HarmonyOS toolchain delivery in their own repositories and workflows so no proprietary SDK, signing key, JDK, HNP, or HAP binary enters Git.

**Tech Stack:** Java 17, JUnit 5, Gradle, PowerShell 7, JSON/JSON5, ArkTS, HarmonyOS N-API C++, CMake, Hvigor, `hnpcli`, GitHub Actions

**Spec:** `docs/superpowers/specs/2026-08-29-aura-harmonyos-pc-packaging-design.md`

## Global Constraints

- Product name is exactly `Aura Launcher`; current-product prose must not use a legacy fork suffix.
- Launcher version, HAP `versionName`, JAR filename, and JAR `Implementation-Version` are exactly `27.1-next`; the suffix appears once.
- HNP version is the deterministic `27.1.next` mapping; HAP `versionCode` is an explicit positive integer.
- Bundle name is `com.eggchina.auralauncher`; module targets `deviceTypes: ["2in1"]` and SDK `6.0.1(21)`.
- The private HNP is `aura_launcher`; its only executable is linked as `/data/app/bin/aura-launcher`.
- `BiShengJDK17-OH` is an external prerequisite and must never be copied into source, staging, CI artifacts, HAPs, or Releases.
- HarmonyOS packaging and Launcher code remain GPLv3; `AuraPluginSystem/` remains Apache-2.0.
- Plugin manifests remain schema v5, Store manifests remain schema v2, Store index remains schema v1, and schema v4 is not modified.
- HarmonyOS artifacts never match Linux hosts. Only `linux` and `linux-arm64` declarations may match a `harmonyos-arm64` host.
- No public HarmonyOS Release asset is produced until all true-device gates pass and promotion is explicitly approved.
- Every new or modified Java class uses `@NotNullByDefault`; nullable types use `@Nullable`; immutable collections use JetBrains immutability annotations; every written or modified class, field, and method uses accurate `///` documentation.
- Each green commit is fetched/rebased, pushed normally without force, and its applicable GitHub Actions runs are watched to a terminal success before the next remote delivery.

---

### Task 1: Distinct HarmonyOS Plugin Platform And One-Way Artifact Selection

**Files:**
- Create: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/runtime/PluginPlatformTargetTest.java`
- Modify: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginPlatformTarget.java`
- Modify: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/runtime/PluginCompatibilityEvaluatorTest.java`
- Modify: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManifest.java`
- Modify: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreManifestTest.java`

**Interfaces:**
- Consumes: schema-v5 canonical target strings and `PluginCompatibilityEvaluator` platform checks.
- Produces: `PluginPlatformTarget.detect(String osName, String osArch, Path osReleasePath)`, canonical `harmonyos`, asymmetric `PluginPlatformTarget.matches(PluginPlatformTarget host)`, and exact-first `PluginVersionEntry.requireArtifact(PluginPlatformTarget target)`.

- [ ] **Step 1: Read the test-quality rules and create focused failing platform tests**

Read `C:\Users\ACX\.codex\plugins\cache\openai-api-curated\superpowers\6d99ee14\skills\test-driven-development\writing-good-tests.md` completely. Then create `PluginPlatformTargetTest.java` with one behavior per test and temporary files rather than process-global property mutation:

```java
/// Verifies canonical plugin target parsing, host detection, and asymmetric compatibility.
@NotNullByDefault
public final class PluginPlatformTargetTest {
    /// Detects explicit HarmonyOS names without consulting Linux release metadata.
    @Test
    public void detectHarmonyOsName() {
        assertEquals("harmonyos-arm64", PluginPlatformTarget.detect(
                "HarmonyOS", "aarch64", Path.of("missing-release-file")).getId());
        assertEquals("harmonyos-arm64", PluginPlatformTarget.detect(
                "OpenHarmony 6.0", "arm64", Path.of("missing-release-file")).getId());
    }

    /// Detects bounded HarmonyOS markers in Linux-compatible release metadata.
    @Test
    public void detectHarmonyOsReleaseMetadata(@TempDir Path temporary) throws IOException {
        Path release = temporary.resolve("os-release");
        Files.writeString(release, "ID=openharmony\nPRETTY_NAME=HarmonyOS PC\n", StandardCharsets.UTF_8);
        assertEquals("harmonyos-arm64",
                PluginPlatformTarget.detect("Linux", "aarch64", release).getId());
    }

    /// Treats missing, unreadable, malformed UTF-8, oversized, and ordinary Linux metadata as Linux.
    @Test
    public void ignoreUntrustedReleaseMetadata(@TempDir Path temporary) throws IOException {
        Path missing = temporary.resolve("missing");
        Path directory = Files.createDirectory(temporary.resolve("directory"));
        Path malformed = Files.write(temporary.resolve("malformed"), new byte[]{(byte) 0xc3, 0x28});
        Path oversized = Files.write(temporary.resolve("oversized"), new byte[65_537]);
        Path linux = Files.writeString(temporary.resolve("linux"), "ID=linux\nNAME=Linux\n");
        for (Path input : List.of(missing, directory, malformed, oversized, linux)) {
            assertEquals("linux-arm64", PluginPlatformTarget.detect("Linux", "aarch64", input).getId());
        }
    }

    /// Allows Linux ARM64 packages on HarmonyOS ARM64 without enabling reverse or cross-architecture matches.
    @Test
    public void applyHarmonyOsCompatibilityOneWay() {
        PluginPlatformTarget harmonyArm64 = PluginPlatformTarget.parse("harmonyos-arm64");
        assertTrue(PluginPlatformTarget.parse("harmonyos-arm64").matches(harmonyArm64));
        assertTrue(PluginPlatformTarget.parse("linux-arm64").matches(harmonyArm64));
        assertTrue(PluginPlatformTarget.parse("linux").matches(harmonyArm64));
        assertFalse(PluginPlatformTarget.parse("harmonyos-arm64")
                .matches(PluginPlatformTarget.parse("linux-arm64")));
        assertFalse(PluginPlatformTarget.parse("linux-x64").matches(harmonyArm64));
        assertFalse(PluginPlatformTarget.parse("linux-arm64")
                .matches(PluginPlatformTarget.parse("harmonyos-x64")));
    }
}
```

- [ ] **Step 2: Run the platform tests to verify RED**

Run:

```powershell
.\gradlew.bat :AuraLauncher:test `
  --tests org.jackhuang.hmcl.plugin.runtime.PluginPlatformTargetTest `
  --no-daemon --stacktrace
```

Expected: compilation fails because `detect(...)` is absent and `harmonyos` is not a known operating-system identifier.

- [ ] **Step 3: Implement bounded detection and asymmetric matching**

Modify `PluginPlatformTarget` around these exact interfaces and constants:

```java
/// Maximum accepted `/etc/os-release` byte length.
private static final int MAX_OS_RELEASE_BYTES = 64 * 1024;

/// Canonical operating-system identifiers accepted in manifests and store indexes.
public static final @Unmodifiable Set<String> KNOWN_OPERATING_SYSTEMS =
        Set.of("windows", "linux", "macos", "freebsd", "harmonyos");

/// Detects a target from explicit host inputs without mutating process-global properties.
///
/// @param osName Java operating-system name
/// @param osArch Java architecture name
/// @param osReleasePath Linux-compatible release metadata path
/// @return detected canonical plugin target
static PluginPlatformTarget detect(String osName, String osArch, Path osReleasePath) {
    String operatingSystem = detectOperatingSystem(osName, osReleasePath);
    return new PluginPlatformTarget(operatingSystem, normalizeArchitecture(osArch));
}

/// Returns whether a package declaring this target can run on the given host.
public boolean matches(PluginPlatformTarget host) {
    if (operatingSystem.equals(host.operatingSystem)) {
        return architecture == null || architecture.equals(host.architecture);
    }
    return operatingSystem.equals("linux")
            && host.operatingSystem.equals("harmonyos")
            && host.architecture.equals("arm64")
            && (architecture == null || architecture.equals("arm64"));
}
```

`current()` must call `detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""), Path.of("/etc/os-release"))`. Read at most 65,537 bytes, reject any input above 65,536 bytes, decode UTF-8 with `CodingErrorAction.REPORT`, inspect only `ID`, `NAME`, `PRETTY_NAME`, and `ID_LIKE`, strip a matching single or double quote pair, and treat every I/O/decoding/format failure as ordinary Linux. Check `harmonyos` and `openharmony` before the existing Windows/macOS/FreeBSD/Linux branches.

- [ ] **Step 4: Run platform tests to verify GREEN**

Run the Step 2 command again.

Expected: all `PluginPlatformTargetTest` methods pass.

- [ ] **Step 5: Add failing evaluator and Store artifact-order tests**

Add an evaluator test proving a schema-v5 `platforms: ["linux-arm64"]` requirement is compatible with injected `harmonyos-arm64` but the reverse is not. Extend `PluginStoreManifestTest` with exact-first and bounded fallback assertions:

```java
/// Prefers a native HarmonyOS artifact and otherwise falls back only to Linux ARM64.
@Test
public void selectHarmonyOsArtifactWithExactFirstFallback() throws IOException {
    PluginStoreManifest.PluginVersionEntry exactFirst = parseArtifactVersion(
            artifact("linux-arm64", "linux.npl", 'a') + "," +
            artifact("harmonyos-arm64", "harmony.npl", 'b'));
    assertEquals("harmonyos-arm64",
            exactFirst.requireArtifact(PluginPlatformTarget.parse("harmonyos-arm64")).platform().getId());

    PluginStoreManifest.PluginVersionEntry fallback = parseArtifactVersion(
            artifact("linux-arm64", "linux.npl", 'a'));
    assertEquals("linux-arm64",
            fallback.requireArtifact(PluginPlatformTarget.parse("harmonyos-arm64")).platform().getId());

    assertThrows(IOException.class,
            () -> fallback.requireArtifact(PluginPlatformTarget.parse("harmonyos-x64")));
    PluginStoreManifest.PluginVersionEntry harmonyOnly = parseArtifactVersion(
            artifact("harmonyos-arm64", "harmony.npl", 'b'));
    assertThrows(IOException.class,
            () -> harmonyOnly.requireArtifact(PluginPlatformTarget.parse("linux-arm64")));
}
```

Use the existing `schemaFiveArtifactManifest(...)` helper instead of adding broad fixture infrastructure. Put the HarmonyOS artifact first in a second matrix to prove declaration order cannot override exact preference.

- [ ] **Step 6: Run the evaluator and Store tests to verify RED**

Run:

```powershell
.\gradlew.bat :AuraLauncher:test `
  --tests org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluatorTest `
  --tests org.jackhuang.hmcl.plugin.store.PluginStoreManifestTest `
  --no-daemon --stacktrace
```

Expected: evaluator compatibility can pass after Step 3, while Store fallback assertions fail because `requireArtifact` still accepts exact targets only.

- [ ] **Step 7: Implement exact-first HarmonyOS artifact fallback**

Keep legacy single-package behavior unchanged. In `requireArtifact`, first scan for `artifact.platform().equals(target)`. Only after that scan, when `target.equals(PluginPlatformTarget.parse("harmonyos-arm64"))`, scan for `linux-arm64`. If neither exists, throw the existing diagnostic with `compatible fallback tried: linux-arm64` appended only for the HarmonyOS ARM64 case. Do not call broad `matches(...)` during artifact selection.

- [ ] **Step 8: Run focused and full Aura gates**

Run:

```powershell
.\gradlew.bat :AuraLauncher:test `
  --tests org.jackhuang.hmcl.plugin.runtime.PluginPlatformTargetTest `
  --tests org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluatorTest `
  --tests org.jackhuang.hmcl.plugin.store.PluginStoreManifestTest `
  --no-daemon --stacktrace
$env:BUILD_VERSION = '27.1'
.\gradlew.bat checkstyle checkTranslations test shadowJar --no-daemon --stacktrace
Remove-Item Env:BUILD_VERSION
```

Expected: all tasks pass; the only Shadow JAR is `AuraLauncher/build/libs/Aura-Launcher-27.1-next.jar`.

- [ ] **Step 9: Verify version metadata, commit, push, and watch CI**

```powershell
$jar = 'AuraLauncher\build\libs\Aura-Launcher-27.1-next.jar'
jar xf $jar META-INF/MANIFEST.MF
Select-String -Path META-INF/MANIFEST.MF -Pattern '^Implementation-Version: 27\.1-next$'
Remove-Item -Recurse -Force META-INF
git diff --check
git add AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginPlatformTarget.java `
  AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManifest.java `
  AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/runtime/PluginPlatformTargetTest.java `
  AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/runtime/PluginCompatibilityEvaluatorTest.java `
  AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreManifestTest.java
git commit -m "feat: recognize HarmonyOS plugin targets"
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
git fetch origin main
git rebase origin/main
git push origin HEAD:main
gh run list --repo Egg-China/Aura-Launcher --commit (git rev-parse HEAD) --limit 10
gh run watch --repo Egg-China/Aura-Launcher (gh run list --repo Egg-China/Aura-Launcher --commit (git rev-parse HEAD) --json databaseId --jq '.[0].databaseId') --exit-status
Remove-Item Env:HTTPS_PROXY
```

Expected: `Java CI` and `Check Codes` finish successfully for the pushed commit.

### Task 2: Schema-v5 SDK And Official Store HarmonyOS Vocabulary

**Files:**
- Modify in SDK: `tools/validate-npl.ps1`
- Modify in SDK: `tools/test-validate-npl.ps1`
- Modify in SDK: `references/hmcl-plugin-api/PluginPlatformTarget.java`
- Modify in SDK: `README.md`
- Modify in SDK: `docs/PLUGIN_QUICKSTART.md`
- Modify in SDK: `examples/java-helloworld/build.gradle.kts`
- Modify in SDK: `examples/java-launch-hook/build.gradle.kts`
- Modify in SDK: `examples/java-mixin/build.gradle.kts`
- Modify in SDK: `examples/kotlin-helloworld/build.gradle.kts`
- Modify in SDK: `examples/offline-unlocker/build.gradle.kts`
- Modify in Store: `tools/validate-store.ps1`
- Modify in Store: `tools/test-validate-store.ps1`
- Modify in Store: `README.md`
- Modify in Store: `.github/workflows/validate.yml`

**Interfaces:**
- Consumes: canonical Aura platform vocabulary from Task 1 and current six-platform Runtime Host manifests.
- Produces: SDK validation for `harmonyos`, a synchronized public API reference, and Store validation requiring the existing six targets while permitting only one optional seventh target, `harmonyos-arm64`.

- [ ] **Step 1: Create clean SDK and Store worktrees from their remote delivery branches**

```powershell
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
git -C C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK fetch origin schema-v5
git -C C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK worktree add `
  C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK\.worktrees\harmonyos-platform `
  -b codex/harmonyos-platform origin/schema-v5
git -C C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-Store fetch aura main
git -C C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-Store worktree add `
  C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-Store\.worktrees\harmonyos-platform `
  -b codex/harmonyos-platform aura/main
Remove-Item Env:HTTPS_PROXY
```

Expected: both new worktrees are clean; unrelated QuickJS and official-root worktrees remain untouched.

- [ ] **Step 2: Add SDK validator RED cases**

In `tools/test-validate-npl.ps1`, add a valid schema-v5 package with `platforms = @('harmonyos-arm64')`, a valid Store artifact target `harmonyos-arm64`, and retain rejection of unknown `harmony-arm64`. Do not add any HarmonyOS field to schema-v4 fixtures.

Run:

```powershell
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK\.worktrees\harmonyos-platform
.\tools\test-validate-npl.ps1
```

Expected: the new HarmonyOS cases fail with `unknown operating system` or `Invalid plugin artifact target`.

- [ ] **Step 3: Extend only schema-v5 OS allowlists and synchronize the reference**

At both schema-v5 target-validation locations in `tools/validate-npl.ps1`, change the operating-system list to:

```powershell
@('windows', 'linux', 'macos', 'freebsd', 'harmonyos')
```

Copy the Task 1 `PluginPlatformTarget.java` into `references/hmcl-plugin-api/PluginPlatformTarget.java`, preserving the SDK reference file's retained compatibility license header if its sync policy requires it. Confirm `schema-v4` has no diff.

Run `.\tools\test-validate-npl.ps1` again.

Expected: every publishing/validator test passes, including HarmonyOS and unknown-target rejection.

- [ ] **Step 4: Correct SDK build commands and Runtime Host status**

Replace repository-relative legacy launcher paths with this source-root contract in `README.md` and `docs/PLUGIN_QUICKSTART.md`:

```powershell
$aura = Resolve-Path $env:AURA_LAUNCHER_SOURCE
$env:HMCL_JAR = (Get-ChildItem "$aura\AuraLauncher\build\libs\Aura-Launcher-*.jar" |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
& "$aura\gradlew.bat" -p examples/java-helloworld clean packageNpl
.\tools\validate-npl.ps1 `
    -Package examples/java-helloworld/build/npl/dev.hmclce.example.java.helloworld-v1.0.0.npl
```

All five example Gradle files must resolve only `HMCL_JAR` as the compatibility environment variable and must not contain a hardcoded `HMCL-CE/HMCL/build/libs` path. Document these published optional providers with their real repositories: Rust, .NET, QuickJS, and Wasm. Document Python as unavailable. Explain that `harmonyos-arm64` is a distinct experimental target and Linux ARM64 packages can match it only through Aura's one-way fallback.

- [ ] **Step 5: Run SDK gates, commit, push `schema-v5`, and watch CI**

```powershell
.\tools\test-publishing-tools.ps1
.\tools\test-validate-npl.ps1
rg -n "HMCL-CE[/\\]HMCL|\.NET.*未提供|QuickJS/WASM.*未提供" README.md docs examples
git diff --check
git add tools/validate-npl.ps1 tools/test-validate-npl.ps1 `
  references/hmcl-plugin-api/PluginPlatformTarget.java README.md docs/PLUGIN_QUICKSTART.md examples
git commit -m "feat: document HarmonyOS plugin targets"
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
git fetch origin schema-v5
git rebase origin/schema-v5
git push origin HEAD:schema-v5
Remove-Item Env:HTTPS_PROXY
```

Expected: all SDK tests pass, the stale scan prints no matches, and any SDK workflow for the pushed commit succeeds.

- [ ] **Step 6: Add Store validator RED cases for a six-plus-optional matrix**

In `tools/test-validate-store.ps1`, add three explicit cases:

```powershell
$withHarmony = New-Manifest
$withHarmony.versions[0].platforms += 'harmonyos-arm64'
$withHarmony.versions[0].artifacts += New-Artifact 'harmonyos-arm64'
# Expected: succeeds.

$missingRequired = New-Manifest
$missingRequired.versions[0].platforms = @($missingRequired.versions[0].platforms |
    Where-Object { $_ -cne 'linux-arm64' })
$missingRequired.versions[0].artifacts = @($missingRequired.versions[0].artifacts |
    Where-Object { $_.platform -cne 'linux-arm64' })
# Expected: fails with "missing required Aura platform: linux-arm64".

$unknown = New-Manifest
$unknown.versions[0].platforms += 'harmonyos-x64'
$unknown.versions[0].artifacts += New-Artifact 'harmonyos-x64'
# Expected: fails with "unsupported artifact platform: harmonyos-x64".
```

Run `.\tools\test-validate-store.ps1` in the Store HarmonyOS worktree.

Expected: the optional HarmonyOS case fails under the current exact-six rule.

- [ ] **Step 7: Implement Store required-plus-optional validation**

Replace `$supportedPlatforms` with these separate constants:

```powershell
$requiredPlatforms = @(
    'windows-x64', 'windows-arm64', 'linux-x64',
    'linux-arm64', 'macos-x64', 'macos-arm64'
)
$optionalPlatforms = @('harmonyos-arm64')
$supportedPlatforms = @($requiredPlatforms + $optionalPlatforms)
```

For both `platforms` and `artifacts`, reject values outside `$supportedPlatforms`, reject duplicates, and iterate `$requiredPlatforms` to report a missing required target. Require the artifact target set to exactly equal the version `platforms` set. This accepts six existing targets or the same six plus `harmonyos-arm64`; it rejects missing six-platform coverage, unknown HarmonyOS architectures, and an artifact/platform disagreement.

Update the Store README to list six required targets and `harmonyos-arm64` as the only optional experimental target. State that Aura on HarmonyOS prefers the optional native target then falls back to `linux-arm64`; do not describe Linux artifacts as tested HarmonyOS builds.

- [ ] **Step 8: Pin Store CI to the just-pushed SDK schema-v5 commit**

Resolve the exact SDK commit with `git -C C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK\.worktrees\harmonyos-platform rev-parse HEAD` and replace `.github/workflows/validate.yml`'s validator `ref` with that full 40-character SHA. Keep every Action reference pinned to a full SHA.

- [ ] **Step 9: Run Store gates, commit, push `main`, and watch CI**

```powershell
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-Store\.worktrees\harmonyos-platform
.\tools\test-validate-store.ps1
.\tools\validate-store.ps1
git diff --check
git add tools/validate-store.ps1 tools/test-validate-store.ps1 README.md .github/workflows/validate.yml
git commit -m "feat: accept optional HarmonyOS runtime artifacts"
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
git fetch aura main
git rebase aura/main
git push aura HEAD:main
$run = gh run list --repo Egg-China/Aura-Launcher-Plugin-Store --commit (git rev-parse HEAD) `
  --json databaseId --jq '.[0].databaseId'
gh run watch --repo Egg-China/Aura-Launcher-Plugin-Store $run --exit-status
Remove-Item Env:HTTPS_PROXY
```

Expected: local structural validation and the remote Store workflow succeed. Existing six-platform public manifests remain unchanged and valid.

### Task 3: Stage HAP, Fixed Native Bridge, And Private Aura HNP Source Layout

**Files:**
- Create: `packaging/harmonyos/README.md`
- Create: `packaging/harmonyos/oh-package.json5`
- Create: `packaging/harmonyos/build-profile.json5`
- Create: `packaging/harmonyos/hvigorfile.ts`
- Create: `packaging/harmonyos/AppScope/app.json5`
- Create: `packaging/harmonyos/AppScope/resources/base/element/string.json`
- Create: `packaging/harmonyos/AppScope/resources/base/media/app_icon.png`
- Create: `packaging/harmonyos/entry/oh-package.json5`
- Create: `packaging/harmonyos/entry/build-profile.json5`
- Create: `packaging/harmonyos/entry/hvigorfile.ts`
- Create: `packaging/harmonyos/entry/src/main/module.json5`
- Create: `packaging/harmonyos/entry/src/main/ets/entryability/EntryAbility.ets`
- Create: `packaging/harmonyos/entry/src/main/ets/pages/Index.ets`
- Create: `packaging/harmonyos/entry/src/main/resources/base/element/string.json`
- Create: `packaging/harmonyos/entry/src/main/resources/zh_CN/element/string.json`
- Create: `packaging/harmonyos/entry/src/main/resources/base/profile/main_pages.json`
- Create: `packaging/harmonyos/entry/src/main/cpp/CMakeLists.txt`
- Create: `packaging/harmonyos/entry/src/main/cpp/aura_launcher_napi.cpp`
- Create: `packaging/harmonyos/entry/src/main/cpp/types/libaura_launcher/index.d.ts`
- Create: `packaging/harmonyos/entry/src/main/cpp/types/libaura_launcher/oh-package.json5`
- Create: `packaging/harmonyos/hnp/bin/aura-launcher`
- Create: `packaging/harmonyos/tools/Test-HarmonyProject.ps1`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: fixed private-HNP executable `/data/app/bin/aura-launcher` and application-private files directory supplied by ArkTS.
- Produces: N-API `startAura(logPath: string): LaunchResult`, `pollAura(pid: number): PollResult`, and `readDiagnosticTail(logPath: string): string`; a Stage `EntryAbility`; and a private-HNP shell entrypoint.

- [ ] **Step 1: Write static project tests before project files**

Create `Test-HarmonyProject.ps1` to parse JSON5 after removing only comments and trailing commas with a bounded helper, then assert:

```powershell
Assert-Condition ($app.bundleName -ceq 'com.eggchina.auralauncher') 'wrong bundle name'
Assert-Condition ($module.deviceTypes.Count -eq 1 -and $module.deviceTypes[0] -ceq '2in1') `
    'HarmonyOS package must target 2in1 only'
Assert-Condition ($module.hnpPackages.Count -eq 1 `
    -and $module.hnpPackages[0].package -ceq 'aura_launcher.hnp' `
    -and $module.hnpPackages[0].type -ceq 'private') 'Aura HNP must be private'
Assert-SetEquals $module.requestPermissions.name @(
    'ohos.permission.INTERNET',
    'ohos.permission.FILE_ACCESS_PERSIST',
    'ohos.permission.READ_WRITE_DESKTOP_DIRECTORY',
    'ohos.permission.READ_WRITE_DOCUMENTS_DIRECTORY',
    'ohos.permission.READ_WRITE_DOWNLOAD_DIRECTORY'
)
Assert-FileContains $nativeSource '"/data/app/bin/aura-launcher"'
Assert-FileDoesNotContain $nativeSource @('system(', 'popen(', '/bin/sh', 'napi_get_cb_info')
Assert-FileContains $nativeSource 'constexpr size_t kMaximumDiagnosticBytes = 16 * 1024'
Assert-FileContains $nativeSource 'constexpr std::chrono::seconds kStartupWindow(15)'
```

Also reject any tracked `*.hnp`, `*.hap`, JDK archive, `.p12`, `.cer`, `.pem`, or private-key file below `packaging/harmonyos`.

- [ ] **Step 2: Run the static test to verify RED**

Run:

```powershell
.\packaging\harmonyos\tools\Test-HarmonyProject.ps1
```

Expected: failure because the Stage project does not yet exist.

- [ ] **Step 3: Create Stage application metadata and bilingual resources**

Use SDK baseline `6.0.1(21)`, `compatibleSdkVersion: "6.0.1(21)"`, `targetSdkVersion: "6.0.1(21)"`, bundle `com.eggchina.auralauncher`, module `entry`, and `deviceTypes: ["2in1"]`. `module.json5` must contain only the five reviewed permissions and:

```json5
"hnpPackages": [
  {
    "package": "aura_launcher.hnp",
    "type": "private"
  }
]
```

Create base English and `zh_CN` strings for prerequisite missing, ready, starting, started-not-verified, early exit, diagnostics, and retry. Copy the existing repository-owned `docs/assets/aura-launcher.png` bytes to `AppScope/resources/base/media/app_icon.png`; do not regenerate or alter the icon.

- [ ] **Step 4: Create the fixed N-API process surface**

Export only these TypeScript declarations:

```typescript
export interface LaunchResult {
  code: number;
  pid: number;
  message: string;
}

export interface PollResult {
  running: boolean;
  exited: boolean;
  exitCode: number;
  message: string;
}

export const startAura: (logPath: string) => LaunchResult;
export const pollAura: (pid: number) => PollResult;
export const readDiagnosticTail: (logPath: string) => string;
```

In C++, use constants `kAuraExecutable = "/data/app/bin/aura-launcher"`, `kMaximumDiagnosticBytes = 16 * 1024`, and `kStartupWindow = 15s`. Validate that the log path is nonempty, absolute, under the application files directory contract passed by ArkTS, and capped at 4096 UTF-8 bytes. Start with `fork`, redirect stdout/stderr using `open(..., O_CREAT | O_WRONLY | O_APPEND | O_CLOEXEC, 0600)` plus `dup2`, then call `execv(kAuraExecutable, argv)` with exactly `{kAuraExecutable, nullptr}`. Hold one mutex-protected live PID, reject duplicate starts, use `waitpid(pid, &status, WNOHANG)` for polling, cap all messages at 4096 bytes, and make post-exit polling idempotent. Never expose a command, argument array, environment map, executable path, or shell API to ArkTS.

- [ ] **Step 5: Create the nonblocking ArkTS state flow**

`EntryAbility.ets` loads `Index`. `Index.ets` derives a fixed application-private `aura-launcher.log`, calls `startAura`, polls at one-second intervals without blocking ArkUI, labels a child alive for 15 seconds as `started` rather than `verified`, clears its timer during disappearance, and shows at most `readDiagnosticTail`'s 16 KiB early-exit output. Disable Retry while a live child exists. Never render a terminal or editable executable/argument field.

- [ ] **Step 6: Create the private-HNP shell entrypoint**

Use this fixed behavior in `hnp/bin/aura-launcher`:

```sh
#!/system/bin/sh
set -eu

SELF="$(readlink -f -- "$0")"
HNP_ROOT="$(CDPATH= cd -- "$(dirname -- "$SELF")/.." && pwd -P)"
JAR="$HNP_ROOT/share/aura/Aura-Launcher-27.1-next.jar"
JAVA="$(command -v java || true)"

if [ -z "$JAVA" ]; then
  echo "BiShengJDK17-OH is required; java was not found in the HNP application PATH." >&2
  exit 69
fi
if [ ! -r "$JAR" ]; then
  echo "Aura Launcher package is incomplete: $JAR is missing." >&2
  exit 66
fi
JAVA_VERSION="$($JAVA -version 2>&1 | sed -n '1p')"
JAVA_FEATURE="$(printf '%s\n' "$JAVA_VERSION" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
if [ -z "$JAVA_FEATURE" ] || [ "$JAVA_FEATURE" -lt 17 ]; then
  echo "BiSheng JDK 17 or later is required." >&2
  exit 69
fi
exec "$JAVA" -jar "$JAR"
```

The packaging script, not Git, supplies `share/aura/Aura-Launcher-27.1-next.jar` and generated `hnp.json`.

- [ ] **Step 7: Run static tests to verify GREEN**

```powershell
.\packaging\harmonyos\tools\Test-HarmonyProject.ps1
git ls-files packaging/harmonyos | rg '\.(hnp|hap|p12|pfx|pem|key|cer|jks)$'
```

Expected: static tests pass and the prohibited-file scan prints nothing.

- [ ] **Step 8: Update ignores, commit, push, and watch CI**

Add these scoped ignores:

```gitignore
/packaging/harmonyos/.hvigor/
/packaging/harmonyos/oh_modules/
/packaging/harmonyos/build/
/packaging/harmonyos/entry/build/
/packaging/harmonyos/entry/src/main/hnp/
/packaging/harmonyos/out/
```

Then run:

```powershell
git diff --check
git add .gitignore packaging/harmonyos
git commit -m "feat: scaffold the Aura HarmonyOS Stage app"
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
git fetch origin main
git rebase origin/main
git push origin HEAD:main
Remove-Item Env:HTTPS_PROXY
```

Expected: the existing Java workflow and any path-triggered checks finish successfully; no binary package or secret is tracked.

### Task 4: Reproducible HNP/HAP Packaging And SDK-Free Contract Tests

**Files:**
- Create: `packaging/harmonyos/tools/Build-HarmonyPackage.ps1`
- Create: `packaging/harmonyos/tools/Test-HarmonyPackaging.ps1`
- Create: `packaging/harmonyos/tools/Test-HarmonyShell.ps1`
- Create: `packaging/harmonyos/evidence.schema.json`
- Modify: `packaging/harmonyos/README.md`

**Interfaces:**
- Consumes: exact Aura Shadow JAR, `hnpcli`, Hvigor executable, explicit positive HAP version code, output directory, and either no signing inputs or one complete signing set.
- Produces: `Aura-Launcher-27.1-next-harmonyos-arm64-unsigned.hap` or verified release-signed `Aura-Launcher-27.1-next-harmonyos-arm64.hap`, SHA-256 files, and `Aura-Launcher-27.1-next-harmonyos-arm64-evidence.json`.

- [ ] **Step 1: Write contract tests with fake external tools**

`Test-HarmonyPackaging.ps1` must create a temporary valid JAR containing `META-INF/MANIFEST.MF` with `Implementation-Version: 27.1-next`, fake `hnpcli` and Hvigor scripts that record their argument arrays as JSON, and a fake unsigned HAP. Cover these named cases independently:

```powershell
Assert-BuildFails -VersionCode 0 -Expected 'versionCode must be a positive integer'
Assert-BuildFails -JarName 'Aura-Launcher-27.1.jar' -Expected 'exactly 27.1-next'
Assert-BuildFails -ImplementationVersion '27.1-next-next' -Expected 'Implementation-Version'
Assert-BuildFails -SigningProfile 'profile.p7b' -Expected 'signing inputs must be complete'
Assert-BuildSucceeds -VersionCode 271001
Assert-RecordedArguments -Tool hnpcli -Expected @('pack', '-i', $expectedStage, '-o', $expectedHnpOutput)
Assert-RecordedArguments -Tool hvigor -Contains @('--mode', 'module', '-p', 'product=default', 'assembleHap')
Assert-EvidenceHasNoMatch @('password', 'privateKey', 'AURA_REPOSITORY_TOKEN', $temporary)
Assert-NoPartialOutputAfterInjectedFailure
```

`Test-HarmonyShell.ps1` runs the shell entrypoint under a test shell with fake `readlink`, `java`, and HNP paths to prove missing Java exits 69, Java 16 exits 69, Java 17 receives exactly `-jar /test/hnp/share/aura/Aura-Launcher-27.1-next.jar`, and no input argument reaches Java.

- [ ] **Step 2: Run contract tests to verify RED**

```powershell
.\packaging\harmonyos\tools\Test-HarmonyPackaging.ps1
.\packaging\harmonyos\tools\Test-HarmonyShell.ps1
```

Expected: failures because `Build-HarmonyPackage.ps1` and its evidence output are absent.

- [ ] **Step 3: Implement strict packaging parameters and input verification**

Use this public parameter surface:

```powershell
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$LauncherJar,
    [Parameter(Mandatory)][string]$HnpCli,
    [Parameter(Mandatory)][string]$Hvigor,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][ValidateRange(1, [int]::MaxValue)][int]$VersionCode,
    [string]$Signer = '',
    [string]$SigningProfile = '',
    [string]$SigningCertificate = '',
    [string]$SigningKeyAlias = '',
    [string]$SigningPasswordReference = ''
)
```

Resolve all paths with `GetFullPath`, require files to exist, reject output inside the source template, and enforce the exact JAR basename and manifest version. Read the JAR through `System.IO.Compression.ZipArchive`; do not invoke the JAR. Require all five signing strings together or none. Never print signing arguments or secret references.

- [ ] **Step 4: Implement fresh staging, strict HNP metadata, and fakeable tool invocation**

Create a GUID-named stage beneath a caller-supplied output-local staging directory and delete it in `finally` only after proving its resolved path is under that staging root. Copy only:

```text
bin/aura-launcher
share/aura/Aura-Launcher-27.1-next.jar
LICENSE
NOTICE
THIRD_PARTY_NOTICES.md
hnp.json
```

Generate `hnp.json` as UTF-8 without BOM:

```json
{
  "name": "aura_launcher",
  "version": "27.1.next",
  "description": "Aura Launcher 27.1-next private HarmonyOS PC package",
  "install": {}
}
```

Invoke `hnpcli` with an argument array, verify exactly one nonempty HNP, stage it only at `entry/src/main/hnp/arm64-v8a/aura_launcher.hnp`, generate `versionCode` and `versionName` in a staged project copy, then invoke Hvigor with an argument array. Preserve no staging directory after success or failure.

- [ ] **Step 5: Implement output naming, hashing, signing state, and evidence**

Unsigned output must end `-unsigned.hap`. Only after a complete signing invocation and a separate signer verification invocation may output use the release filename without a signing adjective. Evidence has these exact top-level keys and no absolute credential/tool paths:

```json
{
  "schemaVersion": 1,
  "sourceCommit": "40 lowercase hexadecimal characters",
  "sdkVersion": "6.0.1(21)",
  "launcherVersion": "27.1-next",
  "hnpVersion": "27.1.next",
  "versionCode": 271001,
  "target": "harmonyos-arm64",
  "signingState": "unsigned",
  "tools": {"hnpcli": "bounded version", "hvigor": "bounded version"},
  "artifacts": [{"name": "file name", "size": 1, "sha256": "64 lowercase hexadecimal characters"}]
}
```

Validate evidence against `evidence.schema.json`, write `Aura-Launcher-27.1-next-harmonyos-arm64-unsigned.hap.sha256` for unsigned output or `Aura-Launcher-27.1-next-harmonyos-arm64.hap.sha256` for release-signed output, and remove partial package/evidence/hash outputs on any error.

- [ ] **Step 6: Run packaging tests to verify GREEN**

```powershell
.\packaging\harmonyos\tools\Test-HarmonyProject.ps1
.\packaging\harmonyos\tools\Test-HarmonyShell.ps1
.\packaging\harmonyos\tools\Test-HarmonyPackaging.ps1
```

Expected: all static, shell, fake-HNP, fake-Hvigor, version, cleanup, and evidence tests pass without a HarmonyOS SDK.

- [ ] **Step 7: Document the exact local packaging contract**

In `packaging/harmonyos/README.md`, include the external prerequisites `BiShengJDK17-OH`, HarmonyOS SDK `6.0.1(21)`, `hnpcli`, Hvigor, a positive version code, and external signing material. Show an unsigned invocation using explicit paths. Label all output experimental and untested. State that HNP/HAP binaries cannot be committed or published and that a real device must pass the design's acceptance gates before promotion.

- [ ] **Step 8: Commit, push, and watch CI**

```powershell
git diff --check
git add packaging/harmonyos
git commit -m "feat: build reproducible HarmonyOS package evidence"
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
git fetch origin main
git rebase origin/main
git push origin HEAD:main
Remove-Item Env:HTTPS_PROXY
```

Expected: all existing checks pass and no generated package enters Git.

### Task 5: CI Gates, Manual Real-SDK Workflow, And Support Documentation

**Files:**
- Create: `.github/workflows/harmonyos-contracts.yml`
- Create: `.github/workflows/harmonyos-sdk.yml`
- Create: `.github/scripts/test-harmonyos-workflow-policy.ps1`
- Modify: `README.md`
- Modify: `docs/PLATFORM.md`
- Modify: `docs/PLATFORM_zh.md`
- Modify: `docs/PLATFORM_zh_Hant.md`
- Modify: `packaging/harmonyos/README.md`

**Interfaces:**
- Consumes: Task 1 Java tests and Task 3/4 PowerShell contracts.
- Produces: GitHub-hosted contract CI, an isolated manual self-hosted real-SDK workflow, and bilingual support statements that keep HarmonyOS separate and experimental.

- [ ] **Step 1: Write failing workflow-policy tests**

Create `.github/scripts/test-harmonyos-workflow-policy.ps1` to assert:

```powershell
Assert-WorkflowUsesFullActionShas '.github/workflows/harmonyos-contracts.yml'
Assert-WorkflowUsesFullActionShas '.github/workflows/harmonyos-sdk.yml'
Assert-WorkflowHasOnlyTrigger '.github/workflows/harmonyos-sdk.yml' 'workflow_dispatch'
Assert-WorkflowContains '.github/workflows/harmonyos-sdk.yml' @(
    'self-hosted', 'harmonyos-sdk', 'arm64', 'environment:', 'Build-HarmonyPackage.ps1'
)
Assert-WorkflowDoesNotContain '.github/workflows/harmonyos-contracts.yml' @(
    'secrets.', 'hnpcli download', 'DevEco', 'upload-release-asset'
)
Assert-WorkflowDoesNotContain '.github/workflows/harmonyos-sdk.yml' @(
    'release create', 'upload-release-asset', 'gh release upload'
)
```

Run the script.

Expected: failure because both workflows are absent.

- [ ] **Step 2: Add GitHub-hosted contract CI**

`harmonyos-contracts.yml` runs on pull requests, pushes to `main`, and changes under `packaging/harmonyos/**`, the two modified Java production files, their tests, or the workflow itself. Use `ubuntu-24.04`, pinned `actions/checkout`, pinned `actions/setup-java`, and pinned `gradle/actions/setup-gradle`. Run:

```yaml
- name: Test HarmonyOS contracts
  shell: pwsh
  run: |
    ./packaging/harmonyos/tools/Test-HarmonyProject.ps1
    ./packaging/harmonyos/tools/Test-HarmonyShell.ps1
    ./packaging/harmonyos/tools/Test-HarmonyPackaging.ps1
    ./.github/scripts/test-harmonyos-workflow-policy.ps1
- name: Test plugin platform behavior
  run: >-
    ./gradlew :AuraLauncher:test
    --tests org.jackhuang.hmcl.plugin.runtime.PluginPlatformTargetTest
    --tests org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluatorTest
    --tests org.jackhuang.hmcl.plugin.store.PluginStoreManifestTest
    --no-daemon --stacktrace
```

- [ ] **Step 3: Add the isolated manual real-SDK workflow**

`harmonyos-sdk.yml` uses only `workflow_dispatch`, a concurrency group, `permissions: contents: read`, `[self-hosted, harmonyos-sdk, arm64]`, and a protected `harmonyos-packaging` environment. Inputs are `version_code` as required string and `signing_mode` as a choice of `unsigned`, `debug`, or `release`. Validate the source Shadow JAR as `Aura-Launcher-27.1-next.jar`, consume runner-provisioned tool paths, call `Build-HarmonyPackage.ps1`, and upload only workflow artifacts with pinned `actions/upload-artifact`. Do not create or modify a GitHub Release. Signing steps run only for complete protected-environment inputs, and command logs must not echo password values or signer command lines.

- [ ] **Step 4: Run workflow-policy tests to verify GREEN**

```powershell
.\.github\scripts\test-harmonyos-workflow-policy.ps1
.\packaging\harmonyos\tools\Test-HarmonyProject.ps1
.\packaging\harmonyos\tools\Test-HarmonyShell.ps1
.\packaging\harmonyos\tools\Test-HarmonyPackaging.ps1
```

Expected: all workflow and packaging policies pass locally without SDK tools.

- [ ] **Step 5: Add separate HarmonyOS sections and plugin/runtime matrices**

In the root bilingual README, add `HarmonyOS PC (ARM64, experimental)` after the ordinary platform sentence and link the three platform pages plus `packaging/harmonyos/README.md`. State in all languages: HarmonyOS PC uses a Linux kernel, so Linux ARM64 artifacts may work in principle, but Aura Launcher, JavaFX, Minecraft launching, and every external Runtime Host remain untested on real HarmonyOS PC hardware.

Do not add HarmonyOS as a claim inside the inherited Windows/Linux/macOS/FreeBSD upstream tables. Instead, append a separate HarmonyOS PC section to each platform page with:

| Capability | HarmonyOS PC ARM64 status |
| --- | --- |
| Plugin platform identity | Built in as `harmonyos-arm64` |
| Native HarmonyOS NPL selection | Exact target preferred; no public native artifact yet |
| Linux ARM64 NPL selection | One-way fallback; experimental and untested |
| Built-in Java Runtime | Present; launcher/JavaFX path untested |
| Aura Rust Runtime Host | Linux ARM64 fallback; untested |
| Aura .NET Runtime Host | Linux ARM64 fallback; untested |
| Aura QuickJS Runtime Host | Linux ARM64 fallback; untested |
| Aura Wasm Runtime Host | Linux ARM64 fallback; untested |
| Python Runtime Host | Unavailable |
| Minecraft launch | Unavailable as a support claim until a real-device gate passes |

Other architectures must be explicitly unsupported. Historical upstream names remain only in provenance or inherited historical matrix notes; all new current-subject prose says Aura Launcher.

- [ ] **Step 6: Run full release-quality local verification**

```powershell
$env:BUILD_VERSION = '27.1'
.\gradlew.bat checkstyle checkTranslations test shadowJar --no-daemon --stacktrace
Remove-Item Env:BUILD_VERSION
.\.github\scripts\test-harmonyos-workflow-policy.ps1
.\packaging\harmonyos\tools\Test-HarmonyProject.ps1
.\packaging\harmonyos\tools\Test-HarmonyShell.ps1
.\packaging\harmonyos\tools\Test-HarmonyPackaging.ps1
git diff --check
$jars = @(Get-ChildItem AuraLauncher\build\libs\Aura-Launcher-*.jar)
if ($jars.Count -ne 1 -or $jars[0].Name -cne 'Aura-Launcher-27.1-next.jar') {
    throw "Unexpected Aura Shadow JAR set: $($jars.Name -join ', ')"
}
git ls-files | rg '\.(hnp|hap|p12|pfx|pem|key|jks)$'
rg -n "HMCL CE|HMCL-CE" README.md docs/PLATFORM*.md packaging/harmonyos
```

Expected: all Gradle and contract gates pass, exactly one correctly named Shadow JAR exists, no forbidden package/key file is tracked, and the final branding scan contains only explicitly reviewed historical provenance or compatibility references.

- [ ] **Step 7: Commit, integrate remote movement, push, and monitor every workflow**

```powershell
git add .github/workflows/harmonyos-contracts.yml .github/workflows/harmonyos-sdk.yml `
  .github/scripts/test-harmonyos-workflow-policy.ps1 README.md docs/PLATFORM.md `
  docs/PLATFORM_zh.md docs/PLATFORM_zh_Hant.md packaging/harmonyos/README.md
git commit -m "ci: gate experimental HarmonyOS packaging"
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
git fetch origin main
git rebase origin/main
git push origin HEAD:main
$sha = git rev-parse HEAD
$runs = gh run list --repo Egg-China/Aura-Launcher --commit $sha --limit 20 `
  --json databaseId,name,status,conclusion
$runs
foreach ($run in ($runs | ConvertFrom-Json)) {
    gh run watch --repo Egg-China/Aura-Launcher $run.databaseId --exit-status
}
Remove-Item Env:HTTPS_PROXY
```

Expected: `Java CI`, `Check Codes` when triggered, and `HarmonyOS Packaging Contracts` all reach `success`. The manual real-SDK workflow remains undispatched because this machine has no provisioned HarmonyOS SDK, signer, or ARM64 HarmonyOS PC.

- [ ] **Step 8: Record the honest delivery boundary**

Run `git status --short --branch` in Aura, SDK, and Store worktrees and confirm each delivery branch is clean and aligned with its remote. Record the pushed SHAs and CI run URLs. Report that static and fake-tool packaging are implemented and green, while actual `.hnp`/`.hap` production, signing, installation, JavaFX rendering, Runtime Host execution, and Minecraft launch remain blocked on the separately provisioned SDK/signing runner and a real ARM64 HarmonyOS PC; do not label those device-dependent gates as passed.
