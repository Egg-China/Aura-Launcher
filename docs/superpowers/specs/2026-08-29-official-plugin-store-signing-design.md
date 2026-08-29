# Aura Official Plugin Store Signing Design

## Status

Approved in chat on 2026-08-29. This specification covers only publication and default loading of the
official Aura Plugin Store. Store facets, SDK example expansion, stable Runtime Host promotion, Python
support, Patch execution, and broader trust-service work remain separate deliverables.

## Goal

Publish the existing official Store registry as an Ed25519-signed envelope that Aura can verify from an
embedded public trust root, then enable that source by default only in builds containing the matching
`official-repository` role.

The result must fail closed: a plain registry, unknown key, invalid signature, changed payload, missing
manifest pin, or invalid embedded root cannot receive official trust or replace a previously loaded source.

## Existing Contract

Aura already defines the compatibility-sensitive signature domain
`HMCLCE-OFFICIAL-REGISTRY-V1`. That identifier remains unchanged. The signature input is:

```text
HMCLCE-OFFICIAL-REGISTRY-V1\n<HMCLCE-CANONICAL-JSON-V1(signed)>
```

Canonical JSON recursively sorts object keys by UTF-16 code-unit order, preserves array order, uses minimal
JSON string escapes, rejects unpaired surrogates, and accepts only integral JSON numbers in the inclusive
range `[-9007199254740991, 9007199254740991]`.

`HMCLCE-OFFICIAL-REGISTRY-V1` is an internal compatibility identifier only. No user-visible launcher text,
Store name, source label, status message, documentation title, artifact title, or release title may contain
`CE` or `HMCL CE`; the product name is exactly `Aura Launcher`. Existing Java namespaces and protocol IDs are
retained only where changing them would break compatibility.

The published `plugins.json` has this shape:

```json
{
  "signed": {
    "schemaVersion": 1,
    "name": "Aura Launcher Plugin Store",
    "description": "Official Aura Launcher plugin registry.",
    "homepageUrl": "https://github.com/Egg-China/Aura-Launcher-Plugin-Store",
    "plugins": []
  },
  "signatures": [
    {
      "keyId": "ed25519:<sha256-of-x509-spki>",
      "signature": "<base64-ed25519-signature>"
    }
  ]
}
```

The envelope contains exactly one production signature for the first release. Each official entry retains a
lowercase `manifestSha256` over the exact public `manifest.json` bytes. Aura verifies that pin before parsing
the manifest, then verifies package URL, SHA-256, size, and embedded NPL metadata through the existing Store
path.

## Store Repository Layout

`registry.json` becomes the reviewed, unsigned schema-v1 registry payload. `plugins.json` becomes generated
publication output and is never edited as source data.

The Store owns a strict signer/verifier tool that:

1. parses JSON without accepting duplicate object keys;
2. validates the canonical JSON numeric and string subset;
3. produces the exact domain-separated bytes defined above;
4. derives `ed25519:<sha256>` from the X.509 SubjectPublicKeyInfo bytes;
5. reads an Ed25519 PKCS#8 private key supplied only at signing time;
6. writes an envelope and immediately verifies it with the configured public key;
7. never prints private-key bytes or places them in a command-line argument.

Golden vectors shared with Aura's `CanonicalJsonTest` prove byte compatibility. Mutation tests change the
payload, signature, key ID, number representation, object keys, and string data one at a time and require
verification failure.

The existing Store validator continues to download every pinned manifest and release asset. It additionally
accepts the public key or purpose-scoped root and verifies the production envelope before inspecting the
signed payload. Validation rejects a plain top-level registry when official mode is selected.

## Signing Workflow

Pull requests never receive the production key. PR CI performs the following operations:

1. validates `registry.json`, every manifest pin, and every public release asset;
2. generates an ephemeral Ed25519 key in the runner temporary directory;
3. signs the candidate payload;
4. verifies the result and all negative mutation fixtures;
5. confirms that the checked-in `plugins.json` is valid under the public production key, without requiring it
   to contain unmerged candidate changes.

A separate `main` publication job uses a concurrency group and `contents: write`. It reads
`AURA_OFFICIAL_REGISTRY_SIGNING_KEY_PKCS8_BASE64` from the Store Actions Secret, writes the decoded key only
under the runner temporary directory with owner-only access, signs `registry.json`, verifies the envelope,
runs the complete Store validator, and commits only `plugins.json` when its bytes changed. The generated
commit triggers validation again but not another signing commit because the source payload is unchanged.

All third-party GitHub Actions remain pinned to immutable commit SHAs. Logs may contain the public key ID and
registry digest, but never private key material or credential-bearing URLs.

## Purpose-Scoped Trust Root

The first production root is intentionally limited to official registry publication. Its `signed` payload
contains:

- `_type: "root"`;
- `schemaVersion: 1`;
- a monotonically increasing positive `version`;
- a future `expires` instant;
- `statusUrl: ""` because certification status is not part of this deliverable;
- no revoked key IDs or certificate serials;
- one Ed25519 public key declaration;
- one `official-repository` role with threshold one.

The root is public metadata stored in the Aura repository variable `AURA_PLUGIN_ROOT_JSON`. Its signatures
array remains accepted but is not a runtime root-rotation mechanism; control of the Aura build configuration
and reviewed source defines the trust-anchor boundary.

Aura build validation changes from requiring every future online role to supporting two explicit profiles:

- development: no configured root, no online roles, blank status URL;
- official-store: a valid `official-repository` role and blank status URL.

If any certification role (`repository-attestor`, `artifact-attestor`, or `trust-status`) is introduced later,
all three roles and a credential-free HTTPS `statusUrl` become mandatory as one separate rollout. Every online
role continues to require threshold one, valid referenced keys, and no key reuse across roles.

Aura Java CI and release workflows pass `${{ vars.AURA_PLUGIN_ROOT_JSON }}` to Gradle as
`AURA_PLUGIN_ROOT_JSON`. Tagged release builds fail if the configured root is malformed, expired, missing the
official role, or contains an invalid key ID/public-key binding.

## Default Source Behavior

`DEFAULT_REGISTRY_URL` remains the public raw URL ending in `/main/plugins.json`.

The default for `aura.plugin_store.enabled` is derived from the embedded trust root:

- `true` when the root parses successfully and authorizes a usable `official-repository` role;
- `false` for the checked-in empty development root.

The system property remains an explicit diagnostic override. Forcing it to `true` without a usable role does
not downgrade trust: loading still fails because `verifyOfficialRegistry` cannot meet the official threshold.

An invalid network response or signature leaves the source unavailable and preserves any previously published
in-memory source generation. It never falls back to treating the official URL as a community registry.

## Key Creation And Rotation

The initial key pair is generated locally in a temporary directory after signer tests pass. The private
PKCS#8 Base64 value is streamed directly into the Store repository secret with GitHub CLI and the temporary
private file is deleted. The public SPKI Base64 value and derived key ID are placed in
`AURA_PLUGIN_ROOT_JSON`; no private material enters Git, an Aura variable, an artifact, or command output.

Rotation follows this order:

1. generate a new independent key;
2. publish an Aura root version containing both old and new official key IDs with threshold one;
3. release Aura builds carrying that root;
4. replace the Store signing secret and start signing with the new key;
5. after the supported old Aura population ages out, publish a later root without the old key;
6. revoke and delete the old private key.

Emergency compromise response stops Store signing, removes or disables the affected workflow secret, restores
the last known-good signed registry, and ships an Aura root update excluding the compromised key. Merely
deleting a GitHub secret does not revoke already trusted signatures.

## Testing And Release Gates

Implementation follows test-first cycles. Required gates are:

- Store signer golden vectors match Aura canonicalization byte-for-byte.
- Store signer and validator reject duplicate keys, unsafe numbers, bad Base64, wrong algorithms, wrong key
  IDs, plain official registries, payload mutation, and signature mutation.
- Store validation fetches all current manifest URLs, checks exact manifest SHA-256 values, verifies all 30
  existing Runtime Host NPLs, and validates their internal schema-v5 metadata.
- Aura unit tests accept a correctly signed official registry and reject all downgrade and mutation cases.
- Aura tests prove the development root disables the default source and an official-store root enables it.
- `checkstyle checkTranslations test shadowJar` succeeds with the production root injected.
- The built Shadow JAR remains `Aura-Launcher-27.1-next.jar`, and its `Implementation-Version` is exactly
  `27.1-next`.
- The public `plugins.json` is downloaded after publication, verified with the root public key, and compared
  structurally to `registry.json`.
- Aura performs a real public official-source load and current-platform installation plan without a community
  downgrade.
- Branding tests and a case-sensitive source scan prove that user-visible launcher and Store text uses
  `Aura Launcher` and does not expose `CE` or `HMCL CE`; compatibility-only namespaces and protocol IDs are
  explicitly allowlisted.

Publication is ordered so no released Aura build defaults to the official source before the signed public
registry is available. Remote byte or CI failure stops the rollout; it does not replace the public envelope,
enable the production variable in a release, or use force push.

## Repository Changes

Aura Launcher changes are limited to trust-root profile validation, workflow variable injection, default-source
enablement, focused tests, and documentation. All modified Java declarations follow the repository's
`@NotNullByDefault`, explicit `@Nullable`, immutability annotation, and `///` documentation requirements.

Aura Plugin Store changes are limited to the source/output split, signer/verifier tooling, CI publication,
key metadata documentation, and signed envelope. Manifest schema v2 and NPL schema v5 remain unchanged.
`schema-v4` is not modified.

## Deferred Work

This design does not enable certified community-plugin attestations, online trust-status snapshots, stable
Runtime Host channels, Store OS/architecture/Runtime facets, SDK example expansion, Python Runtime support,
Patch execution, or Protector matrix expansion. Each requires its own specification and release gate.
