# 热路径优化第 1 轮（每次调用开销）

范围：hook-core 分发（`InvocationFrame`、`Dispatcher`、`CoreRegistration`、`BoundPlan`、`TypeChecks`、
`CoreAfterBridge`/`DirectAfterDispatchChannel`）与 libxposed 101/102 后端的参数适配。安装期、诊断传输、
`inFlight` 计数语义不在本轮修改范围。所有数字均为**主机 JDK 17 x86-64 微基准**，不是 ART、设备或框架证据。

## 1. 研究来源

- libxposed API `XposedInterface.Chain`（[XposedInterface.java](https://github.com/libxposed/api/blob/master/api/src/main/java/io/github/libxposed/api/XposedInterface.java)）：
  `getArgs()` 返回不可变列表；`getArg(i)` 读取单槽，越界抛 `IndexOutOfBoundsException`；`proceed()` 沿用原参数，
  `proceed(Object[])` 使用给定参数；`Hooker` 文档建议不改结果时直接调用 `chain.proceed()`。API 未承诺任何复制行为。
- Vector 链实现（[VectorChain.kt](https://github.com/JingMatrix/Vector/blob/master/xposed/src/main/kotlin/org/matrix/vector/impl/hooks/VectorChain.kt)）：
  `getArgs()` 每次把参数数组复制成新列表并包装为不可变列表；`getArg(i)` 直接读数组；`proceed()` 与
  `proceed(args)` 都把数组原样交给下一节点（不复制），每层 hooker 新建一个链节点。因此在 Vector 上
  `proceed()` 与 `proceed(同内容数组)` 成本相同，节省只来自“不物化”。
- Vector 入口（[VectorNativeHooker.kt](https://github.com/JingMatrix/Vector/blob/master/xposed/src/main/kotlin/org/matrix/vector/impl/hooks/VectorNativeHooker.kt)）：
  非静态方法会先去掉接收者再建根链，根链参数个数等于目标声明的参数个数，这是“按声明个数逐槽读取”的依据。
- ART 编译器（[load_store_elimination.cc](https://android.googlesource.com/platform/art/+/refs/heads/main/compiler/optimizing/load_store_elimination.cc)）：
  只有在方法内未被使用、且不在返回或去优化点存活的“单例”分配才会被删除。SDK 的 frame 会传给用户回调
  （生产环境中通常是多态调用、不内联），因此在 ART 上可以按“每次调用都真实分配”估算。
- HotSpot 标量替换（[JVM Anatomy Quark #18](https://shipilev.net/jvm/anatomy-quarks/18-scalar-replacement/)、
  [Improving OpenJDK Scalar Replacement](https://devblogs.microsoft.com/java/improving-openjdk-scalar-replacement-part-1-3/)）：
  C2 只在对象完全不逃逸且分析可处理控制流合并时才消除分配，结果对内联顺序与代码形状敏感。
- JMH（[JMHSample_12_Forking](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_12_Forking.java)、
  [JMHSample_35_Profilers](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_35_Profilers.java)）：
  不同测试的类型画像会互相污染，需分 fork；`-prof gc` 的 `gc.alloc.rate.norm` 给出每操作分配字节数。
- `LongAdder`（[Javadoc](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/atomic/LongAdder.html)）：
  适合统计用途而非细粒度同步；高争用时吞吐明显高于原子变量，代价是更多内存，`sum()` 不是原子快照。

内容均为转述（Content was rephrased for compliance with licensing restrictions）。

## 2. 改动与合同推理

| # | 改动 | 合同依据 |
|---|---|---|
| a | 101/102 `Chain10xInvocation.arguments()` 用绑定时取得的 `getParameterCount()` 分配一个数组并逐个 `getArg(i)` 填充，链槽不足时回退 `getArgs().toArray()`；`readOnlyArguments()` 改为按下标读 `getArg(i)` 的 `RandomAccess` 只读视图（AbstractList 默认拒绝修改）；`CoreAfterBridge.bind` 反射查找每个后端类只解析一次，旧 core 仍返回 null，core 有 `DirectAfterDispatchChannel` 却缺桥时每次绑定仍抛 `IllegalStateException`。 | `BackendInvocation` 要求视图在调用期间槽位稳定：链节点数组在一次 intercept 内不变（API 声明参数列表不可变），适配器自身从不写入。视图不暴露给回调。 |
| b | `argsForProceed()` 返回“下游执行前已物化的数组”，未物化则为 `null`（`proceed()`）。 | 任务原方案是“只有 `setArgument` 写过才传数组”。该方案违反 ADR-0001 §2 已冻结的“MUTABLE_ARGS 即使未写也把同一数组传给下游，各阶段读取同一组参数槽”，会使 `MutableArgsCopyOnWriteTest` 3 个用例和 `ParameterShapeTest.entrySnapshotKeepsEntrySlotsButSharesObjects`（实时槽反映下游写入）失败；且在 Vector 上 `proceed()` 与 `proceed(args)` 同价，没有可测收益。因此只在“从未物化”时用 `null`，保持“各阶段读取与下游一致的参数槽”。 |
| c | `MUTABLE_ARGS`、`AROUND` 改为回调首次读写参数槽时才向后端获取数组（`argumentCount()`/`argumentType()` 不触发）；物化时的致命事件即使被回调捕获也会重抛。`READ_ARGS` 与 `ENTRY_SNAPSHOT` 保持入口物化。 | ADR-0001 §2 与 `BackendInvocation` 措辞已同步改为“首次访问时获取”。若下游前没人访问，下游用原参数，之后读取再向后端取，对按 SPI 实现的后端结果与入口物化一致。`READ_ARGS` 延迟测得会阻止 HotSpot 标量替换（单形状 24 → 120 B/op），而读参回调必然读取，ART 上无收益，故恢复入口物化。`ENTRY_SNAPSHOT` 单数组方案未实施：`ParameterShapeTest` 固定了“实时槽反映下游写入、快照不变”，且后端数组不承诺独占，快照必须复制。 |
| d | 通用 `PlanChannel` 实现 `DirectAfterDispatchChannel`，后端已有的 `CoreAfterBridge` 探测自动启用（后端无新 API）；`Dispatcher` 的 before/多回调/around 状态机改为“返回 RETURNED 值或抛出 THREW 异常”，`dispatch()` 在外层统一转成 `DispatchResult`，`dispatchValue()` 在本地捕获、失效 frame 后再抛出。 | 未准入返回 `NOT_ENTERED`（后端恰好执行一次原方法）；标记值与 `DispatchResult` 值仍按原规则转义；异常身份、致命事件、在途租约、早退与 around 至多一次均共用同一状态机。`FakeBackend` 与旧后端仍走 `dispatch()`，分类不变（状态机只会抛出 THREW 结果或致命事件）。接口 Javadoc 不再写“仅单 after”。 |
| e | 多回调计划中每个 after 回调的 invocation 与 result 合并为 `AfterBorrow`（继承 `BorrowedInvocation` 并实现 `InvocationResult`）。 | 每个回调仍有独立借用并在返回时共同失效；before、around 与 frame 视图不实现 `InvocationResult`，强转不能扩大形状。单 after 仍是 frame + 独立结果槽（`SingleAfterLeaseFastPathTest` 要求二者不同）。ADR §6 与 callback_contract §4 各补一句。 |
| f | `BoundPlan` 绑定时写入形状、实例方法/构造器标志、参数个数、around 回调与回调数组；返回类型与参数装箱类型首次使用时惰性解析并以不可变对象发布。 | 签名解析可能触发自定义类加载，仍不在 SDK 监视器内进行；错误消息逐字保持（`BoundPlanPrecomputeTest` 对比新旧检查）。 |
| g | `inFlight` 不改，只测 AtomicInteger 与 LongAdder（见 §3.4）。 | ADR §5 / `CloseReport.inFlightAtClose` 语义本轮不动。 |

新增/修改测试：`LazyArgumentMaterializationTest`、`DirectPlanTransportTest`（同一链模型分别走直接传输与
`dispatch()`，比较全部形状、异常身份、致命事件、标记值、关闭透传、重入、在途租约）、`AfterBorrowViewTest`、
`BoundPlanPrecomputeTest`、`ChainArguments101Test`/`ChainArguments102Test`；`MutableArgsCopyOnWriteTest` 的单 after
用例改为断言“下游前未访问 → `proceed(null)`，after 之后物化并看到下游写入”；`ReadArgsView10xTest` 改为断言只调
`getArg`、不调 `getArgs`。

## 3. JMH 结果

方法：`benchmark-jvm` 包 `...benchmark.dispatch`。经 `HookEnvironment` + `CapturingBackend` 安装，再用
`VectorLikeChain`（Vector 链模型）按后端 hooker 的方式驱动捕获的 channel。`TO_ARRAY` 模拟旧后端
（`getArgs().toArray()`/`getArgs()`），`GET_ARG` 模拟新后端，`GET_ARG_DISPATCH` 为不绑定桥的对照。
`MEGA` 先用三种不同回调类驱动各路径，使回调调用点多态（接近真实模块）；`MONO` 只跑单一形状，HotSpot
可内联回调并标量替换 frame（ART 上不会发生）。参数 `-f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc`；ns/op 取 5 次迭代
中位数。旧 core 与新 core 在同一时段背靠背运行；主机同时有其他任务（负载 3–7/8 核），个别格子噪声较大，
B/op 为确定值。**标注：主机 JDK 17 x86-64 微基准，不是 ART/设备证据。**

### 3.1 MEGA（回调调用点多态）：旧 core + 旧后端 → 新 core + 新后端

| 形状 | 旧 ns/op | 新 ns/op | 变化 | 旧 B/op | 新 B/op | 新 core + 旧后端 B/op |
|---|---|---|---|---|---|---|
| 单 before NO_ARGS | 27.7 | 25.7 | -7% | 112 | 88 | 88 |
| 单 before READ_ARGS_VIEW | 45.0 | 36.4 | -19% | 184 | 112 | 160 |
| 单 before READ_ARGS | 54.5 | 36.8 | -33% | 184 | 112 | 184 |
| 单 before ENTRY_SNAPSHOT | 57.3 | 47.7 | -17% | 208 | 136 | 184 |
| 单 before MUTABLE_ARGS + 1 次 setArgument | 52.0 | 32.5 | -38% | 184 | 112 | 160 |
| 单 after | 38.6 | 34.8 | -10% | 120 | 120 | 120 |
| before + after | 46.1 | 42.7 | -7% | 208 | 168 | 168 |
| before + after（READ_ARGS） | 70.2 | 53.7 | -24% | 280 | 192 | 264 |
| AROUND 一次 proceed | 76.8 | 28.7 | -63% | 208 | 112 | 112 |

### 3.2 MONO（单形状，HotSpot 可标量替换）

| 形状 | 旧 ns/op | 新 ns/op | 变化 | 旧 B/op | 新 B/op |
|---|---|---|---|---|---|
| 单 before NO_ARGS | 17.1 | 14.7 | -14% | 0 | 0 |
| 单 before READ_ARGS_VIEW | 35.3 | 26.6 | -24% | 72 | 24 |
| 单 before READ_ARGS | 44.8 | 29.8 | -33% | 72 | 24 |
| 单 before ENTRY_SNAPSHOT | 61.8 | 41.2 | -33% | 144 | 48 |
| 单 before MUTABLE_ARGS + set | 54.6 | 28.8 | -47% | 184 | 112 |
| 单 after | 39.2 | 31.4 | -20% | 120 | 120 |
| before + after | 55.7 | 35.5 | -36% | 184 | 120 |
| before + after（READ_ARGS） | 65.3 | 44.9 | -31% | 256 | 144 |
| AROUND 一次 proceed | 50.6 | 26.2 | -48% | 184 | 112 |

### 3.3 关闭逃逸分析（`-XX:-DoEscapeAnalysis`，MONO）B/op：近似 ART 的真实分配

| 形状 | 旧 core+TO_ARRAY | 旧 core+GET_ARG | 新 core+TO_ARRAY | 新 core+GET_ARG |
|---|---|---|---|---|
| 单 before NO_ARGS | 112 | 112 | 88 | 88 |
| 单 before READ_ARGS_VIEW | 184 | 136 | 160 | 112 |
| 单 before READ_ARGS | 208 | 136 | 184 | 112 |
| 单 before ENTRY_SNAPSHOT | 232 | 160 | 208 | 136 |
| 单 before MUTABLE_ARGS + set | 208 | 136 | 184 | 112 |
| 单 after | 120 | 120 | 120 | 120 |
| before + after | 208 | 208 | 168 | 168 |
| before + after（READ_ARGS） | 304 | 232 | 264 | 192 |
| AROUND 一次 proceed | 232 | 160 | 112 | 112 |

归因（按 B/op）：后端 `arguments()` 逐槽读取每次省 72 B（列表、其数组、不可变包装与 toArray 共 4 个对象
→ 1 个数组），只读视图省 48 B（3 个对象 → 1 个视图）；直接值传输每次省 24 B（`DispatchResult`）；after 借用
合并每个 after 省 16 B；AROUND 延迟物化再省 24 B 数组（旧后端上省掉整条 96 B 的 toArray 链）。
MEGA 下直接传输对照（同为新 core + GET_ARG）：单 before NO_ARGS 112 → 88 B/op、before + after 192 → 168 B/op，
耗时持平（26.3 → 25.7 ns，42.3 → 42.7 ns，均在噪声内）。

### 3.4 独立基准

| 基准 | ns/op | B/op |
|---|---|---|
| 链模型 `proceed()` 下限 | 2.6 | 0 |
| `getArgs().toArray()`（2 参） | 16.7 | 72 |
| `getArg` 循环填数组（2 参） | 3.3 | 24 |
| 准入租约 AtomicInteger，1 线程 | 10.2 | 0 |
| 准入租约 LongAdder，1 线程 | 18.9 | 0 |
| 准入租约 AtomicInteger，4 线程共享 | 196.6 | 0 |
| 准入租约 LongAdder，4 线程共享 | 21.2 | 0 |

## 4. 测得但未采用或刻意保留

- HotSpot 逃逸分析很脆弱：直接路径最初用 try/finally 重新抛出时，单形状 NO_ARGS 从 0 变为 88 B/op；改为方法内
  捕获、失效 frame 后再抛出即恢复。`READ_ARGS` 延迟物化同样破坏标量替换，因此回退为入口物化。这些现象
  不代表 ART 行为，ART 应看 §3.3。
- 方案 b 的“写脏标记”与 `ENTRY_SNAPSHOT` 单数组：与冻结合同及现有用例冲突，且在 Vector 上无收益，未实施。

## 5. 风险

- 101/102 视图按声明个数定长；若某框架链暴露的槽多于声明个数，多出的槽不再可见（Vector 不会发生）。
  槽少于声明个数时 `arguments()` 回退列表形式，视图读到缺失槽时抛 `IndexOutOfBoundsException`（与旧行为同类）。
- AROUND 的回调若在 `proceed()` 进行中（`RUNNING`）首次写参数，写入不再影响已开始的下游；旧实现会改动下游
  已持有的数组。合同从未承诺此行为。
- 延迟物化后，后端 `arguments()` 的普通异常改由触发访问的回调观察（之前在 frame 构造时直接抛出）。
- `PlanChannel` 走直接路径后，后端看到的是原样抛出的异常而非 `DispatchResult.THREW`；101/102 原本即重新抛出，
  外部可见行为相同。

## 6. 下一轮

- `inFlight`：单线程 LongAdder 更慢（18.9 vs 10.2 ns），4 线程共享时快约 9 倍；需先在 ADR §5 / `CloseReport`
  明确“在途数为近似统计”后再评估，或只对不覆盖任何工作的融合常量路径去掉计数。
- `Chain10xInvocation` 每次调用仍分配 24 B；可让 hooker 直接实现 `BackendInvocation`，或为无参形状省去适配器。
- 单 after 路径仍有 frame + 结果槽 + 适配器 120 B，可评估 frame 自身充当结果槽（需保持 `invocation != result`）。
- 设备侧：用 B01–B06（`tools/performance`）在真实 101/102 框架上复测，尤其 B05（AROUND 单次 proceed）与 B02/B04。
