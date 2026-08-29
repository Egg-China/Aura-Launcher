# Aura Plugin System Apache License Boundary Design

**Status:** Approved
**Date:** 2026-08-29

## Objective

Separate the Aura-authored plugin system into a top-level source directory licensed under Apache
License 2.0 while retaining the upstream-derived launcher under its existing GPL terms. Preserve
all Java package names, runtime behavior, binary contents, protocol identifiers, and the
`Aura Launcher` product identity.

The repository will follow the directory-override model used by `PCL-community/PCL-CE`: the root
license is the default, a specifically named top-level directory contains a different license, and
the README states the boundary explicitly.

## Established Facts

- The imported Aura source snapshot contains 140 plugin production files and 73 plugin test files
  under its original `HMCL/` module path.
- The Aura rebrand commit moved those files to `AuraLauncher/` with 98-100% Git similarity; it did
  not create the original subsystem.
- The local source repository begins with root commit `bce314563`, authored by
  `ACX <anxunbcx@gmail.com>`, and its subsequent plugin-system history is also authored by ACX.
- The user has explicitly confirmed ownership of the original plugin-system work and has directed
  that it be relicensed under Apache License 2.0. Commit authorship alone is not treated as proof of
  copyright ownership.
- The current tree contains 151 plugin production Java files, all of which currently carry the
  generic launcher GPL header even though the subsystem is user-confirmed Aura-authored work.
- The plugin package currently has 57 imports from launcher-owned packages, while 15 Java files
  outside the package import plugin types. A standalone Gradle module would therefore create a
  dependency cycle without a larger port-and-adapter refactor.

These facts and the user's ownership confirmation support a source-directory licensing boundary
for the plugin system. They do not support relicensing inherited launcher files merely because the
Aura root commit or later integration commits were authored by ACX.

## License Layout

The root `LICENSE` remains the unmodified GNU GPL license and continues to be the repository default.
It applies to all files unless a more specific directory license or per-file notice says otherwise.

The new top-level `AuraPluginSystem/` directory contains:

- `LICENSE`: the unmodified Apache License 2.0 text.
- `NOTICE`: the Aura Plugin System name and
  `Copyright 2026 Aura Launcher contributors`.
- `README.md`: a concise explanation of scope, integration, and combined-distribution terms.

Every Aura-authored Java file moved into this directory receives an Apache-2.0 header naming
`Aura Launcher contributors`. GPL headers are not retained on those files because that would
contradict the directory's intended single-file license. Any third-party attribution discovered
during migration is preserved and that file is excluded from relicensing until ownership is clear.

The root README mirrors the reference repository's explicit license list:

- `AuraPluginSystem/` uses Apache License 2.0.
- All other directories use GNU GPLv3 under their existing file notices.

Apache-2.0 is compatible with GPLv3, but combining Apache-licensed plugin code with the GPL launcher
does not relicense the launcher. Distributed Aura Launcher binaries remain subject to GPLv3 because
they contain and link the GPL-covered application.

Future standalone Aura-authored subsystems may receive their own specific directory license.
Edits made inside an existing GPL-derived file remain GPL unless the edited material is extracted
into an independently authored file with a clear interface and provenance.

## Source Boundary

The following production code moves without package or class-name changes:

- `AuraLauncher/src/main/java/org/jackhuang/hmcl/plugin/**`
- Standalone plugin UI files whose complete file history is Aura-authored, including the plugin
  management, permission, recovery, source-management, and Store pages and dialogs
- Plugin-specific Java service registration resources

The corresponding plugin tests and fixtures move to `AuraPluginSystem/src/test/**`. Plugin-specific
documentation may move to `AuraPluginSystem/docs/` when it is entirely Aura-authored; mixed or
upstream-derived documentation remains under the root GPL boundary.

Launcher integration points remain in `AuraLauncher/` and remain GPL. This includes modifications
to inherited application entry points, launch orchestration, game-process listeners, navigation
roots, updating, shared translations, and other files belonging to the inherited launcher.

Before moving each file outside `org/jackhuang/hmcl/plugin/**`, migration checks must compare its
source-repository history and content with the inherited launcher and confirm that it belongs to the
user-identified plugin system without third-party or copied upstream material. Ambiguous files
remain GPL.

## Build Integration

`AuraPluginSystem` begins as a separately licensed source tree, not a standalone Gradle subproject.
`AuraLauncher/build.gradle.kts` adds its main, test, and resource directories to the existing
`AuraLauncher` source sets. This keeps all classes in the same compilation unit and avoids changing
package-private access or introducing a circular project dependency.

The existing launcher artifact layout remains unchanged:

- Project version keeps exactly one `-next` suffix.
- The Shadow JAR remains `Aura-Launcher-<version>.jar`.
- Plugin classes and resources remain in the same JAR paths.
- Java and plugin ABI compatibility identifiers remain unchanged.

A later design may extract a true `AuraPluginSystem` Gradle module after launcher services are
replaced by narrow ports and the current bidirectional dependency is removed. That refactor is not
part of this licensing change.

## License Enforcement

The existing Checkstyle configuration enforces the GPL header for launcher sources. Plugin sources
will use a dedicated Apache header pattern and dedicated Checkstyle main/test tasks while retaining
all other Java style checks.

The normal aggregate `checkstyle` task must run both license profiles. CI additionally verifies:

- Every Java file under `AuraPluginSystem/` has the Apache-2.0 header.
- No file under `AuraPluginSystem/` contains the upstream GPL launcher header.
- No Java file outside the explicit Apache directory silently adopts the Apache header.
- `AuraPluginSystem/LICENSE` and `NOTICE` match the documented licensing boundary.

This makes the directory rule mechanically enforceable rather than relying only on README prose.

## Documentation

The bilingual root README will keep the new Aura logo and product presentation, replace the
single-license claim with the two-directory license table, and explain that complete distributions
remain GPLv3. The plugin-system README will describe Apache reuse independently from the combined
launcher.

No user-visible launcher string will introduce a legacy product name. Historical provenance remains
available in internal provenance documentation where required for copyright compliance.

## Verification

The migration is complete only when all of the following pass:

1. `git diff --check` and a rename/provenance audit show no lost or duplicated source files.
2. Apache and GPL header scans match the documented directory boundary.
3. `checkstyle checkTranslations test shadowJar` completes successfully.
4. The focused plugin, Store, Trust, Runtime, Bridge, launch-hook, and Protector tests pass.
5. The Shadow JAR contains the same plugin class and service paths as before the move.
6. The Gradle project version, Shadow JAR filename, and JAR `Implementation-Version` each contain
   the `-next` suffix exactly once.
7. README relative links and GitHub-flavored Markdown rendering pass.
8. A visible-brand scan finds only `Aura Launcher` as the launcher product identity.

## Rollback And Review

The move is performed as ordinary Git renames plus small build-file changes, without history
rewrites or force pushes. Licensing, source relocation, and build integration are kept reviewable
in separate commits. If build integration fails, source files can return to their original paths
without changing package names; the license change is not published until the complete verification
matrix passes.
