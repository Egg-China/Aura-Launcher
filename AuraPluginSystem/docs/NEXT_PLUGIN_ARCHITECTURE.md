# Aura Launcher 下一代插件架构

Aura Launcher `main` 是带 `-next` 版本后缀的预发布产品线。该产品线只执行 schema v5
插件，并允许在正式发布前演进插件 ABI、Runtime Provider、Hook 与 Patch 边界。

## 架构边界

- 启动器本体只内置 Java Runtime 与 Plugin Manager。
- .NET、Python、JavaScript、Rust、WebAssembly 和其他语言由独立、可选 Runtime Provider
  插件提供。
- Runtime Provider 可以使用进程内、隔离进程或原生桥接，但必须声明完整能力。
- 卸载最后一个依赖者后，Provider 可通过现有插件依赖管理流程被移除。
- Rust 插件使用编译后的 `dll`、`so` 或 `dylib` 工件，不分发 `.rs` 源码作为运行载荷。

## schema v5 与 ABI

Aura 会解析旧 schema 以便管理历史包，但只执行 schema v5。v5 的核心兼容字段为：

- `runtime`：规范化运行时标识，例如 `java`、`rust` 或 `wasm`。
- `abi`：插件要求的 ABI 代际。
- `platforms`：平台无关、操作系统级或操作系统与架构组合目标。
- `executionMode`、Bridge ABI 和功能集合：约束 Runtime Provider 的实际执行边界。
- `runtimeProvider`：可选的精确 Provider 插件 ID。

启动器先匹配平台，再匹配 Provider、ABI、执行模式、Bridge ABI 与功能。任何一项不满足都
会在载荷执行前产生兼容性错误。

## Runtime Provider

Provider 本身是普通 schema v5 插件，使用内置 Java Runtime 加载，并通过
`pluginKind: "runtime-provider"` 与 `providesRuntimes` 发布能力。Provider 不得替换保留的
Java Runtime；原生 Provider 必须声明 `native-code` 权限。

Provider 的可选分离是维护边界：Aura 本体不因支持第三方语言而捆绑对应运行时、SDK 或
平台工件。外部 Host 故障由插件启动保护、超时、隔离和恢复状态处理，不改变核心启动器
可独立启动的要求。

## 权限与扩展能力

权限、ABI、平台和 Provider 能力共同决定插件是否可执行。Hook 与 Patch 只能调用启动器
明确发布的点位；Mixin 与原生代码保留额外确认和重启要求。外部 Runtime Host 可以通过
受控桥接访问页面注册、数据对象、启动 Hook 和底层服务，但不会直接绑定 JavaFX/JVM
对象，除非 Provider 明确声明并实现对应高权限能力。
