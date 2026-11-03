# 诊断传输优化 · 第二轮

范围：第一轮“下一轮”清单中的四项：观察等待按到期时刻评估、单条 JSONL 的非 ASCII 尺寸估算、切片记录
写入、`TraceBatchDispatcher` 的每次提交分配。线格式（JSONL v1、MHB1 批帧、MHTC 压缩帧、Binder v2
Parcel 布局）全部逐字节不变；新行为都需要显式选择，默认路径不在 Hook 线程上增加任何工作。

**证据标注**：下列数字全部来自宿主 JDK 17.0.2、x86-64（8 核，与其他任务共享，负载 2–9），不是 ART、
Binder 或设备证据。

## 1. 测量方法

- **JMH 同轮 A/B**：`Round1Transport`、`Round1Poller` 是第一轮实现（main @ 94f6f63）的原样副本，只内联了
  包内的致命错误判断；与当前实现在同一次 JMH 中运行（`-f 2 -wi 3 -i 5 -w 1s -r 1s -prof gc`）。B/op
  是确定的；时间受主机负载影响，同一份代码在不同 fork 间可差 30%。
- **交错 CPU 时间 A/B**（新增 `InterleavedAb`）：两种实现在同一 JVM 中按 AB、BA 交替运行约 1 ms 的块，
  用线程 CPU 时钟计时（不计被调度走的时间），报告每块比值 B/A 的中位数，每个用例单独起 3 个 JVM。
  校准：同一代码对同一代码为 0.98–1.00；两份**内容相同**的 ASCII 路径（ascii0／ascii8）为 0.91–1.02；
  极小的 none0（约 300 ns）在不同 JVM 间呈双峰（0.72 或 1.11），不作结论。可以把约 ±8% 视为 JIT 差异
  带来的噪声下限。
- **延迟**：`PollerLatencyBenchmark`（SampleTime，单线程 `ScheduledThreadPoolExecutor`，内存 transport，
  每次采样前在计时外随机停顿一个轮询周期内的时长），等待路径用 `-r 5s -i 6`，每格约 2000–3000 个样本。

## 2. 参考资料

- AOSP `Parcel.java`：`writeByteArray(byte[])` 的实现就是以偏移 0、整个长度调用
  `writeByteArray(byte[], int, int)`，后者检查区间后交给 native 复制，所以两种写法布局相同。
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Parcel.java>
- `ScheduledThreadPoolExecutor`（JDK 17 文档）：延迟任务不早于到期时间执行，但不保证何时开始；默认情况下
  已取消的任务要等到原定延迟过去才离开队列，可能造成无界滞留，除非打开 `setRemoveOnCancelPolicy`。
  <https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ScheduledThreadPoolExecutor.html>
- STPE 源码（jdk17u）：一次性任务的触发时刻为 `System.nanoTime() + delay`，按触发时刻排在
  `DelayedWorkQueue` 堆中，取消只在 `removeOnCancel` 为真时才从堆中删除。
  <https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/util/concurrent/ScheduledThreadPoolExecutor.java>
- Varghese & Lauck，*Hashed and Hierarchical Timing Wheels*（SOSP 1987）：用循环缓冲区可以 O(1) 启动、
  停止和维护定时器；适合同时存在大量定时器的场景。
  <https://www.cs.columbia.edu/~nahum/w6998/papers/sosp87-timing-wheels.pdf>
- Netty `HashedWheelTimer`：按固定 tick 推进的近似定时器，文档说明 I/O 超时通常不需要精确，默认 tick
  为 100 ms。<https://netty.io/4.0/api/io/netty/util/HashedWheelTimer>
- Martin Thompson《Smart Batching》：数据到达即发送、发送期间积累的数据组成下一批，比靠超时凑批延迟更低。
  <https://mechanical-sympathy.blogspot.com/2011/10/smart-batching.html>
- Disruptor `endOfBatch`：事件处理器知道当前事件是否为本批最后一个，适合在批尾统一刷新 I/O。
  <https://github.com/disruptor-net/Disruptor-net/wiki/Event-Handlers>；Log4j2 异步日志同样把 I/O 放到
  后台线程按批处理。<https://logging.apache.org/log4j/2.x/manual/async.html>
- simdutf README：提供一组“先快速扫描、求出转码后长度”的函数，供调用方一次分配好输出缓冲区。
  <https://github.com/simdutf/simdutf/blob/master/README.md>
- `CountDownLatch` 与 `AbstractQueuedSynchronizer` 源码（jdk17u）：`await()` 即
  `acquireSharedInterruptibly(1)`，`await(t, u)` 即 `tryAcquireSharedNanos(1, u.toNanos(t))`；后者先检查
  中断，再尝试一次获取，超时不大于 0 时立即返回 false。
  <https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/util/concurrent/CountDownLatch.java>、
  <https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/util/concurrent/locks/AbstractQueuedSynchronizer.java>

以上内容均为转述（Content was rephrased for compliance with licensing restrictions）。

## 3. 改动与合同依据

| # | 改动 | 合同依据 |
|---|---|---|
| a | `TraceBatchPoller.WaitExpiry.DEADLINE`（新增 `startDraining(..., Clock, WaitExpiry)` 重载）：评估结束时若窗口仍开着且未到期，用 `executor.schedule` 安排一次普通评估，延迟为“观察时刻 + 等待 − 本次时钟读数”（评估中发送过批次时重新读一次时钟） | 默认 `NEXT_POLL` 与原工厂完全相同；只有评估会安排，所以只在执行器线程上发生。门槛、排空上限、BUSY／EMPTY、失败暂停不变，等待仍从“观察到”起算 |
| a' | 有界与不丢失：每个 poller 同时最多一个待执行的到期评估；每个窗口最多安排一次；窗口提前关闭不取消它，它到时作为普通评估运行并为当前窗口安排；遇到正在进行的评估则记下目标，那次评估结束时若窗口仍开着就提交一次后续评估 | 不取消就不会在 STPE 队列中堆积已取消任务（见上方 STPE 文档）；“每窗口一次”避免时钟慢于执行器时的忙循环；后续评估走 `requestPoll` 的提交路径但**不**启用其积压排空行为 |
| a'' | 拒绝记入 `schedulingFailure()` 且不暂停；暂停、恢复（新代次开始时）和关闭以 `cancel(false)` 取消待执行的到期评估并释放名额；旧代次的任务运行时什么也不做 | 与 `requestPoll` 的拒绝规则一致；代次检查沿用周期任务的做法。评审发现：`resume()` 先置 ACTIVE、稍后才在 `schedule()` 中推进代次，期间的评估会以旧代次占住名额，使 DEADLINE 静默退化为 NEXT_POLL；已改为推进代次时一并释放，并加了 4000 轮竞争的回归测试（去掉修复后第 114／370 轮即失败） |
| b | 单条 JSONL 的非 ASCII 尾部：若剩余容量连每单元 6 字节的最坏情况都放得下，一遍写完；否则按“每单元 3 字节 + 12 字节余量”分块写入可确定放得下的部分，转义单元用掉余量后结束本块；不足 32 个单元时才扩容。尾部写完后把记录估算的终点按实际差额校正，之后的扩容直接到校正后的大小，而不是翻倍 | 字节不变（`JsonSingleRecordTailTest` 在 6 万个随机串、各种容量下与批帧编码器逐字节比较，`TraceWireDifferentialTest` 与第 0 轮编码器比较）；getter 次数与顺序不变；批帧（有界）编码器的路径一字未改 |
| c | `JsonLineTraceTransport.SlicedRecordWriter`（继承 `RecordWriter`，新增 `writeRecord(byte[], int, int)`，整数组方法默认转发）。`JsonLineTraceTransport.send`、`BatchTraceTransport.sender`、codec／会话／串行会话的 `batchSender` 遇到它时交出私有编码缓冲区的切片，省去最后的裁剪复制；codec 直接压缩或 RAW 编码这段区间 | 一次调用必须等价于对复制出的区间调用 `writeRecord`；缓冲区每次调用私有、调用后不再写入；只实现 `RecordWriter` 的写入器照旧收到精确长度的数组；帧和 `Decision` 只取决于区间内字节 |
| c' | `BinderTrace102.sender` 返回实现该接口的私有类，用 `Parcel.writeByteArray(buffer, offset, length)`；切片先按数组边界和记录上限检查再取 Parcel。`ModuleRemoteTraceWriter102` 也实现它，打开流之前检查切片 | 返回类型仍是 `RecordWriter`（二进制兼容）；AOSP 中整数组重载即以偏移 0 调用切片重载，Parcel 布局不变；空数组、超限、null 的异常类型与消息不变 |
| d | `TraceBatchDispatcher`：交给执行器的任务与完成闩合并为一个私有对象 `Completion extends AbstractQueuedSynchronizer implements Runnable`；`get` 调用与 `CountDownLatch(1)` 完全相同的 AQS 方法 | 中断、超时、`null` 单位的异常顺序、`Long.MAX_VALUE` 超时都由同一段 AQS 代码处理。状态只能由 Attempt 的终态转换清零；从队列里拿到任务的代码调用公开的 `releaseShared` 只会得到“尚未完成”，不能提前放行等待者。AQS 可序列化而原方法引用不可：`writeObject` 抛 `NotSerializableException`，序列化任务的执行器仍在 `execute` 中失败（评审发现，已有对照测试） |

## 4. JSONL 与切片写入

JMH 同轮 A/B（`AbRound2EncodeBenchmark`，round1 → current，ns/op 与 B/op）。`encode` 返回精确长度数组；
`send` 中 round1 把复制交给 `RecordWriter`，current 把切片交给 `SlicedRecordWriter`：

| 形状 | encode ns | encode B | send ns | send B |
|---|---:|---:|---:|---:|
| none0 | 218 → 245 | 704 → 704 | 351 → 228 | 704 → 432 |
| ascii0（512 字符 ASCII 消息） | 710 → 695 | 1792 → 1792 | 696 → 1027 | 1792 → 976 |
| ascii8 | 2466 → 1990 | 4072 → 4072 | 2051 → 2118 | 4072 → 2232 |
| unicode0（400 字符中英混排含转义） | 1282 → 987 | 3368 → 2952 | 1132 → 996 | 3368 → 2048 |
| unicode8 | 2940 → 2873 | 7808 → 6160 | 2778 → 2457 | 7808 → 4232 |
| cjk0（512 个汉字） | 2121 → 1931 | 8272 → 4776 | 2395 → 1700 | 8272 → 2936 |

时间的误差为 ±3%–±35%（详见日志）。send/ascii0 一格在两个 fork 中都偏慢，交错测量中该形状为
0.85–0.95，判断为 fork 间噪声。

交错 CPU 时间（B/A，3 个 JVM 各自的中位数）：

| 用例 | B/A | 说明 |
|---|---|---|
| encode unicode0 / unicode8 / cjk0 | 0.84–0.92 / 0.90–0.94 / 0.81–0.85 | 另用一份与当前实现内容相同、同样编译在基准模块中的副本复测：0.74–0.88 / 0.87–0.95 / 0.84–0.85 |
| encode unicode32 | 0.90–1.05 | 记录估算已足够，分配不变 |
| encode ascii0 / ascii8（代码未变） | 0.93–0.95 / 0.94–0.99 | 噪声带 |
| send（切片）none0 / ascii0 / ascii8 / unicode0 / unicode8 / cjk0 | 0.69–0.71 / 0.85–0.95 / 0.91–0.93 / 0.82–0.87 / 0.86–0.90 / 0.84–0.89 | |
| 批帧发送（切片）1 / 16 / 128 条 | 0.90–0.95 / 0.91–0.92 / 0.84–0.86 | 复制路径（代码未变）16 条：0.97–0.99 |

`JsonLineEncodeBenchmark` 的 unicode1k／0 帧与 8 帧两格从第一轮的 3384／7816 B 降到 2960／6160 B，均低于
第 0 轮的 3336／6848 B；其余七格（含 unicode1k／32 帧，记录估算本已足够）B/op 不变。

批帧发送（`AbBatchSendBenchmark`，round1 / current 复制 / current 切片）：1 条 422／416／408 ns、
1064／1064／640 B；16 条 8000／7860／7301 ns、23120／23120／16728 B；128 条 65779／66575／60803 ns、
187360／187360／136392 B。压缩批量发送（`CompressionBenchmark.batchSend`，无会话）现为 1 条 2280 B、
16 条 42128 B；省去的那次复制是一个与明文批帧等长的数组，该基准没有第一轮副本对照。

试过并放弃（交错测量，均与第一轮副本对比）：

- **先整段扫描求精确长度再分配**（任务清单中的方案）：分配与最终方案相当（unicode0 2800 B），但扫描
  覆盖整个尾部，CPU 时间 +9%～+14%（unicode0、cjk0）；无分支的长度公式反而更慢。
- **边写边查、容量不足时在循环内扩容**：循环里重新赋值数组后 C2 保留了逐次边界检查，+30%～+40%。
- 最终方案的写循环内从不扩容，数组对 JIT 是循环不变量；只在尾部超出剩余容量时才扫描。

## 5. 等待路径延迟（µs）

单条记录、低于门槛，`PollerLatencyBenchmark`。`round1` 为第一轮 poller 副本，`poll` 为当前默认
（`NEXT_POLL`），`deadline` 为 `WaitExpiry.DEADLINE`。配置为“轮询 ms／等待 ms／门槛”。

| 配置 | 实现 | 唤醒 | p50 | p90 | p99 | p99.9 |
|---|---|---|---:|---:|---:|---:|
| 5/20/16 | round1 | 周期 | 22905 | 25002 | 26640 | 31079 |
| 5/20/16 | round1 | 信号 | 22643 | 24674 | 25199 | 28647 |
| 5/20/16 | poll | 周期 | 23167 | 25231 | 29256 | 35913 |
| 5/20/16 | poll | 信号 | 22675 | 24674 | 25231 | 28576 |
| 5/20/16 | deadline | 周期 | 22675 | 24642 | 25174 | 25588 |
| 5/20/16 | deadline | 信号 | **20120** | **20316** | **20499** | 21269 |
| 10/15/16 | round1 | 周期 | 25199 | 29196 | 30114 | 30789 |
| 10/15/16 | round1 | 信号 | 20087 | 24216 | 25100 | 25410 |
| 10/15/16 | poll | 周期 | 25264 | 29164 | 30147 | 30409 |
| 10/15/16 | poll | 信号 | 20218 | 24183 | 25075 | 25297 |
| 10/15/16 | deadline | 周期 | 20054 | 24111 | 25133 | 25389 |
| 10/15/16 | deadline | 信号 | **15155** | **15335** | **15516** | 16205 |

评审修正（728bdc6）后复测 `DEADLINE`＋信号：5/20/16 为 20152／20316／20578／20852 µs，10/15/16 为
15172／15352／15647／21184 µs，与上表一致。

结论：默认路径与第一轮一致。`DEADLINE` 加信号时延迟约为“等待 + 0.1～0.5 ms”；只用周期任务时窗口
仍在捕获后的第一次轮询打开，省掉的是结束一侧的量化（10/15/16 的 p50 25.3 → 20.1 ms，等于从“等待
向上取整到轮询间隔”变为“等待 + U(0, 轮询)”）。

等待为 0 的配置（门槛路径）同轮对照，顺序为 poll、round1、poll、round1：2/0/1 信号 p50 23／25／24／25 µs，
p99 255／255／269／258 µs；10/0/1 信号 p99 377／363／347／388 µs。一次在负载 6–7 时先跑 round1 后跑当前
版本的测量曾出现 ms 级尾部只落在后者，调换顺序后消失，判断为负载漂移。Hook 线程上信号的代价
（`CaptureSignalCostBenchmark`）为无信号 172 ns、48 B，有信号 377 ns、67–129 B（随合并比例波动，
第一轮约 78 B），默认路径没有新增工作。

为什么不用时间轮：每个 poller 同时最多一个到期评估，向执行器队列只贡献最多 3 个任务（周期、到期、
请求），STPE 堆操作的代价可以忽略；Varghese & Lauck 与 Netty 的时间轮针对大量定时器，并按 tick 近似，
反而会重新引入量化误差。

## 6. TraceBatchDispatcher

JMH 同轮 A/B（`AbDispatcherBenchmark`，每个参数独立 JVM）：

| 执行器 | 结果 | round1 ns | current ns | round1 B | current B |
|---|---|---:|---:|---:|---:|
| 内联 | EMPTY | 100.9 ± 3.5 | 101.7 ± 7.1 | 96 | 80 |
| 内联 | ACCEPTED | 283 ± 53 | 253 ± 103 | 216 | 200 |
| 延后（任务逃逸） | EMPTY | 113.1 ± 9.9 | 105.2 ± 4.7 | 112 | 80 |
| 延后（任务逃逸） | ACCEPTED | 236 ± 54 | 222 ± 44 | 232 | 200 |

每次提交从 4 个对象（Attempt、方法引用、CountDownLatch、其同步器）减为 2 个，少 32 B。交错测量中
延后执行器为 0.97–1.02；内联执行器在 3 个 JVM 中有 2 个出现 round1 55 ns 对 current 98 ns：两种实现
共享同一段 AQS 代码，同一 JVM 内的类型剖面和 C2 编译顺序不同，第一轮的版本偶尔被编译得更好，独立 JVM
的 JMH 中两者相同。真实执行器中任务总会逃逸，因此保留。

差分测试 `TraceBatchDispatcherDifferentialTest` 以第一轮原样副本 `LegacyTraceBatchDispatcher` 为准：
6000 个随机脚本（8 种执行器行为含内联、保留后抛错、拒绝、运行后抛错、非致命 Error、致命错误、重复运行；
6 种 transport 结果；先置中断的 `get`、超时为负／0／1 ns／1 ms／`Long.MIN_VALUE`、`null` 单位、取消、
状态查询、引用释放），逐步比较返回值、异常类型与原因、调用后的中断状态；另比较 9 种并发等待（无限期、
限时、`Long.MAX_VALUE` 纳秒与天、三个等待者、等待中被中断、取消、超时）。把同步器改成永不阻塞的变异
会让当时的四个测试全部失败。

## 7. 风险

- **到期评估**：执行器上每个观察窗口多一次 `schedule`（一个任务对象和一次堆插入）和最多一次额外评估；
  每个 poller 最多一个待执行任务。自定义 `ScheduledExecutorService` 若在 `schedule` 中阻塞或内联执行，
  会阻塞或嵌套在执行器线程上（不会在 Hook 线程上）。窗口提前关闭时到期评估不取消，它会多跑一次空评估。
  使用自定义时钟时到期评估可能早于时钟到期，此时退回周期任务。
- **切片写入**：自定义 `SlicedRecordWriter` 若保留缓冲区或读取区间外字节（可能是被回滚记录的编码），
  会违反合同；SDK 不复制、无法防止。Binder 切片路径只做了代码层面的核对与参数检查测试，宿主无法执行
  Parcel，需设备验证。
- **JSONL**：分块路径多了少量分支；对每个字段都含大量非 ASCII 的记录，扩容次数仍为对数级（第二次起至少
  1.5 倍）。只验证了 HotSpot C2，ART 的编译结果不同。
- **Dispatcher**：交给执行器的对象现在是一个 AQS 子类；检查队列的代码能看到它的公开查询方法和带状态的
  `toString()`，但不能改变完成状态，也不能把它序列化。
- 所有结论都只来自宿主 JVM。

## 8. 评估后未做

- **接收端的切片批写入**（`BatchWriter` 收到帧数组＋偏移表）：`receiver` 的合同是每条记录为独立数组、
  输入只读一次以防并发修改；只有 `compressedReceiver` 的明文是私有的。模块侧随后是文件 I/O，省下的
  逐条复制相对很小，本轮不加接口。
- **时间轮**：见第 5 节。
- **ASCII 长度预扫描**：ASCII 记录本来就按精确上界一次分配，再扫描只会更慢。

## 9. 下一轮

- 在设备上验证 Binder 切片写入（`writeByteArray(b, off, len)`）、到期评估的唤醒延迟与功耗。
- `compressedReceiver` 的私有明文可以评估切片交付给 `ModuleRemoteTraceWriter102`，与文件 I/O 一起测。
- 到期评估可考虑与 `requestPoll` 合并为一个“下一次评估时刻”，进一步减少空评估。
- 编码缓冲区目前每次调用新建；若在 poller 的单一执行线程上复用，需要新的线程归属合同。
