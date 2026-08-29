# Aura HarmonyOS PC Packaging Design

## Purpose

Aura Launcher will recognize HarmonyOS PC as a distinct plugin platform and provide an experimental
ARM64 Stage HAP package. The package installs an Aura-owned private OpenHarmony Native Package
(HNP) and starts the existing Aura Launcher JAR through the separately installed
`BiShengJDK17-OH` public HNP.

The first delivery is intentionally experimental. HarmonyOS PC uses a Linux kernel and can in
principle execute Linux ARM64 runtime-host artifacts, but Aura's JavaFX UI, Minecraft launching,
and the published runtime hosts have not been tested on a HarmonyOS PC. No documentation, Store
entry, workflow, or Release may describe the platform as supported until the device gates in this
design have passed.

## Reference Basis

The packaging layout follows the OpenHarmony native-package flow described by:

- the user-provided HarmonyOS PC HNP/HAP packaging article at
  `https://harmonypc.csdn.net/6916a6a00e4c466a32e7c283.html`;
- OpenHarmony's `startup_appspawn/service/hnp/README_zh.md` and
  `startup_appspawn/service/hnp/pack/README_zh.md`;
- the `harmonyos-17.0.13` branch of
  `OpenHarmonyPCDeveloper/BiShengJDKInstaller`.

Those sources establish that an HNP is not a standalone application package. Native payloads are
packed by `hnpcli`, placed below the HAP project's `hnp/arm64-v8a/` directory, declared through
`module.json5`, included in a Stage HAP, and finally signed. They also establish that public-HNP
and private-HNP `bin` links are available to the application process and that C++ application code
may launch HNP executables with `execv` or equivalent process APIs.

## Product And Compatibility Boundaries

- Product name and user-visible package identity are `Aura Launcher`; no legacy fork suffix is
  introduced.
- Aura remains on `27.1-next`. The Gradle project version, JAR filename, JAR
  `Implementation-Version`, HAP `versionName`, and user-visible diagnostics retain exactly one
  `-next` suffix.
- The HAP bundle name is `com.eggchina.auralauncher`.
- The first HAP targets HarmonyOS PC `2in1` devices on ARM64 only, with HarmonyOS SDK
  `6.0.1(21)` as the initial compatible and target SDK baseline.
- The Aura HNP is named `aura_launcher`, is declared `private`, and exposes only an application-local
  `aura-launcher` command.
- `BiShengJDK17-OH` is a separately installed prerequisite. Its JDK or HNP is never copied into the
  Aura repository, HNP, HAP, CI artifact, or Release asset.
- Aura's plugin manifest remains schema v5, Store index schemas remain unchanged, and schema v4 is
  not modified.
- Compatibility package names and protocol identifiers remain unchanged where changing them would
  break source, binary, data, or plugin compatibility.
- `AuraPluginSystem/` remains Apache-2.0. The HarmonyOS application wrapper and packaging files are
  launcher distribution code under the root GPLv3 boundary.
- The launcher core continues to use its Linux-compatible execution paths on HarmonyOS. Distinct
  HarmonyOS identity is introduced at the Aura plugin-platform boundary instead of adding a new
  upstream `OperatingSystem` enum value and forcing unrelated launcher subsystems to recognize it.

## Goals

- Detect HarmonyOS/OpenHarmony hosts as the canonical plugin target `harmonyos-arm64` on current
  HarmonyOS PC hardware.
- Preserve `harmonyos` as a distinct operating-system identity in schema-v5 manifests, Store
  artifacts, diagnostics, and documentation.
- Prefer a native `harmonyos-arm64` plugin artifact when one exists and otherwise allow a deliberate
  one-way fallback to `linux-arm64`.
- Produce a reproducible Stage HAP project containing a private Aura HNP, native launch bridge,
  bilingual status UI, Aura icon, and exact Aura JAR.
- Detect missing or unsuitable BiSheng JDK installations and surface bounded launch diagnostics in
  the HAP UI.
- Keep package signing credentials outside Git and make unsigned, debug-signed, and release-signed
  output states unambiguous.
- Add contract CI that does not require proprietary local state, plus an opt-in real SDK/device
  workflow for HNP, HAP, signing, install, and launch verification.

## Non-Goals

- Claiming that Linux OpenJFX native libraries work on HarmonyOS.
- Porting OpenJFX, AWT, Swing, LWJGL, Minecraft native libraries, or any runtime-host engine to the
  HarmonyOS ABI in this delivery.
- Bundling a JRE or replacing the official BiSheng JDK installer.
- Publishing an Aura HAP to a public Release or application market before true-device gates pass.
- Treating an HNP as an independently installable package.
- Making HarmonyOS artifacts eligible on Linux hosts.
- Adding HarmonyOS x64, ARM32, phone, tablet, television, wearable, or embedded-device support.

## Plugin Platform Identity

`PluginPlatformTarget` gains the canonical operating-system identifier `harmonyos`. The existing
architecture vocabulary remains unchanged. Schema parsers may represent future combinations such
as `harmonyos-x64`, but the first package, compatibility fallback, documentation, and release gates
support only `harmonyos-arm64`.

Host detection uses two inputs in deterministic order:

1. A normalized `os.name` containing `harmonyos` or `openharmony` identifies HarmonyOS.
2. Otherwise, when `os.name` follows a Linux-compatible path, a UTF-8 read capped at 64 KiB of
   `/etc/os-release` identifies HarmonyOS when the parsed `ID`, `NAME`, `PRETTY_NAME`, or `ID_LIKE`
   value contains a case-insensitive `harmonyos` or `openharmony` marker.

Unreadable, oversized, malformed, or absent release metadata does not crash startup and does not
guess HarmonyOS. Existing Windows, macOS, FreeBSD, and Linux detection remains unchanged. Tests use
an input-oriented detection helper and never modify process-global system properties concurrently.

Compatibility is asymmetric:

- `harmonyos-arm64` matches a HarmonyOS ARM64 host.
- `linux-arm64` and architecture-neutral `linux` declarations also match a HarmonyOS ARM64 host.
- `harmonyos-arm64` never matches a Linux ARM64 host.
- No Linux fallback is allowed for another HarmonyOS architecture.
- Other operating systems and architecture mismatches retain exact existing behavior.

This rule lets the currently published Linux ARM64 Runtime Hosts participate in an experimental
HarmonyOS install plan without misrepresenting them as native HarmonyOS builds.

## Store Artifact Selection

For a platform artifact matrix, `requireArtifact(host)` applies this deterministic order:

1. Select an exact artifact equal to the host target.
2. For `harmonyos-arm64` only, select `linux-arm64` when no exact artifact exists.
3. Otherwise fail with the existing available-target diagnostic, extended to identify the attempted
   compatibility target when applicable.

An exact HarmonyOS artifact always wins regardless of matrix order. Legacy single-package Store
entries retain their existing compatibility view. No fallback crosses source, version, checksum,
size, permission, runtime, ABI, or transaction boundaries: it changes only the selected artifact
inside the already selected version entry.

Manifest compatibility and artifact selection use the same asymmetric rule. A version cannot pass
its declared `platforms` check and then select an artifact using a broader or different fallback.

## Stage HAP Architecture

The repository adds a self-contained project at `packaging/harmonyos/` with these units:

1. `AppScope` and `entry` define a HarmonyOS Stage application for `2in1` devices.
2. A small ArkTS UIAbility presents prerequisite, starting, running, early-exit, and diagnostic
   states. It never claims successful JavaFX startup merely because process creation succeeded.
3. An ARM64 N-API library owns process creation and polling. It starts only the fixed
   `/data/app/bin/aura-launcher` private-HNP link, passes no user-controlled command or shell text,
   rejects duplicate live children, redirects output to an application-private bounded log, and
   reports a PID or stable launch error.
4. The private Aura HNP contains `bin/aura-launcher`, `share/aura/Aura-Launcher-27.1-next.jar`,
   `hnp.json`, root GPL notices, and third-party notices required by the complete launcher artifact.
5. The shell entrypoint resolves its real HNP location, finds `java` through the HNP-provided
   application PATH, verifies Java 17 or later, and then uses `exec java -jar` with the exact Aura
   JAR. The fixed arguments never include Store tokens, signing data, or external shell input.

The wrapper UI polls the child without blocking the ArkUI thread. A process that survives the
15-second early-startup window is reported as `started`, not as `verified`. An early exit exposes
only the last 16 KiB of the application-private diagnostic log. The bridge caps every returned
string and numeric field, validates PIDs before polling, and treats cleanup as idempotent.

The initial HAP permission allowlist is `ohos.permission.INTERNET`,
`ohos.permission.FILE_ACCESS_PERSIST`, `ohos.permission.READ_WRITE_DESKTOP_DIRECTORY`,
`ohos.permission.READ_WRITE_DOCUMENTS_DIRECTORY`, and
`ohos.permission.READ_WRITE_DOWNLOAD_DIRECTORY`. Permission additions require a separate review.
The wrapper does not expose a terminal, arbitrary command execution, a generic native-process API,
or a path picker for executables.

## Version And Package Mapping

The source launcher version is the canonical Gradle value, for example `27.1-next`.

- JAR filename: `Aura-Launcher-27.1-next.jar`.
- JAR `Implementation-Version`: `27.1-next`.
- HAP `versionName`: `27.1-next`.
- HNP `version`: replace the one terminal `-next` with `.next`, producing `27.1.next`. This keeps an
  HNP-safe version under the OpenHarmony 31-byte limit without removing the channel identity.
- HAP `versionCode`: a checked positive integer supplied explicitly by the packaging invocation; it
  is not guessed from arbitrary SemVer text.

The build fails when the launcher version lacks exactly one terminal `-next`, when filenames and
manifest metadata disagree, when the HNP version is not the deterministic mapping, or when the
requested HAP version code is not a positive integer.

## Reproducible Packaging Flow

The packaging command accepts explicit paths for the Aura JAR, `hnpcli`, HarmonyOS SDK/Hvigor
entrypoint, output directory, and optional signing inputs. It performs these stages:

1. Verify the clean source tree inputs, ARM64-only target, launcher filename, JAR digest, embedded
   version, HNP-safe version, and package metadata.
2. Create a fresh staging directory and copy only the allowlisted HNP files.
3. Generate strict `hnp.json`, make the shell entrypoint executable, and run
   `hnpcli pack -i <stage> -o <output>`.
4. Place the resulting `aura_launcher.hnp` at `hnp/arm64-v8a/` in a staged HAP project and verify
   that `module.json5` declares it as `private`.
5. Invoke the pinned HarmonyOS/Hvigor build in release mode to create an unsigned HAP.
6. When complete signing inputs are provided, invoke the pinned HAP signer and verify the signed
   HAP. Partial signing input is an error and never silently falls back to unsigned output.
7. Emit SHA-256 files and a machine-readable evidence manifest containing source commit, tool
   versions, SDK version, launcher version, version code, artifact names, sizes, hashes, and signing
   state. It contains no credential path, password, certificate private material, or environment
   dump.

Build and staging outputs are ignored. HNP or HAP binaries are not committed. The script never
downloads an SDK, JDK, certificate, launcher JAR, or private key implicitly.

## Signing And Release Security

Release signing consumes externally provisioned HarmonyOS profile, certificate chain, private key,
and secret-manager password references. Repository files contain only documented parameter names
and public trust material. Logs redact values and do not print signing commands with secret
arguments.

Unsigned HAPs are named with an explicit `-unsigned` suffix and are CI evidence only. Debug-signed
packages are named `-debug-signed` and are never Release assets. Only a successfully verified
release-signed package may use `Aura-Launcher-<version>-harmonyos-arm64.hap`.

The first implementation does not alter the existing general Aura Release workflow. HarmonyOS uses
an isolated manual workflow until every device gate passes and the user explicitly approves public
release integration.

## CI And Test Strategy

Development is test-first. Required Java coverage includes:

- parsing and normalization of `harmonyos-arm64`;
- positive `os.name` and `/etc/os-release` markers plus malformed, oversized, unreadable, and
  non-Harmony Linux metadata;
- Linux-to-Harmony manifest compatibility and proof that the reverse direction is rejected;
- exact Harmony artifact preference over Linux fallback independent of matrix order;
- Linux ARM64 fallback only for HarmonyOS ARM64;
- no fallback across architecture, operating system, version entry, or legacy validation rules.

Packaging contract tests run without a HarmonyOS SDK by using temporary fake tool executables. They
prove input validation, exact tool arguments, generated HNP layout, executable mode expectations,
private `hnpPackages` declaration, version mapping, partial-signing rejection, evidence redaction,
and cleanup after failures. Static tests validate the Stage project, bundle/device identity,
permission allowlist, ArkTS-to-N-API surface, fixed executable path, process/log bounds, and absence
of committed HNP/HAP/JDK/signing binaries.

Normal GitHub-hosted CI runs Java tests, checkstyle, translation checks, packaging contract tests,
workflow policy tests, and the ordinary Shadow JAR build. A separate manually dispatched workflow
targets a pre-provisioned runner with the pinned HarmonyOS SDK and performs real HNP and unsigned
HAP construction. Signing and device jobs run only when their explicit protected environment is
selected. All third-party Actions remain pinned to full commit SHAs.

## True-Device Acceptance Gates

The package remains experimental until an ARM64 HarmonyOS PC proves all of the following from a
single source commit:

1. Install and signature verification succeed, and uninstall removes the private HNP.
2. The plugin platform reports `harmonyos-arm64` while launcher Linux-compatible paths remain
   operational.
3. Missing BiSheng JDK is diagnosed without a crash; installed BiSheng JDK 17 or later is selected.
4. The wrapper starts Aura, detects an early failure, and bounds its diagnostics correctly.
5. The JavaFX Aura window becomes visible and nonblank, receives input, and closes cleanly.
6. Store planning prefers a fixture `harmonyos-arm64` NPL, then successfully exercises a separate
   fixture using the `linux-arm64` fallback.
7. Built-in Java and every advertised external Runtime Host complete a real schema-v5 load,
   enable, Bridge/Hook invocation, disable, and unload cycle, or are explicitly marked unavailable.
8. At least one supported Minecraft version completes native resolution and reaches a running game
   process, or game launching remains explicitly marked unavailable.
9. Repeated launch, crash, Protector recovery, update, and uninstall leave no orphan process,
   private HNP, plugin security state, or shared legacy data.

Evidence includes device/OS build, JDK build, HAP hash, source commit, selected NPL hashes, bounded
logs, and screenshots. It contains no account token, signing secret, absolute private-key path, or
personal game data.

## Documentation And Support Matrix

The root bilingual README and English, Simplified Chinese, and Traditional Chinese platform pages
list HarmonyOS PC as its own operating system column or section. Only ARM64 receives an experimental
marker. Other architectures remain not supported.

The platform pages add a plugin-system and Runtime Host matrix separate from upstream launcher,
game, and Terracotta claims. HarmonyOS cells state whether support is built in, selected through the
Linux ARM64 fallback, unavailable, or not tested. A visible tip explains that HarmonyOS PC uses a
Linux kernel, so Linux ARM64 artifacts may work in principle, but Aura Launcher, JavaFX, Minecraft,
and current Runtime Hosts have not been tested on real hardware.

Historical upstream names remain only in provenance, compatibility, and historical support text.
Current-product prose uses Aura Launcher.

## Delivery Sequence

1. Land this design and the test-first platform identity/artifact-selection change.
2. Land documentation and SDK/Store vocabulary support for `harmonyos-arm64` without changing
   existing public artifacts.
3. Land the Stage HAP project, native bridge, private HNP layout, and SDK-free contract tests.
4. Land the opt-in real SDK workflow after its toolchain inputs are reproducibly pinned.
5. Produce unsigned and debug-signed evidence builds, then perform true-device validation.
6. Consider release-signed packaging and Store/Release integration only after every applicable
   acceptance gate passes and the user explicitly approves promotion.

Every commit must pass its focused tests before a normal non-force push. Remote `main` movement is
fetched and integrated before each push.

## Deferred Work

- A native OpenJFX port or a HarmonyOS-native Aura UI.
- Bundled or application-private BiSheng JDK distributions.
- HarmonyOS-specific runtime-host engine builds and `harmonyos-arm64` NPL assets.
- HarmonyOS application-market publication and stable-channel promotion.
- Non-ARM64 HarmonyOS PC and non-PC HarmonyOS device families.
