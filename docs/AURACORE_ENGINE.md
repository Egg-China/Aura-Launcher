# AuraCore Native Engine

The launcher can optionally drive instance launches through the native AuraCore
backend instead of the built-in HMCL-derived Java core.

## Partial replacement, not retirement

AuraCore is an opt-in replacement for the launch core only. The HMCL-derived Java
core stays the default, stays embedded, and remains the recovery path:

- **Stays launcher-owned** — settings store, plugin system and trust state, UI
  supervision, and the built-in JavaFX rescue interface never move to AuraCore.
- **AuraCore owns while selected** — instances, accounts, launch tasks, and game
  logs under its own `<local home>/auracore` data directory.
- **Switching is reversible** — selecting `hmcl` again restores the Java core
  path without deleting anything; migration into AuraCore copies an explicit
  settings allowlist and never mutates HMCL-side data.
- **Accounts and instances are intentionally isolated** — neither tokens nor
  instance directories are imported across engines.

## Selecting the engine

1. Place the backend shared library in the launcher data directory or set
   `AURACORE_BACKEND_PATH` to its absolute path.
2. Open Settings → Launcher Core and choose **AuraCore native core**.
3. The next `core.instance.launch` command from a native UI runs through the
   backend and returns its task id.

The default stays `hmcl`; the AuraCore engine only activates after an explicit
selection, and the HMCL core remains available without deleting anything.

## Data isolation and migration

The backend uses its own `<local home>/auracore` data directory. Switching
engines never imports plugins, plugin security state, or account tokens. The
`core.auracore.migrate` bridge command copies only the allowlisted settings
below:

| Launcher key | AuraCore key |
| --- | --- |
| `download.source.meta` | `MetaURLOverride` |
| `download.concurrent-tasks` | `NumberOfConcurrentTasks` |
| `download.concurrent-downloads` | `NumberOfConcurrentDownloads` |
| `download.retries` | `NumberOfManualRetries` |
| `network.timeout` | `RequestTimeout` |
| `proxy.type` | `ProxyType` |
| `proxy.host` | `ProxyAddr` |
| `proxy.port` | `ProxyPort` |
| `proxy.user` | `ProxyUser` |
| `proxy.password` | `ProxyPass` |

System-proxy mode has no AuraCore equivalent and is skipped.

## Moving instances between engines (evaluation)

Both sides already speak the MultiMC modpack format, so an explicit,
user-driven migration channel is feasible without any format conversion:

1. In the built-in JavaFX interface, open an instance and export it as a
   **MultiMC modpack** (`MultiMCModpackExportTask`) — this produces the zip
   layout `instance.cfg` + `mmc-pack.json` + `overrides/` that AuraCore's
   `InstanceImportTask` detects natively.
2. Switch the launcher core to AuraCore and import the archive through
   `core.auracore.instance.import` (`source` path or URL, display `name`).
3. Mods, configs, and saves travel inside `overrides/`; the loader components
   travel inside `mmc-pack.json`, so Fabric/Forge/NeoForge/Quilt instances
   re-resolve on the AuraCore side.

Channel status:

- **Export** — headless through `core.instance.export.multimc` (wraps
  `MultiMCModpackExportTask`; an absent whitelist exports everything outside the
  standard blacklist, while a present whitelist must be a non-empty array of
  exact forward-slash paths and invalid selections are rejected instead of
  silently widening the export; all instance overrides default to off and
  AuraCore applies its own defaults). Curated native selection is powered by
  `core.instance.export.files.list`. The JavaFX export wizard also remains
  available. The Modern UI exposes a per-instance export dialog under the HMCL
  engine.
- **Import** — the Modern UI exposes an import dialog (archive path or URL,
  derived name, optional group) that drives `core.auracore.instance.import`
  with task polling under the AuraCore engine.
- **Qt runtime** — consumes backend state and exposes import/export dialogs
  that drive the same bridge commands as the Modern UI.
- **No automatic bulk migration** — intentional. Engine switching stays an
  explicit, reversible, per-instance user action; nothing moves accounts or
  instances without consent.

## Bridge commands

- `core.auracore.status` — engine selection, library path, backend state, and
  migration allowlist.
- `core.auracore.migrate` — copy the allowlisted settings into the backend and
  report per-key outcomes.
- `core.instance.launch` — launches through the backend when the AuraCore
  engine is selected, returning the backend task id.
- `core.instance.export.multimc` — exports one launcher-side instance as a
  MultiMC modpack archive (`id`, `output`, optional `name`, optional `whitelist`
  array of exact forward-slash paths; an absent whitelist keeps every file
  outside the standard blacklist, while a present array must be non-empty and
  valid).
- `core.instance.export.files.list` — lists one wizard-compatible selection
  tree level (`id`, optional `path`, optional opaque `token`) for native
  curated-export UIs, returning `{path, token, entries, truncated}` with hidden
  entries filtered out, symlink- and junction-safe containment, and bounded
  scanning; levels beyond 4096 entries are explicitly truncated.
- `core.auracore.instance.create` — create a vanilla instance (`name`,
  `version`, optional `group`).
- `core.auracore.instance.list` — list the backend's own instances; native
  UIs use this instead of the HMCL snapshot while AuraCore is selected.
- `core.auracore.instance.rename` / `group` / `icon` / `delete` — edit and
  remove instances.
- `core.auracore.instance.export` / `import` — MultiMC-format archive round
  trips.
- `core.auracore.instance.logs` — tail the live game output (`id`, optional
  `maxLines`).
- `core.auracore.instance.stop` — terminate a running game process.
- `core.auracore.task.status` — poll one backend task (`taskId`).
- `core.auracore.accounts.list` / `add-offline` / `remove` / `set-default` —
  account management.
- `core.auracore.auth.msa.begin` / `msa.info` — Microsoft device-code login.
### Native UI transport gating

The supervised native UI transport intentionally exposes only the AuraCore display and account
commands needed by the Phase 4B account suggestion flow:

- `core.auracore.status`
- `core.auracore.instance.list`
- `core.auracore.task.status`
- `core.auracore.accounts.list`
- `core.auracore.auth.msa.begin`
- `core.auracore.auth.msa.info`

Account reads and Microsoft authentication require the plugin `account` grant. AuraCore snapshots
with that grant expose only Microsoft account `type` and `profileName` suggestions (wire field `username`), even though HMCL
UI snapshots may carry the fuller token-free display record. Without the `filesystem` grant, status
and instance listings omit local paths, task diagnostics are reduced to scalar safe fields, nested
safe-field values and backend errors are replaced with generic messages, and launcher snapshots omit
unauthorized `commonDirectory`.
Proxy credentials are always omitted because the Modern UI does not consume them.

Other bridge methods implemented by `NativeUiBridge` remain deliberately unreachable through this
transport until their filesystem, network, launcher-core, and destructive-action permission cohort
is defined. The Modern UI mirrors this method set before invoking Tauri so an unavailable action
returns a normal UI error instead of terminating its supervised protocol session. In particular,
instance mutation/import/export, settings writes, migration, and account mutations must not be
enabled merely by adding method names to the transport allowlist.
