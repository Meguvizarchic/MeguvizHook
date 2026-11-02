# libxposed 接口行为与适配边界

本文记录 SDK 针对 libxposed API 101 和 102 所依据的接口形状与适配决策。接口源码和 stub 测试不等于所有框架或设备的运行行为。

## 两代共同的调用链

| 接口行为 | SDK 约束 |
| --- | --- |
| 两代均提供 `Hooker` / `Chain` 模型 | API101 并非 classic before/after 后端 |
| `Chain.proceed()` 及其参数变体调用下游 | 原方法与调用链按 SDK 回调合同执行 |
| `Chain.getArgs()` 提供参数视图，参数替换经 proceed 传递 | 根据选定的参数访问模式处理快照、只读访问或写回 |
| 框架 chain 在拦截返回后及跨线程不可继续借用 | 调用帧的借用范围受生命周期约束 |
| 框架 `ExceptionMode` 控制 Hooker 异常处理 | SDK 显式映射所选策略，不能将不同策略的异常行为视为等价 |
| `HookHandle.unhook()` 提供框架卸载入口 | SDK close 报告和在途调用语义不等于设备上所有物理卸载效果 |

## 代际差异

| 能力 | API101 | API102 | SDK 处理 |
| --- | --- | --- | --- |
| 初装框架 ID | 不支持 `setId` | 提供 `setId` | 普通初装不设置 ID；逻辑身份在各自环境内管理 |
| 单目标替换 | 无框架 `replaceHook` | 提供 handle 的 `replaceHook` | 101 在一个框架入口中切换 SDK channel；102 使用框架 handle |
| 模块热重载 | 没有 API102 模块生命周期 | 提供模块代际与旧 handle 交接入口 | 102 提供显式接手；模块负责确认映射、生命周期和资源 |
| 模块入口样例 | 101 独立模块 | 102 独立模块 | 示例分别选择版本；102 示例默认不启用自动热重载 |

身份与替换边界见 [ADR-0003](../../docs/adr/ADR-0003-hook-identity-scope.md)，热重载示例及未决条件见 [接入说明](../../docs/hot-reload-102.md)。真实类加载、框架优先级、异常保护、卸载及设备性能须按框架和场景分别验证。
