# 模块入口与生命周期

`ModuleEntryRouter` 和 `ModuleLifecycle` 是纯 JVM 的可选组合层。模块仍由框架加载，
SDK 不生成入口元数据、不发现 service、不创建线程或自动接管旧 Hook。它们可分别接到
API101 和 API102 的实际 `onModuleLoaded`、`onPackageReady` 回调；可编译的完整示例是：

- `sample-module-101/.../RouterEntryExample101.java`
- `sample-module-102/.../RouterEntryExample102.java`

示例没有写入现有 `java_init.list`，不会自动启用；使用者需选一代后端、填写目标包名并
自行配置模块入口和作用范围。实际设备上的回调顺序、宿主加载器和运行框架行为不由 JVM
测试保证。API102 示例默认拒绝热重载，因为它没有应用专属的跨代身份映射和资源交接。

## 路由和定位扩展

在 `onModuleLoaded` 从框架参数捕获**当前进程名**，以独立代次、上限、过滤器、环境工厂
和安装回调构造一个 router。`onPackageReady` 将框架提供的包名与实际宿主 ClassLoader
交给它。每一代内按 `(包名, ClassLoader 对象身份)` 有界去重；新 ClassLoader 是新路由，
不会依赖 `ClassLoader.toString()`。重复进入的相同路由返回 `IN_PROGRESS` 或
`ALREADY_READY`，不再次执行安装。过滤、创建环境或安装抛异常时，异常原样传出，路由
记为 `FAILED`；此时可能已有部分 Hook 安装，不自动重试，也不会擅自卸载。

`ModuleEntryRouter.fromBackend` 可包装 API101/102 或第三方 `BackendFactory`，并校验其
`BackendContext` 的进程、ClassLoader **对象身份**与 epoch 等于事件上下文。还可提供自定义
`EnvironmentFactory`，构造带 `MemberResolver` 与 `DiagnosticsSink` 的 `HookEnvironment`。
这使现有反射／目标定位系统能够通过 SPI 接入，不改变核心依赖；自定义工厂自行负责对应的
上下文验证、设备能力和错误处理。路由不验证框架传入事实的真实性，也不从类名猜测 loader。

## 资源清理和在途工作

调用方可把需要其拥有的监听器、线程停止句柄或其他 `AutoCloseable` 加入生命周期：

```java
ModuleLifecycle lifecycle = new ModuleLifecycle(32);
lifecycle.own("listener", listenerRegistration);

// 每段由模块管理的工作显式获取租约；没有租约的工作不在计数范围。
ModuleLifecycle.Lease lease = lifecycle.tryEnter();
if (lease != null) {
    try {
        runModuleWork();
    } finally {
        lease.close();
    }
}

// 调用方控制同步时机。SDK 不等待、不调度、不强制停止线程。
lifecycle.requestQuiescence();
ModuleLifecycle.CleanupReport report = lifecycle.tryCleanup();
if (report.status() == ModuleLifecycle.CleanupStatus.BUSY) {
    // 拒绝本次重载且希望恢复运行时，在清理尚未开始时显式恢复接纳。
    boolean resumed = lifecycle.resumeAdmissions();
    // resumed 为 false 表示清理已开始或状态已变化，不能假定旧资源完好。
}
```

请求静默后拒绝新的租约和资源注册，已获得的租约保持有效。`BUSY` 只表示显式租约仍在途；
清理会在它们全部结束后反序、至多一次地调用各资源。普通失败不会跳过后续资源，报告保留
每个失败；失败可能已有副作用，不自动重试或恢复。fatal VM 事件继续传播，剩余资源可能未
执行。清理回调不会在 router 监视锁或生命周期锁内执行，重入清理返回 `IN_PROGRESS`。

**租约不是自动 Hook 跟踪。**调用方必须另行确认未使用租约的 Hook 回调、宿主回调、外部线程、
native Hook 和 JNI 引用等已经按其协议处理；`QUIESCED` 不代表框架所有调用已排空，也不代表
代码、类加载器或资源已物理释放。不要在仍需跨代接管句柄时把旧 `HookEnvironment` 加入
资源清理：关闭它可能卸载待接管 Hook。

## API102 热重载与所有权

`ModuleLifecycle` 只处理使用者显式登记的资源，不会关闭任何 `HookEnvironment` 或自动映射
旧句柄。只有清理成功、已单独确认未跟踪工作可安全交接，并建立可信的目标、owner、priority、
异常策略和旧句柄映射时，模块才应同意框架重载。新代按自身实际进程、宿主 loader 和新 epoch
重新构造 router / backend / environment；框架不会自动重放普通加载回调。旧代对象不可作为新代
状态。旧句柄需在回调内通过 `LibXposed102ReloadSession` 显式处理，具体见
[热重载合同](hot-reload-102.md)。

清理完成后，`snapshotContexts()` 可检查保留的正常和失败路由；`detachContexts()` 返回这些
记录并从 router 移除对它们和 ClassLoader 的强引用，但不会关闭 environment、证明接管成功
或处理旧 handle。调用方必须继续持有并处理返回的环境／失败记录；不要对结果未知的句柄盲目
重试或卸载。路由容量和资源容量都有界；达到上限时明确失败，不自动移除旧项目。
