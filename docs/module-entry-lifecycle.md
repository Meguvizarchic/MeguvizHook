# 模块入口与生命周期

`ModuleEntryRouter` 与 `ModuleLifecycle` 是纯 JVM 的可选组合层。模块仍由框架加载，SDK 不生成入口元数据、不发现 service、不创建后台线程，也不会自动接管旧 Hook。它们可直接接入 libxposed API 101 或 API 102 的 `onModuleLoaded` 与 `onPackageReady` 回调，完整可编译示例见：

- [RouterEntryExample101.java](../sample-module-101/src/main/java/io/github/jichuo1/meguviz/hook/sample101/RouterEntryExample101.java)
- [RouterEntryExample102.java](../sample-module-102/src/main/java/io/github/jichuo1/meguviz/hook/sample102/RouterEntryExample102.java)

示例默认未加入 `java_init.list`，不会自动启用；使用方需选定一代后端、配置目标包名并自行负责模块入口与作用范围。API 102 示例默认拒绝热重载，因为它不包含特定应用的跨代身份映射与资源交接逻辑。

> **适用范围与验证说明**：JVM 测试仅覆盖路由与租约协议本身，实际设备上的回调触发顺序、宿主加载器和运行框架行为仍需在目标设备验证。

## 路由与定位扩展

在 `onModuleLoaded` 从框架参数捕获当前进程名后，以独立代次、上限、过滤器、环境工厂和安装回调构造 `ModuleEntryRouter`。`onPackageReady` 将框架提供的包名与宿主 `ClassLoader` 传入 `onPackageReady(packageName, loader)`：

- 同一代次内按 `(包名, ClassLoader 对象引用)` 做有界去重；新的 `ClassLoader` 实例视为新路由，不依赖 `ClassLoader.toString()`。
- 重复进入已就绪或进行中的路由时返回 `ALREADY_READY` 或 `IN_PROGRESS`，不重复执行安装。
- 若过滤、环境创建或安装回调抛出异常，异常原样传播，该路由记为 `FAILED`（可能已有部分 Hook 安装，不会自动重试或擅自卸载）。
- `ModuleEntryRouter.fromBackend` 可包装 `BackendFactory`，并严格校验其 `BackendContext` 中的进程名、`ClassLoader` 对象引用与 `codeEpoch` 是否与事件上下文一致。也可通过自定义 `EnvironmentFactory` 配置 `MemberResolver` 与 `DiagnosticsSink`。

## 资源清理与在途工作租约

调用方可将自己拥有的监听器、后台线程停止句柄或其他 `AutoCloseable` 资源托管至 `ModuleLifecycle`：

```java
ModuleLifecycle lifecycle = new ModuleLifecycle(32);
lifecycle.own("listener", listenerRegistration);

// 每段由模块管理的工作显式获取租约；未获取租约的工作不在统计范围内。
ModuleLifecycle.Lease lease = lifecycle.tryEnter();
if (lease != null) {
    try {
        runModuleWork();
    } finally {
        lease.close();
    }
}

// 由调用方控制同步时机，SDK 不等待、不调度也不强停线程。
lifecycle.requestQuiescence();
ModuleLifecycle.CleanupReport report = lifecycle.tryCleanup();
if (report.status() == ModuleLifecycle.CleanupStatus.BUSY) {
    // 拒绝本次重载且希望恢复运行时，在清理尚未开始时恢复接收新任务。
    boolean resumed = lifecycle.resumeAdmissions();
    // resumed 为 false 表示清理已开始或状态已变化，不能假定旧资源仍然完整。
}
```

- 调用 `requestQuiescence()` 后会拒绝新的租约和资源注册，已领取的租约仍可正常结束。
- `BUSY` 表示仍有未释放的租约；所有租约关闭后，按逆序逐一、至多一次关闭已托管资源。普通资源关闭异常不会跳过后续资源，所有失败均记录在报告中；致命 VM 事件立即向上传播。
- **租约不自动追踪 Hook 回调**：未申请租约的 Hook 回调、宿主回调、外部线程、native Hook 与 JNI 引用须由调用方另行停止或协调。`QUIESCED` 仅代表已登记租约与资源清理完毕，不代表宿主调用已全部排空，也不代表代码、类加载器或底层资源已被系统回收。
- 若后续需要跨代接管旧句柄，**不要**把旧 `HookEnvironment` 加入 `ModuleLifecycle` 资源列表，否则清理时会卸载待移交的 Hook。

## API 102 热重载与上下文移除

只有确认托管资源清理完毕、未追踪工作可安全交接，且建立了可靠的跨代映射与异常处理策略时，模块才应同意框架热重载。新代需按新进程、宿主加载器和递增的 `codeEpoch` 重新构造后端与环境，并通过 `LibXposed102ReloadSession` 显式接管旧句柄，详见 [API 102 热重载接入](hot-reload-102.md)。

清理完成后，可调用 `snapshotContexts()` 查看保留的正常与失败路由；调用 `detachContexts()` 取回这些上下文记录并解除 `ModuleEntryRouter` 对环境和 `ClassLoader` 的强引用。`detachContexts()` 不会自动关闭 `HookEnvironment` 或处理旧句柄，调用方仍须负责后续释放与清理。
