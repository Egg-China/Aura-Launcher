# AuraCore Native Engine

The launcher can optionally drive instance launches through the native AuraCore
backend instead of the built-in HMCL-derived Java core.

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

## Bridge commands

- `core.auracore.status` — engine selection, library path, backend state, and
  migration allowlist.
- `core.auracore.migrate` — copy the allowlisted settings into the backend and
  report per-key outcomes.
- `core.instance.launch` — launches through the backend when the AuraCore
  engine is selected, returning the backend task id.
- `core.auracore.instance.create` — create a vanilla instance (`name`,
  `version`, optional `group`).
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