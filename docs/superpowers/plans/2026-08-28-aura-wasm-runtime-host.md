# Aura Wasm Runtime Host Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a six-platform isolated Wasmtime Component Model Runtime Host, versioned WIT and Rust guest SDK, example, root manifest, and Aura Store entry as `0.1.0-beta.1`.

**Architecture:** A thin schema-v5 Java Provider delegates each payload process to Aura `RuntimeProcessSession`. The Rust Host combines the frozen process/value protocol with Wasmtime 48.0.1, generated Component Model bindings, constrained WASI 0.2, Bridge imports, fuel, epoch interruption, and store limits.

**Tech Stack:** Rust 1.97.1, edition 2024, Wasmtime and wasmtime-wasi 48.0.1, WIT Component Model, Gradle Kotlin DSL, Java 17 API surface, PowerShell, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-08-28-aura-wasm-runtime-host-design.md`

## Global Constraints

- Repository: `Egg-China/Aura-Wasm-Runtime-Host`; branch `main`; GPL-3.0-or-later; topic `aura-launcher`.
- Plugin: `dev.hmclce.runtime.wasm-host` `0.1.0-beta.1`; runtime `wasm`; ABI 1; Bridge ABI 1.
- Schema v5, isolated only, features `bridge/hooks/native`, required permission `native-code`.
- Launcher `>=27.1-0-next`; six Windows/Linux/macOS x64/arm64 platforms.
- WIT package `aura:runtime@0.1.0`; Component Model only; constrained WASI 0.2.
- Frozen protocol source: Rust Host commit `8e65a577d20903ad6eb07ff2afc536c049b9e907`.
- Aura input: commit `6d37f20d104c8e8d1c8b2b693ce1944207b85f84`, run `33159830461`, JAR SHA-256 `d3a0918e4f27a8ce16958c7321ecb3a6113e1dc24b0762425b5fbd4d8d5c9d92`.
- No crates.io publication, core-module fallback, schema-v4 edits, force push, secret output, protocol change, or Store trust bypass.

## File Structure

- `crates/aura-bridge-value/`: Bridge Value v1 codec and vectors.
- `crates/aura-runtime-protocol/`: process protocol v1 and vectors.
- `crates/aura-wasm-engine/`: bindings, Wasmtime, WASI, Bridge, limits, lifecycle.
- `crates/aura-wasm-host/`: descriptor, path policy, state machine, stdio binary.
- `host-plugin/`: Java Provider, manifest, Gradle tests.
- `sdk/wit/`, `sdk/rust/aura-wasm-guest/`: public WIT and unpublished guest SDK.
- `examples/launch-hook/`: real Component Model Hook.
- `tools/`, `.github/workflows/`, `manifest.json`: packaging, CI, release, Store metadata.

---

### Task 1: Repository And Frozen Protocol Baseline

**Files:**
- Create: `LICENSE`, `README.md`, `.gitignore`, `Cargo.toml`, `Cargo.lock`, `rust-toolchain.toml`
- Create: `crates/aura-bridge-value/src/lib.rs`, `crates/aura-bridge-value/tests/value.rs`
- Create: `crates/aura-runtime-protocol/src/lib.rs`, `crates/aura-runtime-protocol/tests/protocol.rs`
- Create: `docs/protocol-provenance.md`

**Interfaces:**
- Produces: `aura_bridge_value::{Value, HandleValue, Error, ErrorCode}`.
- Produces: `aura_runtime_protocol::{Message, MessageBody, read_frame, write_frame}`.
- Produces: `BridgeTransport::{invoke, retain, release}` scoped to an active guest call.

- [ ] **Step 1: Create repository and local main branch**

```powershell
gh repo create Egg-China/Aura-Wasm-Runtime-Host --public --clone=false
gh repo edit Egg-China/Aura-Wasm-Runtime-Host --add-topic aura-launcher
git init C:\Users\ACX\Documents\Plugins\Aura-Wasm-Runtime-Host
git -C C:\Users\ACX\Documents\Plugins\Aura-Wasm-Runtime-Host remote add origin https://github.com/Egg-China/Aura-Wasm-Runtime-Host.git
git -C C:\Users\ACX\Documents\Plugins\Aura-Wasm-Runtime-Host switch -c main
```

- [ ] **Step 2: Write failing byte-compatibility tests**

```rust
#[test]
fn hello_vector_is_frozen() {
    let message = Message::new(1, MessageBody::Hello).unwrap();
    assert_eq!(hex::encode(message.to_wire().unwrap()), HELLO_VECTOR_HEX);
}
```

- [ ] **Step 3: Run tests and confirm missing crates fail**

```powershell
cargo test -p aura-bridge-value -p aura-runtime-protocol
```

- [ ] **Step 4: Copy exact codec, protocol, errors, and tests from `8e65a577...`**

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

Record original file hashes and namespace-only changes. Preserve every tag, field order, limit,
request-ID parity rule, and validation branch.

- [ ] **Step 5: Verify and commit baseline**

```powershell
cargo fmt --all --check
cargo test -p aura-bridge-value -p aura-runtime-protocol
git add .
git commit -m "Establish Wasm Host protocol baseline"
```

### Task 2: WIT World And Strict Payload Descriptor

**Files:**
- Create: `sdk/wit/aura-runtime.wit`
- Create: `crates/aura-wasm-engine/Cargo.toml`, `crates/aura-wasm-engine/src/bindings.rs`
- Create: `crates/aura-wasm-engine/tests/wit.rs`
- Create: `crates/aura-wasm-host/Cargo.toml`, `crates/aura-wasm-host/src/descriptor.rs`, `crates/aura-wasm-host/src/path_policy.rs`
- Create: `crates/aura-wasm-host/tests/descriptor.rs`

**Interfaces:**
- Produces: world `aura-plugin-v1`, import interface `bridge`, export interface `plugin`.
- Produces: `PayloadDescriptor::read(&Path, &str) -> HostResult<PayloadDescriptor>`.

- [ ] **Step 1: Write failing WIT, JSON, escape, and core-module tests**

```rust
#[test]
fn accepts_only_exact_component_descriptor() {
    let parsed = descriptor(r#"{"schemaVersion":1,"component":"plugin.wasm"}"#).unwrap();
    assert_eq!(parsed.component(), Path::new("plugin.wasm"));
    assert_code(descriptor(r#"{"schemaVersion":1,"component":"../x.wasm"}"#), "path-escape");
}
```

- [ ] **Step 2: Run and confirm tests fail**

```powershell
cargo test -p aura-wasm-engine --test wit
cargo test -p aura-wasm-host --test descriptor
```

- [ ] **Step 3: Add the exact versioned WIT contract**

```wit
package aura:runtime@0.1.0;
interface bridge {
  invoke: func(operation: string, input: list<u8>) -> result<list<u8>, string>;
  retain-handle: func(object-id: u64, generation: u64) -> result<_, string>;
  release-handle: func(object-id: u64, generation: u64) -> result<_, string>;
}
interface plugin {
  record plugin-error { code: string, message: string }
  load: func() -> result<_, plugin-error>;
  enable: func() -> result<_, plugin-error>;
  invoke: func(operation: string, input: list<u8>, callback-id: u64) -> result<list<u8>, plugin-error>;
  disable: func() -> result<_, plugin-error>;
  unload: func() -> result<_, plugin-error>;
}
world aura-plugin-v1 { import bridge; export plugin; }
```

- [ ] **Step 4: Implement strict JSON, canonical containment, and Component-only validation**

```rust
#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct RawDescriptor {
    #[serde(rename = "schemaVersion")]
    schema_version: i64,
    component: String,
}
```

Require schema 1, safe `/` path, `.wasm`, regular file, no symlink escape, and successful
`wasmtime::component::Component::validate`; reject valid raw `wasmtime::Module` input.

- [ ] **Step 5: Verify and commit contract**

```powershell
cargo test -p aura-wasm-engine --test wit
cargo test -p aura-wasm-host --test descriptor
git add sdk/wit crates/aura-wasm-engine crates/aura-wasm-host
git commit -m "Freeze Aura Wasm payload contract"
```

### Task 3: Wasmtime Limits And Constrained WASI 0.2

**Files:**
- Create: `crates/aura-wasm-engine/src/lib.rs`, `config.rs`, `store.rs`, `wasi.rs`, `diagnostics.rs`
- Create: `crates/aura-wasm-engine/tests/limits.rs`, `tests/wasi.rs`

**Interfaces:**
- Produces: `WasmEngine::new(EngineLimits) -> EngineResult<WasmEngine>`.
- Produces: `build_wasi(&Path) -> EngineResult<(WasiCtx, ResourceTable, GuestDiagnostics)>`.

- [ ] **Step 1: Write failing memory, fuel, epoch, filesystem, network, and environment tests**

```rust
#[test]
fn denies_write_network_and_environment() {
    assert_guest_denied(write_guest_file("/plugin/new.txt"));
    assert_guest_denied(open_socket());
    assert_eq!(guest_environment(), Vec::<String>::new());
}
```

- [ ] **Step 2: Run and confirm red**

```powershell
cargo test -p aura-wasm-engine --test limits --test wasi
```

- [ ] **Step 3: Configure hard store limits and per-call interruption**

```rust
pub const MEMORY_BYTES: usize = 256 * 1024 * 1024;
pub const FUEL_PER_CALL: u64 = 50_000_000;
pub const CALL_TIMEOUT: Duration = Duration::from_secs(10);
pub const MAX_WASI_RESOURCES: usize = 10_000;

let limits = StoreLimitsBuilder::new()
    .memory_size(MEMORY_BYTES).table_elements(100_000)
    .instances(100).tables(100).memories(100).build();
```

Enable Component Model, fuel, and epoch interruption. Reset fuel and deadline for every lifecycle
call. Reject a resource-table insertion after 10,000 live WASI resources. Poison the store after a
trap or limit interruption.

- [ ] **Step 4: Build minimum WASI context with bounded non-inherited stdio**

```rust
let ctx = WasiCtxBuilder::new()
    .stdin(ClosedInputStream)
    .stdout(BoundedOutput::new(64 * 1024))
    .stderr(BoundedOutput::new(64 * 1024))
    .preopened_dir(read_only_dir, "/plugin", DirPerms::READ, FilePerms::READ)?
    .build();
```

Add clocks and secure random explicitly; do not inherit args, environment, network, or stdio.

- [ ] **Step 5: Verify release-profile limits and commit**

```powershell
cargo test -p aura-wasm-engine --test limits --test wasi --release
git add Cargo.toml Cargo.lock crates/aura-wasm-engine
git commit -m "Bound Wasmtime and WASI capabilities"
```

### Task 4: Component Lifecycle And Bridge Imports

**Files:**
- Create: `crates/aura-wasm-engine/src/plugin.rs`, `bridge.rs`
- Create: `crates/aura-wasm-engine/tests/lifecycle.rs`, `bridge.rs`, `isolation.rs`

**Interfaces:**
- Produces: `WasmPlugin::{load, enable, invoke, disable, unload}`.
- Consumes: `BridgeTransport::{invoke, retain, release}`.

- [ ] **Step 1: Write failing real-component lifecycle and Bridge tests**

```rust
#[test]
fn bridge_round_trip_is_canonical() {
    let input = Value::String("x".into()).to_wire().unwrap();
    let output = fixture().invoke("echo", input, 0).unwrap();
    assert_eq!(Value::from_wire(&output).unwrap(), Value::String("x".into()));
}
```

- [ ] **Step 2: Run and confirm red**

```powershell
cargo test -p aura-wasm-engine --test lifecycle --test bridge --test isolation
```

- [ ] **Step 3: Instantiate generated world and validate callback bytes/errors**

```rust
impl bindings::aura::runtime::bridge::Host for HostState {
    fn invoke(&mut self, operation: String, input: Vec<u8>) -> wasmtime::Result<Result<Vec<u8>, String>> {
        Value::from_wire(&input).map_err(|_| anyhow!("invalid-value"))?;
        Ok(self.bridge.invoke(self.plugin_id, self.session, &operation, &input).map_err(|error| error.stable_code().to_owned()))
    }
}
```

Validate both directions, lower-case kebab errors, 128-byte codes, 4096-byte messages, retain and
release IDs, lifecycle order, and per-instance state isolation.

- [ ] **Step 4: Verify and commit engine lifecycle**

```powershell
cargo test -p aura-wasm-engine --test lifecycle --test bridge --test isolation
git add crates/aura-wasm-engine
git commit -m "Execute Aura Wasm components"
```

### Task 5: Protocol Process Server And Fatal Faults

**Files:**
- Create: `crates/aura-wasm-host/src/error.rs`, `server.rs`, `main.rs`
- Create: `crates/aura-wasm-host/tests/stdio.rs`, `faults.rs`

**Interfaces:**
- Produces: `ProcessServer::run<R: Read, W: Write>(&mut self, R, W) -> HostResult<()>`.

- [ ] **Step 1: Write failing state, callback, EOF, trap, and stdout tests**

```rust
#[test]
fn trap_and_wrong_callback_id_are_fatal() {
    assert_fatal(Fault::GuestTrap, "runtime-failure");
    assert_fatal(Fault::WrongCallbackId, "protocol-error");
}
```

- [ ] **Step 2: Run and confirm red**

```powershell
cargo test -p aura-wasm-host --test stdio --test faults
```

- [ ] **Step 3: Implement strict state and callback reentry**

```rust
enum LifecycleState { Created, Loaded, Enabled, Disabled, Unloaded, Poisoned }
```

Accept exactly `--stdio`, reserve stdout for frames, serialize parent calls, match even callback
IDs, reject invalid order/frame/EOF/output, and perform unload plus idempotent cleanup.

- [ ] **Step 4: Run workspace tests and commit**

```powershell
cargo test -p aura-wasm-host --test stdio --test faults
cargo test --workspace
git add crates/aura-wasm-host
git commit -m "Supervise Wasm payload processes"
```

### Task 6: Java Provider And Schema-v5 NPL Manifest

**Files:**
- Create: `host-plugin/build.gradle.kts`, `host-plugin/plugin.json`
- Create: `host-plugin/src/main/java/dev/hmclce/runtime/wasm/WasmRuntimeHostPlugin.java`
- Create: `host-plugin/src/main/java/dev/hmclce/runtime/wasm/WasmRuntimeProvider.java`
- Create: `host-plugin/src/test/java/dev/hmclce/runtime/wasm/WasmRuntimeProviderTest.java`
- Create: `host-plugin/src/test/java/dev/hmclce/runtime/wasm/WasmRuntimeHostPluginTest.java`

**Interfaces:**
- Produces: Provider for `wasm` ABI 1, Bridge ABI 1, isolated mode.
- Consumes: `RuntimeProcessSession.start(Path, RuntimePayloadContext)`.

- [ ] **Step 1: Write failing ownership and cleanup tests**

```java
/// Exercises the Aura process-session adapter for Wasm payloads.
@NotNullByDefault
final class WasmRuntimeProviderTest {
    /// Verifies an unknown handle cannot invoke a child.
    @Test void rejectsUnknownHandle() {
        assertThrows(IOException.class, () -> provider.disablePayload(unknownHandle));
    }
}
```

- [ ] **Step 2: Run exact Aura-JAR tests and confirm red**

```powershell
./gradlew.bat -p host-plugin test --rerun-tasks
```

- [ ] **Step 3: Implement thin annotated Provider and exact manifest**

```java
/// Provides one supervised Wasmtime process per Aura payload.
@NotNullByDefault
public final class WasmRuntimeProvider implements RuntimeProvider {
    /// Returns the immutable Provider descriptor.
    @Override public RuntimeProviderDescriptor descriptor() { return descriptor; }
}
```

Use `///` documentation for every class, field, and method; explicit nullability; immutable
annotations; and the .NET Provider's `Session` adapter pattern.

- [ ] **Step 4: Verify and commit Provider**

```powershell
./gradlew.bat -p host-plugin test --rerun-tasks
pwsh -File tools/validate-plugin-json.ps1 -Path host-plugin/plugin.json
git add host-plugin tools/validate-plugin-json.ps1
git commit -m "Add Wasm Runtime Provider"
```

### Task 7: Rust Guest SDK, Component Example, And Packager

**Files:**
- Create: `sdk/rust/aura-wasm-guest/Cargo.toml`, `sdk/rust/aura-wasm-guest/src/lib.rs`, `sdk/README.md`
- Create: `examples/launch-hook/Cargo.toml`, `src/lib.rs`, `aura-wasm.json`, `plugin.json`, `README.md`
- Create: `tools/package-wasm-plugin.ps1`, `tools/test-package-wasm-plugin.ps1`

**Interfaces:**
- Produces: unpublished guest codec/lifecycle helpers and real `before-game-launch` component.

- [ ] **Step 1: Write failing SDK and raw-module rejection tests**

```rust
#[test]
fn guest_value_round_trip_is_canonical() {
    let bytes = encode(&AuraValue::Integer(42)).unwrap();
    assert_eq!(decode(&bytes).unwrap(), AuraValue::Integer(42));
}
```

- [ ] **Step 2: Run and confirm red**

```powershell
cargo test --manifest-path sdk/rust/aura-wasm-guest/Cargo.toml
pwsh -File tools/test-package-wasm-plugin.ps1
```

- [ ] **Step 3: Implement wrappers, Hook, component build, and deterministic package validation**

```rust
impl Guest for LaunchHook {
    fn invoke(operation: String, input: Vec<u8>, _callback_id: u64) -> Result<Vec<u8>, PluginError> {
        require_operation(&operation, "before-game-launch")?;
        mutate_launch_plan(input)
    }
}
```

- [ ] **Step 4: Build real component, test final plan mutation, and commit**

```powershell
cargo test --manifest-path sdk/rust/aura-wasm-guest/Cargo.toml
cargo component build --manifest-path examples/launch-hook/Cargo.toml --release
pwsh -File tools/test-package-wasm-plugin.ps1
cargo test -p aura-wasm-host --test launch_hook --release
git add sdk examples tools
git commit -m "Provide Aura Wasm guest SDK"
```

### Task 8: Fault Matrix And Artifact Verification

**Files:**
- Create: `tests/fixtures/` malformed and fault components
- Create: `tools/verify-wasm-host-artifacts.ps1`, `tools/test-verify-wasm-host-artifacts.ps1`

**Interfaces:**
- Produces: verifier for NPL schema/content, hashes, sizes, machine architecture, WIT/SDK, and Aura provenance.

- [ ] **Step 1: Write failing verifier mutation tests**

```powershell
Assert-VerifierRejects -Mutation WrongAuraJarHash
Assert-VerifierRejects -Mutation DuplicateZipEntry
Assert-VerifierRejects -Mutation MissingWit
Assert-VerifierRejects -Mutation WrongMachineArchitecture
```

- [ ] **Step 2: Run and confirm red**

```powershell
pwsh -File tools/test-verify-wasm-host-artifacts.ps1
```

- [ ] **Step 3: Enforce exact provenance and structure**

```powershell
$expectedAura = 'd3a0918e4f27a8ce16958c7321ecb3a6113e1dc24b0762425b5fbd4d8d5c9d92'
if ($Manifest.auraJarSha256 -ne $expectedAura) { throw 'unexpected Aura JAR hash' }
```

Validate PE/ELF/Mach-O architecture, six platforms, ZIP duplicates, manifest fields, WIT version,
SDK files, Wasmtime notices, and public hash/size fields.

- [ ] **Step 4: Run all local gates and commit**

```powershell
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
./gradlew.bat -p host-plugin test --rerun-tasks
cargo component build --manifest-path examples/launch-hook/Cargo.toml --release
pwsh -File tools/test-package-wasm-plugin.ps1
pwsh -File tools/test-verify-wasm-host-artifacts.ps1
git add tests tools
git commit -m "Verify Wasm Host artifacts"
```

### Task 9: Six-Platform CI And Draft Release

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/release.yml`
- Create: `tools/package-host-npl.ps1`, `merge-artifact-manifests.ps1`, `test-ci-workflows.ps1`
- Create: `manifest.json`

**Interfaces:**
- Produces: six NPLs, manifest, checksums, SBOM, and guest SDK ZIP.

- [ ] **Step 1: Write failing workflow matrix and secret-boundary tests**

```powershell
$targets = @('windows-x64','windows-arm64','linux-x64','linux-arm64','macos-x64','macos-arm64')
Assert-WorkflowMatrix -Path .github/workflows/ci.yml -ExactPlatforms $targets
Assert-PinnedWasmtimeVersion -LockFile Cargo.lock -Version 48.0.1
Assert-NoPullRequestSecretExecution -Path .github/workflows/release.yml
```

- [ ] **Step 2: Run and confirm red**

```powershell
pwsh -File tools/test-ci-workflows.ps1
```

- [ ] **Step 3: Implement pinned builds and draft prerelease**

```yaml
env:
  AURA_COMMIT: 6d37f20d104c8e8d1c8b2b693ce1944207b85f84
  AURA_RUN_ID: "33159830461"
  AURA_JAR_SHA256: d3a0918e4f27a8ce16958c7321ecb3a6113e1dc24b0762425b5fbd4d8d5c9d92
```

Pin action SHAs and component tools, apply least permissions, test before package, generate SBOM,
create draft prerelease, and re-download assets before public release.

- [ ] **Step 4: Validate local Windows x64 package**

```powershell
pwsh -File tools/test-ci-workflows.ps1
cargo build -p aura-wasm-host --release
./gradlew.bat -p host-plugin test jar
pwsh -File tools/package-host-npl.ps1 -Platform windows-x64 -Version 0.1.0-beta.1
pwsh -File tools/verify-wasm-host-artifacts.ps1 -ArtifactManifest artifacts/manifest.json -PackageDirectory artifacts
```

- [ ] **Step 5: Commit, push, configure secret, and watch CI**

```powershell
git add .github tools manifest.json
git commit -m "Build Wasm Host for six platforms"
git push -u origin main
gh repo edit Egg-China/Aura-Wasm-Runtime-Host --default-branch main
gh auth token | gh secret set AURA_REPOSITORY_TOKEN --repo Egg-China/Aura-Wasm-Runtime-Host
gh run watch --repo Egg-China/Aura-Wasm-Runtime-Host --exit-status
```

Expected: main CI succeeds without exposing the token.

### Task 10: Public Release And Store Entry

**Files:**
- Modify: `manifest.json`
- Modify in Store worktree: `plugins.json`
- Create: `artifacts/release-evidence.json`

**Interfaces:**
- Produces: public prerelease and exact hash-pinned Store entry.

- [ ] **Step 1: Tag and monitor the draft prerelease**

```powershell
git tag -a v0.1.0-beta.1 -m "Aura Wasm Runtime Host 0.1.0-beta.1"
git push origin v0.1.0-beta.1
gh run watch --repo Egg-China/Aura-Wasm-Runtime-Host --exit-status
```

- [ ] **Step 2: Publish only after draft verification, then re-download public bytes**

```powershell
gh release edit v0.1.0-beta.1 --repo Egg-China/Aura-Wasm-Runtime-Host --draft=false --prerelease
pwsh -File tools/verify-public-release.ps1 -Repository Egg-China/Aura-Wasm-Runtime-Host -Tag v0.1.0-beta.1
```

- [ ] **Step 3: Commit root manifest from verified evidence**

```powershell
pwsh -File tools/update-root-manifest.ps1 -ReleaseEvidence artifacts/release-evidence.json -Manifest manifest.json
pwsh -File tools/validate-root-manifest.ps1 -Manifest manifest.json -FetchPublicAssets
git add manifest.json artifacts/release-evidence.json
git commit -m "Publish Wasm Host beta manifest"
git push origin main
```

- [ ] **Step 4: Pin root manifest in Store and execute Aura installation planning**

```powershell
$manifestHash = (Get-FileHash manifest.json -Algorithm SHA256).Hash.ToLowerInvariant()
pwsh -File tools/update-store-entry.ps1 -PluginId dev.hmclce.runtime.wasm-host -ManifestSha256 $manifestHash
pwsh -File tools/validate-store.ps1 -FetchPublicAssets -AuraInstallPlan
git add plugins.json
git commit -m "List Aura Wasm Runtime Host"
git push origin main
```

- [ ] **Step 5: Verify clean worktrees, public release, and successful CI**

```powershell
git status --short --branch
gh release view v0.1.0-beta.1 --repo Egg-China/Aura-Wasm-Runtime-Host --json isDraft,isPrerelease,assets
gh run list --repo Egg-China/Aura-Wasm-Runtime-Host --limit 5
```

Expected: clean repositories, public prerelease, successful Host and Store CI.
