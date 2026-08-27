# Aura Launcher

Aura Launcher is Egg-China's future Minecraft launcher product line. It is currently a private,
pre-release project built from the HMCL CE Next codebase while its launcher architecture is being
prepared for later Java and Rust development.

This repository is not a stable release channel. Every build identifies itself with a version
ending in `-next`, and packaged launcher files use the name `Aura-Launcher-<version>.jar`.
Automatic updates and built-in Aura Store discovery remain disabled until private release services
are available. User-configured plugin sources continue to work.

## Current Compatibility

- Java 17 or later is required to build and run the launcher.
- Aura plugin packages (`.npl`) must use plugin manifest schema v5.
- Store index document schema versions are independent from plugin manifest schema versions.
- Aura keeps its live settings and data separate from HMCL CE.
- A first-run or manual migration can copy an allowlisted subset of HMCL CE settings without
  importing plugins, trust decisions, runtime-host bindings, caches, logs, or quarantine state.

## Build

On Windows:

```powershell
.\gradlew.bat :AuraLauncher:build --no-daemon
```

On Linux or macOS:

```bash
./gradlew :AuraLauncher:build --no-daemon
```

Artifacts are written to `AuraLauncher/build/libs/`. Run the development launcher with:

```powershell
.\gradlew.bat :AuraLauncher:run --no-daemon
```

## Source And License

Aura Launcher was imported as a source-only snapshot from
[`HMCL-Community/HMCL-CE`](https://github.com/HMCL-Community/HMCL-CE), which itself is based on
[`HMCL-dev/HMCL`](https://github.com/HMCL-dev/HMCL). The imported source commit is
`5d8b16fda5012d5cc99067582d7d4f34d3f30d7d`.

The original Git history was intentionally not copied. Upstream copyright notices, third-party
notices, compatibility package names, and GPL terms are retained. See
[`docs/MIGRATION_PROVENANCE.md`](docs/MIGRATION_PROVENANCE.md), [`LICENSE`](LICENSE), and
[`LICENSE.additional`](LICENSE.additional) for details.

Aura Launcher is free software licensed under GNU General Public License version 3 with the retained
additional terms described in the repository license files.
