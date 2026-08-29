# Aura Launcher for HarmonyOS PC

This directory contains the experimental ARM64 Stage application and private-HNP source layout for
Aura Launcher on HarmonyOS PC. It targets `2in1` devices and HarmonyOS SDK `6.0.1(21)`.

The package does not include Java. Install `BiShengJDK17-OH` separately so its public HNP exposes a
Java 17 or later executable to the application. The private `aura_launcher` HNP contains only the
fixed launcher entrypoint, the exact `Aura-Launcher-27.1-next.jar`, and required license notices.

This target is experimental and has not been tested on a physical HarmonyOS PC. A Linux ARM64
kernel does not establish that JavaFX, Minecraft, or external Runtime Hosts work correctly. Do not
publish an HNP or HAP until the device acceptance gates in the packaging design have passed.

Generated HNP/HAP files, JDK archives, SDK files, signing profiles, certificates, and private keys
must stay outside Git. The application wrapper and packaging sources remain under the launcher's
GPL-3.0 license boundary.

## Packaging prerequisites

The packaging command requires all of the following to be provisioned outside this repository:

- `BiShengJDK17-OH` installed separately on the target HarmonyOS PC;
- HarmonyOS SDK `6.0.1(21)` with an ARM64-capable Hvigor installation;
- `hnpcli` from the matching HarmonyOS toolchain;
- the exact `Aura-Launcher-27.1-next.jar`, including the matching JAR
  `Implementation-Version`;
- an explicit positive HAP `versionCode`; and
- for signed builds, an external signer adapter plus a complete signing profile, certificate, key
  alias, and secret-manager password reference.

The signer adapter must expose separate `sign` and `verify` commands. The package builder does not
download tools, resolve credentials, invoke the launcher JAR, or print signing arguments.
Pass `-SigningKind debug` only with a complete signing set to produce an explicitly named
`-debug-signed.hap`; the default signing kind is `release`.

## Unsigned evidence build

Run the builder from a PowerShell session and keep its output directory outside
`packaging/harmonyos`:

```powershell
$jar = Resolve-Path 'C:\aura-input\Aura-Launcher-27.1-next.jar'
$hnpcli = Resolve-Path 'C:\HarmonyOS\tools\hnpcli.exe'
$hvigor = Resolve-Path 'C:\HarmonyOS\tools\hvigorw.bat'
$output = 'C:\aura-output\harmonyos'

& .\packaging\harmonyos\tools\Build-HarmonyPackage.ps1 `
    -LauncherJar $jar `
    -HnpCli $hnpcli `
    -Hvigor $hvigor `
    -OutputDirectory $output `
    -VersionCode 271001
```

An unsigned build emits an explicitly suffixed `.hap`, its SHA-256 sidecar, and schema-v1 evidence.
Providing any signing input requires all five signing inputs; partial configuration fails without
falling back to an unsigned result. Debug signing always retains the `-debug-signed` adjective. A
release filename is emitted only after separate signing and verification operations both succeed.

## Manual real-SDK workflow

The `HarmonyOS Real SDK Evidence` workflow is manual-only. It runs on a separately administered
runner carrying the `self-hosted`, `harmonyos-sdk`, and `arm64` labels and requires approval through
the protected `harmonyos-packaging` environment. The environment supplies these public path
variables:

- `HARMONYOS_HNPCLI_PATH` and `HARMONYOS_HVIGOR_PATH`;
- `HARMONYOS_SIGNER_PATH` for signed builds;
- `HARMONYOS_DEBUG_SIGNING_PROFILE`, `HARMONYOS_DEBUG_SIGNING_CERTIFICATE`, and
  `HARMONYOS_DEBUG_SIGNING_KEY_ALIAS`; and
- the corresponding `HARMONYOS_RELEASE_SIGNING_*` variables for release evidence.

Debug and release signing also require the protected
`HARMONYOS_DEBUG_SIGNING_PASSWORD_REFERENCE` or
`HARMONYOS_RELEASE_SIGNING_PASSWORD_REFERENCE` environment secret. These values are references to
external secret-manager material, not embedded passwords or private keys.

The workflow uploads only short-lived GitHub Actions evidence artifacts and deletes its runner-local
output afterward. It never creates or updates a GitHub Release. The SDK, JDK, HNP tools, signing
adapter, certificates, and keys must already be provisioned on the protected runner.

All outputs remain experimental and untested. HNP/HAP binaries must not be committed or published as
public Release or application-market assets, and no package may be promoted until a real ARM64
HarmonyOS PC passes every acceptance gate in the
[packaging design](../../docs/superpowers/specs/2026-08-29-aura-harmonyos-pc-packaging-design.md).
