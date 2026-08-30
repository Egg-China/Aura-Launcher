# Aura Plugin Patch Engine Design

## Purpose

This milestone turns schema-v5 `patches` declarations into an executable, language-neutral JVM
Patch facility. Aura owns Instrumentation, bytecode transformation, dispatch, permission checks,
ordering, deadlines, diagnostics, and restoration. Java plugins and optional Runtime Host payloads
provide callbacks; they never receive ASM, `Instrumentation`, or capability-token objects.

The launcher must remain usable when a Patch callback, Runtime Host, or retransformation fails.
Every failure therefore removes only the affected registration and falls back to the unpatched
method behavior. This milestone does not expand Core/UI Bridge operations, implement arbitrary raw
JVM access, or broaden Protector recovery policy.

## Existing Contract And Compatibility

- Plugin manifest schema remains v5. Store schema and Runtime process protocol versions do not
  change.
- `PluginPatchDeclaration` keeps `target`, `method`, `type`, and ordered `parameters` fields.
- Existing Java-style parameter names such as `java.lang.String` and `int` remain valid. The engine
  converts them to JVM descriptors internally; manifests do not use descriptor syntax.
- Existing `before`, `after`, and `replace` serialized values retain their meaning.
- `launcher-patch` remains the required high-risk permission.
- Existing Runtime Provider ABI 1 and Bridge ABI 1 remain valid. External callbacks travel through
  the existing generic `RuntimeProvider.invokePayload` boundary and Bridge Value v1 bytes.
- `Plugin` gains only a default Patch callback, preserving source and binary compatibility for
  existing Java/Kotlin plugins.
- `RuntimePatchEndpoint.register(PluginPatchDeclaration)` keeps its current method descriptor and
  adds a successful `REGISTERED` status. Standalone endpoints without an engine continue returning
  `PATCH_ENGINE_UNAVAILABLE`.
- Schema-v4 is unchanged and cannot declare or execute Patches.

## Fixed Safety Decisions

### Target Boundary

The safe Patch API may transform only Aura application classes whose binary name starts with
`org.jackhuang.hmcl.` and whose defining loader and code source match the active launcher. It rejects
JDK, JavaFX, dependency, bootstrap-loader, plugin-class-loader, generated, and foreign-code-source
classes.

The complete `org.jackhuang.hmcl.plugin.` namespace is protected. This prevents a Patch from
rewriting the Patch engine, Plugin Manager, Trust, Store verification, permission authority,
Runtime Supervisor, Protector, Mixin agent, or recovery path. Raw JVM access remains the separate
explicitly unsafe route for plugins that need control beyond this boundary.

Constructors and class initializers are already excluded by the manifest method-name grammar.
Abstract and native methods are rejected because they have no transformable body. Exact synthetic
or bridge methods remain addressable when their legal Java method name and parameters are declared.

### Parameter Grammar

Each `parameters` item is exactly one of:

- a primitive name: `boolean`, `byte`, `char`, `short`, `int`, `long`, `float`, or `double`;
- a canonical binary class name, including `$` for nested classes;
- either form followed by one or more `[]` array suffixes.

`void`, generic syntax, whitespace, slash-separated names, JVM descriptors, and source-only nested
class spelling are rejected. Empty `parameters` continues to identify a no-argument overload. Java
return types are not part of overload identity and remain absent from the manifest.

### Callback Failure Policy

Callbacks are synchronous from the patched method's perspective but execute on an engine-owned
worker. Each callback receives 500 ms and the complete method dispatch receives at most two seconds.
The target thread waits only for those bounded results.

An exception, timeout, interruption, malformed Bridge value, wrong argument count or type, stale
handle, unavailable Runtime Host, or lifecycle revocation atomically disables that registration:

- failed `before` callbacks discard their proposed argument changes and continue;
- failed `replace` callbacks execute the original method;
- failed `after` callbacks preserve the current result;
- remaining healthy callbacks continue in deterministic order.

Aura records one redacted diagnostic containing plugin ID, target, method, Patch type, and stable
failure category. It never logs callback payload values, object contents, capability tokens, or
foreign stack traces. A callback failure does not automatically disable the entire plugin or create
a Protector startup quarantine record.

## Architecture

### Agent-Owned Instrumentation

`HmclMixinAgent.premain` installs a launcher-owned `PluginInstrumentation` service while the current
safe-mode and mutation-lock rules are in force. The Shadow JAR keeps the existing `Premain-Class`
and changes `Can-Retransform-Classes` to `true`; `Can-Redefine-Classes` stays `false`.

The service requires `isRetransformClassesSupported()`, installs exactly one
`PluginPatchTransformer` with `canRetransform=true`, and publishes no raw Instrumentation handle to
plugins. Agent disablement, safe mode, initialization failure, or missing retransformation support
leaves the Patch engine unavailable and fail-closed.

The existing Mixin transformer remains non-retransformable. JVM retransformation reuses its prior
output before invoking the Patch transformer, so removing the final Patch restores the post-Mixin
class definition rather than erasing valid Mixin changes.

### Engine And Transformer

`PluginPatchEngine` owns immutable registration snapshots, per-method plans, callback admission,
conflict detection, failure state, and retransformation requests. `PluginPatchTransformer` performs
only target validation and ASM rewriting from the current immutable plan. `PluginPatchDispatcher`
is the stable static call surface injected into transformed methods.

The transformer never embeds plugin classes, callback objects, input values, or foreign bytecode.
It injects dispatcher calls keyed by an engine-local method ID. Consequently plugin unload can close
its class loader without leaving a constant-pool reference to plugin code.

The transformer receives the JVM-provided pre-Patch bytes on every retransformation. It does not
chain from its previous output. Removing registrations and retransformation therefore removes the
dispatcher calls. If restoration itself fails, the live registry is already empty, so any remaining
dispatcher call is a no-op and original behavior still runs.

### Registration Model

`PluginPatchRegistration` is an idempotent closeable owner record containing:

- exact artifact identity and plugin ID;
- dependency-order rank and canonical plugin ID tie breaker;
- exact declaration and resolved JVM method descriptor;
- callback endpoint and lifecycle lease;
- active, failed, or closed state.

Registration validates the manifest declaration, current artifact-bound `launcher-patch` grant,
target policy, method existence, transformer availability, class modifiability, and callback
lifecycle before mutating the live snapshot. If the target class is loaded, the engine retransforms
it transactionally. A transform failure rolls back the new registration and restores the previous
plan. If the class is not loaded, its launcher resource bytes are validated and the transformer
applies the plan on first definition.

Disable and unload close the owner's registrations before revoking handles or closing its class
loader. Closing the last registration for a loaded target requests retransformation. In-flight
callbacks stop being admissible immediately; their bounded workers are cancelled and their results
are discarded.

### Deterministic Composition

Registrations use the same dependency topology and canonical plugin-ID tie breaker as Hook
dispatch. For one exact JVM method:

1. `before` callbacks run in dependency order.
2. At most one `replace` callback may be active. A second replacement is rejected with a diagnostic
   naming both plugin IDs.
3. Without an active replacement, the original method executes.
4. `after` callbacks run in reverse dependency order, producing wrapper-style composition.

A successful `before` callback may replace the complete argument array with the same length and
assignable types. A successful `replace` callback supplies the method result. A successful `after`
callback may preserve or replace a normal result. `void` methods accept only unchanged results.
`after` callbacks do not run when the original method throws, and Patch ABI v1 cannot synthesize or
replace a Java exception. Those exception semantics can be added only in a later Patch ABI.

### Value And Handle Mapping

Patch invocation and result envelopes use Bridge Value v1 ordered maps. The operation name for an
external Runtime payload is `aura.patch.v1`. Its request contains only:

- `schemaVersion` equal to `1`;
- target class, method, Java parameter names, and Patch type;
- an invocation-local receiver value;
- ordered invocation-local argument values;
- the current normal result for `after`, otherwise null.

Primitive values, `String`, and `byte[]` use their corresponding Bridge scalar values. Other
receivers, references, and arrays use invocation-local opaque handles. A callback may return an
input handle only where its referenced object is assignable to the declared JVM type. All temporary
handles are invalidated after the callback, including timeout and malformed-result paths.

The response is an ordered map with `schemaVersion: 1`, an `unchanged`, `arguments`, or `return`
action, and only the value required by that action. Unknown, duplicate, missing, out-of-order,
oversized, or wrong-type fields fail the registration invocation without exposing JVM objects.

Capability tokens remain inside Aura. Every external invocation is reauthorized against the exact
artifact, execution mode, payload generation, callback domain, and current permission before the
Provider is entered.

### Java And External Runtime Adapters

The Java adapter calls a new default `Plugin.onPatch(PluginPatchInvocation)` method through the
plugin class loader and the same engine callback contract. The default returns unchanged. Java
plugins do not receive raw dispatcher frames or Instrumentation.

The external adapter is owned by `RuntimePatchEndpoint` and `RuntimeSupervisor`. It invokes the
already loaded and enabled payload through `RuntimeProvider.invokePayload` using `aura.patch.v1`,
callback ID zero, and canonical Bridge Value v1 bytes. This reuses protocol-v1 `invoke` messages, so
Rust, .NET, QuickJS, and Wasm Hosts need no process-wire change. Host SDKs and examples only need to
route the new operation to payload code.

The engine deadline remains authoritative even for an older Provider binary that does not yet
accept an exact timeout. An official Host update may adopt the same 500 ms deadline internally, but
Aura does not change Runtime Provider ABI 1 for this milestone.

## Bytecode Shape

For each selected method, ASM injects an entry call that boxes the receiver and arguments and asks
the dispatcher for an invocation frame. The frame contains validated replacement arguments and an
optional replacement result. The method either writes the replacement arguments back to locals and
continues, or returns the validated replacement value.

Every normal return path boxes its current result, runs reverse-ordered `after` callbacks, validates
the final value, unboxes it, and returns. No catch-all handler is added, so original exceptions,
monitor behavior, and declared exception flow remain unchanged. Stack map frames and maximum stack
sizes are recomputed with a loader-aware common-superclass resolver that never loads plugin code.

The engine rejects bytecode whose exact declared method cannot be found or safely rewritten. It
also rejects a class-file version or JVM value category the transformer does not support, rather
than emitting unverifiable bytecode.

## Lifecycle And Threading

- Registration and removal are serialized with the existing plugin mutation lock and engine lock.
- Transformation never calls plugin or Runtime code.
- Patched methods read an immutable plan without holding the engine mutation lock.
- Callback workers carry only the immutable invocation envelope and lifecycle lease.
- The same registration cannot recursively invoke itself; recursive entry bypasses that
  registration while leaving other ordered registrations eligible.
- A global depth limit of 16 prevents cross-method Patch cycles. Exceeding it disables the
  registration that attempted the next callback and continues unpatched.
- Plugin disable, update, uninstall, recovery, and Provider shutdown revoke registrations before
  dependent code or processes are released.

## Diagnostics And User Surface

Registration failures report stable categories: unavailable engine, denied target, missing method,
unsupported method, permission denied, replacement conflict, unmodifiable class, transform failure,
and lifecycle revoked. Callback failures add timeout, transport, malformed value, type mismatch,
and callback exception.

The Plugin Manager shows whether each declaration is pending, active, failed, or restored and names
the target without displaying invocation data. Because `launcher-patch` is Level 3, installation and
updates continue requiring explicit artifact-bound user approval.

## Testing And Acceptance

Development follows red-green-refactor. Acceptance requires:

- manifest tests for every legal Java parameter form and rejection of ambiguous forms;
- target-policy tests for Aura classes, protected plugin packages, foreign code sources, bootstrap
  classes, plugin loaders, abstract methods, native methods, and overload mismatches;
- ASM transformation tests for instance/static methods, every primitive category, references,
  arrays, wide locals, void returns, normal results, verifier correctness, and unchanged exceptions;
- real javaagent child-JVM tests proving registration, retransformation, deterministic order,
  replacement conflict rejection, unload restoration, Mixin preservation, and future class load;
- Java plugin tests for default unchanged behavior, argument/result mutation, recursion control,
  timeout, failure isolation, and class-loader release;
- external Runtime tests using real Bridge Value bytes and generic `invoke`, including exact
  reauthorization, stale handles, malformed values, Host crash, timeout, disable, and unload;
- rollback tests for failed registration and failed restoration;
- safe-mode and missing-agent tests proving no Patch executes;
- Shadow JAR inspection proving the unchanged `Premain-Class`, exactly one `-next`, and
  `Can-Retransform-Classes: true`;
- focused Patch/Mixin/Runtime/permission/Protector suites and the complete
  `checkstyle checkTranslations test shadowJar` gate;
- six-workflow actionlint, gitleaks on changed files and the commit range, license-boundary tests,
  and a clean artifact audit.

## Delivery Boundaries

The Aura implementation lands first and keeps version `27.1-next`. It does not publish a stable
launcher release. After Aura CI is green, schema-v5 SDK documentation and Java/Rust/.NET/QuickJS/Wasm
examples may add `aura.patch.v1` handlers without changing schema-v4 or process protocol v1. Any Host
package version promotion remains a separate release gate with its own artifacts and Store update.

No Release or HarmonyOS Real SDK workflow is triggered by this implementation milestone. All pushes
are ordinary non-force pushes after local gates pass.
