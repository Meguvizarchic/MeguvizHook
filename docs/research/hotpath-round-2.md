# 热路径优化第 2 轮（每次调用的适配器对象与在途计数争用）

范围：hook-core 分发（`InvocationFrame`、`Dispatcher`、`CoreRegistration`、`CoreAfterBridge` 及新增的
`InFlightCounter`、`ChainDispatchChannel`、`CoreChainBridge`、`spi.ChainAccessor`、`InvocationAccessor`），
libxposed 101/102 后端，以及 benchmark-jvm 的 `...benchmark.dispatch`。安装期、诊断传输、ReflectiveMemberResolver、
hook-ref-bridge、hook-api/hook-dsl 不在本轮范围。**所有数字均为主机 JDK 17 x86-64（8 vCPU = 4 核 × 2 SMT）
微基准，不是 ART、设备或框架证据。**

## 1. 研究来源

- libxposed `XposedInterface.Chain`（[XposedInterface.java](https://github.com/libxposed/api/blob/master/api/src/main/java/io/github/libxposed/api/XposedInterface.java)）：
  链对象不能跨线程共享，也不能在 `Hooker.intercept` 结束后复用；`getArg(i)` 越界抛
  `IndexOutOfBoundsException`。这正是 frame 可以在一次分发内直接读取链对象、分发结束即失效的依据。
- Vector 链（[VectorChain.kt](https://github.com/JingMatrix/Vector/blob/master/xposed/src/main/kotlin/org/matrix/vector/impl/hooks/VectorChain.kt)）：
  参数数组是节点的私有不可变引用，`getArg` 直接读数组；每一层 hooker 在 `proceed` 时新建下一个节点，
  并用 try/catch 记录下游结果与异常，PROTECTIVE 模式据此决定跳过或恢复。节点约 48 B（7 个引用 + int + boolean）。
- Vector 入口（[VectorNativeHooker.kt](https://github.com/JingMatrix/Vector/blob/master/xposed/src/main/kotlin/org/matrix/vector/impl/hooks/VectorNativeHooker.kt)、
  [xposed/README](https://github.com/JingMatrix/Vector/blob/master/xposed/README.md)）：每次调用从原生注册表取一次
  hook 快照并新建根链；hook 记录不可变，替换是整体换记录，所以在途调用不受影响。底层 ART 方法替换由
  [LSPlant](https://github.com/LSPosed/LSPlant) 完成，回调里调用备份方法执行原方法，这一层的成本不在 SDK 控制范围内。
- `Striped64`/`LongAdder`（[Striped64.java](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/atomic/Striped64.java)、
  [LongAdder Javadoc](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/atomic/LongAdder.html)）：
  无争用时只更新 base；base 的 CAS 第一次失败才建表（初始 2 格，按争用翻倍到不小于 CPU 数），格子用
  `@Contended` 填充；冲突时用线程探针重新散列。`sum()` 不是原子快照。重新散列意味着同一租约的加和减
  可能落在不同格子，读取时可能短暂看到“只减未加”的负值，所以不能直接拿来做在途计数。
- SNZI（Ellen、Lev、Luchangco、Moir，PODC 2007，[论文 PDF](http://courses.csail.mit.edu/6.852/08/papers/p13-ellen.pdf)）：
  只回答“到达是否多于离开”的指示器语义比计数器弱，因而可以层次化、可扩展；到达与离开必须作用在同一节点。
  本轮借用两点：在途数只用于报告，可以放宽为非原子观察；同一租约在同一位置加减。
- Android `Thread.getId()`（[libcore Thread.java](https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/main/java/java/lang/Thread.java)）：
  返回字段 `tid`，自 Java 19 标记弃用，原因是该方法可被子类覆盖；`threadId()` 为 final 但高于本库的 Java 8 /
  Android 下限。
- JMH 伪共享样例（[JMHSample_22_FalseSharing](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_22_FalseSharing.java)）：
  `@State` 对象本身有填充，但其内部引用的对象没有；并且 JVM 可重排字段，字段填充不可靠。本轮据此修正了
  多线程基准，并用数组步长而不是字段来填充计数格。
- JOL（`org.openjdk.jol:jol-core:0.17`，仅基准模块，`./gradlew :benchmark-jvm:layouts`）给出本文的 HotSpot 对象布局。
- ART 逃逸与分配（第 1 轮已引用的 [load_store_elimination.cc](https://android.googlesource.com/platform/art/+/refs/heads/main/compiler/optimizing/load_store_elimination.cc)）：
  frame 会传给多态的用户回调，ART 上按“真实分配”估算，因此以关闭逃逸分析的 B/op 作为 ART 代理指标。

以上均为转述（Content was rephrased for compliance with licensing restrictions）。

## 2. 改动与合同推理

### 2.1 在途计数按争用自适应（`InFlightCounter`）

旧实现：每次调用对注册共享的一个 `AtomicInteger` 做两次原子加减，所有线程写同一缓存行。

新实现（`InFlightCounter extends AtomicInteger`，继承的值即“共享字”）：

1. 无表时 `enter()` 对共享字做一次 `getAndIncrement`；若旧值不为 0（另一租约仍在途，包括同线程重入），
   发布一张填充计数格表，本次租约仍留在共享字上。之后的租约按 `Thread.getId()` 的 Fibonacci 散列选格。
   表只发布一次、不扩容，所以一个线程的格子在计数器生命周期内固定。
2. 格子是一个 `AtomicIntegerArray` 中相隔 32 个 int（128 B，同 `@Contended` 默认宽度）的元素，首尾留空；
   格数为不小于 2×CPU 的 2 的幂，上限 64（8 核 16 格，约 2.2 KB，只在观察到重叠的注册上分配）。
3. 通道内一次完成的租约（融合常量、通用与单 after 通道）由 `enter()` 返回位置令牌，`exit(token)` 在同一
   位置减。拆分式 `tryEnter()`/`exit()`（常量、成功替换、条件通道）没有令牌，`exitUntracked()` 在无表时减
   共享字、有表时减本线程格。唯一的不同位置情形是“表发布前在共享字进入、发布后在本线程格退出”。
4. `sum()` 先读表引用和各格，最后读共享字。对每个租约，若它的减被读到，则它的加一定先于该读取：同位置时
   显然；共享字→格子时，加发生在减之前、减在读格之前、读格在读共享字之前。所以每个租约贡献 0 或 +1，
   总和不为负；没有租约在读取期间进出时总和精确；读取全程在途的租约一定计入。另以 long 累加并饱和到
   `[0, Integer.MAX_VALUE]`，覆盖 `getId()` 被子类覆盖成不稳定值这一病态情形。

被测过但未采用的方案（§3.5）：Striped64 式“base CAS 失败才膨胀”在单线程下 14.3 ns，比原子加 10.2 ns
慢约 40%（读后 CAS 比直接原子加多一个依赖），不满足“单线程不慢”；`LongAdder` 单线程 18.1 ns，且重新散列
破坏同位置加减。以重叠作为触发信号会把同线程重入也算作争用，代价只是该注册多 2.2 KB，且膨胀后单线程路径
实测与未膨胀持平（2 线程 10.1 ns/op）。

合同：准入仍是“状态读、计数加、状态再读”，close 不等待计数；ADR-0001 §5 新增一条精确说明
“在途观察数”的含义（无并发进出时精确；并发时非原子，读取全程在途的租约一定计入，每个调用线程至多
多计一个，不为负），`callback_contract.md` §3 补一句并指向 ADR。B08（受控静止场景断言恰为 1）属于“静止时
精确”。融合常量、成功替换、条件通道的次序（状态读、原子加、状态再读、退出原子减）不变。

行为变化：`AtomicGateLifecycleContractTest` 原用例 `countWrapKeepsStateAndSignedIntReport` 通过反射把计数播种为
`Integer.MAX_VALUE`，固定“回绕后报告负数”。真实租约不可能回绕；新实现报告饱和为 0，用例改名为
`countWrapKeepsStateAndNonNegativeReport`，仍断言准入只由状态决定。文档从未承诺回绕行为。

新增测试：`InFlightCounterTest`（格数、顺序租约不膨胀、重叠膨胀后静止精确、8 线程 × 20 万次两种协议下并发
读取始终落在 [0, 8]、3 个持有租约 + 5 个搅动线程时读数始终在 [3, 8]）；`InFlightContentionTest`（8 线程争用
后静止关闭恰为持有数；持续搅动中关闭时在 [持有数, 持有数 + 调用线程数]；静止后为 0）。

### 2.2 可选 flyweight 链传输（去掉每次调用的后端适配器）

旧路径：101/102 每次调用新建 `Chain10xInvocation`（24 B），`READ_ARGS_VIEW` 还新建 `ArgumentView`（24 B）。

新 SPI（全部为附加 API）：

- `spi.ChainAccessor<C>`：`thisObject(chain)`、`argument(chain, i)`（`READ_ARGS_VIEW` 的单槽读取，越界抛
  `IndexOutOfBoundsException`，槽位在调用期内稳定）、`arguments(chain)`（时机与 `BackendInvocation.arguments()`
  相同）、`proceed(chain, args)`（null 保留原参数）。访问器无每次调用状态，每个 hooker 绑定一次。
  未列入 `argumentCount`：frame 的计数一直来自 `BoundPlan`，不需要后端提供。
- `ChainDispatchChannel extends DirectAfterDispatchChannel`：`dispatchChain(accessor, chain)`，编码与
  `dispatchDirect` 相同（`NOT_ENTERED`、一层转义、原样抛出 THREW、致命事件直接传播）。通用通道与单 after
  通道实现它，共用同一状态机、准入与在途租约。
- `CoreChainBridge.bind(channel, notEntered, accessor)`：返回 `Function<Object, Object>`（旧 core 也有的类型），
  解码规则与 `CoreAfterBridge` 共用 `CoreAfterBridge.decode`；不支持的通道返回 null。

frame：后端访问统一经 `(accessor, chain)`。普通传输用包内 `InvocationAccessor` 把 `BackendInvocation` 当作
“链”，并在入口照旧取一次 `readOnlyArguments()`；flyweight 的 `READ_ARGS_VIEW` 不建视图，按需调用
`accessor.argument`。`ENTRY_SNAPSHOT` 快照与普通传输的视图互斥，合并为一个字段，frame 仍为 64 B（JOL：12 个
引用 + 2 个 boolean）。`InvocationAccessor` 以 `Object` 为类型参数并显式强转，避免泛型桥方法多一层内联——
初版带桥方法时，旧后端策略（GET_ARG）在 MONO 下 NO_ARGS 从 0 变为 24 B/op，去掉后恢复。

四个方向的兼容性：

| 组合 | 行为 |
|---|---|
| 新 core + 新后端 | 101/102 先反射探测 `CoreChainBridge`，通用与单 after 通道走 flyweight |
| 新 core + 旧后端 | 旧后端继续用 `CoreAfterBridge`/`dispatch()`；frame 经 `InvocationAccessor`，no-EA B/op 与旧 core 相同 |
| 旧 core + 新后端 | `ChainBridgeLookup` 找不到 `CoreChainBridge` 与 `ChainDispatchChannel` → 回退 `CoreAfterBridge`；有通道类型却缺桥或访问器类型时每次绑定抛 `IllegalStateException`（与第 1 轮同一模式） |
| FakeBackend / 第三方后端 / 第三方通道 | 普通 `dispatch()` 不变；只实现 `DirectAfterDispatchChannel` 的第三方通道得到 null 绑定并回退 `CoreAfterBridge` |

后端里只有 `ChainAccessor101/102` 引用新 SPI 类型，且只在探测成功后的冷路径实例化；路由与 hooker 只持有
`Function` 与标记，保持“热方法只引用旧 core 也有的类型”。后端 consumer-rules 新增对 `CoreChainBridge`、
`ChainDispatchChannel`、`spi.ChainAccessor` 的 keep（三者都参与按名查找，只保留其中一部分会被判为不一致的
core）；`device-benchmark-module-102:assembleRelease` 的 mapping 确认三者保名。

保留的规则：borrow/frame 寿命与线程检查、致命事件处理、标记转义、at-most-once proceed、`MUTABLE_ARGS`/`AROUND`
首次访问才物化、`READ_ARGS`/`ENTRY_SNAPSHOT` 入口物化、视图按声明个数定长且越界抛异常。ADR-0001 §2（视图对象
不再创建、读取范围）与 §6（新传输）已同步。

新增/修改测试：`DirectPlanTransportTest` 每个场景改为跑三种传输（flyweight、direct-after、dispatch），并新增
“过期借用与跨线程在三种传输上都抛合同异常、视图越界”“各形状的物化次数”；`CoreChainBridgeTest`；后端
`ChainArguments10xTest` 新增访问器单测、视图越界、仅 direct-after 的第三方通道回退、`ChainBridgeLookup`
三种 core 判定；`LibXposed10xBackendTest` 改为断言选中 flyweight 路由。

### 2.3 基准修正

`RegistrationContentionBenchmark` 初版让每个线程复用一个链节点。节点每次 `proceed` 都被写；GC 把这些长寿节点
复制到相邻位置后，它们在线程间伪共享，同一配置的不同 fork 相差最多 5 倍（与计数无关，融合常量路径不写节点
因而不受影响）。改为每次调用新建节点（与 Vector 一致）后结果稳定；该修正在基线测量之前提交（c889f33）。

## 3. JMH 结果

方法：基线类路径取 c889f33（旧 core + 修正后的基准），最终类路径取本轮 HEAD，二者交替运行（A/B 相邻，
第二轮倒序）；`-wi 3 -i 5 -w 1s -r 1s -prof gc`，ns/op 为合并后所有测量迭代的中位数。主机同时有其他工作树的任务，
负载 3.3–9.5，单元格噪声常达 ±10%，B/op 为确定值。旧 = 旧 core + GET_ARG 后端策略（第 1 轮后端），
新 = 新 core + FLYWEIGHT（本轮后端），另列 新 core + GET_ARG（旧后端在新 core 上）。
**标注：主机 JDK 17 x86-64 微基准，不是 ART/设备证据。**

### 3.1 MEGA（回调调用点已多态）

`*` 行为 6 fork（n=30），其余 2 fork（n=10）。

| 形状 | 旧 ns | 新 ns | 新 core+旧后端 ns | 旧 B/op | 新 B/op | 新 core+旧后端 B/op |
|---|---|---|---|---|---|---|
| 单 before NO_ARGS | 27.1 | 23.6 | 25.3 | 88 | 64 | 88 |
| 单 before READ_ARGS_VIEW | 33.4 | 32.1 | 36.3 | 112 | 64 | 112 |
| 单 before READ_ARGS `*` | 37.8 | 36.2 | 38.1 | 112 | 88 | 112 |
| 单 before ENTRY_SNAPSHOT `*` | 49.0 | 48.7 | 49.7 | 136 | 112 | 136 |
| 单 before MUTABLE_ARGS + set `*` | 32.0 | 31.7 | 32.7 | 112 | 88 | 112 |
| 单 after | 38.2 | 36.3 | 37.7 | 120 | 96 | 120 |
| before + after | 46.3 | 40.2 | 42.5 | 168 | 144 | 168 |
| before + after（READ_ARGS）`*` | 54.3 | 54.4 | 60.7 | 192 | 168 | 192 |
| AROUND 一次 proceed | 32.4 | 28.6 | 29.8 | 112 | 88 | 112 |
| 类型化 beforeArgs | 34.9 | 32.2 | 34.8 | 112 | 64 | 112 |
| 类型化 beforeMutableArgs | 31.7 | 31.0 | 32.7 | 112 | 88 | 112 |

新 core + 旧后端在 before + after（READ_ARGS）上高 6 ns，来自 6 个 fork 中的两个离群 fork（中位 fork 54.0 / 57.6，
基线 54.5 / 53.2），无法与噪声区分；其 B/op 与旧 core 完全相同。

### 3.2 MONO（单形状，HotSpot 可标量替换；结果随 fork 的逃逸分析结果双峰，2 fork）

| 形状 | 旧 ns | 新 ns | 旧 B/op | 新 B/op |
|---|---|---|---|---|
| 单 before NO_ARGS | 18.7 | 15.9 | 12（0/24 两 fork） | 0 |
| 单 before READ_ARGS_VIEW | 27.2 | 16.3 | 24 | 0 |
| 单 before READ_ARGS | 30.4 | 30.2 | 24 | 24 |
| 单 before ENTRY_SNAPSHOT | 41.9 | 42.4 | 48 | 48 |
| 单 before MUTABLE_ARGS + set | 30.5 | 33.2 | 112 | 88 |
| 单 after | 32.8 | 31.5 | 120 | 96 |
| before + after | 36.1 | 39.3 | 120 | 96 |
| before + after（READ_ARGS） | 56.0 | 44.9 | 144 | 120 |
| AROUND 一次 proceed | 52.0 | 53.4 | 112 | 88 |
| 类型化 beforeArgs | 27.7 | 17.9 | 24 | 0 |
| 类型化 beforeMutableArgs | 32.3 | 31.0 | 112 | 88 |

MONO 只说明 HotSpot 在单形状下能做什么，不外推到 ART（见第 1 轮 §4）。

### 3.3 关闭逃逸分析（`-XX:-DoEscapeAnalysis`，MONO）B/op：ART 代理

| 形状 | 旧 core + GET_ARG | 新 core + GET_ARG | 新 core + FLYWEIGHT |
|---|---|---|---|
| 单 before NO_ARGS | 88 | 88 | 64 |
| 单 before READ_ARGS_VIEW | 112 | 112 | 64 |
| 单 before READ_ARGS | 112 | 112 | 88 |
| 单 before ENTRY_SNAPSHOT | 136 | 136 | 112 |
| 单 before MUTABLE_ARGS + set | 112 | 112 | 88 |
| 单 after | 120 | 120 | 96 |
| before + after | 168 | 168 | 144 |
| before + after（READ_ARGS） | 192 | 192 | 168 |
| AROUND 一次 proceed | 112 | 112 | 88 |
| 类型化 beforeArgs | 112 | 112 | 64 |
| 类型化 beforeMutableArgs | 112 | 112 | 88 |

归因：每个建 frame 的调用省掉 `Chain10xInvocation`（24 B）；`READ_ARGS_VIEW` 再省 `ArgumentView`（24 B）。
旧后端在新 core 上 B/op 不变。剩余的 64 B 是 frame 本身（JOL：HotSpot 64 B）；单 after 另有 32 B 结果槽。

### 3.4 单注册多线程扩展（`RegistrationContentionBenchmark`，2 轮交替，n=10）

每次调用含一个模型链节点；B/op 含该节点（融合常量路径被 JIT 消除为 0）。

| 形状 | 线程 | 旧 ns/op | 新 ns/op | 旧 B/op | 新 B/op |
|---|---|---|---|---|---|
| 融合常量 | 1 | 12.0 | 12.1 | 0 | 0 |
| 融合常量 | 2 | 69.2 | 12.9 | 0 | 0 |
| 融合常量 | 4 | 187.5 | 18.6 | 0 | 0 |
| 融合常量 | 8 | 534.4 | 28.9 | 0 | 0 |
| 单 before | 1 | 24.8 | 22.5 | 136 | 112 |
| 单 before | 2 | 111.8 | 25.9 | 136 | 112 |
| 单 before | 4 | 223.7 | 30.5 | 136 | 112 |
| 单 before | 8 | 434.4 | 47.6 | 136 | 112 |
| AROUND | 1 | 28.0 | 26.9 | 160 | 136 |
| AROUND | 2 | 104.8 | 26.5 | 160 | 136 |
| AROUND | 4 | 265.4 | 37.9 | 160 | 136 |
| AROUND | 8 | 439.8 | 54.4 | 160 | 136 |

8 线程跑在 4 个物理核上（SMT），所以 8 线程的每操作时间约为 4 线程的 1.5–2 倍属于预期。单线程融合常量另跑
12 fork：旧 10.45 ns，新 10.20 ns（fork 中位数的中位数）。

### 3.5 独立计数器对照（`InFlightCounterBenchmark`，状态读 + 加 + 状态再读 + 减）

| 线程 | AtomicInteger | LongAdder | CAS 失败才膨胀 | 观察到重叠才膨胀（采用） |
|---|---|---|---|---|
| 1 | 10.2 | 18.1 | 14.3 | 10.3 |
| 2 | 70.9 | 21.4 | 10.1 | 10.1 |
| 4 | 188.0 | 22.6 | 17.1 | 15.0 |
| 8 | 371.3 | 27.6 | 24.6 | 24.6 |

1 线程一列为 6 fork 中位数；其余为单 fork。

### 3.6 同目标多回调：N 个注册 vs 一个 spec 带 N 个回调（`SameTargetFusionBenchmark`）

每次调用新建根节点；SEPARATE 每个注册一层 hooker（新链节点 + SDK frame + 租约），ONE_SPEC 一层 hooker、
一个 frame、每个回调一个借用（单回调时 frame 即借用）。ns 为 EA 开启时交替单 fork；B/op 为关闭 EA。

| N | SEPARATE before 旧/新 ns | 旧/新 B/op | ONE_SPEC before 旧/新 ns | 旧/新 B/op | SEPARATE after 旧/新 B/op | ONE_SPEC after 旧/新 B/op |
|---|---|---|---|---|---|---|
| 1 | 36.6 / 36.8 | 184 / 160 | 29.8 / 29.6 | 184 / 160 | 216 / 192 | 216 / 192 |
| 2 | 58.2 / 67.9 | 320 / 272 | 38.1 / 31.9 | 232 / 208 | 384 / 336 | 264 / 240 |
| 4 | 130.5 / 130.8 | 592 / 496 | 39.8 / 39.3 | 280 / 256 | 720 / 624 | 312 / 288 |
| 8 | 265.9 / 236.5 | 1136 / 944 | 55.8 / 56.7 | 376 / 352 | 1392 / 1200 | 408 / 384 |

after 的 ns：SEPARATE 8 个 285.7 / 264.2，ONE_SPEC 8 个 70.0 / 67.6。每多一个独立注册多 112 B（链节点 48 +
frame 64；after 再加结果槽 32）与约 25–30 ns；同一 spec 每多一个回调只多一个 24 B 借用与一次回调调用。

## 4. 同目标融合（仅研究，未实现）

数字：8 个 before 写成 8 个注册比写成一个 spec 慢约 4 倍、分配多 2.7 倍；N 越大差距越大。但“自动把同目标的
多个注册合成一个 hooker”不是合同中立的变换，差异如下：

1. 早退：链式注册中，第 k 层设置早退结果后，内层 k+1..N 的 before 与 after 都不运行；一个 spec 中早退只停止
   后续 before，**所有** after 仍观察该结果（ADR-0001 §3）。
2. before 失败：链式时第 k 层 before 抛出，对外层 1..k-1 而言是“下游失败”，外层 after 会运行并看到它；一个
   spec 中 before 失败则不运行任何 after。
3. 形状：每个注册有自己的 `ParameterAccess`；合并后若用最宽形状，`READ_ARGS_VIEW` 回调可强转写参数，违反
   “强转不能扩大形状”；`ENTRY_SNAPSHOT` 在链式中是“进入该层时”的槽，而不是整条调用的入口。
4. 错误策略：`FRAMEWORK_PROTECTIVE` 由框架按层处理（崩溃于 proceed 前则跳过该层、之后则恢复下游结果），
   合并后框架只看到一层，无法逐成员委托。
5. 优先级与交错：框架按优先级排序所有模块的 hook；另一模块的 hook 可能排在我们的两个注册之间，合并会改变
   全局顺序。同优先级的先后也由框架决定。
6. 生命周期：每个注册独立 close/replace、独立 `CloseReport`（入口恢复、在途数）、独立幂等与冲突键、进程过滤
   与能力要求。合并后单个成员 close 时共享入口并未恢复，按现有隔离规则会报告 `NOT_RESTORED` 并拒绝同 ID
   重装。
7. around：多个 around 注册是嵌套 continuation，一个 spec 只允许一个 around。

若要做，建议是显式 opt-in 的“组”而不是自动合并：

- 新的组规格（属于 hook-api，由负责人决定），成员必须同目标、同 owner、同优先级、`PROPAGATE`、同进程过滤与
  能力；不接受 around 与常量/条件替换成员。组是一个逻辑注册：一个 HookId、一个 `HookRegistration`、一个
  `CloseReport`、一个在途计数；成员不能单独 close，换成员即对组做原子 replace（102 `replaceHook`，101 SDK 内
  通道切换），沿用现有替换合同。
- 组内执行按嵌套语义模拟链：成员 k 的 before 运行后才算“进入”，after 只对已进入的成员按逆序运行；早退与
  before 失败按“对外层是下游结果”传递；每个成员拿到按自己形状收窄的借用，`ENTRY_SNAPSHOT` 在该成员进入时复制。
  这样外部可见行为与“同优先级、相邻的 N 个注册”一致，但仍不等价于与其他模块交错的情形，必须在文档中写明。
- 代价估算：N 个成员 = 1 层 hooker + 1 个 frame + N 个借用（每个 24 B）+ N 个可能的快照；与 ONE_SPEC 行相当。

现在已经可用、且合同清楚的做法：同一组件在同一目标上的多个回调，若共享 ID 生命周期、形状与策略，直接写进
一个 `HookSpec`（多个 `before`/`after`），接受上面第 1、2 条的语义。

## 5. 风险

- 在途数语义从“原子快照”放宽为“非原子、不为负、静止时精确”。依赖并发中精确值的调用方（仓库内没有）会看到
  不同结果；已在 ADR §5 写明。合成的回绕值不再以负数报告。
- 计数格只增不减：曾经观察到重叠（含同线程重入）的注册永久多约 2.2 KB（8 核）。两个线程散列到同一格时仍有
  二路争用，不会重新散列（重新散列会破坏同位置加减）。
- `Thread.getId()` 可被覆盖：覆盖成不稳定值时报告可能短暂偏差，`sum()` 以饱和兜底；JDK 19+ 下有弃用警告，
  已用 `@SuppressWarnings("deprecation")`，并已在 JDK 25 + `-Xlint:all -Werror` 下编译验证。
- flyweight 路径下后端访问器的普通异常（如 `getArg` 抛 `ClassCastException`）在回调读取时出现，与第 1 轮视图
  一致；`READ_ARGS_VIEW` 的越界检查改在访问器内，范围仍是声明个数。
- 旧 core + 新后端时，`ChainAccessor101/102` 实现了缺失的接口，只在探测成功后才实例化；ART 校验器对引用它的冷
  方法可能记软失败，热方法不受影响。真实 ART/框架未验证。
- 多线程基准在 4 核 SMT 主机上测得，手机大小核的缓存行为与此不同；主机负载很高，ns 噪声常达 ±10%。

## 6. 下一轮

- 设备上用 B01–B06/B08 复测 flyweight 与在途计数（尤其多 binder 线程命中同一 hook 的场景）。
- frame 仍有 64 B：可评估把 `proceedState`/`proceedOutcome`、`earlyResult`/`earlyThrowable` 合并，或为 NO_ARGS
  单 before 提供不建 frame 的专用通道（需证明借用寿命规则不变）。
- 单 after 的独立结果槽（32 B）：在保持 `invocation != result` 的前提下评估由 frame 内嵌。
- 若负责人决定提供显式组规格，按 §4 的嵌套语义实现并补齐与“相邻 N 个注册”的差分测试。
