# 接入指南

本页给出从依赖到安装、诊断和关闭的最短路径。所有语义细节以[回调合同](contracts/callback_contract.md)
和[运行时合同 ADR](adr/ADR-0001-runtime-contract.md)为准。标注“开发源码”的入口尚不在已发布的 `1.0.0` 中。

## 1. 依赖

Maven Central 的 `1.0.0` 坐标为 `io.github.jichuo1.meguviz.hook:<artifactId>:1.0.0`。
按运行框架选择**一代**后端，并把对应的 libxposed API 声明为 `compileOnly`：

```kotlin
dependencies {
    implementation("io.github.jichuo1.meguviz.hook:hook-core:1.0.0")
    implementation("io.github.jichuo1.meguviz.hook:backend-libxposed-102:1.0.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    // 可选：Kotlin DSL
    implementation("io.github.jichuo1.meguviz.hook:hook-dsl:1.0.0")
}
```

不要同时打包两代后端或框架类。可选的 `hook-ref-bridge` 尚未发布，只能从源码接入，见
[MeguvizRef 桥接](../hook-ref-bridge/README.md)。

## 2. 创建环境

在框架给出进程名和目标 ClassLoader 后创建一次 backend 和 environment：

```java
LibXposed102Backend backend = new LibXposed102Backend(this,
        new LibXposed102Context(processName, targetLoader, codeEpoch));
HookEnvironment environment = HookEnvironment.create(backend);
```

`codeEpoch` 必须在已解析成员可能失效时单调变化。完整入口示例见
[API102 样例](../sample-module-102/)；入口路由、资源租约和热重载见
[模块入口与生命周期](module-entry-lifecycle.md)。

## 3. 安装 Hook

已持有经过验证的 `Method` 时，直接传入成员；需要参数时使用类型化回调（开发源码），无需强转，
也无需单独调用 `access(...)`：

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

只按类名、方法名和参数类型名定位时，用描述符目标；默认解析器只查声明成员：

```kotlin
val target = method("com.example.Foo", "title") {
    parameters("java.lang.String")
    returning("java.lang.String")
}
hooks.install("feature.title", target) { replaceConstant("") }
```

每次安装都检查回执：`isInstalled()` 表示本次取得新的 `registration()`，需由调用方保存并关闭；
`isSatisfied()`（开发源码）还包括 `ALREADY_INSTALLED`，此时不会得到新 handle。部分安装也可能
携带需要关闭的 registration。

## 4. 按开销选择形状

每次被 Hook 的调用都要执行所选形状。能用前面的形状就不要用后面的：

| 需求 | 写法 | 每次调用的 SDK 工作 |
|---|---|---|
| 固定返回值，不执行原方法 | `replaceConstant(v)` | 无调用帧；只有准入和在途计数 |
| 原方法成功后替换结果 | `replaceResultOnSuccess(v)` | 无调用帧 |
| 某个字符串参数等于给定值时返回常量 | `replaceIfStringArgumentEquals(i, s, v)` | 无调用帧；按下标读一个参数 |
| 只需知道被调用 | `before { }`（默认 `NO_ARGS`） | 一个调用帧，不取参数 |
| 读取参数 | `beforeArgs { }`／`afterArgs { }`（`READ_ARGS_VIEW`） | 一个调用帧加一个按下标读取的只读视图 |
| 改写参数 | `beforeMutableArgs { }`（`MUTABLE_ARGS`） | 首次访问参数槽时才取一个数组 |
| 包住原方法、至多调用一次下游 | `around { it.proceed() }` | 参数只在访问时获取 |
| 需要入口时刻的参数快照 | `access(ENTRY_SNAPSHOT)` | 入口取参数并复制一份快照 |

宿主 JDK 17 x86-64 上的 JMH 微基准（回调调用点已多态，见[热路径第 1 轮](research/hotpath-round-1.md)）：
单 before `NO_ARGS` 约 26 ns、88 B；`READ_ARGS_VIEW` 约 36 ns、112 B；before + after 约 43 ns、168 B；
AROUND 一次 proceed 约 29 ns、112 B。这些数字只用于比较形状之间的相对开销，**不是** ART、真实框架或
设备上的结果。

## 5. 多个 Hook 与解析

- 同一模块的多个 Hook 复用 `forOwner(owner)`（Kotlin）。
- 多个描述符需要并发定位时，见[显式并行预解析与顺序安装](prepared-install.md)。
- 需要继承成员、结构查询或 provider 时，用 [MeguvizRef 桥](../hook-ref-bridge/README.md)；
  同一 loader 与代码代次内多次解析可显式复用 `MeguvizRefResolverSession`。

## 6. 异常诊断（可选）

诊断默认关闭，不启动线程。需要时显式包装回调并选择投递方式：

```java
CallbackExceptionTracker tracker = new CallbackExceptionTracker("target-process", 128);
HookSpec spec = HookSpec.forMember("feature.title", "my-module", titleMethod)
        .before(tracker.before(invocation -> risky()))
        .build();
// 在调用方的后台调度器上：攒到 16 条，或首次观察到记录后等待 20 ms，即发送一批（每批最多 64 条）；
// 每 100 ms 检查一次。worker 不能是 Hook 线程或 UI 线程。
TraceBatchPoller poller = TraceBatchPoller.start(worker, tracker, transport, 16, 64, 100, 20, TimeUnit.MILLISECONDS);
```

- 低延迟投递（开发源码）：给 tracker 传入 `CaptureSignal` 并在其中调用 `poller.requestPoll()`，
  宿主测量中捕获到确认的 p50 从毫秒级降到数十微秒；代价只在失败路径，约 0.3 µs。执行器的
  `execute` 必须不阻塞、不内联执行。见[后台批量轮询](trace-batch-poller.md#显式提前触发可选)。
- 跨进程投递、压缩和授权见[异常诊断与远程传输](remote-diagnostics-102.md)。接收确认不代表持久化。

## 7. 关闭

单个 Hook 用 `registration.close()` 并检查 `CloseReport`；模块生命周期结束时关闭 environment。
`MeguvizRefResolverSession`、`TraceBatchPoller` 和压缩会话各自独立关闭。关闭请求、在途工作退出
和物理释放是不同状态，见[显式请求关闭](close-request.md)。

## 常见问题

- **回调里读参数抛 `HookContractException`**：形状仍是 `NO_ARGS`。改用 `beforeArgs`，或显式
  `access(READ_ARGS_VIEW)`。
- **`build()` 报 typed argument callbacks require …**：显式 `access(...)` 与类型化回调冲突，
  例如 `access(NO_ARGS)` 配 `beforeArgs`，或 `READ_ARGS_VIEW` 配 `beforeMutableArgs`。
- **重复安装返回 `CONFLICT`**：回调身份是语义的一部分；需要幂等安装时保存并复用同一个回调对象。
- **`TARGET_MISSING`**：描述符按类型名匹配；检查 ClassLoader、宿主版本和参数类型名，或在外部验证后
  改用 `HookSpec.forMember`。
