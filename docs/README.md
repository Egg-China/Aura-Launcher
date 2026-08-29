<!-- #BEGIN BLOCK -->
<!-- #PROPERTY NAME=TITLE -->
<div align="center">
    <img src="../AuraLauncher/src/main/resources/assets/img/icon@8x.png" alt="Aura Launcher Logo" width="64"/>
</div>

<h1 align="center">Aura Launcher</h1>
<!-- #END BLOCK -->

<!-- #BEGIN BLOCK -->
<!-- #PROPERTY NAME=BADGES -->
<div align="center">

[![GitHub](https://img.shields.io/badge/GitHub-repo-blue?style=flat-square&logo=github)](https://github.com/Egg-China/Aura-Launcher)
[![QQ Group](https://img.shields.io/badge/QQ-gray?style=flat-square&logo=qq&logoColor=ffffff)](https://qun.qq.com/universal-share/share?ac=1&authKey=pOw%2BTFtCWoazxuhJo6aSk%2BnmPW3lVVH0t5LCnE3ya2EFzj%2BEy9kHLci1ahepvW6t&busi_data=eyJncm91cENvZGUiOiIxMDk3MTIxNzUxIiwidG9rZW4iOiJXVzhRZkZEYit3N1BRT1o2dWNjQkw4WElFYjR0ZFQ3R01vYVo3bmsvR2htZThZNXhXOTgyQXpZYU5Ua2NNU3VsIiwidWluIjoiMzYxNjQzOTUwNSJ9&data=2bSHbitmgkNabOpdNYYdvazyW7GDY_7Mj7eeonhQ7whmvotadJdwtlQC5Sg60CxIo-uu9ZukUgzQUcQYGRMy6w&svctype=4&tempid=h5_group_info)
[![Bilibili](https://img.shields.io/badge/Bilibili-gray?style=flat-square&logo=bilibili)](https://b23.tv/CTHjMv6)

</div>
<!-- #END BLOCK -->

---

<!-- #BEGIN LANGUAGE_SWITCHER -->
**English** (**Standard**, [uʍoᗡ ǝpᴉsd∩](README_en_Qabs.md)) | 中文 ([简体](README_zh.md), [繁體](README_zh_Hant.md), [文言](README_lzh.md)) | [日本語](README_ja.md) | [español](README_es.md) | [русский](README_ru.md) | [українська](README_uk.md)
<!-- #END LANGUAGE_SWITCHER -->

## Introduction

Aura Launcher is Egg-China's future Minecraft launcher product line, developed from HMCL CE and the
HMCL upstream project. It retains cross-platform instance management while its plugin and launcher
architecture evolves independently.

Aura Launcher has broad cross-platform capabilities. It runs on Windows, Linux, macOS, and FreeBSD, and supports CPU architectures including x86, ARM, RISC-V, MIPS, and LoongArch.

For systems and CPU architectures supported by Aura Launcher, please refer to [this table](PLATFORM.md).

## Pre-release Status

Aura Launcher is currently a public pre-release line and has no stable release. Builds from this
repository always use a version ending in `-next`. Automatic updates remain disabled. Builds that
embed the reviewed official Store trust root enable built-in Store discovery; development-root
builds keep it disabled.

See the [plugin system documentation](../AuraPluginSystem/docs/PLUGIN_SYSTEM.md) for manifest format, permissions, and source management, and the [plugin contract](../AuraPluginSystem/docs/PLUGIN_CONTRACT.md) for the formal launcher-plugin behavioral guarantees.

## Contributing

Aura Launcher is based on HMCL CE and HMCL, with an independent future release and plugin track.

You can contribute to Aura Launcher development in the following ways:

- Report bugs or request features by [creating an issue](https://github.com/Egg-China/Aura-Launcher/issues/new/choose) on GitHub.
- Contribute code by forking the repository on GitHub and [submitting a pull request](https://github.com/Egg-China/Aura-Launcher/compare).

Before contributing, please read the [Contributing Guide](./Contributing.md), which includes the following:

- [How to build and run Aura Launcher from source](./Contributing.md#build-hmcl)
- [Adjusting Aura Launcher behavior using debug options](./Contributing.md#debug-options)

## Contributors

[Aura Launcher contributors](https://github.com/Egg-China/Aura-Launcher/graphs/contributors)

## Acknowledgements

- [HMCL upstream project](https://github.com/HMCL-dev/HMCL): for the foundation, design, and long-term upstream work used by Aura Launcher.
- [HMCL CE](https://github.com/HMCL-Community/HMCL-CE): for the exact source line imported into Aura Launcher.

## License

The software is distributed under [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) license with the following additional terms:

### Additional terms under GPLv3 Section 7

1. When you distribute a modified version of the software, you must change the software name or the version number in a reasonable way in order to distinguish it from the original version. (Under [GPLv3, 7(c)](https://github.com/Egg-China/Aura-Launcher/blob/main/LICENSE#L372-L374))

   The software name and version are defined in [`Metadata.java`](../AuraLauncher/src/main/java/org/jackhuang/hmcl/Metadata.java) and the build configuration.

2. You must not remove the copyright declaration displayed in the software. (Under [GPLv3, 7(b)](https://github.com/Egg-China/Aura-Launcher/blob/main/LICENSE#L368-L370))
