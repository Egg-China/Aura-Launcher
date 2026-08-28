# Aura QuickJS Runtime Host Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a six-platform isolated QuickJS-NG Runtime Host, author SDK, example, root manifest, and Aura Store entry as `0.1.0-beta.1`.

**Architecture:** A thin schema-v5 Java Provider starts one Rust process per payload through Aura `RuntimeProcessSession`. The Rust process contains an audited protocol/value snapshot, a strict state machine, and a focused QuickJS-NG adapter that owns module loading, asynchronous lifecycle execution, Bridge callbacks, and resource limits.

**Tech Stack:** Rust 1.97.1, edition 2024, `libquickjs-ng-sys` 0.13.0, Gradle Kotlin DSL, Java 17 API surface, TypeScript declarations, PowerShell packaging, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-08-28-aura-quickjs-runtime-host-design.md`

## Global Constraints

- Repository is `Egg-China/Aura-QuickJS-Runtime-Host`, default branch `main`, GPL-3.0-or-later, topic `aura-launcher`.
- Plugin ID is `dev.hmclce.runtime.quickjs-host`; runtime is `javascript`; version is `0.1.0-beta.1`.
- NPL schema is v5; runtime ABI is 1; Bridge ABI is 1; execution is `isolated` only.
- Features are exactly `bridge`, `hooks`, `native`; required permission is `native-code`.
- Launcher constraint is `>=27.1-0-next`; every referenced Aura version contains `-next` exactly once.
- Platforms are `windows-x64`, `windows-arm64`, `linux-x64`, `linux-arm64`, `macos-x64`, and `macos-arm64`.
- Protocol/value source is frozen from Rust Host commit `8e65a577d20903ad6eb07ff2afc536c049b9e907`.
- Aura build input is commit `6d37f20d104c8e8d1c8b2b693ce1944207b85f84`, run `33159830461`, JAR SHA-256 `d3a0918e4f27a8ce16958c7321ecb3a6113e1dc24b0762425b5fbd4d8d5c9d92`.
- No npm publication, schema-v4 change, force push, token disclosure, protocol v1 change, or Store trust bypass.

## File Structure

- `Cargo.toml`, `Cargo.lock`, `rust-toolchain.toml`: locked Rust workspace and toolchain.
- `crates/aura-bridge-value/`: canonical Bridge Value v1 codec and vectors.
- `crates/aura-runtime-protocol/`: framed process protocol v1 and vectors.
- `crates/aura-quickjs-engine/`: sole unsafe QuickJS boundary, values, modules, jobs, limits.
- `crates/aura-quickjs-host/`: descriptor, path policy, state machine, stdio executable.
- `host-plugin/`: Java Provider, schema-v5 `plugin.json`, Gradle tests and packaging input.
- `sdk/`: `.d.ts`, validation tool, archive layout.
- `examples/launch-hook/`: real `.mjs` launch Hook payload.
- `tools/`: NPL, manifest, checksum, SBOM, and public asset verification scripts.
- `.github/workflows/ci.yml`: native tests plus six target builds.
- `.github/workflows/release.yml`: draft prerelease, public verification, root manifest update.
- `manifest.json`: Store topic manifest.

---

### Task 1: Repository Baseline And Frozen Protocol

**Files:**
- Create: `LICENSE`, `README.md`, `.gitignore`, `Cargo.toml`, `Cargo.lock`, `rust-toolchain.toml`
- Create: `crates/aura-bridge-value/src/lib.rs`
- Create: `crates/aura-bridge-value/tests/value.rs`
- Create: `crates/aura-runtime-protocol/src/lib.rs`
- Create: `crates/aura-runtime-protocol/tests/protocol.rs`
- Create: `docs/protocol-provenance.md`

**Interfaces:**
- Produces: `aura_bridge_value::{Value, HandleValue, Error, ErrorCode}` with `to_wire` and `from_wire`.
- Produces: `aura_runtime_protocol::{Message, MessageBody, read_frame, write_frame, PROTOCOL_VERSION, MAX_FRAME_BYTES}`.
- Produces: language-neutral `BridgeTransport::{invoke, retain, release}` used only during an active lifecycle call.

- [ ] **Step 1: Create the public repository and isolated local checkout**

```powershell
gh repo create Egg-China/Aura-QuickJS-Runtime-Host --public --clone=false
gh repo edit Egg-China/Aura-QuickJS-Runtime-Host --add-topic aura-launcher
git init C:\Users\ACX\Documents\Plugins\Aura-QuickJS-Runtime-Host
git -C C:\Users\ACX\Documents\Plugins\Aura-QuickJS-Runtime-Host remote add origin https://github.com/Egg-China/Aura-QuickJS-Runtime-Host.git
git -C C:\Users\ACX\Documents\Plugins\Aura-QuickJS-Runtime-Host switch -c main
```

Expected: repository exists and local branch is `main` with no commits.

- [ ] **Step 2: Write failing provenance and golden-vector tests**

```rust
#[test]
fn hello_vector_is_frozen() {
    let message = Message::new(1, MessageBody::Hello).unwrap();
    assert_eq!(hex::encode(message.to_wire().unwrap()), HELLO_VECTOR_HEX);
}

#[test]
fn rejects_frame_larger_than_sixteen_mib() {
    let mut frame = (MAX_FRAME_BYTES + 1).to_be_bytes().to_vec();
    frame.extend_from_slice(&[0]);
    assert!(read_frame(&mut frame.as_slice()).is_err());
}
```

- [ ] **Step 3: Run the focused tests and verify they fail**

```powershell
cargo test -p aura-bridge-value -p aura-runtime-protocol
```

Expected: failure because the workspace crates and frozen types do not exist.

- [ ] **Step 4: Copy the exact audited codec and protocol source, then rename only crate namespaces**

```rust
pub const PROTOCOL_VERSION: i64 = 1;
pub const MAX_FRAME_BYTES: u32 = 16 * 1024 * 1024;

pub enum BridgeError { Callback(String), Protocol(ProtocolError) }

impl BridgeError {
    pub fn stable_code(&self) -> &str {
        match self { Self::Callback(code) => code, Self::Protocol(_) => "protocol-error" }
    }
}

pub trait BridgeTransport: Send + Sync {
    fn invoke(&self, plugin_id: u64, session: u64, operation: &str, input: &[u8]) -> Result<Vec<u8>, BridgeError>;
    fn retain_handle(&self, session: u64, object_id: u64, generation: u64) -> Result<(), BridgeError>;
    fn release_handle(&self, session: u64, object_id: u64, generation: u64) -> Result<(), BridgeError>;
}
```

Copy `hmcl-plugin-sdk/src/value.rs`, its error types, `hmcl-runtime-protocol/src/lib.rs`, and their
tests from commit `8e65a577...`. Record source paths, commit, pre-copy SHA-256, and permitted namespace
renames in `docs/protocol-provenance.md`. Do not alter tags, map order, limits, request-ID parity, or
error validation.

- [ ] **Step 5: Run formatting and protocol tests**

```powershell
cargo fmt --all --check
cargo test -p aura-bridge-value -p aura-runtime-protocol
```

Expected: all copied and added tests pass.

- [ ] **Step 6: Commit the baseline**

```powershell
git add .
git commit -m "Establish QuickJS Host protocol baseline"
```

### Task 2: Strict Descriptor And Package Path Policy

**Files:**
- Create: `crates/aura-quickjs-host/Cargo.toml`
- Create: `crates/aura-quickjs-host/src/descriptor.rs`
- Create: `crates/aura-quickjs-host/src/path_policy.rs`
- Create: `crates/aura-quickjs-host/tests/descriptor.rs`
- Create: `crates/aura-quickjs-host/tests/path_policy.rs`

**Interfaces:**
- Produces: `PayloadDescriptor::read(package_root: &Path, entrypoint: &str) -> Result<PayloadDescriptor, HostError>`.
- Produces: `PackagePathPolicy::module_path(&self, specifier: &str, referrer: Option<&Path>) -> Result<PathBuf, HostError>`.

- [ ] **Step 1: Write descriptor and traversal rejection tests**

```rust
#[test]
fn accepts_exact_descriptor() {
    let fixture = package(r#"{"schemaVersion":1,"module":"main.mjs"}"#);
    let parsed = PayloadDescriptor::read(fixture.path(), "aura-javascript.json").unwrap();
    assert_eq!(parsed.module(), Path::new("main.mjs"));
}

#[test]
fn rejects_unknown_field_and_parent_segment() {
    assert_code(descriptor(r#"{"schemaVersion":1,"module":"../x.mjs"}"#), "path-escape");
    assert_code(descriptor(r#"{"schemaVersion":1,"module":"x.mjs","extra":1}"#), "invalid-descriptor");
}
```

- [ ] **Step 2: Run tests and verify red**

```powershell
cargo test -p aura-quickjs-host --test descriptor --test path_policy
```

Expected: compilation fails because descriptor and path policy are absent.

- [ ] **Step 3: Implement exact JSON and canonical containment**

```rust
#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct RawDescriptor {
    #[serde(rename = "schemaVersion")]
    schema_version: i64,
    module: String,
}

pub struct PayloadDescriptor {
    module: PathBuf,
}
```

Validate schema `1`, `/` separators, `.mjs`, regular files, canonical containment, and symlink or
junction escape. Accept only `aura:runtime` or relative `.mjs` module imports.

- [ ] **Step 4: Run focused tests**

```powershell
cargo test -p aura-quickjs-host --test descriptor --test path_policy
```

Expected: all descriptor and path tests pass, including Windows prefix cases.

- [ ] **Step 5: Commit path policy**

```powershell
git add crates/aura-quickjs-host
git commit -m "Validate QuickJS payload paths"
```

### Task 3: Process Lifecycle State Machine

**Files:**
- Create: `crates/aura-quickjs-host/src/error.rs`
- Create: `crates/aura-quickjs-host/src/server.rs`
- Create: `crates/aura-quickjs-host/src/main.rs`
- Create: `crates/aura-quickjs-host/tests/stdio.rs`

**Interfaces:**
- Consumes: protocol messages from Task 1 and descriptor policy from Task 2.
- Produces: `ProcessServer<E: GuestEngine>::run<R: Read, W: Write>(&mut self, reader: R, writer: W) -> Result<(), HostError>`.
- Produces: `GuestEngine` lifecycle trait used by the QuickJS adapter.

- [ ] **Step 1: Write failing state and stdio tests**

```rust
trait GuestEngine {
    fn load(&mut self, package_root: &Path, module: &Path, plugin_id: u64, session: u64, bridge: Arc<dyn BridgeTransport>) -> HostResult<()>;
    fn enable(&mut self) -> HostResult<()>;
    fn invoke(&mut self, operation: &str, input: &[u8], callback_id: u64) -> HostResult<Vec<u8>>;
    fn disable(&mut self) -> HostResult<()>;
    fn unload(&mut self) -> HostResult<()>;
}

#[test]
fn rejects_invoke_before_enable() {
    let output = exchange([hello(1), load(3), invoke(5)]);
    assert_eq!(error_code(output.last()), "invalid-state");
}
```

- [ ] **Step 2: Run and verify failure**

```powershell
cargo test -p aura-quickjs-host --test stdio
```

Expected: compilation fails because server, trait, and executable are absent.

- [ ] **Step 3: Implement the strict state machine and protocol-only stdout**

```rust
enum LifecycleState { Created, Loaded, Enabled, Disabled, Unloaded, Poisoned }

fn require_state(&self, expected: LifecycleState) -> HostResult<()> {
    (self.state == expected).then_some(()).ok_or_else(|| HostError::stable("invalid-state"))
}
```

Respond once per odd request, service even Bridge callbacks while a guest call is active, reject
EOF during an active session, and make cleanup idempotent. `main` accepts exactly `--stdio`.

- [ ] **Step 4: Run state, protocol, and malformed-frame tests**

```powershell
cargo test -p aura-quickjs-host --test stdio
cargo test --workspace
```

Expected: lifecycle, request parity, EOF, bad frame, and shutdown tests pass.

- [ ] **Step 5: Commit process supervision endpoint**

```powershell
git add crates/aura-quickjs-host
git commit -m "Implement QuickJS process lifecycle"
```

### Task 4: QuickJS Runtime Ownership And Limits

**Files:**
- Create: `crates/aura-quickjs-engine/Cargo.toml`
- Create: `crates/aura-quickjs-engine/src/lib.rs`
- Create: `crates/aura-quickjs-engine/src/runtime.rs`
- Create: `crates/aura-quickjs-engine/tests/runtime.rs`

**Interfaces:**
- Produces: `QuickJsRuntime::new(Limits) -> Result<QuickJsRuntime, EngineError>`.
- Produces: `QuickJsRuntime::run_with_deadline<T>(&mut self, deadline: Instant, call: impl FnOnce(&mut Context) -> EngineResult<T>) -> EngineResult<T>`.

- [ ] **Step 1: Write failing ownership and resource tests**

```rust
#[test]
fn interrupts_infinite_loop() {
    let mut runtime = QuickJsRuntime::new(Limits::default()).unwrap();
    let error = runtime.eval_for_test("for (;;) {}", Duration::from_millis(20)).unwrap_err();
    assert_eq!(error.code(), "deadline-exceeded");
}

#[test]
fn enforces_heap_limit() {
    assert_engine_code(repeated_allocation(), "resource-limit");
}
```

- [ ] **Step 2: Run and verify red**

```powershell
cargo test -p aura-quickjs-engine --test runtime
```

Expected: crate and runtime wrapper are missing.

- [ ] **Step 3: Implement the sole unsafe FFI boundary**

```rust
pub struct Limits { pub memory_bytes: usize, pub stack_bytes: usize }

impl Default for Limits {
    fn default() -> Self { Self { memory_bytes: 128 * 1024 * 1024, stack_bytes: 1024 * 1024 } }
}
```

Create and free `JSRuntime` before its `JSContext`, install memory and stack limits, store a
monotonic deadline in runtime opaque data, install an interrupt handler, and convert QuickJS
exceptions without leaking source paths or stacks. Document every unsafe function invariant.

- [ ] **Step 4: Run engine tests under normal and release profiles**

```powershell
cargo test -p aura-quickjs-engine --test runtime
cargo test -p aura-quickjs-engine --test runtime --release
```

Expected: ownership, exception, OOM, stack, and deadline tests pass.

- [ ] **Step 5: Commit runtime boundary**

```powershell
git add Cargo.toml Cargo.lock crates/aura-quickjs-engine
git commit -m "Embed bounded QuickJS-NG runtime"
```

### Task 5: ES Module Loader And Async Lifecycle

**Files:**
- Create: `crates/aura-quickjs-engine/src/module_loader.rs`
- Create: `crates/aura-quickjs-engine/src/plugin.rs`
- Create: `crates/aura-quickjs-engine/tests/modules.rs`
- Create: `crates/aura-quickjs-engine/tests/lifecycle.rs`
- Modify: `crates/aura-quickjs-host/src/main.rs`

**Interfaces:**
- Produces: `QuickJsPlugin::load(root: &Path, module: &Path, plugin_id: u64, session: u64, bridge: Arc<dyn BridgeTransport>) -> EngineResult<QuickJsPlugin>`.
- Produces: `QuickJsPlugin::{enable, invoke, disable, unload}` implementing `GuestEngine`.

- [ ] **Step 1: Write failing module and promise tests**

```rust
#[test]
fn executes_async_lifecycle_in_order() {
    let plugin = fixture(r#"
        export async function load(c) { globalThis.context = c; }
        export async function enable() { globalThis.enabled = true; }
        export async function invoke(operation, input) { return input; }
        export async function disable() { globalThis.enabled = false; }
        export async function unload() { globalThis.context = null; }
    "#);
    assert_eq!(drive(plugin, ["load", "enable", "disable", "unload"]), vec!["ok"; 4]);
}

#[test]
fn rejects_bare_and_outside_imports() {
    assert_load_code("import 'node:fs'", "invalid-module");
    assert_load_code("import '../outside.mjs'", "path-escape");
}
```

- [ ] **Step 2: Run and verify red**

```powershell
cargo test -p aura-quickjs-engine --test modules --test lifecycle
```

Expected: loader and plugin lifecycle are absent.

- [ ] **Step 3: Implement module normalization, evaluation, exports, and job pumping**

```rust
const EXPORTS: [&str; 5] = ["load", "enable", "invoke", "disable", "unload"];

fn settle_promise(&mut self, value: JsValue, deadline: Instant) -> EngineResult<JsValue> {
    self.runtime.execute_jobs_until_settled(value, deadline)
}
```

Use the Task 2 path policy for static and dynamic imports. Initialize only ECMAScript intrinsics
required by modules and promises. Validate all five named exports before calling `load`.

- [ ] **Step 4: Run module, lifecycle, and no-host-API tests**

```powershell
cargo test -p aura-quickjs-engine --test modules --test lifecycle
cargo test --workspace
```

Expected: async lifecycle and denial tests pass, including unresolved promises and dynamic imports.

- [ ] **Step 5: Commit lifecycle engine**

```powershell
git add crates/aura-quickjs-engine crates/aura-quickjs-host
git commit -m "Load QuickJS ES module payloads"
```

### Task 6: Lossless JavaScript Bridge Values

**Files:**
- Create: `crates/aura-quickjs-engine/src/value.rs`
- Create: `crates/aura-quickjs-engine/tests/value.rs`
- Create: `sdk/aura-runtime.d.ts`

**Interfaces:**
- Produces: `to_js(context: &Context, value: &Value) -> EngineResult<JsValue>`.
- Produces: `from_js(context: &Context, value: JsValue) -> EngineResult<Value>`.

- [ ] **Step 1: Write round-trip and rejection tests**

```rust
#[test]
fn maps_integer_to_bigint_and_map_to_map() {
    assert_js_type(Value::Integer(i64::MAX), "bigint");
    assert_js_constructor(Value::Map(vec![("x".into(), Value::Null)]), "Map");
}

#[test]
fn rejects_plain_object_cycle_and_nan() {
    assert_from_js_code("({x: 1})", "invalid-value");
    assert_from_js_code("(()=>{const a=[];a.push(a);return a})()", "invalid-value");
    assert_from_js_code("NaN", "invalid-value");
}
```

- [ ] **Step 2: Run and verify red**

```powershell
cargo test -p aura-quickjs-engine --test value
```

Expected: conversion functions and JS wrapper classes are absent.

- [ ] **Step 3: Implement exact mapping and frozen wrapper classes**

```ts
export type AuraValue = null | boolean | bigint | number | string | Uint8Array |
  AuraValue[] | Map<string, AuraValue> | AuraHandle | AuraError;
```

Track visited object identities while encoding, enforce codec depth/content/count limits, reject
plain objects and detached buffers, and freeze `AuraHandle` and `AuraError` instances.

- [ ] **Step 4: Run conversion and canonical codec tests**

```powershell
cargo test -p aura-quickjs-engine --test value
cargo test -p aura-bridge-value
```

Expected: every Bridge Value tag round-trips and invalid JavaScript values fail deterministically.

- [ ] **Step 5: Commit value mapping**

```powershell
git add crates/aura-quickjs-engine sdk/aura-runtime.d.ts
git commit -m "Map QuickJS Bridge values"
```

### Task 7: Built-In Bridge Module And Callback Reentry

**Files:**
- Create: `crates/aura-quickjs-engine/src/bridge.rs`
- Create: `crates/aura-quickjs-engine/tests/bridge.rs`
- Modify: `sdk/aura-runtime.d.ts`
- Modify: `crates/aura-quickjs-host/src/server.rs`

**Interfaces:**
- Produces: built-in `aura:runtime` exports `bridge`, `AuraHandle`, and `AuraError`.
- Consumes: `BridgeTransport::{invoke, retain, release}` backed by protocol even request IDs.

- [ ] **Step 1: Write failing callback and denial tests**

```rust
#[test]
fn nested_bridge_invoke_resolves_promise() {
    let result = invoke_fixture("return await context.bridge.invoke('launcher.test.echo', input)");
    assert_eq!(result, Value::String("echo".into()));
}

#[test]
fn bridge_denial_is_aura_error() {
    assert_js_rejection_code(denied_callback(), "permission-denied");
}
```

- [ ] **Step 2: Run and verify red**

```powershell
cargo test -p aura-quickjs-engine --test bridge
cargo test -p aura-quickjs-host --test stdio
```

Expected: built-in module and callback transport are not implemented.

- [ ] **Step 3: Implement promise Bridge calls and explicit handle lifetime**

```ts
export interface AuraBridge {
  invoke(operation: string, input: AuraValue): Promise<AuraValue>;
  retain(handle: AuraHandle): Promise<void>;
  release(handle: AuraHandle): Promise<void>;
}
```

Store an `Arc<dyn BridgeTransport>` scoped to the payload's plugin and session IDs. The process
transport allocates monotonically increasing even request IDs, waits for the matching callback
response, rejects mismatches, and never serializes a JVM token.

- [ ] **Step 4: Run Bridge, server, and full workspace tests**

```powershell
cargo test -p aura-quickjs-engine --test bridge
cargo test --workspace
```

Expected: callback success, error, retain, release, wrong ID, and unload cleanup pass.

- [ ] **Step 5: Commit Bridge module**

```powershell
git add crates/aura-quickjs-engine crates/aura-quickjs-host sdk/aura-runtime.d.ts
git commit -m "Expose Aura Bridge to QuickJS"
```

### Task 8: Java Runtime Provider And NPL Manifest

**Files:**
- Create: `host-plugin/build.gradle.kts`
- Create: `host-plugin/plugin.json`
- Create: `host-plugin/src/main/java/dev/hmclce/runtime/quickjs/QuickJsRuntimeHostPlugin.java`
- Create: `host-plugin/src/main/java/dev/hmclce/runtime/quickjs/QuickJsRuntimeProvider.java`
- Create: `host-plugin/src/test/java/dev/hmclce/runtime/quickjs/QuickJsRuntimeProviderTest.java`
- Create: `host-plugin/src/test/java/dev/hmclce/runtime/quickjs/QuickJsRuntimeHostPluginTest.java`

**Interfaces:**
- Produces: schema-v5 Provider declaration for `javascript` ABI 1 and Bridge ABI 1.
- Consumes: `RuntimeProcessSession.start(Path, RuntimePayloadContext)`.

- [ ] **Step 1: Write failing Provider tests**

```java
/// Verifies the Provider delegates one isolated payload to Aura supervision.
@NotNullByDefault
final class QuickJsRuntimeProviderTest {
    /// Confirms foreign handles cannot reach a session.
    @Test void rejectsForeignHandle() {
        assertThrows(IOException.class, () -> provider.enablePayload(foreignHandle));
    }
}
```

- [ ] **Step 2: Run tests against the exact Aura JAR and verify red**

```powershell
./gradlew.bat -p host-plugin test --rerun-tasks
```

Expected: Provider classes do not exist.

- [ ] **Step 3: Implement the documented, annotated thin Provider**

```java
/// Provides isolated QuickJS payload execution through Aura supervision.
@NotNullByDefault
public final class QuickJsRuntimeProvider implements RuntimeProvider {
    /// Starts and loads one process session.
    @Override
    public synchronized RuntimePayloadHandle loadPayload(RuntimePayloadContext context) throws IOException {
        Session session = sessionFactory.start(executable, context);
        return register(context, session);
    }
}
```

Document every class, field, and method with `///`; annotate every class with `@NotNullByDefault`,
every nullable use with `@Nullable`, and immutable arrays/collections with JetBrains annotations.
Mirror the tested .NET Provider session adapter rather than reimplementing supervision.

- [ ] **Step 4: Run Java tests and manifest validation**

```powershell
./gradlew.bat -p host-plugin test --rerun-tasks
pwsh -File tools/validate-plugin-json.ps1 -Path host-plugin/plugin.json
```

Expected: Provider tests pass and manifest declares only approved identities and capabilities.

- [ ] **Step 5: Commit Provider**

```powershell
git add host-plugin tools/validate-plugin-json.ps1
git commit -m "Add QuickJS Runtime Provider"
```

### Task 9: Author SDK, Packaging Tool, And Real Hook Example

**Files:**
- Create: `sdk/README.md`, `sdk/tsconfig.json`, `sdk/package.json`
- Create: `tools/package-javascript-plugin.ps1`
- Create: `tools/test-package-javascript-plugin.ps1`
- Create: `examples/launch-hook/aura-javascript.json`
- Create: `examples/launch-hook/main.mjs`
- Create: `examples/launch-hook/plugin.json`
- Create: `examples/launch-hook/README.md`

**Interfaces:**
- Produces: validated JavaScript payload ZIP/NPL input with strict descriptor and `.mjs` graph.
- Produces: launch Hook operation returning canonical Bridge values.

- [ ] **Step 1: Write failing packaging tests and type-check fixture**

```powershell
& ./tools/package-javascript-plugin.ps1 -Source ./tests/fixtures/unsafe-parent -Output $TestDrive/out.npl
if ($LASTEXITCODE -eq 0) { throw 'unsafe parent import was accepted' }
npx --yes typescript@5.9.2 --project sdk/tsconfig.json --noEmit
```

- [ ] **Step 2: Run and verify red**

```powershell
pwsh -File tools/test-package-javascript-plugin.ps1
npm exec --yes --package typescript@5.9.2 tsc -- --project sdk/tsconfig.json --noEmit
```

Expected: packaging tool and example are absent.

- [ ] **Step 3: Implement deterministic packaging and Hook example**

```js
export async function invoke(operation, input) {
  if (operation !== "before-game-launch") throw new Error("unsupported operation");
  input.set("workingDirectory", input.get("workingDirectory"));
  return input;
}
```

The script parses JSON structurally, walks the module graph with the same specifier rules, sorts
archive entries, uses fixed timestamps, and rejects bytecode, unsafe paths, and undeclared files.

- [ ] **Step 4: Run SDK, packaging, and real-process Hook tests**

```powershell
pwsh -File tools/test-package-javascript-plugin.ps1
npm exec --yes --package typescript@5.9.2 tsc -- --project sdk/tsconfig.json --noEmit
cargo test -p aura-quickjs-host --test launch_hook --release
```

Expected: package validation and final launch-plan mutation pass.

- [ ] **Step 5: Commit SDK and example**

```powershell
git add sdk tools examples
git commit -m "Document QuickJS plugin authoring"
```

### Task 10: Fault Injection And Full Local Gates

**Files:**
- Create: `crates/aura-quickjs-host/tests/faults.rs`
- Create: `tests/fixtures/` payload fixtures named by failure category
- Create: `tools/verify-quickjs-host-artifacts.ps1`
- Create: `tools/test-verify-quickjs-host-artifacts.ps1`

**Interfaces:**
- Produces: artifact verifier accepting `-ArtifactManifest`, `-PackageDirectory`, and exact Aura provenance.

- [ ] **Step 1: Add failing fault and verifier tests**

```rust
#[test]
fn stdout_log_and_wrong_callback_id_are_fatal() {
    assert_fatal(Fault::StdoutByte, "protocol-error");
    assert_fatal(Fault::WrongCallbackId, "protocol-error");
}
```

- [ ] **Step 2: Run and verify red**

```powershell
cargo test -p aura-quickjs-host --test faults
pwsh -File tools/test-verify-quickjs-host-artifacts.ps1
```

Expected: fixtures and verifier behavior are incomplete.

- [ ] **Step 3: Complete crash, EOF, timeout, resource, stderr, and NPL checks**

```powershell
$required = @('plugin.json', 'lib/quickjs-host.exe')
$entries = Get-ZipEntryNames -LiteralPath $Package
Assert-ExactRequiredEntries -Entries $entries -Required $required
```

Use platform-specific executable names, verify PE/ELF/Mach-O architecture, reject duplicate ZIP
entries, validate hash/size, and assert Aura commit/run/JAR hash.

- [ ] **Step 4: Run all local quality gates**

```powershell
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
./gradlew.bat -p host-plugin test --rerun-tasks
pwsh -File tools/test-package-javascript-plugin.ps1
pwsh -File tools/test-verify-quickjs-host-artifacts.ps1
```

Expected: every command succeeds with no warnings.

- [ ] **Step 5: Commit fault coverage**

```powershell
git add crates tests tools
git commit -m "Harden QuickJS Host failures"
```

### Task 11: Six-Platform CI And Draft Release

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/release.yml`
- Create: `tools/package-host-npl.ps1`
- Create: `tools/merge-artifact-manifests.ps1`
- Create: `tools/test-ci-workflows.ps1`
- Create: `manifest.json`

**Interfaces:**
- Produces: six NPL assets, merged manifest, checksums, SBOM, and SDK ZIP.

- [ ] **Step 1: Write workflow contract tests**

```powershell
$targets = @('windows-x64','windows-arm64','linux-x64','linux-arm64','macos-x64','macos-arm64')
Assert-WorkflowMatrix -Path .github/workflows/ci.yml -ExactPlatforms $targets
Assert-NoPullRequestSecretExecution -Path .github/workflows/release.yml
```

- [ ] **Step 2: Run and verify red**

```powershell
pwsh -File tools/test-ci-workflows.ps1
```

Expected: workflows and six-platform matrix are absent.

- [ ] **Step 3: Implement pinned build, packaging, SBOM, and draft release workflows**

```yaml
env:
  AURA_COMMIT: 6d37f20d104c8e8d1c8b2b693ce1944207b85f84
  AURA_RUN_ID: "33159830461"
  AURA_JAR_SHA256: d3a0918e4f27a8ce16958c7321ecb3a6113e1dc24b0762425b5fbd4d8d5c9d92
```

Use least-privilege workflow permissions, immutable action SHAs, release concurrency, native tests
before packaging, draft prerelease creation, and public URL re-download before publication.

- [ ] **Step 4: Validate workflows and build Windows x64 NPL locally**

```powershell
pwsh -File tools/test-ci-workflows.ps1
cargo build -p aura-quickjs-host --release
./gradlew.bat -p host-plugin test jar
pwsh -File tools/package-host-npl.ps1 -Platform windows-x64 -Version 0.1.0-beta.1
pwsh -File tools/verify-quickjs-host-artifacts.ps1 -ArtifactManifest artifacts/manifest.json -PackageDirectory artifacts
```

Expected: workflow tests and local NPL verification pass.

- [ ] **Step 5: Commit CI and push normally**

```powershell
git add .github tools manifest.json
git commit -m "Build QuickJS Host for six platforms"
git push -u origin main
gh repo edit Egg-China/Aura-QuickJS-Runtime-Host --default-branch main
```

- [ ] **Step 6: Configure private Aura artifact read secret**

```powershell
gh auth token | gh secret set AURA_REPOSITORY_TOKEN --repo Egg-China/Aura-QuickJS-Runtime-Host
gh run watch --repo Egg-China/Aura-QuickJS-Runtime-Host --exit-status
```

Expected: main CI reaches `success`; secret value is never displayed.

### Task 12: Public Release And Official Store Entry

**Files:**
- Modify: `manifest.json` with final public hashes and sizes
- Modify in Store worktree: `plugins.json`
- Create: release evidence under `artifacts/release-evidence.json` without secrets

**Interfaces:**
- Produces: publicly verified prerelease and hash-pinned Store registry entry.

- [ ] **Step 1: Tag and start the draft prerelease workflow**

```powershell
git tag -a v0.1.0-beta.1 -m "Aura QuickJS Runtime Host 0.1.0-beta.1"
git push origin v0.1.0-beta.1
gh run watch --repo Egg-China/Aura-QuickJS-Runtime-Host --exit-status
```

Expected: six NPLs and support assets exist on a draft prerelease.

- [ ] **Step 2: Publish only after draft asset verification succeeds**

```powershell
gh release edit v0.1.0-beta.1 --repo Egg-China/Aura-QuickJS-Runtime-Host --draft=false --prerelease
pwsh -File tools/verify-public-release.ps1 -Repository Egg-China/Aura-QuickJS-Runtime-Host -Tag v0.1.0-beta.1
```

Expected: public bytes match merged manifest exactly.

- [ ] **Step 3: Update and validate root manifest**

```powershell
pwsh -File tools/update-root-manifest.ps1 -ReleaseEvidence artifacts/release-evidence.json -Manifest manifest.json
pwsh -File tools/validate-root-manifest.ps1 -Manifest manifest.json -FetchPublicAssets
git add manifest.json artifacts/release-evidence.json
git commit -m "Publish QuickJS Host beta manifest"
git push origin main
```

Expected: root manifest contains six immutable URLs, exact hashes/sizes, and beta metadata.

- [ ] **Step 4: Add the hash-pinned Store entry and run install planning**

```powershell
$manifestHash = (Get-FileHash manifest.json -Algorithm SHA256).Hash.ToLowerInvariant()
pwsh -File tools/update-store-entry.ps1 -PluginId dev.hmclce.runtime.quickjs-host -ManifestSha256 $manifestHash
pwsh -File tools/validate-store.ps1 -FetchPublicAssets -AuraInstallPlan
git add plugins.json
git commit -m "List Aura QuickJS Runtime Host"
git push origin main
```

Expected: Store CI succeeds and Aura 27.1 selects `0.1.0-beta.1` with `native-code` review.

- [ ] **Step 5: Verify clean repositories and public terminal state**

```powershell
git status --short --branch
gh release view v0.1.0-beta.1 --repo Egg-China/Aura-QuickJS-Runtime-Host --json isDraft,isPrerelease,assets
gh run list --repo Egg-China/Aura-QuickJS-Runtime-Host --limit 5
```

Expected: worktrees are clean, release is public prerelease, and relevant CI runs succeeded.
