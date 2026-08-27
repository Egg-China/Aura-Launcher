# Migration Provenance

Aura Launcher began as a source-only migration from the `next` branch of HMCL CE.

| Item | Value |
| --- | --- |
| Source repository | `https://github.com/HMCL-Community/HMCL-CE` |
| Source branch | `next` |
| Source commit | `5d8b16fda5012d5cc99067582d7d4f34d3f30d7d` |
| Source tree | `6b6fbd79abe0293e8a2d8c15352fd34e74c4cfd2` |
| Aura snapshot commit | `397022b343b3784b06a529977c84d494282e6601` |
| Migration date | 2026-08-27 |

The target repository was initialized from the exact tracked tree at the source commit. The source
repository's Git history, worktrees, ignored build outputs, and unfinished Runtime Host work were not
imported. The root Aura snapshot and the source commit therefore have identical Git tree IDs.

HMCL CE is based on [Hello Minecraft! Launcher](https://github.com/HMCL-dev/HMCL). Aura retains the
upstream license headers, copyright statements, GPL license files, third-party notices, Java package
namespace `org.jackhuang.hmcl`, and compatibility identifiers whose renaming would break existing
consumers. Later Aura-specific commits change product-facing identity and behavior without claiming
authorship of the retained upstream work.

The frozen Runtime Host prototype from the source development environment was intentionally excluded.
Any future Java/Rust architecture or external-language runtime support must be developed and reviewed
as Aura-native work after this migration.
