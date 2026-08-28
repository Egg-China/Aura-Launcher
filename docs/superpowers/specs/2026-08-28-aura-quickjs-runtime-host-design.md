# Aura QuickJS Runtime Host Design

## Purpose

Aura QuickJS Runtime Host makes JavaScript a separately installable schema-v5 runtime without
embedding JavaScript in Aura itself. The Host runs every payload in its own supervised process,
embeds QuickJS-NG, and exposes Aura Bridge and launch Hooks through the frozen process protocol.
JavaScript plugin authors write UTF-8 ES modules and do not need Rust, Node.js, or a system
JavaScript installation.

The first public version is `0.1.0-beta.1`. It is a GitHub prerelease and an Aura Store `beta`
entry. It requires Aura `>=27.1-0-next`, Plugin ABI 1, and Bridge ABI 1.

## Repository And Product Identity

- Public repository: `Egg-China/Aura-QuickJS-Runtime-Host`.
- Local repository: `Plugins/Aura-QuickJS-Runtime-Host`.
- Default branch: `main`.
- License: GPL-3.0-or-later.
- GitHub topic: `aura-launcher`.
- Plugin ID: `dev.hmclce.runtime.quickjs-host`.
- Provided runtime: `javascript`.
- Execution mode: `isolated` only.
- Features: `bridge`, `hooks`, and `native`.
- Required permission: `native-code`.
- Platforms: Windows, Linux, and macOS on x64 and arm64.

The Java bootstrap retains the compatibility namespaces required by Aura contracts. The Host does
not modify the SDK `schema-v4` branch and does not add QuickJS to the Aura launcher artifact.

## Goals

- Execute schema-v5 JavaScript payloads through Aura's shared `RuntimeProcessSession`.
- Preserve process protocol v1 and Bridge Value v1 byte-for-byte.
- Provide a small, deterministic JavaScript API with asynchronous lifecycle functions.
- Prevent access to Node.js APIs, networking, shell execution, inherited environment variables,
  and host filesystem paths.
- Enforce per-payload memory, stack, time, path, and protocol limits.
- Publish six platform NPLs, an author SDK archive, examples, SBOM, checksums, and Store metadata.

## Non-Goals

- Node.js, CommonJS, npm package resolution, Web APIs, or browser DOM compatibility.
- QuickJS bytecode input or cache compatibility across Host versions.
- An embedded execution mode or direct JVM access.
- A malicious-code security sandbox after the user grants `native-code`; process isolation and
  language restrictions are defense-in-depth and fault containment.
- Publishing an npm package in the first beta.
- Changing process protocol v1, Bridge Value v1, or Aura's shared supervisor.

## Architecture

The repository contains five independently testable units:

1. `host-plugin` is a thin schema-v5 Java Runtime Provider. It resolves the packaged executable,
   owns opaque payload handles, and delegates lifecycle operations to `RuntimeProcessSession`.
2. `aura-runtime-protocol` is an audited source snapshot of the Rust Host protocol and value codec
   at commit `8e65a577d20903ad6eb07ff2afc536c049b9e907`. Its provenance is documented and its golden
   vectors must remain identical. Builds do not fetch this source from Git.
3. `aura-quickjs-host` owns the stdio state machine, payload descriptor, path validation, Bridge
   callbacks, and process exit behavior.
4. `aura-quickjs-engine` is the only unsafe QuickJS-NG boundary. It owns runtime/context lifetime,
   module loading, value conversion, promise jobs, interrupt handling, and resource limits.
5. `sdk` contains `aura-runtime.d.ts`, packaging validation, and a launch-hook example. It is
   shipped as a Release archive rather than an npm package.

`libquickjs-ng-sys` is pinned in `Cargo.lock`. The engine adapter uses the C API directly instead
of a large JavaScript framework so memory limits, stack limits, interrupt handling, module loading,
and job pumping remain explicit and testable.

## Provider And Process Data Flow

Aura selects the Provider from the schema-v5 runtime requirement. The Java Provider starts exactly
one Host process for one payload with `RuntimeProcessSession.start(executable, context)`. Aura owns
the environment whitelist, package-root working directory, deadlines, stderr tail, capability
token, and final process termination.

The process completes protocol `hello`, receives `load`, and validates the entrypoint as a package
relative `aura-javascript.json`. It parses the descriptor before creating a QuickJS runtime. It then
loads and evaluates the root module, validates the lifecycle exports, and calls `load(context)`.
Subsequent process messages call `enable`, `invoke`, `disable`, and finally `unload` during
`shutdown`.

The state machine is strictly:

`created -> loaded -> enabled -> disabled -> unloaded`

No command may be repeated or reordered. `invoke` is legal only while enabled. The server handles
one parent command at a time. A nested JavaScript Bridge call uses the existing even request-ID
callback direction while the parent odd request remains active.

## Payload Descriptor And Module Loading

The payload entrypoint is a UTF-8 JSON file with exactly these fields:

```json
{
  "schemaVersion": 1,
  "module": "main.mjs"
}
```

Unknown or missing fields are errors. `schemaVersion` must be integer `1`. `module` must be a
non-empty relative path using `/`, end in `.mjs`, and contain no empty, `.`, or `..` segment,
backslash, drive prefix, UNC prefix, NUL, or URI scheme. Canonical resolution must remain beneath
the canonical package root, and every resolved file must be a regular file. Symlink and junction
resolution may not escape the package root.

All JavaScript source is strict UTF-8 text. QuickJS bytecode and binary source are rejected. The
module loader accepts only:

- the built-in module `aura:runtime`;
- relative `.mjs` imports that resolve beneath the package root.

Bare package imports, absolute paths, URL imports, native modules, and filesystem lookup outside
the package are rejected. Static and dynamic imports use the same resolver and containment checks.

## JavaScript Lifecycle API

The root module exports these named functions:

```ts
export function load(context: AuraPluginContext): Promise<void>;
export function enable(): Promise<void>;
export function invoke(
  operation: string,
  input: AuraValue,
  callbackId: bigint
): Promise<AuraValue>;
export function disable(): Promise<void>;
export function unload(): Promise<void>;
```

Each export must be callable. The Host applies `Promise.resolve` semantics, so a synchronous return
is accepted while the public type contract remains asynchronous. `load` receives a deeply frozen
context containing an immutable numeric plugin identity and a Bridge facade. It does not expose the
capability token, JVM objects, environment, executable path, or canonical package root.

The built-in `aura:runtime` module exports types plus the Bridge facade:

- `bridge.invoke(operation, input)` returns a promise for one `AuraValue`.
- `bridge.retain(handle)` and `bridge.release(handle)` return promises and use protocol callbacks.
- `AuraHandle` is immutable and carries object ID, generation, and canonical type name.
- `AuraError` is immutable and carries only a stable Bridge error code.

The JVM reauthorizes every Bridge, retain, and release callback against the original
`RuntimePayloadContext`. The token never enters the process.

## Bridge Value Mapping

The mapping is lossless and deliberately excludes ambiguous JavaScript values:

| Bridge Value v1 | JavaScript |
| --- | --- |
| null | `null` |
| bool | `boolean` |
| int64 | `bigint` in the signed 64-bit range |
| finite double | `number` |
| UTF-8 string | `string` |
| bytes | `Uint8Array` |
| array | `Array<AuraValue>` |
| ordered unique-key map | `Map<string, AuraValue>` |
| handle | immutable `AuraHandle` |
| stable error | immutable `AuraError` |

JavaScript `number` always encodes as double and `bigint` always encodes as int64. Plain objects,
`undefined`, symbols, functions, non-finite numbers, out-of-range bigint values, cyclic containers,
duplicate map keys after string conversion, and detached byte buffers are rejected. Decoding
preserves array and map insertion order. Session shutdown releases all remaining handles; explicit
release is still required for prompt lifetime management.

## Resource And Security Boundaries

Each payload receives one QuickJS runtime and one context. The runtime has a 128 MiB memory limit
and 1 MiB maximum stack. Every lifecycle call has a local ten-second ceiling in addition to Aura's
authoritative session deadline. The QuickJS interrupt handler checks a monotonic deadline while the
job pump runs. An unresolved promise cannot hold the process beyond that deadline.

The Host installs no Node.js, network, process, shell, environment, or host filesystem APIs. The
QuickJS standard library is not initialized wholesale. Only explicitly registered ECMAScript
intrinsics and `aura:runtime` exist. Host process stdout is reserved exclusively for framed
protocol bytes; all diagnostics use stderr.

## Failure Semantics

Descriptor, path, source, export, and initial evaluation failures abort `load` and leave no valid
payload handle. A normal JavaScript throw or rejected promise becomes a bounded `guest-exception`
error. The message is sanitized and contains no stack source text, package path, Bridge input, or
secret. The session may continue only when the runtime remains internally consistent.

Protocol violations, unexpected stdout bytes, OOM, stack overflow, interrupt deadline, corrupted
value state, QuickJS internal failure, and cleanup failure poison the session. A poisoned Host emits
at most one bounded diagnostic to stderr and exits nonzero. It never retries lifecycle operations,
because those operations may have side effects.

`disable` and `unload` are best effort during shutdown, but process termination and handle
revocation are unconditional and idempotent. Aura retains the final 64 KiB of stderr and Protector
retains its existing startup recovery role.

Stable Host error codes include `invalid-descriptor`, `path-escape`, `invalid-module`,
`invalid-export`, `invalid-value`, `guest-exception`, `bridge-denied`, `resource-limit`,
`deadline-exceeded`, `protocol-error`, and `runtime-failure`. Protocol error messages remain bounded
by the existing 4096-byte rule.

## Testing

Development is test-first. Required coverage includes:

- exact protocol and Bridge Value golden vectors from Rust Host commit `8e65a577...`;
- descriptor unknown fields, wrong types, unsafe paths, symlink escape, invalid UTF-8, bytecode,
  absolute and bare imports;
- every JavaScript value mapping, nesting and byte limit, duplicate keys, cycles, bigint bounds,
  non-finite numbers, handles, and stable errors;
- module evaluation and every valid and invalid lifecycle transition;
- promise resolve/reject, nested Bridge callbacks, retain/release, callback denial, and unload;
- infinite loops, unresolved promises, heap and stack exhaustion, child crash, EOF, bad frame,
  wrong request ID, stdout logging, and stderr truncation;
- absence of Node, network, shell, environment, and host filesystem facilities;
- Java Provider descriptor, foreign handle, session cleanup, and real-process integration tests;
- a real `before-game-launch` example whose returned mutation reaches Aura's final launch plan.

CI runs Rust formatting, Clippy with warnings denied, workspace tests, Java Provider tests against
the exact Aura 27.1 CI JAR, TypeScript declaration checks, example packaging, and dependency/license
checks. All six NPLs are built. Native runners execute real Host smoke tests where available; every
cross-built binary is checked for target architecture and expected dynamic dependencies.

## Packaging And Release

Each NPL contains the Java Provider classes, exact platform Host executable, GPL notices, and
schema-v5 `plugin.json`. It declares runtime Provider ABI 1, Bridge ABI 1, isolated mode only,
`bridge/hooks/native`, `native-code`, and `launcherVersion: >=27.1-0-next`.

The tag `v0.1.0-beta.1` creates a draft prerelease. Release assets are:

- six platform-specific NPL files;
- a merged artifact manifest;
- `SHA256SUMS.txt`;
- SPDX or CycloneDX SBOM;
- `aura-quickjs-sdk-v0.1.0-beta.1.zip` containing `.d.ts`, tools, and the example.

The workflow builds against the exact Aura artifact from commit
`6d37f20d104c8e8d1c8b2b693ce1944207b85f84`, CI run `33159830461`, whose JAR SHA-256 is
`d3a0918e4f27a8ce16958c7321ecb3a6113e1dc24b0762425b5fbd4d8d5c9d92`. Repository secret
`AURA_REPOSITORY_TOKEN` grants read access to that private artifact. No secret value is printed or
committed.

Before publication, CI downloads every asset from its public Release URL and verifies size,
SHA-256, NPL manifest, target platform, executable architecture, SDK contents, and SBOM presence.
Any mismatch leaves the Release as draft.

After successful public verification, root `manifest.json` is published with a `beta` channel and
six immutable artifact URLs. The official Aura Store `plugins.json` pins the exact SHA-256 of the
root manifest. Store validation fetches the public root manifest and all assets, then runs an Aura
27.1 installation plan. The built-in Store trust root is not enabled or weakened by this work.

## Deferred Work

- npm publication and generated JavaScript client packages.
- Node.js, Web APIs, CommonJS, package registries, and native extensions.
- Embedded JavaScript execution and raw JVM access.
- Additional runtime ABI or Bridge ABI generations.

