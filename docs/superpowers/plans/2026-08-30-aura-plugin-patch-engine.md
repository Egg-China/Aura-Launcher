# Aura Plugin Patch Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute schema-v5 `patches` declarations through a bounded, language-neutral Aura-owned JVM Patch engine.

**Architecture:** A single retransformation-capable ASM transformer injects calls to a stable launcher dispatcher. The process-wide engine owns immutable method plans, deterministic callback composition, lifecycle revocation, deadlines, validation, and restoration; Java plugins and external Runtime payloads are adapters behind the same callback interface.

**Tech Stack:** Java 17, `java.lang.instrument`, ASM 9.8, Bridge Value v1, JUnit Jupiter, Gradle Shadow.

**Spec:** `docs/superpowers/specs/2026-08-30-aura-plugin-patch-engine-design.md`

## Global Constraints

- Keep plugin manifest schema v5, Runtime Provider ABI 1, Bridge ABI 1, and process protocol v1 unchanged.
- Transform only launcher-owned `org.jackhuang.hmcl.*` classes and reject all `org.jackhuang.hmcl.plugin.*` targets.
- Require `launcher-patch`; capability tokens stay inside Aura and external callbacks are reauthorized per invocation.
- Use 500 ms per callback, two seconds aggregate per method dispatch, recursion depth 16, fail-open method behavior, and registration-local failure isolation.
- Keep `Premain-Class` unchanged, set `Can-Retransform-Classes: true`, keep `Can-Redefine-Classes: false`, and retain version `27.1-next` exactly once.
- Every written or modified Java declaration uses `@NotNullByDefault`, explicit `@Nullable`, immutable annotations, and `///` documentation as required by `AGENTS.md`.
- Plugin System code remains under `AuraPluginSystem/` and Apache-2.0; schema-v4 is not modified.
- Do not trigger Release or HarmonyOS Real SDK workflows; every remote update is an ordinary non-force push after its local gate.

---

### Task 1: Patch Parameter Grammar And Method Identity

**Files:**
- Modify: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/PluginPatchDeclaration.java`
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchMethod.java`
- Modify: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/PluginManifestTest.java`
- Create: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/patch/PluginPatchMethodTest.java`

**Interfaces:**
- Consumes: schema-v5 `PluginPatchDeclaration(target, method, type, parameters)`.
- Produces: `PluginPatchMethod.from(PluginPatchDeclaration)`, `parameterDescriptor()`, `matches(String, String)`, and `withReturnDescriptor(String)`.

- [x] **Step 1: Write grammar tests that fail on the current blank-only parameter validation**

```java
@ParameterizedTest
@ValueSource(strings = {"boolean", "byte", "char", "short", "int", "long", "float", "double",
        "java.lang.String", "java.util.Map$Entry", "int[]", "java.lang.String[][]"})
public void acceptsCanonicalPatchParameterNames(String parameter) {
    assertDoesNotThrow(() -> new PluginPatchDeclaration(
            "org.jackhuang.hmcl.Launcher", "launch",
            PluginPatchDeclaration.PatchType.BEFORE, List.of(parameter)));
}

@ParameterizedTest
@ValueSource(strings = {"void", " java.lang.String", "java.lang.String ", "java/lang/String",
        "Ljava.lang.String;", "[I", "List<String>", "java.util.Map.Entry", "int...", ""})
public void rejectsAmbiguousPatchParameterNames(String parameter) {
    assertThrows(IllegalArgumentException.class, () -> new PluginPatchDeclaration(
            "org.jackhuang.hmcl.Launcher", "launch",
            PluginPatchDeclaration.PatchType.BEFORE, List.of(parameter)));
}
```

- [x] **Step 2: Run the focused manifest tests and confirm an invalid form is accepted**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.PluginManifestTest --rerun-tasks --no-daemon --console plain`

Expected: FAIL because at least `void` or descriptor syntax passes current validation.

- [x] **Step 3: Implement exact Java-name validation and descriptor conversion**

```java
public static PluginPatchMethod from(PluginPatchDeclaration declaration) {
    String parameterDescriptor = declaration.getParameters().stream()
            .map(PluginPatchMethod::descriptorForParameter)
            .collect(Collectors.joining("", "(", ")"));
    return new PluginPatchMethod(declaration.getTarget(), declaration.getMethod(),
            declaration.getParameters(), parameterDescriptor, null);
}
```

Use a full-match pattern for primitives or binary names followed by zero or more `[]` suffixes. Reject `void`, whitespace, `/`, descriptors, generic punctuation, source-only nested spelling, and a trailing or empty array element.

- [x] **Step 4: Run focused tests and checkstyle**

Run: `./gradlew.bat :AuraLauncher:test :AuraLauncher:checkstylePluginMain :AuraLauncher:checkstylePluginTest --tests org.jackhuang.hmcl.plugin.PluginManifestTest --tests org.jackhuang.hmcl.plugin.patch.PluginPatchMethodTest --rerun-tasks --no-daemon --console plain`

Expected: PASS.

- [x] **Step 5: Commit the independently usable grammar slice**

```text
feat: validate Patch method parameter identities
```

### Task 2: Public Patch Callback Values

**Files:**
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/PluginPatchInvocation.java`
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/PluginPatchResult.java`
- Modify: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/Plugin.java`
- Create: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/PluginPatchCallbackContractTest.java`

**Interfaces:**
- Consumes: resolved target identity, callback type, invocation-local receiver/arguments/result.
- Produces: binary-compatible `default PluginPatchResult onPatch(PluginPatchInvocation invocation)` and immutable result factories `unchanged()`, `arguments(List<Object>)`, and `returnValue(@Nullable Object)`.

- [x] **Step 1: Write tests for immutable inputs, null return values, action exclusivity, and the default callback**

```java
@Test
public void defaultPluginPatchCallbackPreservesInvocation() {
    Plugin plugin = lifecycleWithoutPatchOverride();
    PluginPatchInvocation invocation = PluginPatchInvocation.before(
            declaration(), null, List.of("original"));
    assertSame(PluginPatchResult.unchanged(), plugin.onPatch(invocation));
    assertThrows(UnsupportedOperationException.class,
            () -> invocation.arguments().add("mutated"));
}
```

- [x] **Step 2: Run the callback contract test and confirm compilation fails because the API is absent**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.PluginPatchCallbackContractTest --rerun-tasks --no-daemon --console plain`

Expected: FAIL at test compilation with missing `PluginPatchInvocation` and `PluginPatchResult`.

- [x] **Step 3: Implement the immutable public API and default Plugin method**

```java
default PluginPatchResult onPatch(PluginPatchInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    return PluginPatchResult.unchanged();
}
```

The invocation exposes the declaration, callback type, nullable receiver, immutable arguments, and nullable current result. The result stores one `Action` and copies any argument list.

- [x] **Step 4: Run callback, manifest, and compatibility tests**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.PluginPatchCallbackContractTest --tests org.jackhuang.hmcl.plugin.PluginManifestTest --tests org.jackhuang.hmcl.plugin.NextPluginRuntimeTest --rerun-tasks --no-daemon --console plain`

Expected: PASS, including legacy Plugin implementations that do not override `onPatch`.

- [x] **Step 5: Commit the public callback contract**

```text
feat: expose the Java Patch callback contract
```

### Task 3: Target Policy And Bytecode Resolution

**Files:**
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchTargetPolicy.java`
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchTarget.java`
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchFailure.java`
- Create: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/patch/PluginPatchTargetPolicyTest.java`
- Create: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/patchfixture/PatchTargetFixture.java`

**Interfaces:**
- Consumes: `PluginPatchMethod`, launcher class loader, expected launcher code source, optional loaded class, and class resource bytes.
- Produces: `PluginPatchTargetPolicy.resolve(PluginPatchMethod)` returning the exact full JVM descriptor, access flags, loaded class when present, and validated original bytecode.

- [x] **Step 1: Write policy tests for namespace, loader, code source, method body, and overload rejection**

```java
@Test
public void rejectsProtectedPluginNamespace() {
    PluginPatchMethod method = method("org.jackhuang.hmcl.plugin.PluginManager", "enablePlugin", "java.lang.String");
    assertEquals(PluginPatchFailure.Category.DENIED_TARGET,
            assertThrows(PluginPatchFailure.class, () -> policy.resolve(method)).category());
}

@Test
public void resolvesExactOverloadWithoutLoadingPluginCode() throws Exception {
    PluginPatchTarget target = fixturePolicy.resolve(method(
            "org.jackhuang.hmcl.patchfixture.PatchTargetFixture", "join", "java.lang.String", "int"));
    assertEquals("(Ljava/lang/String;I)Ljava/lang/String;", target.method().descriptor());
}
```

Cover bootstrap and plugin loaders, a different code source, `abstract`, `native`, missing overloads, `$$` generated names, and legal synthetic/bridge methods.

- [x] **Step 2: Run the target-policy test and confirm the absent policy fails test compilation**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.patch.PluginPatchTargetPolicyTest --rerun-tasks --no-daemon --console plain`

Expected: FAIL because policy and target types do not exist.

- [x] **Step 3: Resolve target resources with ASM without initializing the target class**

```java
ClassReader reader = new ClassReader(classBytes);
reader.accept(new ClassVisitor(Opcodes.ASM9) {
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        if (method.matches(name, descriptor)) {
            matches.add(new ResolvedMethod(access, descriptor));
        }
        return null;
    }
}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
```

Enforce system-loader identity and normalized code-source equality for loaded classes. For unloaded classes, require a launcher resource URL rooted at the expected directory or JAR.

- [x] **Step 4: Run policy and verifier-focused tests**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.patch.PluginPatchTargetPolicyTest --rerun-tasks --no-daemon --console plain`

Expected: PASS.

- [x] **Step 5: Commit the target boundary**

```text
feat: enforce the launcher Patch target boundary
```

### Task 4: Deterministic Dispatch Engine

**Files:**
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchCallback.java`
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchRegistration.java`
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchDispatchFrame.java`
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchDispatcher.java`
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchEngine.java`
- Create: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/patch/PluginPatchEngineTest.java`

**Interfaces:**
- Consumes: `register(PluginArtifactIdentity, Set<String>, PluginPatchDeclaration, PluginPatchCallback)`.
- Produces: idempotent `PluginPatchRegistration.close()`, immutable method snapshots, `PluginPatchDispatcher.enter(long, Object, Object[])`, and `finish(PluginPatchDispatchFrame, Object)`.

- [x] **Step 1: Write engine tests for order, replacement conflict, close, timeout, failure isolation, recursion, and type validation**

```java
@Test
public void composesBeforeAndAfterInDependencyWrapperOrder() throws Exception {
    register("dev.example.base", Set.of(), BEFORE, invocation -> record("base-before"));
    register("dev.example.child", Set.of("dev.example.base"), BEFORE, invocation -> record("child-before"));
    register("dev.example.base", Set.of(), AFTER, invocation -> record("base-after"));
    register("dev.example.child", Set.of("dev.example.base"), AFTER, invocation -> record("child-after"));
    dispatchOriginal();
    assertEquals(List.of("base-before", "child-before", "original", "child-after", "base-after"), events);
}
```

Use a real executor and latches for timeout/cancellation. Assert the failed registration is skipped on the next call while a healthy neighbor still executes.

- [x] **Step 2: Run the engine test and confirm compilation fails for the absent engine**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.patch.PluginPatchEngineTest --rerun-tasks --no-daemon --console plain`

Expected: FAIL at test compilation.

- [x] **Step 3: Implement copy-on-write plans and bounded callbacks**

```java
private PluginPatchResult invokeBounded(RegistrationState registration,
                                        PluginPatchInvocation invocation,
                                        long aggregateDeadlineNanos) throws PluginPatchFailure {
    long remaining = Math.min(CALLBACK_TIMEOUT_NANOS, aggregateDeadlineNanos - System.nanoTime());
    if (remaining <= 0L) {
        throw new PluginPatchFailure(PluginPatchFailure.Category.TIMEOUT);
    }
    Future<PluginPatchResult> future = callbackExecutor.submit(
            () -> invokeWithoutRecursing(registration, invocation));
    try {
        return future.get(remaining, TimeUnit.NANOSECONDS);
    } catch (TimeoutException exception) {
        future.cancel(true);
        throw new PluginPatchFailure(PluginPatchFailure.Category.TIMEOUT, exception);
    }
}
```

Publish immutable maps under the engine mutation lock. Generate one stable method ID per exact target/name/descriptor. Topologically order active registrations with canonical ID tie breaks; reverse only the `after` list. Reject a second active `replace` before publication.

- [x] **Step 4: Run engine tests repeatedly to expose races**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.patch.PluginPatchEngineTest --rerun-tasks --no-daemon --console plain`

Run the same command three times. Expected: PASS each time with no leaked non-daemon worker.

- [x] **Step 5: Commit the language-neutral engine**

```text
feat: add bounded deterministic Patch dispatch
```

### Task 5: ASM Transformation And Instrumentation Publication

**Files:**
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchTransformer.java`
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginInstrumentation.java`
- Modify: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/HmclMixinAgent.java`
- Modify: `AuraLauncher/build.gradle.kts`
- Create: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/patch/PluginPatchTransformerTest.java`
- Modify: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/HmclMixinAgentTest.java`

**Interfaces:**
- Consumes: engine method plans and JVM-provided pre-Patch class bytes.
- Produces: one `ClassFileTransformer` registered with `canRetransform=true`; transformed methods call `PluginPatchDispatcher.enter` and `finish` without plugin-class constant-pool references.

- [ ] **Step 1: Write bytecode tests covering receiver/static methods, primitives, references, arrays, wide locals, void, and exceptions**

```java
@Test
public void transformsWideStaticMethodAndKeepsOriginalExceptionFlow() throws Exception {
    byte[] transformed = transformFixture("wide", "(JDI)J");
    Class<?> fixture = defineAndVerify(transformed);
    assertEquals(17L, invoke(fixture, "wide", 5L, 3.0d, 9));
    assertSame(originalFailure, assertThrows(IllegalStateException.class,
            () -> invoke(fixture, "throwsOriginal")));
}
```

Inspect constant-pool class references and assert none use a test plugin package. Run ASM `CheckClassAdapter.verify` and define transformed bytes in a child verifier loader.

- [ ] **Step 2: Run transformer and agent tests and confirm missing transformation or manifest flag failures**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.patch.PluginPatchTransformerTest --tests org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinAgentTest --rerun-tasks --no-daemon --console plain`

Expected: FAIL because the transformer is absent and `Can-Retransform-Classes` is false.

- [ ] **Step 3: Implement ASM advice and safe frame computation**

At method entry, box receiver and parameters into an `Object[]`, call `enter`, store the frame in a new local, write validated replacement arguments back to original locals, and branch to a validated replacement return when requested. Rewrite only normal return opcodes to call `finish`; leave `ATHROW` and exception tables unchanged. Use `ClassWriter.COMPUTE_FRAMES | COMPUTE_MAXS` with a system-loader hierarchy resolver that refuses plugin artifact resources.

- [ ] **Step 4: Publish Instrumentation after any Mixin transformer and enable retransformation metadata**

```kotlin
manifest.attributes(
    "Premain-Class" to "org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinAgent",
    "Can-Redefine-Classes" to "false",
    "Can-Retransform-Classes" to "true",
)
```

Install the Patch transformer even when there are zero Mixin configurations. Agent disablement, initialization failure, or unsupported retransformation leaves `PluginInstrumentation.current()` empty.

- [ ] **Step 5: Run transformation, agent, and Shadow manifest tests**

Run: `./gradlew.bat :AuraLauncher:test :AuraLauncher:shadowJar --tests org.jackhuang.hmcl.plugin.patch.PluginPatchTransformerTest --tests org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinAgentTest --rerun-tasks --no-daemon --console plain`

Inspect: `META-INF/MANIFEST.MF` contains the unchanged premain and `Can-Retransform-Classes: true`.

- [ ] **Step 6: Commit the transformer and Agent publication**

```text
feat: install the Aura Patch transformer
```

### Task 6: Java Plugin Lifecycle Integration

**Files:**
- Modify: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/PluginContainer.java`
- Modify: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java`
- Create: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/PluginManagerPatchLifecycleTest.java`
- Create: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/PackagedPatchPlugin.java`

**Interfaces:**
- Consumes: enabled schema-v5 Java plugin declarations, exact artifact permissions, dependency IDs, class loader, and `Plugin.onPatch`.
- Produces: automatic registration after enable, closure before disable/unload, leased callback execution under plugin TCCL, and observable declaration status.

- [ ] **Step 1: Write lifecycle tests for enable registration, permission denial, disable restoration, unload class-loader release, and failed registration isolation**

```java
@Test
public void closesPatchRegistrationsBeforePluginDisableCallback() throws Exception {
    PluginContainer container = loadEnabledPatchPlugin();
    manager.disablePlugin(container.getManifest().getId());
    assertEquals("original", PatchLifecycleFixture.value());
    assertEquals(List.of("patch-closed", "onDisable"), PackagedPatchPlugin.events());
}
```

- [ ] **Step 2: Run the lifecycle test and confirm the declaration never becomes active**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.PluginManagerPatchLifecycleTest --rerun-tasks --no-daemon --console plain`

Expected: FAIL on the active result or missing registration status.

- [ ] **Step 3: Retain registrations in PluginContainer and wire manager enable/disable/unload**

Acquire a container callback lease per registration. The Java callback calls `runPluginCallback(container classLoader, () -> plugin.onPatch(invocation))`. Close every Patch registration before suspending capability sessions, calling `onDisable`, revoking handles, or requesting class-loader close. Registration failures update only the declaration diagnostic and do not disable the plugin.

- [ ] **Step 4: Run lifecycle, Hook, permission, and recovery regressions**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.PluginManagerPatchLifecycleTest --tests org.jackhuang.hmcl.plugin.PluginHookDispatcherTest --tests org.jackhuang.hmcl.plugin.PluginManagerExecutablePermissionTest --tests org.jackhuang.hmcl.plugin.PluginManagerRecoveryTest --rerun-tasks --no-daemon --console plain`

Expected: PASS.

- [ ] **Step 5: Commit Java lifecycle integration**

```text
feat: activate Java plugin Patch declarations
```

### Task 7: Runtime Payload Patch Adapter

**Files:**
- Create: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimePatchWireCodec.java`
- Modify: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimePatchEndpoint.java`
- Modify: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeSupervisor.java`
- Modify: `AuraPluginSystem/src/main/java/org/jackhuang/hmcl/plugin/loader/RuntimePluginLoader.java`
- Create: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/runtime/RuntimePatchWireCodecTest.java`
- Modify: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/runtime/RuntimeSupervisorTest.java`

**Interfaces:**
- Consumes: `PluginPatchInvocation`, invocation-local handle table, exact payload endpoint, current capability session, and `RuntimeProvider.invokePayload`.
- Produces: operation `aura.patch.v1`, callback ID `0`, canonical Bridge Value v1 bytes, `REGISTERED` status, and fail-closed malformed-value handling.

- [ ] **Step 1: Write literal golden-vector tests for request and response maps**

```java
@Test
public void encodesBeforeInvocationAsCanonicalBridgeValueV1() throws Exception {
    byte[] encoded = codec.encodeInvocation(invocation("java.lang.String", 4L));
    Map<String, BridgeValue> expected = new LinkedHashMap<>();
    expected.put("schemaVersion", BridgeValue.integer(1L));
    expected.put("target", BridgeValue.string("org.jackhuang.hmcl.Launcher"));
    expected.put("method", BridgeValue.string("launch"));
    expected.put("parameters", BridgeValue.array(List.of(
            BridgeValue.string("java.lang.String"), BridgeValue.string("long"))));
    expected.put("type", BridgeValue.string("before"));
    expected.put("receiver", BridgeValue.nullValue());
    expected.put("arguments", BridgeValue.array(List.of(
            BridgeValue.string("value"), BridgeValue.integer(4L))));
    expected.put("result", BridgeValue.nullValue());
    assertEquals(BridgeValue.map(expected), RuntimeBridgeWireCodec.decode(encoded));
}

@Test
public void rejectsUnknownOrOutOfOrderResponseMembers() {
    Map<String, BridgeValue> malformed = new LinkedHashMap<>();
    malformed.put("action", BridgeValue.string("unchanged"));
    malformed.put("schemaVersion", BridgeValue.integer(1L));
    byte[] response = RuntimeBridgeWireCodec.encode(BridgeValue.map(malformed));
    assertThrows(IOException.class, () -> codec.decodeResult(
            response, invocation("java.lang.String", 4L)));
}
```

Cover null, bool, int64, finite double, UTF-8 string, bytes, arrays, maps, handles, duplicate keys, stale handles, wrong argument count/type, non-finite values, unknown fields, and oversized values.

- [ ] **Step 2: Run codec and Supervisor tests and confirm RED behavior**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.runtime.RuntimePatchWireCodecTest --tests org.jackhuang.hmcl.plugin.runtime.RuntimeSupervisorTest --rerun-tasks --no-daemon --console plain`

Expected: FAIL because runtime Patch registration still returns `PATCH_ENGINE_UNAVAILABLE` and no codec exists.

- [ ] **Step 3: Implement invocation-local value and handle mapping**

Use `RuntimeBridgeWireCodec` for bytes. Allocate handles only for receiver, reference arguments, and reference results; bind each to the exact invocation and declared JVM type. Invalidate the entire table in `finally`, including timeout and malformed response paths.

- [ ] **Step 4: Reauthorize and invoke the exact Runtime payload**

`RuntimePatchEndpoint` implements the engine callback. Every invocation obtains a fresh token, calls `permissionAuthority.requirePermission(token, artifactIdentity.getPluginId(), artifactIdentity, executionMode, PluginPermission.LAUNCHER_PATCH, RuntimeHookEndpoint.CALLBACK_DOMAIN)`, runs the exact-record lifecycle gate, invokes `RuntimeSupervisor.invokePayload(artifactIdentity.getPluginId(), "aura.patch.v1", input, 0L)`, validates the response, and revokes the token in `finally`.

- [ ] **Step 5: Run real generic-invoke and lifecycle failure tests**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.runtime.RuntimePatchWireCodecTest --tests org.jackhuang.hmcl.plugin.runtime.RuntimeSupervisorTest --tests org.jackhuang.hmcl.plugin.PluginManagerRuntimeProviderLifecycleTest --rerun-tasks --no-daemon --console plain`

Expected: PASS for registration, callback, Host crash, timeout, disable, unload, stale handle, and current permission reauthorization.

- [ ] **Step 6: Commit Runtime adaptation**

```text
feat: route Runtime payload Patches through Bridge Value v1
```

### Task 8: Real Javaagent Retransformation And Restoration

**Files:**
- Create: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/patch/PluginPatchAgentIntegrationTest.java`
- Create: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/patch/PluginPatchAgentChild.java`
- Create: `AuraPluginSystem/src/test/resources/patch-agent-fixtures/` test package resources as required by the child JVM
- Modify: `AuraPluginSystem/src/test/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/HmclMixinAgentTest.java`

**Interfaces:**
- Consumes: the built Shadow JAR as `-javaagent` and classpath, test plugin packages, and process output.
- Produces: end-to-end proof of loaded-class retransformation, future-load transformation, unload restoration, replacement conflict rejection, and post-Mixin restoration.

- [ ] **Step 1: Write a child-JVM test that reports literal phase results**

```java
assertEquals(List.of(
        "baseline=original",
        "before=base,child,original",
        "replace=replaced",
        "restored=original",
        "future=future-patched",
        "mixin=mixin-preserved"
), runAgentChild().stdoutLines());
```

- [ ] **Step 2: Run the integration test without lifecycle wiring and confirm RED output**

Run: `./gradlew.bat :AuraLauncher:shadowJar :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.patch.PluginPatchAgentIntegrationTest --rerun-tasks --no-daemon --console plain`

Expected: FAIL because at least one patched phase still reports the original value.

- [ ] **Step 3: Complete transactional retransform and restoration behavior exposed by the child test**

Registration publishes the new immutable plan before one-class retransformation and rolls back publication if retransformation fails. Close removes registration first and attempts restoration; a failed restoration leaves stale dispatcher calls as no-ops because the method ID no longer resolves to a live plan.

- [ ] **Step 4: Run the real javaagent test plus focused Mixin tests**

Run: `./gradlew.bat :AuraLauncher:shadowJar :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.patch.PluginPatchAgentIntegrationTest --tests org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinAgentTest --rerun-tasks --no-daemon --console plain`

Expected: PASS with child exit code zero and no verifier errors.

- [ ] **Step 5: Commit end-to-end restoration coverage**

```text
test: verify Patch retransformation and restoration
```

### Task 9: Documentation, SDK Contract, And Final Gate

**Files:**
- Modify: `AuraPluginSystem/README.md`
- Modify: `docs/PLUGIN_QUICKSTART.md`
- Modify: schema-v5 SDK examples under the repository path discovered by `rg --files | rg 'schema-v5|examples'`
- Modify: workflow policy tests only if an existing test requires the Patch javaagent gate to be named explicitly

**Interfaces:**
- Consumes: the finished `Plugin.onPatch` and `aura.patch.v1` contracts.
- Produces: Java and external Runtime examples that declare schema-v5 Patches without changing Host wire protocol or release versions.

- [ ] **Step 1: Update docs and examples with executable before/after/replace handlers**

Document safe targets, protected namespaces, callback actions, ordering, deadline behavior, failure fallback, permission review, and the Runtime operation map. Keep all product-facing names as Aura Launcher and retain compatibility IDs only where technically required.

- [ ] **Step 2: Run example/package validators and focused public-contract tests**

Run the repository's existing schema-v5 validator command discovered from its README or workflow, then run:

`./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.PluginManifestTest --tests org.jackhuang.hmcl.plugin.PluginManagerPatchLifecycleTest --tests org.jackhuang.hmcl.plugin.runtime.RuntimePatchWireCodecTest --rerun-tasks --no-daemon --console plain`

Expected: PASS.

- [ ] **Step 3: Run the complete launcher gate with production version input**

```powershell
$env:BUILD_VERSION='27.1'
./gradlew.bat checkstyle checkTranslations test shadowJar --no-daemon --stacktrace --console plain
```

Expected: PASS and exactly one `Aura-Launcher-27.1-next.jar`.

- [ ] **Step 4: Audit the distributable**

Verify `Implementation-Version: 27.1-next`, unchanged `Premain-Class`, retransformation true, no duplicate `-next`, one Apache Plugin System LICENSE/NOTICE, retained GPL launcher license, no private trust key, no credential, and no current-product CE wording. Run `actionlint` against all six workflows and `gitleaks` against changed files and the delivered commit range.

- [ ] **Step 5: Commit docs only after all contract examples validate**

```text
docs: document schema-v5 Patch callbacks
```

- [ ] **Step 6: Fetch and ordinary-push each green commit to main, then monitor Java CI**

Before each push, require `origin/main` to equal the previously delivered commit; if it advanced, integrate normally and rerun the affected gate. Do not force-push, trigger Release, or trigger HarmonyOS Real SDK.
