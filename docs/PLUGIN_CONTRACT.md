# Aura Launcher 插件契约

本文定义插件作者可以依赖的行为以及 Aura Launcher 对插件包的硬性要求。使用指南见
[插件系统](./PLUGIN_SYSTEM.md)。文档与实现冲突时以实现为准，并应修正文档。

## 契约版本

- 当前清单版本为 `5`，最低可执行版本也为 `5`。
- schema v1-v4 只用于管理、禁用、升级或移除旧包，不会安装或执行代码。
- schema v5 必须显式声明 `runtime` 与 `abi`。
- `launcherVersion` 必须是有效的版本约束，且当前 Aura 版本必须满足该约束。

## 打包插件

`.npl` 是 ZIP 容器，根目录必须包含唯一的 `plugin.json`。内置 JVM 插件把 JAR 放入
`libs/`，并使用完整类名作为 `entrypoint`。

| 字段 | 要求 |
| --- | --- |
| `schemaVersion` | 必须为 `5` |
| `id`, `name`, `version` | 必须存在且通过格式校验 |
| `type` | `java` 或 `kotlin` |
| `entrypoint` | 非空；内置 JVM 插件为 `Plugin` 实现类 |
| `runtime` | 规范化运行时 ID；内置运行时为 `java` |
| `abi` | 受支持的插件 ABI 代际 |
| `platforms` | 可选规范化平台目标数组；空数组表示平台无关 |
| `dependencies` | 结构化插件依赖与版本约束 |
| `permissions` | 插件可能使用的权限上限 |
| `requiredPermissions` | 执行前必须授权的权限子集 |
| `launcherVersion` | Aura 版本约束 |
| `hooks`, `patches`, `mixins` | 可选高能力声明，受权限与重启规则约束 |

清单在进入安装事务前完整校验。运行时返回不可变清单快照；磁盘包、权限、版本或哈希不
匹配时，插件不能复用先前的执行资格。

## 执行 JVM 生命周期

内置 Java/Kotlin 插件使用以下顺序：

```text
onLoad(context) → onEnable()
onDisable()
onDisable() → onUnload() → 关闭类加载器
```

生命周期回调在 JavaFX 应用线程执行，线程上下文类加载器指向插件类加载器。插件不得
长期阻塞该线程，并必须在 `onUnload` 中释放线程、监听器和资源。回调异常会禁用对应
插件，但不应导致整个启动器退出。

## 使用 Runtime Provider

外部语言必须通过可选 Runtime Provider 插件接入，不能依赖 Aura 本体捆绑语言运行时。
Provider 声明其运行时 ID、插件 ABI、Bridge ABI、执行模式、平台和功能集合。普通插件
可以用 `runtimeProvider` 固定 Provider；未固定时由注册表按完整能力要求确定性选择。

缺少 Provider 或能力不匹配时，安装和启动会返回明确兼容性结果，不会把外部载荷交给
内置 JVM 类加载器。Runtime Provider 插件必须使用 `runtime: "java"`；提供原生边界时
必须声明 `native-code` 权限。

## 使用权限与存储

有效权限为用户授权与清单声明的交集。调用受保护 API 时，未声明或被拒绝的权限会抛出
`PluginPermissionException`。授权与插件 ID、版本和包 SHA-256 绑定。

- 包目录只读且内容寻址，插件不得在其中持久化状态。
- 数据目录按插件 ID 分配，在版本更新后保持稳定。
- Aura live 数据位于 `.aura` 和 Aura user home；插件不得假定旧 `.hmcl` 路径。
- 安装、卸载、权限、启用状态和依赖变更使用事务文件，失败时不得留下半发布状态。

## 修改启动器与游戏

`launcher-ui` 权限允许注册页面和侧栏命令。`game-launch` 允许参与受支持的游戏启动 Hook。
Mixin、Patch 和原生代码属于高能力边界，必须声明对应权限，并可能要求重启。

插件只能调用实现公开的 Hook/Patch 端点。声明尚未实现的点位、错误方法描述符、越权目标
或不受支持的平台都会在执行前被拒绝。

## 兼容性承诺

移除必填字段、改变生命周期顺序、重命名权限或改变已有 Hook/Patch 语义属于契约破坏，
必须提升 schema 或 ABI。新增可选字段、权限、上下文方法以及不改变合法输入含义的严格
校验可以在 schema v5 内演进。
