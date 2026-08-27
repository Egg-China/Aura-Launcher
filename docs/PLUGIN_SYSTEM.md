# Aura Launcher 插件系统

Aura Launcher 只安装和执行 `plugin.json` schema v5 插件。schema v1-v4 包仍可被读取，
以便用户禁用、升级或移除旧包，但其代码不会执行。正式兼容保证见
[插件契约](./PLUGIN_CONTRACT.md)。

## 创建 JVM 插件

内置运行时支持 Java 与 Kotlin。插件包是扩展名为 `.npl` 的 ZIP 文件：

```text
example-plugin.npl
├── plugin.json
└── libs/
    └── example-plugin.jar
```

最小的 Java 插件清单如下：

```json
{
  "schemaVersion": 5,
  "id": "com.example.aura.plugin",
  "name": "Aura Example Plugin",
  "version": "1.0.0",
  "type": "java",
  "entrypoint": "com.example.aura.PluginMain",
  "runtime": "java",
  "abi": 2,
  "platforms": [],
  "dependencies": [],
  "permissions": [],
  "requiredPermissions": [],
  "launcherVersion": ">=26.8-next"
}
```

`type` 目前只接受 `java` 或 `kotlin`。使用内置运行时时，`entrypoint` 必须是实现
`Plugin` 接口的完整类名。

## 使用外部语言运行时

Aura 本体不捆绑 .NET、Python、JavaScript、Rust 或 WebAssembly 运行时。其他语言由
可选的 Runtime Provider 插件提供；安装相应 Provider 后，schema v5 插件可通过
`runtime`、`abi`、`executionMode`、`runtimeProvider` 和 `platforms` 声明所需边界。

Runtime Provider 自身是使用内置 Java 运行时加载的插件，必须声明
`pluginKind: "runtime-provider"` 和 `providesRuntimes`。原生 Provider 还必须声明
`native-code` 权限。缺少匹配 Provider、ABI、执行模式、Bridge ABI、功能或平台工件时，
插件会在安装或启动兼容性检查中被拒绝。

Aura 仓库当前不包含可分发的官方外部 Runtime Host；这些 Host 必须作为独立、可选插件
安装，不能成为启动器本体的强制依赖。

## 理解生命周期

JVM 插件的基本顺序为：

```text
安装或更新 → 待重启事务
启动后     → onLoad(context) → onEnable()
禁用       → onDisable()
卸载       → onDisable() → onUnload() → 关闭类加载器
```

包含 `mixins`、Hook 或 Patch 的插件受额外权限和重启规则约束。启动器会在发现、安装、
依赖解析、权限确认、Mixin 引导和实际执行前重复验证 schema v5 边界。

## 声明权限

`permissions` 是插件可能使用的能力上限，`requiredPermissions` 是执行前必须获得授权的
子集。有效授权与插件 ID、版本及 `.npl` SHA-256 绑定；包内容变化会使已有授权失效。

| 权限 | 能力 |
| --- | --- |
| `launcher-ui` | 注册启动器页面或侧栏操作 |
| `game-launch` | 观察或修改游戏启动流程 |
| `filesystem` / `network` / `process` | 访问系统资源 |
| `account` / `clipboard` | 访问账户或剪贴板数据 |
| `mixin` | 转换启动器 JVM 类 |
| `native-code` | 加载原生代码或提供原生运行时 |

插件调用受保护 API 前应检查授权，并处理 `PluginPermissionException`。

## 配置插件来源

Aura 私有预发布仓库不启用内置官方 Store URL。用户可以添加自定义 registry；保留的
`github-topic-hmclce` 源 ID 与 `hmclce` Topic 仅用于兼容既有插件生态和本地设置。

下载前，启动器验证仓库清单、插件 ID、版本、下载地址、SHA-256、依赖、权限、运行时和
平台要求。包含 Mixin 或高权限能力的包还会显示额外确认。
