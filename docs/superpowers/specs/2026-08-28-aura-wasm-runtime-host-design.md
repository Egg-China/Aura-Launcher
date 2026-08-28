# Aura Wasm Runtime Host Design

## Purpose

Aura Wasm Runtime Host makes WebAssembly components a separately installable schema-v5 runtime.
It embeds Wasmtime, executes one Component Model payload per supervised process, exposes Aura Bridge
through a stable WIT contract, and supplies a constrained WASI 0.2 environment. Payload authors can
use any Component Model toolchain; the first beta includes a Rust guest SDK source tree and example
but does not require Rust at runtime.

The first public version is `0.1.0-beta.1`. It is a GitHub prerelease and an Aura Store `beta`
entry. It requires Aura `>=27.1-0-next`, Plugin ABI 1, and Bridge ABI 1.

## Repository And Product Identity

- Public repository: `Egg-China/Aura-Wasm-Runtime-Host`.
- Local repository: `Plugins/Aura-Wasm-Runtime-Host`.
- Default branch: `main`.
- License: GPL-3.0-or-later.
- GitHub topic: `aura-launcher`.
- Plugin ID: `dev.hmclce.runtime.wasm-host`.
- Provided runtime: `wasm`.
- Execution mode: `isolated` only.
- Features: `bridge`, `hooks`, and `native`.
- Required permission: `native-code`.
- Platforms: Windows, Linux, and macOS on x64 and arm64.

The Java bootstrap retains compatibility namespaces required by Aura. The Host does not modify the
SDK `schema-v4` branch and does not add Wasmtime to Aura itself.

## Goals

- Execute Component Model payloads through Aura's shared `RuntimeProcessSession`.
- Preserve process protocol v1 and Bridge Value v1 bytes.
- Freeze a small WIT world for lifecycle and Bridge callbacks.
- Provide WASI 0.2 with read-only `/plugin`, no networking, no inherited environment, and no
  inherited stdio.
- Enforce memory, fuel, epoch, table, instance, path, and protocol limits per payload.
- Publish six platform NPLs, WIT, a Rust guest SDK archive, examples, SBOM, checksums, and Store
  metadata.

## Non-Goals

- Raw core WebAssembly modules, preview1-only modules, or automatic component adaptation at load.
- Browser APIs, HTTP, sockets, host environment inheritance, or writable host filesystem access.
- An embedded execution mode, direct JVM access, or shared Wasmtime stores between payloads.
- A malicious-code security sandbox after `native-code` is granted; the process and Wasmtime limits
  are fault and capability boundaries, not a claim against all native-host compromise.
- Publishing the Rust guest SDK to crates.io in the first beta.
- Changing process protocol v1, Bridge Value v1, or Aura's shared supervisor.

## Architecture

The repository contains five independently testable units:

1. `host-plugin` is the thin Java Runtime Provider. It resolves the packaged executable, owns
   payload handles, and delegates to `RuntimeProcessSession`.
2. `aura-runtime-protocol` is an audited source snapshot from Rust Host commit
   `8e65a577d20903ad6eb07ff2afc536c049b9e907`. Golden vectors and provenance prevent protocol
   drift without a network Git dependency.
3. `aura-wasm-host` owns stdio framing, payload descriptor validation, the lifecycle state machine,
   callback routing, and process exit behavior.
4. `aura-wasm-engine` owns Wasmtime configuration, component instantiation, WIT bindings, WASI
   context, Bridge imports, fuel, epoch interruption, and resource limits.
5. `sdk` contains the versioned WIT package, Rust guest wrappers, component build/package tools,
   and a launch-hook example. It ships as a Release archive, not a crates.io package.

Wasmtime crates are pinned to one compatible version in `Cargo.lock`, with Component Model, async
support only where Host internals require it, and WASI 0.2 features explicitly selected. The public
guest lifecycle remains synchronous.

## Provider And Process Data Flow

Aura selects the Provider for a schema-v5 `wasm` payload and starts one Host process through
`RuntimeProcessSession.start(executable, context)`. Aura owns environment filtering, deadlines,
stderr capture, capability authorization, and final termination.

After `hello`, the Host receives `load`, validates `aura-wasm.json`, reads the component within the
package root, rejects a core module, creates a new Wasmtime engine/store/linker/WASI context, and
instantiates the component. It calls the exported synchronous `load`. Later process messages call
`enable`, `invoke`, `disable`, and `unload` during `shutdown`.

The state machine is strictly:

`created -> loaded -> enabled -> disabled -> unloaded`

One parent command runs at a time. Guest Bridge imports use the existing even request-ID callback
direction while the odd parent request is active. Every payload owns a distinct process, store,
component instance, resource table, WASI context, handle set, fuel budget, and epoch deadline.

## Payload Descriptor And Component Validation

The payload entrypoint is a UTF-8 JSON file with exactly these fields:

```json
{
  "schemaVersion": 1,
  "component": "plugin.wasm"
}
```

Unknown or missing fields are errors. `schemaVersion` must be integer `1`. `component` must be a
non-empty relative `/`-separated path ending in `.wasm`, with no empty, `.`, or `..` segment,
backslash, drive prefix, UNC prefix, NUL, or URI scheme. Canonical resolution and every symlink or
junction remain beneath the package root. The target must be a regular file.

Wasmtime Component validation must succeed before instantiation. A valid raw core module is still
rejected because it does not implement the Component Model contract. The Host performs no
preview1-to-preview2 or module-to-component adaptation; packaging tools do that before NPL
creation.

## WIT Contract

The Release contains the exact `aura:runtime@0.1.0` WIT package. Its world is
`aura-plugin-v1`. The component imports `aura:runtime/bridge@0.1.0` and exports
`aura:runtime/plugin@0.1.0`.

The Bridge interface provides synchronous functions:

```wit
invoke: func(operation: string, input: list<u8>) -> result<list<u8>, string>;
retain-handle: func(object-id: u64, generation: u64) -> result<_, string>;
release-handle: func(object-id: u64, generation: u64) -> result<_, string>;
```

The returned string is a stable lower-case kebab Bridge error code, never a JVM exception or
diagnostic. The lifecycle export provides:

```wit
record plugin-error {
  code: string,
  message: string,
}

load: func() -> result<_, plugin-error>;
enable: func() -> result<_, plugin-error>;
invoke: func(
  operation: string,
  input: list<u8>,
  callback-id: u64
) -> result<list<u8>, plugin-error>;
disable: func() -> result<_, plugin-error>;
unload: func() -> result<_, plugin-error>;
```

Guest error codes must be lower-case kebab text of at most 128 bytes. Messages must be nonblank
UTF-8 and at most 4096 bytes before Host sanitization. Wrong world, missing exports, unexpected
imports, invalid result types, or malformed errors fail loading or invocation.

Bridge Values cross WIT only as canonical Bridge Value v1 `list<u8>`. The Host validates bytes
before sending a parent callback and validates returned bytes before handing them to the component.
The WIT ABI does not duplicate the Bridge Value type tree and therefore cannot drift from Aura's
canonical codec.

## WASI 0.2 Environment

The component receives a preopened `/plugin` directory backed by the canonical package root. It is
read-only: create, write, append, truncate, rename, remove, and metadata mutation fail. Capability
filesystem resolution prevents symlink traversal outside the preopen.

No environment variables, command-line arguments, sockets, DNS, HTTP, or inherited host handles
are supplied. Secure random and WASI clocks are available. Guest stdin is closed. Guest stdout and
stderr are bounded in-memory diagnostic sinks and are never connected to Host stdout; sanitized
tails may be copied to Host stderr after a failure. Host stdout remains protocol-only.

## Resource Limits

Each payload has a hard aggregate linear-memory limit of 256 MiB. The store also limits tables,
table elements, memories, instances, and component resources to conservative constants documented
beside the configuration and covered by boundary tests. Limits are Host constants in the first
beta and cannot be raised by a payload descriptor.

Every lifecycle operation receives a fresh fuel budget and a local ten-second deadline. Fuel is
not carried between operations. A monotonic epoch ticker interrupts calls at the deadline, while
Aura's process deadline remains authoritative. Blocking Bridge callbacks consume the same
operation deadline and do not reset fuel or epoch time.

## Failure Semantics

Descriptor, path, component validation, WIT mismatch, WASI construction, and instantiation failures
abort `load` and create no valid handle. A component-declared `plugin-error` becomes a bounded
`guest-error`. It is recoverable only if the store and component remain internally valid.

Protocol violations, unexpected Host stdout, traps, fuel exhaustion, epoch interruption, resource
limit failures, Wasmtime internal errors, invalid Bridge bytes, and cleanup failures poison the
session. A poisoned process writes one bounded redacted diagnostic to stderr and exits nonzero. A
trap is treated as fatal even when Wasmtime could technically call the instance again; continuing
would make lifecycle state and guest invariants ambiguous.

Lifecycle commands are never retried. `disable` and `unload` are best effort during shutdown, but
process termination, resource destruction, handle revocation, and cleanup are unconditional and
idempotent. Aura captures the final 64 KiB of stderr and Protector retains startup recovery.

Stable Host error codes include `invalid-descriptor`, `path-escape`, `invalid-component`,
`wit-mismatch`, `invalid-value`, `guest-error`, `bridge-denied`, `resource-limit`, `fuel-exhausted`,
`deadline-exceeded`, `protocol-error`, and `runtime-failure`.

## Testing

Development is test-first. Required coverage includes:

- exact protocol and Bridge Value golden vectors from Rust Host commit `8e65a577...`;
- descriptor unknown fields, wrong types, unsafe paths, symlink escape, core modules, malformed
  components, wrong world, imports, exports, and result types;
- WIT Bridge callback success and denial, canonical `list<u8>` validation, retain/release, and
  callback reentry;
- every valid and invalid lifecycle transition with real Component Model fixtures;
- read-only `/plugin`, symlink containment, closed stdin, bounded guest diagnostics, no inherited
  environment, and denied network access;
- memory, table, instance, component-resource, fuel, and epoch boundaries;
- trap, child crash, EOF, bad frame, wrong request ID, Host stdout logging, stderr truncation, and
  cleanup after partial instantiation;
- two simultaneous payloads proving process, store, WASI, handle, and state isolation;
- Java Provider descriptor, foreign handle, session cleanup, and real-process integration tests;
- a real Rust guest `before-game-launch` example whose mutation reaches Aura's final launch plan.

CI runs Rust formatting, Clippy with warnings denied, workspace tests, Java Provider tests against
the exact Aura 27.1 CI JAR, WIT validation, guest SDK/example builds, and dependency/license checks.
All six NPLs are built. Native runners execute real Host smoke tests where available; cross-built
binaries are checked for target architecture and expected dynamic dependencies.

## Packaging And Release

Each NPL contains Java Provider classes, the exact platform Host executable, GPL and Wasmtime
notices, and schema-v5 `plugin.json`. It declares Runtime ABI 1, Bridge ABI 1, isolated mode only,
`bridge/hooks/native`, `native-code`, and `launcherVersion: >=27.1-0-next`.

The tag `v0.1.0-beta.1` creates a draft prerelease. Release assets are:

- six platform-specific NPL files;
- a merged artifact manifest;
- `SHA256SUMS.txt`;
- SPDX or CycloneDX SBOM;
- `aura-wasm-guest-sdk-v0.1.0-beta.1.zip` containing WIT, Rust source, tools, and example.

The workflow builds against the exact Aura artifact from commit
`6d37f20d104c8e8d1c8b2b693ce1944207b85f84`, CI run `33159830461`, whose JAR SHA-256 is
`d3a0918e4f27a8ce16958c7321ecb3a6113e1dc24b0762425b5fbd4d8d5c9d92`. Repository secret
`AURA_REPOSITORY_TOKEN` grants read access. It is never printed or committed.

Before publication, CI downloads all assets from public Release URLs and verifies SHA-256, size,
NPL manifest, target platform, executable architecture, SDK contents, WIT version, example build,
and SBOM presence. A mismatch leaves the Release as draft.

After successful public verification, root `manifest.json` is published with a `beta` channel and
six immutable artifact URLs. The official Aura Store `plugins.json` pins the exact root manifest
SHA-256. Store validation downloads the public manifest and assets and runs an Aura 27.1 install
plan. This work does not enable the built-in Store or weaken its unsigned-root security boundary.

## Deferred Work

- crates.io guest SDK publication and generated SDKs for other guest languages.
- Raw core modules, automatic adapters, preview1-only payloads, and networking.
- Embedded Wasm execution and direct JVM access.
- Additional runtime ABI, Bridge ABI, or WIT generations.

