# 性能研究与验证边界

MeguvizHook 使用所选框架执行 Hook。SDK 的优化目标是减少不必要的分发、参数处理和诊断传输开销，同时保留回调、异常、关闭和在途调用合同。现有证据不足以承诺相对原生框架接口的通用性能提升；具体结果取决于框架、Android Runtime、工作负载和接入方式。

## 已实现的设计

- 常量替换提供不创建调用帧的专用通道；关闭状态检查和在途调用计数仍然保留。单 before、单 after 和 around 路径按各自合同减少不必要的包装。
- `NO_ARGS` 不向后端请求参数数组；`READ_ARGS_VIEW` 借用调用期槽位稳定的只读视图，但底层框架仍可能复制参数。需要入口槽位快照或修改参数时，应选择相应形状。
- 多目标安装按已知位置关联计划与回执；跨目标安装不承诺原子性。可选的并行预解析在提交前重新核验身份，是否提升吞吐取决于解析器、线程和实际负载。
- 诊断支持显式批量、RAW 回退和可选压缩会话；所有传输均由调用方选择、配对并提供后台执行。压缩或批量可能增加小记录、低压缩率数据的成本。
- 可选 Ref 桥在安装期解析目标，并可显式复用与 loader、代码代次绑定的会话；它不参与普通 Hook 回调，不能因为缓存命中就推断全 DEX 覆盖。

## 第 1 轮优化（当前开发源码）

本轮分三条线并行：分发热路径、诊断传输、MeguvizRef 与桥。所有数字来自宿主 JDK 17 x86-64 上的
JMH，只说明同一宿主上新旧实现的相对差异，不是 ART、真实框架、Binder 或设备证据。

| 方向 | 主要改动 | 宿主测量（旧 → 新） | 详细记录 |
|---|---|---|---|
| 后端参数 | 101/102 按 `getArg(i)` 逐槽填一个数组；`READ_ARGS_VIEW` 改为按下标读取的视图。Vector 的 `getArgs()` 每次复制列表 | 2 参数取值 16.7 → 3.3 ns，72 → 24 B | [热路径第 1 轮](research/hotpath-round-1.md) |
| 通用分发 | 通用计划通道走直接值传输，不再每次分配 `DispatchResult`；多回调 after 的调用与结果视图合并；`MUTABLE_ARGS`／`AROUND` 首次访问参数槽时才取参数；`BoundPlan` 绑定期预计算 | AROUND 76.8 → 28.7 ns；before + after（读参）70.2 → 53.7 ns，280 → 192 B | 同上 |
| 诊断编码 | JSONL 与批帧直接写入一个预估缓冲区；接收端单次读取、不克隆整帧；线格式逐字节不变 | JSONL 小记录 659 → 283 ns；128 条批帧编码 177 → 100 µs | [传输第 1 轮](research/transport-round-1.md) |
| 诊断队列 | tracker 一次加锁取快照，`drainTo` 移除确认前缀 | 128 条待发 `tryDeliverBatch` 4348 → 490 ns | 同上 |
| 投递延迟 | 可选 `TraceBatchPoller.requestPoll()` 与 `CaptureSignal` 唤醒；默认行为不变 | 捕获到确认 p50 1027 → 21 µs（轮询 2 ms、门槛 1）；失败路径每次约多 0.3 µs | 同上 |
| Ref 解析与调用 | 调用计划无锁准入；作用域内成员表缓存；描述符单次校验；缓存命中无锁 | 计划调用 38.7 → 16.7 ns（4 线程 995 → 229 ns）；同一作用域 8 个结构查询 53.2 → 10.9 µs | MeguvizRef `docs/performance.md`（1.1.0 开发版） |
| Ref 桥 | 导出前按 JVM 描述符保守预筛；排序键只算一次 | 安装期，未单独测量 | [桥接说明](../hook-ref-bridge/README.md) |

易用性：`beforeArgs`、`beforeMutableArgs`、`afterArgs` 让回调直接拿到读写参数的调用类型，并自动选择
形状；接入步骤见[接入指南](integration-guide.md)。它们在构建 spec 时创建一个收窄类型的适配器，宿主测量
（回调调用点多态）中每次调用多约 2 ns（`READ_ARGS_VIEW` 33.2 → 35.0 ns，`MUTABLE_ARGS` 29.7 → 32.1 ns），
不增加分配；对延迟极敏感的目标仍可使用普通回调加显式 `access(...)`。

评估后未采用：只在写过参数时才把数组传给下游（违反 ADR-0001 §2 已冻结的槽位共享语义，且 Vector 上
`proceed()` 与 `proceed(args)` 成本相同）；`ENTRY_SNAPSHOT` 单数组；`READ_ARGS` 延迟取参（破坏 HotSpot 标量
替换且 ART 无收益）；改变 `inFlight` 计数语义（单线程 LongAdder 更慢）；把 Ref 计划切换到 MethodHandle
（异常、转换与调用者语义不同，Android 运行时不能依赖 LambdaMetafactory）。

下一轮候选：在真实 101/102 框架上用 B01–B06 复测；`inFlight` 多线程争用；`Chain10xInvocation` 每次调用的
24 B 适配器；桥在 Ref 1.1 发布后改用 `resolveRaw` 与单锁 `isCurrent`；等待到期的定时评估。

## 生命周期与通信约束

关闭请求、已认领工作退出、框架入口恢复和物理释放是不同状态。安装和替换期间的外部回调仍可能与调用方或框架形成等待环；非等待关闭请求只提供协调入口，不自动执行清理，也不证明所有锁环已消除。参见[显式请求关闭](close-request.md)与[热重载接入](hot-reload-102.md)。

批量传输只确认接收方约定的连续前缀，不等同持久化。发送异常可能发生在外部副作用之后；显式重试可能重复。同步 Binder 仍会阻塞调用线程，帧大小限制也不能保证共享事务缓冲一定有空间。参见[异常诊断与远程传输](remote-diagnostics-102.md)。

## 研究依据

- [AOSP ART 逃逸分析](https://android.googlesource.com/platform/art/+/1755ad0062e97d0e358c65600fd5e51b40b85d95/compiler/optimizing/escape.cc)说明对象能否消除依赖于运行时对逃逸和副作用的分析；源码层包装数量不能直接作为设备分配结果。
- [OpenJDK JMH 循环示例](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_11_Loops.java)和[死代码示例](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_08_DeadCode.java)说明预热、结果消费和循环优化可能影响测量。
- [AOSP Binder 线程模型](https://source.android.com/docs/core/architecture/ipc/binder-threading)说明跨进程同步调用和持锁调用的约束；[事务过大说明](https://developer.android.com/reference/android/os/TransactionTooLargeException)说明并发事务共享有限缓冲。
- [Java Deflater 合同](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/Deflater.html)定义了可复用压缩状态的重置与释放；每帧仍需遵循配对格式、限制和校验合同。
- [WeakHashMap 合同](https://docs.oracle.com/javase/8/docs/api/java/util/WeakHashMap.html)不保证通过值间接持有的 key 可回收；反射对象和查询对象的 loader 生命周期必须显式管理。
- [Java FFM API](https://openjdk.org/jeps/454)的桌面 JDK 支持不等于 Android ART 提供相同接口；跨 JNI 的额外转换和调用成本需要独立评估。

## 验证边界

仓库提供 JVM 合同测试、后端接口替身测试、公开 API 检查、Android lint 和样例构建。它们覆盖对应测试路径，不自动证明真实框架的回调行为、Android 设备兼容、Binder 端到端可靠性、持久化或性能提升。性能配置及工作负载说明见[性能测量协议](../work/performance/workloads.md)。

正式比较应使用相同工作负载、语义、框架和设备条件，区分注册、回调、批量传输和持久化成本；异常、原方法调用次数与对象身份等合同必须一并检查。未验证的结果不应外推为全局性能结论。
