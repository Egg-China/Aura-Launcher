# Aura Official Plugin Store Signing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a strictly verified Ed25519 envelope for Aura's official plugin registry and enable that source by default only in Aura builds carrying the matching public role key.

**Architecture:** The Store keeps reviewable schema-v1 data in `registry.json` and generates `plugins.json` with a strict Node signer whose canonical bytes match Aura's Java verifier. Aura embeds a purpose-scoped public root through `AURA_PLUGIN_ROOT_JSON`, derives default-source enablement from the verified role, and fails closed for unsigned or mutated official content.

**Tech Stack:** Java 17/JUnit 5/Gradle, Node.js 24 `node:test` and `crypto`, pinned `jsonc-parser`, PowerShell 7, GitHub Actions, GitHub CLI.

**Spec:** `docs/superpowers/specs/2026-08-29-official-plugin-store-signing-design.md`

## Global Constraints

- User-visible product, Store, source, status, artifact, and release text must use exactly `Aura Launcher`; it must not expose `CE` or `HMCL CE`.
- `HMCLCE-OFFICIAL-REGISTRY-V1`, `org.jackhuang.hmcl`, and `dev.hmclce` remain only as compatibility identifiers and must not be renamed.
- The initial production root contains only the `official-repository` online role and has a blank `statusUrl`.
- The Store signing secret is `AURA_OFFICIAL_REGISTRY_SIGNING_KEY_PKCS8_BASE64`; private bytes never enter Git, logs, command arguments, repository variables, or artifacts.
- The public Aura build variable is `AURA_PLUGIN_ROOT_JSON`.
- Store registry schema remains v1, repository manifest schema remains v2, and NPL plugin schema remains v5.
- `schema-v4` is not modified.
- Aura remains version `27.1-next`, with exactly one `-next`; the Shadow JAR remains `Aura-Launcher-27.1-next.jar` and carries `Implementation-Version: 27.1-next`.
- Every new or modified Java declaration follows `@NotNullByDefault`, explicit `@Nullable`, immutable collection/array annotations, and `///` documentation.
- All GitHub Actions are pinned to immutable commit SHAs. All pushes are ordinary non-force pushes.

## Repository Roots

- Aura: `C:\Users\ACX\Documents\Aura-Launcher`
- Store: `C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-Store`

---

### Task 1: Strict Store Canonical JSON And Ed25519 Tool

**Files:**
- Create: Store `package.json`
- Create: Store `package-lock.json`
- Create: Store `tools/registry-envelope.mjs`
- Create: Store `tools/registry-envelope.test.mjs`

**Interfaces:**
- Produces: `parseStrictJson(text)`, `canonicalizeJsonText(text)`, `signatureInput(text)`, `keyId(publicKey)`, `createEnvelope(registryText, privateKeyBase64)`, `verifyEnvelope(envelopeText, rootText)`.
- Produces CLI: `generate-key`, `sign`, `verify`, and `extract` subcommands.
- Consumes: PKCS#8 and X.509 SPKI Ed25519 DER encoded as Base64.

- [ ] **Step 1: Add failing canonicalization tests**

Use `node:test` to assert the exact Aura vectors and strict parser failures:

```js
test('matches Aura canonical JSON and signature-domain bytes', () => {
  assert.equal(
    canonicalizeJsonText('{"z":[3,{"b":true,"a":null}],"a":-12}').toString('utf8'),
    '{"a":-12,"z":[3,{"a":null,"b":true}]}'
  );
  assert.equal(
    signatureInput('{"id":"dev.example"}').toString('utf8'),
    'HMCLCE-OFFICIAL-REGISTRY-V1\n{"id":"dev.example"}'
  );
});

test('rejects duplicate keys, fractions, unsafe integers, and unpaired surrogates', () => {
  for (const value of [
    '{"a":1,"a":2}',
    '{"value":1.5}',
    '{"value":9007199254740992}',
    '{"value":"\\ud800"}'
  ]) {
    assert.throws(() => canonicalizeJsonText(value));
  }
});
```

- [ ] **Step 2: Run the test and verify RED**

Run: `npm test`

Expected: FAIL because `tools/registry-envelope.mjs` and its exports do not exist.

- [ ] **Step 3: Implement strict AST canonicalization**

Pin `jsonc-parser` to `3.3.1` in `package.json` and traverse its AST rather than a lossy JavaScript object. Reject parser errors and duplicate property names. Canonicalize number source text to an exact `BigInt`, require an integral safe value, sort object names with ordinal UTF-16 comparison, and escape strings with Aura's minimal rules.

```js
export const OFFICIAL_REGISTRY_DOMAIN = 'HMCLCE-OFFICIAL-REGISTRY-V1';
export const MAX_SAFE_INTEGER = 9007199254740991n;

export function signatureInput(text) {
  return Buffer.concat([
    Buffer.from(`${OFFICIAL_REGISTRY_DOMAIN}\n`, 'ascii'),
    canonicalizeJsonText(text)
  ]);
}
```

- [ ] **Step 4: Add failing signing and mutation tests**

Generate an ephemeral Ed25519 pair with `generateKeyPairSync('ed25519')`. Assert that a valid envelope verifies and that payload, signature, key ID, malformed Base64, non-Ed25519 key, and extra signature-object fields fail.

```js
const envelope = createEnvelope(REGISTRY, privateDer.toString('base64'));
assert.deepEqual(verifyEnvelope(JSON.stringify(envelope), JSON.stringify(root)), JSON.parse(REGISTRY));
envelope.signed.name = 'Mutated';
assert.throws(() => verifyEnvelope(JSON.stringify(envelope), JSON.stringify(root)));
```

- [ ] **Step 5: Run the signing test and verify RED**

Run: `npm test`

Expected: canonical tests pass and signing tests fail because key loading, envelope creation, and role verification are absent.

- [ ] **Step 6: Implement key and envelope operations**

Use `createPrivateKey`, `createPublicKey`, `sign`, `verify`, and SHA-256 from `node:crypto`. `sign` reads the private key only from `AURA_OFFICIAL_REGISTRY_SIGNING_KEY_PKCS8_BASE64` or a protected file named by an environment variable; it never accepts private bytes as a CLI argument. `verify` selects only keys referenced by `official-repository`, requires threshold one, and rejects all malformed declarations.

- [ ] **Step 7: Verify GREEN**

Run: `npm ci; npm test`

Expected: all canonicalization, strict parsing, signing, and mutation tests PASS with no warnings.

- [ ] **Step 8: Commit the signer**

```powershell
git add package.json package-lock.json tools/registry-envelope.mjs tools/registry-envelope.test.mjs
git commit -m "feat: add strict official registry signer"
```

### Task 2: Signed Registry Validation And Source/Output Split

**Files:**
- Rename: Store `plugins.json` to `registry.json`
- Delete: Store plain `plugins.json`; Task 4 recreates it only as the production envelope
- Modify: Store `tools/validate-store.ps1`
- Modify: Store `tools/test-validate-store.ps1`
- Modify: Store `README.md`

**Interfaces:**
- `validate-store.ps1 -Registry <path> -TrustRoot <path>` verifies a signed official envelope by default.
- `validate-store.ps1 -Registry <path> -UnsignedPayload` validates review-source `registry.json` without assigning official trust.
- `registry-envelope.mjs extract --envelope <path> --root <path> --output <path>` writes only a verified payload.

- [ ] **Step 1: Add failing validator cases**

Extend `test-validate-store.ps1` to generate an ephemeral key/root and cover:

```powershell
$plainOfficial = Invoke-Validator -Registry $registry -TrustRoot $rootFile
Assert-Fails $plainOfficial 'Plain official registry'

$signedOfficial = Invoke-Validator -Registry $envelope -TrustRoot $rootFile
Assert-Succeeds $signedOfficial 'Signed official registry'

$unsignedSource = Invoke-Validator -Registry $registry -UnsignedPayload
Assert-Succeeds $unsignedSource 'Reviewed unsigned payload'
```

Add cases for wrong root, changed `signed.name`, changed signature, missing `manifestSha256`, and mismatched local manifest bytes.

- [ ] **Step 2: Run validator tests and verify RED**

Run: `pwsh -NoProfile -File ./tools/test-validate-store.ps1`

Expected: FAIL because `-TrustRoot`, `-UnsignedPayload`, and envelope extraction are unsupported.

- [ ] **Step 3: Implement envelope-aware validation**

Add parameters:

```powershell
param(
    [string]$Registry = (Join-Path (Split-Path -Parent $PSScriptRoot) 'plugins.json'),
    [string]$TrustRoot = '',
    [switch]$UnsignedPayload,
    [string[]]$Manifests = @(),
    [switch]$VerifyRemote,
    [string]$NplValidator = ''
)
```

For signed mode, invoke Node `extract` into an explicitly validated temporary directory, parse only that output, and delete it in `finally`. Keep all existing schema-v1, manifest pin, six-platform artifact, size, SHA-256, and NPL checks.

- [ ] **Step 4: Split source and generated registry files**

Move the current schema-v1 object to `registry.json` and remove the old plain `plugins.json` path. Change the Rust entry's visible author from `HMCL CE Community` to `Aura Launcher Community`. Validator tests create signed envelopes only under their OS temporary directory; no temporary-key publication output enters Git.

- [ ] **Step 5: Update Store documentation**

Document `registry.json` as reviewed source, `plugins.json` as generated signed output, exact local commands, secret/public-key boundaries, rotation order, and the fact that Store display text uses `Aura Launcher` only.

- [ ] **Step 6: Verify GREEN**

Run:

```powershell
npm test
pwsh -NoProfile -File ./tools/test-validate-store.ps1
pwsh -NoProfile -File ./tools/validate-store.ps1 -Registry ./registry.json -UnsignedPayload
```

Expected: all commands PASS.

- [ ] **Step 7: Commit validation and source split**

```powershell
git add registry.json plugins.json tools/validate-store.ps1 tools/test-validate-store.ps1 README.md
git commit -m "feat: validate signed official registry envelopes"
```

### Task 3: Store CI Validation And Main-Branch Publication

**Files:**
- Create: Store `.github/workflows/publish-registry.yml`
- Modify: Store `.github/workflows/validate.yml`
- Create: Store `tools/test-ci-workflows.ps1`

**Interfaces:**
- PR workflow has `contents: read` and never references the production secret.
- Main publication workflow has `contents: write`, references the secret only in the signing step, and triggers on source/tool/root changes but not generated `plugins.json` alone.

- [ ] **Step 1: Write failing workflow policy tests**

Assert pinned checkout/setup-node actions, Node 24, `npm ci`, `npm test`, unsigned source validation, signed output validation, main-only publication, a concurrency group, no PR trigger on the signing workflow, and no production-secret token in `validate.yml`.

```powershell
Assert-Condition (-not $validate.Contains('AURA_OFFICIAL_REGISTRY_SIGNING_KEY')) `
    'PR validation must not reference the production signing secret.'
Assert-Condition ($publish.Contains('contents: write')) `
    'Registry publication requires narrowly scoped contents write.'
```

- [ ] **Step 2: Run workflow tests and verify RED**

Run: `pwsh -NoProfile -File ./tools/test-ci-workflows.ps1`

Expected: FAIL because the publication workflow and Node gates do not exist.

- [ ] **Step 3: Implement validation workflow changes**

Keep every action SHA-pinned. Add setup-node, `npm ci`, signer tests, source validation, production-envelope verification, and existing complete remote NPL validation. PR CI verifies the current checked-in envelope separately and does not require it to match unmerged `registry.json`.

- [ ] **Step 4: Implement main publication workflow**

Use `on.push.branches: [main]`, path filters excluding generated-only changes, `concurrency: official-registry-publication`, and `cancel-in-progress: false`. Decode the secret into `$RUNNER_TEMP`, sign, verify, run complete validation, and commit only `plugins.json` as `github-actions[bot]` when changed.

- [ ] **Step 5: Verify GREEN**

Run:

```powershell
pwsh -NoProfile -File ./tools/test-ci-workflows.ps1
npm test
pwsh -NoProfile -File ./tools/test-validate-store.ps1
```

Expected: all commands PASS and no secret values appear in output.

- [ ] **Step 6: Commit Store CI**

```powershell
git add .github/workflows/validate.yml .github/workflows/publish-registry.yml tools/test-ci-workflows.ps1
git commit -m "ci: publish signed official registry"
```

### Task 4: Create The Production Store Key And Publish The Envelope

**Files:**
- Create: Store `trust/aura-plugin-root.json`
- Replace: Store `plugins.json` with the production envelope

**Interfaces:**
- Root profile: version 1, future expiry, blank `statusUrl`, one Ed25519 key, one `official-repository` role at threshold one.
- GitHub secret: `Egg-China/Aura-Launcher-Plugin-Store/AURA_OFFICIAL_REGISTRY_SIGNING_KEY_PKCS8_BASE64`.

- [ ] **Step 1: Verify repositories and exact targets before key creation**

Run:

```powershell
gh repo view Egg-China/Aura-Launcher-Plugin-Store --json nameWithOwner,defaultBranchRef,url
gh repo view Egg-China/Aura-Launcher --json nameWithOwner,defaultBranchRef,url
git status --short
```

Expected: both repositories are under `Egg-China`, both default branches are `main`, and the Store worktree contains only planned changes.

- [ ] **Step 2: Generate the key into an OS temporary directory**

Use `registry-envelope.mjs generate-key` with private and public output paths under a newly created temporary directory. Do not print or inspect the private file. Derive the public key ID and generate the exact purpose-scoped root.

- [ ] **Step 3: Prove the root and key match before storing the secret**

Sign `registry.json`, verify the envelope against `trust/aura-plugin-root.json`, change one payload byte and require failure, then restore the verified output.

- [ ] **Step 4: Store the private key without exposing it**

Stream the file through standard input:

```powershell
Get-Content -Raw -LiteralPath $privateKeyPath |
    gh secret set AURA_OFFICIAL_REGISTRY_SIGNING_KEY_PKCS8_BASE64 `
        --repo Egg-China/Aura-Launcher-Plugin-Store
```

Confirm only the secret name with `gh secret list`; never retrieve or echo its value.

- [ ] **Step 5: Remove temporary private material**

Resolve the temporary directory to an absolute path, verify it remains under the OS temporary root, and remove only that directory recursively. Report that the local private copy is unrecoverable and the GitHub Secret is the retained operational copy.

- [ ] **Step 6: Run the complete Store gate**

Run:

```powershell
npm ci
npm test
pwsh -NoProfile -File ./tools/test-ci-workflows.ps1
pwsh -NoProfile -File ./tools/test-validate-store.ps1
pwsh -NoProfile -File ./tools/validate-store.ps1 `
    -Registry ./plugins.json `
    -TrustRoot ./trust/aura-plugin-root.json `
    -VerifyRemote `
    -NplValidator ../HMCL-CE-Plugin-SDK/tools/validate-npl.ps1
```

Expected: the public root verifies the envelope and all 30 existing Runtime Host NPL artifacts pass.

- [ ] **Step 7: Commit and publish the signed Store before Aura enablement**

```powershell
git add trust/aura-plugin-root.json plugins.json
git commit -m "release: publish signed Aura plugin registry"
git push aura HEAD:codex/signed-official-store
gh pr create --repo Egg-China/Aura-Launcher-Plugin-Store `
    --base main --head codex/signed-official-store `
    --title "Publish signed Aura plugin registry" `
    --body "Adds the reviewed registry source, purpose-scoped public root, strict signer, and signed publication workflow."
gh pr merge --repo Egg-China/Aura-Launcher-Plugin-Store `
    codex/signed-official-store --merge --delete-branch
```

Wait for PR validation, merge with a normal merge commit, and monitor both Store workflows on `main` to terminal success. Download raw `plugins.json`, verify it with the checked-in root, and compare its `signed` object structurally to raw `registry.json`.

### Task 5: Aura Purpose-Scoped Root Validation

**Files:**
- Modify: Aura `AuraLauncher/build.gradle.kts`
- Modify: Aura `AuraLauncher/src/main/java/org/jackhuang/hmcl/plugin/trust/PluginTrustRoot.java`
- Modify: Aura `AuraLauncher/src/main/java/org/jackhuang/hmcl/plugin/trust/PluginTrustVerifier.java`
- Modify: Aura `AuraLauncher/src/test/java/org/jackhuang/hmcl/plugin/trust/PluginTrustVerifierTest.java`
- Modify: Aura `AuraLauncher/src/test/java/org/jackhuang/hmcl/plugin/trust/PluginTrustRootResourceTest.java`
- Create: Aura `tools/test-plugin-trust-root.ps1`

**Interfaces:**
- `PluginTrustRoot.hasRole(String roleName): boolean` reports a fully parsed role.
- `PluginTrustVerifier.supportsOfficialRegistry(): boolean` exposes only official-registry capability.
- Build validation accepts development and official-store profiles; certification roles are all-or-none with HTTPS status.

- [ ] **Step 1: Add failing Java role-capability tests**

Create an official-only root fixture and assert:

```java
PluginTrustVerifier official = PluginTrustVerifier.fromRoot(
        officialOnlyRoot(), CLOCK, Set.of(), Set.of());
assertTrue(official.supportsOfficialRegistry());
assertFalse(developmentVerifier().supportsOfficialRegistry());
```

Also assert an official-only root verifies an official envelope but cannot verify attestations signed by undeclared roles.

- [ ] **Step 2: Run focused Java tests and verify RED**

Run:

```powershell
./gradlew.bat :AuraLauncher:test `
    --tests org.jackhuang.hmcl.plugin.trust.PluginTrustVerifierTest `
    --tests org.jackhuang.hmcl.plugin.trust.PluginTrustRootResourceTest
```

Expected: FAIL because the role-capability API does not exist.

- [ ] **Step 3: Implement the minimal Java role API**

Add documented methods without exposing keys or thresholds:

```java
/// Returns whether this root contains a fully parsed role.
boolean hasRole(String roleName) {
    return roles.containsKey(roleName);
}

/// Returns whether the embedded root can authenticate the official Store registry.
public boolean supportsOfficialRegistry() {
    return root.hasRole(OFFICIAL_REPOSITORY_ROLE);
}
```

- [ ] **Step 4: Write failing build-profile tests**

`test-plugin-trust-root.ps1` invokes `:AuraLauncher:createPluginTrustRoot` with isolated Gradle user homes and exact JSON fixtures. Require success for the checked-in development root and official-only root; require failure for expired root, missing official role, wrong key ID, reused online key, partial certification role suite, or non-HTTPS certification status URL.

- [ ] **Step 5: Run the build-profile test and verify RED**

Run: `pwsh -NoProfile -File ./tools/test-plugin-trust-root.ps1`

Expected: official-only root FAILS because current Gradle validation requires four online roles and HTTPS status.

- [ ] **Step 6: Implement explicit root profiles in Gradle**

Always validate root schema, expiry, key IDs, Ed25519 declarations, role thresholds, key references, and cross-role key uniqueness. Require `official-repository` for a configured production root. Treat `repository-attestor`, `artifact-attestor`, and `trust-status` as an all-or-none suite; require a credential-free HTTPS `statusUrl` only when that suite is present, otherwise require it to be blank.

- [ ] **Step 7: Verify GREEN**

Run the focused Java tests and `tools/test-plugin-trust-root.ps1` again.

Expected: all profile, role, and signature tests PASS.

- [ ] **Step 8: Commit Aura root support**

```powershell
git add AuraLauncher/build.gradle.kts `
    AuraLauncher/src/main/java/org/jackhuang/hmcl/plugin/trust/PluginTrustRoot.java `
    AuraLauncher/src/main/java/org/jackhuang/hmcl/plugin/trust/PluginTrustVerifier.java `
    AuraLauncher/src/test/java/org/jackhuang/hmcl/plugin/trust/PluginTrustVerifierTest.java `
    AuraLauncher/src/test/java/org/jackhuang/hmcl/plugin/trust/PluginTrustRootResourceTest.java `
    tools/test-plugin-trust-root.ps1
git commit -m "feat: support official Store trust roots"
```

### Task 6: Aura Default Official Source And Visible Branding Gate

**Files:**
- Modify: Aura `AuraLauncher/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java`
- Modify: Aura `AuraLauncher/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreManagerTest.java`
- Modify: Aura `AuraLauncher/src/test/java/org/jackhuang/hmcl/AuraBrandingTest.java`
- Modify: Aura `AuraLauncher/src/main/resources/assets/lang/I18N.properties`
- Modify: Aura `AuraLauncher/src/main/resources/assets/lang/I18N_zh_CN.properties`
- Modify: Aura `AuraLauncher/src/main/resources/assets/lang/I18N_zh.properties`
- Modify: Aura `AuraLauncher/src/main/resources/assets/lang/I18N_ar.properties`
- Modify: Aura `AuraLauncher/src/main/java/org/jackhuang/hmcl/setting/LegacyHmclCeDataImporter.java`
- Create: Aura `AuraLauncher/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStorePublicSmokeTest.java`

**Interfaces:**
- `PluginStoreManager.defaultRegistryEnabled(@Nullable String override, PluginTrustVerifier verifier): boolean` is a package-local pure policy seam.
- `DEFAULT_REGISTRY_ENABLED` uses the system-property override when present, otherwise the embedded role capability.
- Public smoke test runs only when `AURA_PUBLIC_STORE_SMOKE=true`.

- [ ] **Step 1: Add failing default-source policy tests**

Assert no-role/official-role and explicit override cases:

```java
assertFalse(PluginStoreManager.defaultRegistryEnabled(null, developmentVerifier));
assertTrue(PluginStoreManager.defaultRegistryEnabled(null, officialVerifier));
assertFalse(PluginStoreManager.defaultRegistryEnabled("false", officialVerifier));
assertTrue(PluginStoreManager.defaultRegistryEnabled("true", developmentVerifier));
```

The last case enables the attempt only; the existing official signature check still rejects unsigned content.

- [ ] **Step 2: Run the manager test and verify RED**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.plugin.store.PluginStoreManagerTest`

Expected: FAIL because `defaultRegistryEnabled` is absent and the constant still falls back to `false`.

- [ ] **Step 3: Implement role-derived default enablement**

Load the embedded verifier once for static default calculation and use:

```java
static boolean defaultRegistryEnabled(
        @Nullable String override,
        PluginTrustVerifier verifier
) {
    return override == null
            ? verifier.supportsOfficialRegistry()
            : Boolean.parseBoolean(override);
}
```

Retain `DEFAULT_REGISTRY_URL` and the fail-closed official envelope verification path unchanged.

- [ ] **Step 4: Add a failing visible-brand scan**

Extend `AuraBrandingTest` to read every translation property value and reject case-insensitive `HMCL CE` or `PCL CE`. Exclude keys and compatibility-only Java identifiers; exclude `mod_data.txt` because third-party mod names are not Launcher branding.

```java
assertFalse(value.toLowerCase(Locale.ROOT).contains("hmcl ce"), source + " exposes HMCL CE");
assertFalse(value.toLowerCase(Locale.ROOT).contains("pcl ce"), source + " exposes PCL CE");
```

- [ ] **Step 5: Run branding test and verify RED**

Run: `./gradlew.bat :AuraLauncher:test --tests org.jackhuang.hmcl.AuraBrandingTest`

Expected: FAIL on legacy-import and multiplayer translation values that currently display `CE`.

- [ ] **Step 6: Remove visible CE branding**

Change migration display text to `legacy launcher settings` / `旧启动器设置` / equivalent neutral wording, keep `.hmcl` only as the selected directory detail, replace visible `PCL CE` with `PCL`, and change user-visible importer failure/log strings to `legacy launcher`. Do not rename compatibility classes, methods, state files, source IDs, or protocol domains.

- [ ] **Step 7: Add the opt-in public Store smoke test**

With `@EnabledIfEnvironmentVariable(named = "AURA_PUBLIC_STORE_SMOKE", matches = "true")`, call `loadDefaultRegistry()`, require official trust for every resolved item, and resolve a current-platform install plan for `dev.hmclce.runtime.wasm-host`:

```java
manager.loadDefaultRegistry();
assertFalse(manager.getStoreItems().isEmpty());
assertTrue(manager.getStoreItems().stream()
        .allMatch(item -> item.getTrust().level() == PluginTrustLevel.OFFICIAL));
PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(
        manager.getStoreItems().stream()
                .filter(item -> item.getEntry().getId().equals("dev.hmclce.runtime.wasm-host"))
                .findFirst()
                .orElseThrow()
                .getLatestVersion()
);
PluginInstallPlan plan = manager.resolveInstallPlan(
        "dev.hmclce.runtime.wasm-host", version, Map.of());
assertFalse(plan.getEntries().isEmpty());
```

- [ ] **Step 8: Verify GREEN**

Run manager, branding, and Store tests locally. Leave the network smoke test skipped until Task 8 injects the production root.

- [ ] **Step 9: Commit default source and branding**

```powershell
git add AuraLauncher/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java `
    AuraLauncher/src/main/java/org/jackhuang/hmcl/setting/LegacyHmclCeDataImporter.java `
    AuraLauncher/src/main/resources/assets/lang `
    AuraLauncher/src/test/java/org/jackhuang/hmcl/AuraBrandingTest.java `
    AuraLauncher/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreManagerTest.java `
    AuraLauncher/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStorePublicSmokeTest.java
git commit -m "feat: enable the verified Aura plugin Store"
```

### Task 7: Aura CI Root Injection And Workflow Policy

**Files:**
- Modify: Aura `.github/workflows/gradle.yml`
- Modify: Aura `.github/workflows/release.yml`
- Create: Aura `tools/test-plugin-trust-workflows.ps1`

**Interfaces:**
- Both build workflows pass `${{ vars.AURA_PLUGIN_ROOT_JSON }}` as `AURA_PLUGIN_ROOT_JSON`.
- Java CI runs root-profile tests and the public Store smoke after ordinary unit tests.

- [ ] **Step 1: Write failing static workflow tests**

Require both workflows to inject the public variable, never reference the Store private secret, preserve exact `BUILD_VERSION`/`-next` behavior, and keep actions SHA-pinned.

```powershell
Assert-Condition ($gradle.Contains('AURA_PLUGIN_ROOT_JSON: ${{ vars.AURA_PLUGIN_ROOT_JSON }}')) `
    'Java CI must inject the public plugin root.'
Assert-Condition (-not ($gradle + $release).Contains('AURA_OFFICIAL_REGISTRY_SIGNING_KEY')) `
    'Aura workflows must never receive the Store signing key.'
```

- [ ] **Step 2: Run workflow test and verify RED**

Run: `pwsh -NoProfile -File ./tools/test-plugin-trust-workflows.ps1`

Expected: FAIL because neither workflow injects the root.

- [ ] **Step 3: Inject and test the public root**

Add the variable to Java CI's build environment and release build environment. Add focused steps for `tools/test-plugin-trust-root.ps1` and the opt-in public Store smoke. Keep release artifact verification unchanged.

- [ ] **Step 4: Verify GREEN**

Run:

```powershell
pwsh -NoProfile -File ./tools/test-plugin-trust-workflows.ps1
pwsh -NoProfile -File ./tools/test-plugin-trust-root.ps1
```

Expected: all workflow and root-profile assertions PASS.

- [ ] **Step 5: Commit workflow injection**

```powershell
git add .github/workflows/gradle.yml .github/workflows/release.yml `
    tools/test-plugin-trust-workflows.ps1
git commit -m "ci: inject the Aura plugin trust root"
```

### Task 8: Production Variable, Full Verification, And Rollout

**Files:**
- No new source files; this task publishes already committed bytes and records command/run evidence.

**Interfaces:**
- Aura repository variable value equals Store `trust/aura-plugin-root.json` bytes structurally.
- Public raw Store envelope verifies and Aura assigns `OFFICIAL` trust without fallback.

- [ ] **Step 1: Set the public Aura repository variable**

Use the checked-in Store root as the only input:

```powershell
$rootJson = Get-Content -Raw `
    'C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-Store\trust\aura-plugin-root.json'
gh variable set AURA_PLUGIN_ROOT_JSON --repo Egg-China/Aura-Launcher --body $rootJson
```

Read the public variable back with `gh variable get`, parse both documents, and require structural equality. No private key is involved.

- [ ] **Step 2: Run full Aura gates with the production root**

Set the local process environment from the Store root and run:

```powershell
$env:AURA_PLUGIN_ROOT_JSON = $rootJson
./gradlew.bat checkstyle checkTranslations test shadowJar --no-daemon --stacktrace
```

Expected: all tasks PASS; the public smoke remains opt-in for the next step.

- [ ] **Step 3: Run real public Aura Store loading**

```powershell
$env:AURA_PUBLIC_STORE_SMOKE = 'true'
./gradlew.bat :AuraLauncher:test `
    --tests org.jackhuang.hmcl.plugin.store.PluginStorePublicSmokeTest `
    --rerun-tasks --no-daemon --stacktrace
```

Expected: the signed public registry loads, every item is official, and the Wasm current-platform install plan resolves.

- [ ] **Step 4: Verify Aura identity artifacts**

Require exactly one `Aura-Launcher-27.1-next.jar`, inspect its manifest, and verify:

```text
Implementation-Version: 27.1-next
```

Open the JAR's `/assets/aura-plugin-root.json`, compare it structurally with the Store root, and assert that it contains no private material.

- [ ] **Step 5: Scan visible branding and secrets**

Run focused branding tests, `rg` over translation values and Store registry display fields, and `gitleaks detect --no-banner --redact`. Allow compatibility IDs only in Java namespaces, protocol constants, legacy state names, tests, and technical documentation; no UI value may expose `CE`.

- [ ] **Step 6: Push Aura normally and monitor CI**

```powershell
git push origin main
gh run list --workflow "Java CI" --limit 5
```

Wait for Java CI to reach terminal success. Inspect failed logs if any, fix through a new test-first commit, and never force push.

- [ ] **Step 7: Final public-byte audit**

Download Store `plugins.json`, every pinned manifest, and every release asset again. Verify envelope signature, manifest hashes, all package hashes/sizes, NPL internals, and current-platform selection. Record Store and Aura commit SHAs plus successful workflow run IDs in the delivery summary.

- [ ] **Step 8: Confirm clean worktrees**

Run `git status --short` in Aura and Store. Expected: no tracked or untracked task residue, no temporary key files, and no private-key material anywhere under either repository.
