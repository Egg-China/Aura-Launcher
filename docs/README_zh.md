<!-- #BEGIN COPY -->
<!-- #PROPERTY NAME=TITLE -->
<div align="center">
    <img src="../AuraLauncher/src/main/resources/assets/img/icon@8x.png" alt="Aura Launcher Logo" width="64"/>
</div>

<h1 align="center">Aura Launcher</h1>
<!-- #END COPY -->

<!-- #BEGIN COPY -->
<!-- #PROPERTY NAME=BADGES -->
<div align="center">

[![GitHub](https://img.shields.io/badge/GitHub-repo-blue?style=flat-square&logo=github)](https://github.com/Egg-China/Aura-Launcher)
[![QQ Group](https://img.shields.io/badge/QQ-gray?style=flat-square&logo=qq&logoColor=ffffff)](https://qun.qq.com/universal-share/share?ac=1&authKey=pOw%2BTFtCWoazxuhJo6aSk%2BnmPW3lVVH0t5LCnE3ya2EFzj%2BEy9kHLci1ahepvW6t&busi_data=eyJncm91cENvZGUiOiIxMDk3MTIxNzUxIiwidG9rZW4iOiJXVzhRZkZEYit3N1BRT1o2dWNjQkw4WElFYjR0ZFQ3R01vYVo3bmsvR2htZThZNXhXOTgyQXpZYU5Ua2NNU3VsIiwidWluIjoiMzYxNjQzOTUwNSJ9&data=2bSHbitmgkNabOpdNYYdvazyW7GDY_7Mj7eeonhQ7whmvotadJdwtlQC5Sg60CxIo-uu9ZukUgzQUcQYGRMy6w&svctype=4&tempid=h5_group_info)
[![Bilibili](https://img.shields.io/badge/Bilibili-gray?style=flat-square&logo=bilibili)](https://b23.tv/CTHjMv6)

</div>
<!-- #END COPY -->

---

<!-- #BEGIN LANGUAGE_SWITCHER -->
English ([Standard](README.md), [uʍoᗡ ǝpᴉsd∩](README_en_Qabs.md)) | **中文** (**简体**, [繁體](README_zh_Hant.md), [文言](README_lzh.md)) | [日本語](README_ja.md) | [español](README_es.md) | [русский](README_ru.md) | [українська](README_uk.md)
<!-- #END LANGUAGE_SWITCHER -->

## 简介

Aura Launcher 基于既有开源代码持续开发，是一款开源、跨平台 Minecraft 启动器。除模组管理、游戏自定义、整合包安装和多平台支持外，Aura Launcher 还提供插件管理、插件商店来源管理，以及带签名校验的更新链。

Aura Launcher 有着强大的跨平台能力。它不仅支持 Windows、Linux、macOS、FreeBSD 等常见的操作系统，同时也支持 x86、ARM、RISC-V、MIPS、LoongArch 等不同的 CPU 架构。你可以使用 Aura Launcher 在不同平台上轻松地游玩 Minecraft。

如果你想要了解 Aura Launcher 对不同平台的支持程度，请参见 [此表格](PLATFORM_zh.md)。

## 下载

Aura Launcher 目前处于公开预发布阶段，尚无稳定版本。所有构建的版本号均以 `-next` 结尾。
自动更新仍保持禁用。嵌入经审核官方插件商店信任根的构建会启用内置商店；使用开发信任根的构建仍保持禁用。

插件系统的清单格式、权限模型和来源管理规则见[插件系统文档](../AuraPluginSystem/docs/PLUGIN_SYSTEM.md)。

## 参与贡献

Aura Launcher 是一个社区驱动的开源项目，欢迎任何人参与贡献代码或提出建议。Aura Launcher 保留上游项目的原始作者历史，并在此基础上维护独立的发布和插件功能。

你可以通过以下方式参与 Aura Launcher 的开发：

- 通过在 GitHub 上[创建 Issue](https://github.com/Egg-China/Aura-Launcher/issues/new/choose) 来报告 Bug 或提出功能请求。
- 通过在 GitHub 上 Fork 仓库并[提交 Pull Request](https://github.com/Egg-China/Aura-Launcher/compare) 来贡献代码。

在参与贡献前，请阅读[贡献指南](./Contributing_zh.md)，其中包含以下内容：

- [如何从源码构建并运行 Aura Launcher](./Contributing_zh.md#构建-hmcl)
- [通过调试选项调整 Aura Launcher 的行为](./Contributing_zh.md#调试选项)

## 贡献者

[Aura Launcher contributors](https://github.com/Egg-China/Aura-Launcher/graphs/contributors)

## 致谢

- [HMCL 上游项目](https://github.com/HMCL-dev/HMCL)：感谢上游项目及其维护者为 Aura Launcher 提供的基础代码、设计和长期工作。
- [HMCL CE](https://github.com/HMCL-Community/HMCL-CE)：Aura Launcher 所迁移源码的直接来源。

## 开源协议

该程序在 [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) 开源协议下发布，同时附有以下附加条款。

### 附加条款 (依据 GPLv3 开源协议第七条)

1. 当你分发该程序的修改版本时，你必须以一种合理的方式修改该程序的名称或版本号，以示其与原始版本不同。(
   依据 [GPLv3, 7(c)](https://github.com/Egg-China/Aura-Launcher/blob/main/LICENSE#L372-L374))

   该程序的名称及版本号可在 [Metadata.java](../AuraLauncher/src/main/java/org/jackhuang/hmcl/Metadata.java) 和构建配置中修改。

2. 你不得移除该程序所显示的版权声明。(依据 [GPLv3, 7(b)](https://github.com/Egg-China/Aura-Launcher/blob/main/LICENSE#L368-L370))
