# 接入指南

本页给出从依赖配置到安装、诊断和关闭的最短路径。所有语义细节以[回调合同](contracts/callback_contract.md)和[运行时合同 ADR](adr/ADR-0001-runtime-contract.md) 为准。

> **适用范围与版本说明**：Maven Central 已发布 `io.github.jichuo1.meguviz.hook:*:1.2.0` 全部 8 个制品（由 `meguviz-bom:1.2.0` 统一管理）。若直接从仓库 `main` 源码构建，`main` 当前包含 7 个模块（`hook-dex-bridge` 及 `installRequired`、`BinderTrace102.slicedReceiver*`、`BatchTraceTransport.newSession`、`ResolutionMode.RAW_IF_AVAILABLE` 位于 `1.2.0` 发布分支）。文中性能数据均来自宿主 JDK 17 环境下的 `FakeBackend` / 微基准测量，不直接等同于 ART 或实机设备表现。

## 1. 依赖

通过 `meguviz-bom:1.2.0` 或直接指定 `:1.2.0` 引入坐标。按运行框架选择**一代**后端，并把对应的 libxposed API 声明为 `compileOnly`：

```kotlin
dependencies {
    implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))
    implementation("io.github.jichuo1.meguviz.hook:hook-core")
    implementation("io.github.jichuo1.meguviz.hook:backend-libxposed-102")
    compileOnly("io.github.libxposed:api:102.0.0")
    // 可选：Kotlin DSL 与反射定位桥
    implementation("io.github.jichuo1.meguviz.hook:hook-dsl")
    implementation("io.github.jichuo1.meguviz.hook:hook-ref-bridge")
}
```

不要在同一应用中打包两代后端或把 `libxposed:api` 打入 APK。需要结合离线 DEX 扫描定位方法时，在 Central `1.2.0` 中按需加入 `io.github.jichuo1.meguviz.hook:hook-dex-bridge:1.2.0`（Ref 桥说明见 [MeguvizRef 桥接](../hook-ref-bridge/README.md)）。

## 2. 创建环境

在框架给出进程名和目标 `ClassLoader` 后创建一次 backend 和 environment：

```java
LibXposed102Backend backend = new LibXposed102Backend(this,
        new LibXposed102Context(processName, targetLoader, codeEpoch));
HookEnvironment environment = HookEnvironment.create(backend);
```

`codeEpoch` 必须在已解析成员可能失效时单调递增。完整入口示例见 [API 102 样例](../sample-module-102/)；入口路由、资源租约和热重载见[模块入口与生命周期](module-entry-lifecycle.md)。

## 3. 安装 Hook

已持有经过验证的 `Method` 时，直接传入成员；需要读写参数时使用类型化回调，无需强转，也无需单独调用 `access(...)`：

```java
RegistrationReceipt receipt = environment.install(
        HookSpec.forMember("feature.title", "my-module", titleMethod)
                .beforeArgs(invocation -> log(invocation.getArgument(0)))
                .build());
```

```kotlin
val hooks = environment.forOwner("my-module")
val receipt = hooks.install("feature.title", titleMethod) {
    beforeMutableArgs { it.setArgument(0, "rewritten") }
    afterArgs { invocation, result -> audit(invocation.getArgument(0), result.result) }
}
```

只按类名、方法名和参数类型名定位时使用描述符目标，默认解析器仅查找目标类自身声明成员：

```kotlin
val target = method("com.example.Foo", "title") {
    parameters("java.lang.String")
    returning("java.lang.String")
}
hooks.install("feature.title", target) { replaceConstant("") }
```

每次安装都应检查回执：`isInstalled()` 表示本次取得新的 `registration()`，需由调用方保存并负责关闭；`isSatisfied()` 还包括 `ALREADY_INSTALLED`，此时不会返回新句柄。部分安装（`PARTIALLY_INSTALLED`）也可能携带需要关闭的 `registration()`。Central `1.2.0` 还提供 `environment.installRequired(spec)`（不满足时抛出携带原回执的 `HookInstallException`）。

## 4. 按开销选择回调形状

每次被 Hook 的调用都会经过所选形状，优先选用靠前的轻量形状：

| 需求 | 写法 | 每次调用的 SDK 开销 |
| --- | --- | --- |
| 固定返回值，不执行原方法 | `replaceConstant(v)` | 无调用帧；仅检查状态与在途计数 |
| 原方法成功后替换结果 | `replaceResultOnSuccess(v)` | 无调用帧 |
| 某个字符串参数等于给定值时返回常量 | `replaceIfStringArgumentEquals(i, s, v)` | 无调用帧；按下标读取单个参数 |
| 只需知道被调用 | `before { }`（默认 `NO_ARGS`） | 建调用帧，不读取参数 |
| 只读参数 | `beforeArgs { }` / `afterArgs { }`（`READ_ARGS_VIEW`） | 建调用帧；101/102 上按下标直读框架参数槽，不建视图对象 |
| 改写参数 | `beforeMutableArgs { }`（`MUTABLE_ARGS`） | 首次访问参数槽时才获取参数数组 |
| 包裹原方法、至多调用一次下游 | `around { it.proceed() }` | 参数仅在访问时获取 |
| 需要入口时刻的参数快照 | `access(ENTRY_SNAPSHOT)` | 入口取参数并浅复制一份快照 |

宿主 JDK 17 x86-64 上的 JMH 微基准（见[热路径第 2 轮](research/hotpath-round-2.md)）：单 before `NO_ARGS` 约 24 ns、64 B；`READ_ARGS_VIEW` 约 32 ns、64 B；before + after 约 40 ns、144 B；`AROUND` 一次 proceed 约 29 ns、88 B；8 线程并发单 before 约 43 ns。

## 5. 批量安装与解析

模块启动时一次安装多个 Hook，优先使用 `installAll`。每个 spec 的检查、顺序和回执与逐个 `install` 一致，但默认解析器在本次调用内对同一个类只加载一次、只复制一次声明成员表：

```kotlin
val receipts = environment.forOwner("my-module").installAll {
    hook("feature.title", method("com.example.Foo", "title")) { replaceConstant("") }
    hook("feature.log", method("com.example.Foo", "log")) { beforeArgs { trace(it.getArgument(0)) } }
}
```

```java
List<RegistrationReceipt> receipts = environment.installAll(Arrays.asList(titleSpec, logSpec));
```

宿主微基准对比：`java.lang.String` 上安装 20 个描述符 Hook，逐个 `install` 为 79 µs / 321 KB，`installAll` 为 23 µs / 75 KB；仅有一个 spec 时直接调用 `install` 更轻（`installAll` 约多 0.4 µs）。某个 spec 抛出未检查异常时，剩余 spec 不再尝试，之前已安装的注册仍归环境所有；需边安装边处理回执时使用 `installAll(specs, receipt -> ...)`。

- 同一模块的多个 Hook 可复用 `forOwner(owner)`（Kotlin）。
- 多描述符需要并发解析、后台协调线程按序提交时，见[显式并行预解析与顺序安装](prepared-install.md)。
- 需要继承成员、结构查询或持久化能力时，使用 [MeguvizRef 桥](../hook-ref-bridge/README.md)；同一加载器与代码代次内可复用 `MeguvizRefResolverSession`。

## 6. 异常诊断（可选）

异常跟踪默认关闭且不创建线程。需要时显式包装回调并在自己的后台线程投递：

```java
CallbackExceptionTracker tracker = new CallbackExceptionTracker("target-process", 128);
HookSpec spec = HookSpec.forMember("feature.title", "my-module", titleMethod)
        .before(tracker.before(invocation -> risky()))
        .build();
// 在调用方自己的后台调度器运行（严禁在 Hook 线程或 UI 线程调用）
TraceBatchPoller poller = TraceBatchPoller.start(
        worker, tracker, transport, 16, 64, 100, 20, TimeUnit.MILLISECONDS);
```

- **低延迟触发**：构造 `CallbackExceptionTracker` 时传入 `CaptureSignal` 并在其中调用 `poller.requestPoll()`，宿主测量中 p50 从毫秒级降至数十微秒（仅失败路径约多 0.3 µs）。执行器 `execute` 不得阻塞或内联执行，见[后台批量轮询](trace-batch-poller.md#显式提前触发可选)。
- **到期即发送**：使用 `TraceBatchPoller.startDraining(..., clock, WaitExpiry.DEADLINE)`，不足门槛的记录可在观察等待到期时触发发送，见[按到期时刻评估观察等待](trace-batch-poller.md#按到期时刻评估观察等待可选)。
- **零裁剪切片写入**：自定义写入器实现 `JsonLineTraceTransport.SlicedRecordWriter` 后，可直接读取编码缓冲区切片，省去最后一次数组复制（`BinderTrace102` 与 `ModuleRemoteTraceWriter102` 已实现）。写入器不得保留缓冲区或读取区间外字节。
- **跨进程传输与压缩**：详见[异常诊断与远程传输](remote-diagnostics-102.md)。传输返回仅代表接收方回调返回，不等于持久化落盘。

## 7. 关闭

单个 Hook 调用 `registration.close()` 并检查 `CloseReport`；模块退出时关闭 `environment`。`MeguvizRefResolverSession`、`TraceBatchPoller` 与压缩会话需各自独立关闭。关闭请求、在途工作退出与底层资源释放属于不同阶段，见[显式请求关闭](close-request.md)。

## 8. 自定义后端（可选）

第三方后端实现 `HookBackend` 并将 `DispatchChannel` 接到框架回调即可，普通 `dispatch()` 路径始终可用。若希望省去每次调用的适配器对象，可额外实现无状态的 `spi.ChainAccessor<框架链类型>`，在绑定时通过 `CoreChainBridge.bind(channel, 标记, accessor)` 获取函数并在每次回调调用（返回该标记表示原样执行一次原方法）。101/102 后端已实现此模式，语义与 `BackendInvocation` 一致（见 [ADR-0001](adr/ADR-0001-runtime-contract.md) §2、§6）。后端的 `exit` 必须与 `tryEnter` 在同一线程成对调用。

## 常见问题

- **回调中读参数抛 `HookContractException`**：当前形状仍是 `NO_ARGS`。改用 `beforeArgs` 或显式设置 `access(READ_ARGS_VIEW)`。
- **`build()` 报错 `typed argument callbacks require ...`**：显式 `access(...)` 与类型化回调冲突（如 `NO_ARGS` 配 `beforeArgs`，或 `READ_ARGS_VIEW` 配 `beforeMutableArgs`）。
- **重复安装返回 `CONFLICT`**：回调实例引用也是语义的一部分；需要幂等安装时请保存并复用同一回调与常量实例。
- **`TARGET_MISSING`**：描述符按类型名称精确匹配；请核对 `ClassLoader`、宿主版本和参数类型名，或预先解析验证后改用 `HookSpec.forMember`。
