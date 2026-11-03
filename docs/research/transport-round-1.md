# 诊断传输优化 · 第一轮

范围：捕获 → 队列 → 批帧 → 编码／压缩 → Binder／远程文件。线格式（JSONL 记录、MHB1 批帧、MHTC
压缩帧、Binder v2 Parcel 布局）全部保持逐字节不变；新增能力均为显式选择。

**证据标注**：下列数字全部来自宿主 JDK 17.0.2、x86-64（Xeon Platinum 8488C，8 核）上的 JMH，
不是 ART、Binder 或设备证据。测量期间同一主机上还有其他任务（负载 6–10），分开运行的前后结果
噪声较大，因此主要结论以“同一轮 A/B”（旧实现副本与新实现在同一次 JMH 中交替运行，`-f 2`）为准。

## 1. 参考资料

- AOSP Binder 线程模型：同步事务阻塞到收到回复；oneway 事务交给内核后即返回，同一节点上的
  异步事务被串行化；不要在持锁时发起 Binder 调用；libbinder 默认 15 个线程。
  <https://source.android.com/docs/core/architecture/ipc/binder-threading>
- 事务缓冲区：每个进程约 1 MB，由所有进行中的事务共享，即使单个事务不大也可能失败。
  <https://developer.android.com/reference/android/os/TransactionTooLargeException>
- `Binder` 文档：对同一 IBinder 的多次 oneway 调用在对端按原顺序逐个分派。
  <https://developer.android.com/reference/android/os/Binder>
- `Parcel.java`：`writeByteArray(byte[], int, int)` 与整数组版本写出相同布局（长度＋数据），
  由 native 复制进 Parcel；读取可写入已有数组或新建数组。
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Parcel.java>
- `ArrayBlockingQueue`（JDK 17）：单一锁；`drainTo` 可能比反复 `poll` 更高效，向目标集合添加
  失败时元素可能处于任一侧；迭代器为弱一致。
  <https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ArrayBlockingQueue.html>
- `Deflater`（JDK 17）：`reset`／`end` 语义及数组区间输入。
  <https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/zip/Deflater.html>
- JMH `Level.Invocation`：仅适合单次调用超过毫秒级的基准，每次调用都要打时间戳，且有协调遗漏
  风险。<https://github.com/openjdk/jmh/blob/master/jmh-core/src/main/java/org/openjdk/jmh/annotations/Level.java>
- Martin Thompson《Smart Batching》：靠超时凑批会天然增加延迟；更好的做法是数据一到就发送，
  发送期间积累的数据自然组成下一批。<https://mechanical-sympathy.blogspot.com/2011/10/smart-batching.html>
- RFC 896（Nagle）：仍有未确认数据时暂缓发送新小段，靠确认而非定时器合并。
  <https://datatracker.ietf.org/doc/html/rfc896>
- LMAX Disruptor `LiteBlockingWaitStrategy`：生产者仅在消费者标记“需要唤醒”时才发信号
  （`getAndSet` 合并唤醒）。
  <https://github.com/LMAX-Exchange/disruptor/blob/master/src/main/java/com/lmax/disruptor/LiteBlockingWaitStrategy.java>
- Log4j2 异步日志：`Timeout` 等待策略周期醒来以防漏掉通知；`Block`／`Sleep`／`Yield` 在 CPU 与
  延迟间取舍；应用线程不做 I/O。<https://logging.apache.org/log4j/2.x/manual/async.html>
- Android 日志：logd 维护固定的环形缓冲区，liblog 经 socket 发给 logd。
  <https://developer.android.com/tools/logcat>

以上内容均为转述（Content was rephrased for compliance with licensing restrictions）。

## 2. 改动与合同依据

| # | 改动 | 合同依据 |
|---|---|---|
| a | `JsonLineTraceTransport`：先取 message 和 stack（顺序、次数不变），按 ASCII 上界一次分配；ASCII 连续段无逐字符扩容检查；非 ASCII 尾部按每单元 3 字节预算，`\uXXXX` 转义时再补足 | 字节不变；Throwable getter 仍是 `getMessage` 一次、`getStackTrace` 一次且先后不变；其余输入是 final、无副作用的访问器；每次调用仍新建缓冲区（不跨调用复用） |
| b | `BatchTraceTransport.sender`：记录直接编码进每次调用私有的有界帧缓冲区（初始 512 B，翻倍增长，上限约为帧上限加一次预留）；记录超出帧上限后改为覆写本条字节，getter 照常执行完 | 迭代方式（`iterator/hasNext/next`）、装入判定、首条过大抛错、短前缀确认、帧字节均不变 |
| b' | 接收端：长度字段读入私有长度表，记录直接复制为独立数组，不再先克隆整帧 | 每个输入字节只读一次，验证与交付记录来自同一次读取；输入数组不被保留；记录仍是独立数组 |
| c | `CallbackExceptionTracker`：`toArray` 一次加锁取快照（超过 limit 时截成前缀数组）；确认后一次 `drainTo`，在排空目标中逐个核对对象身份 | 交付锁、BUSY/EMPTY、生产者并发追加、前缀确认、异常时不移除、`ownership lost` 均保留；传给 transport 的仍是不可变、有序、RandomAccess 列表；保留列表的 transport 只引用前缀 |
| d | `TraceBatchPoller.requestPoll()` + `CallbackExceptionTracker.CaptureSignal`（显式选择） | 默认构造的 tracker 无信号，Hook 线程失败时仍只做入队（新增一次 final 字段判空）；请求只触发一次普通评估，门槛／等待／排空／失败暂停不变；不等待 poller 状态锁；执行器内联执行时跳过；拒绝记入 `schedulingFailure()` 且不暂停 |
| d' | tick 中观察窗口字段改由持有 `inFlight` 的评估独占，少一次同步 | 同一时刻只有一个评估持有 `inFlight`，监视器交接保证先后可见 |
| d'' | 用过 `requestPoll` 后，评估达到 `maxBatchesPerTick` 且仍有积压时再请求一次评估 | 由多生产者压力测试发现：否则积压会滞留到下一个周期；从未调用 `requestPoll` 的 poller 行为不变 |
| e | `BoundedTraceCompression`：RAW 解码只读一次帧头、负载直接复制为明文（校验和覆盖这份副本），压缩帧仍先快照；低于阈值的 RAW 编码对任意大小都只复制一次 | 帧字节、Decision、错误信息不变；校验与输出不会因调用方并发修改而分离 |
| e' | `ModuleRemoteTraceWriter102` 实现 `BatchWriter.writeBatch`：先整批校验，一次打开流、按序写入、一次 flush | 逐条 `writeRecord` 不变；部分写入、重试重复、非持久化语义照旧 |

仍须复制、未改的部分：`Encoding.bytes()` 文档承诺返回独立副本；`codec.encode`／`encodingWriter`
承诺快照调用方输入；`BinderTrace102` 发送端必须把记录写进 Parcel，接收端必须把 Parcel 中的
记录复制成独立数组交给 writer（Parcel 在 `onTransact` 返回后回收）；每个事务的 32 字节指纹数组
相对一次 Binder 往返可忽略，Binder 线程并发，不能共享。

## 3. JMH：同一轮 A/B（`-f 2 -wi 3 -i 5 -w 1s -r 1s -prof gc`）

| 基准 | 参数 | 旧 ns/op | 新 ns/op | 旧/新 | 旧 B/op | 新 B/op |
|---|---|---:|---:|---:|---:|---:|
| JSONL 编码 | 无消息、无帧 | 659 ± 167 | 283 ± 88 | 2.3× | 928 | 696 |
| JSONL 编码 | 8 帧 | 3507 ± 1134 | 1838 ± 417 | 1.9× | 6208 | 2976 |
| JSONL 编码 | 1 KB 消息＋32 帧 | 10930 ± 1349 | 7274 ± 1459 | 1.5× | 23656 | 10392 |
| 批帧编码 | 16 条 | 21476 ± 3543 | 10136 ± 2355 | 2.1× | 34064 | 23104 |
| 批帧编码 | 128 条 | 177017 ± 16022 | 99651 ± 5933 | 1.8× | 272080 | 187248 |
| 批帧接收 | 16 条 | 1254 ± 42 | 622 ± 28 | 2.0× | 13160 | 6864 |
| 批帧接收 | 128 条 | 9888 ± 432 | 5228 ± 187 | 1.9× | 104664 | 54352 |
| RAW 解码 | 单条帧 | 88 ± 3 | 47 ± 1 | 1.9× | 872 | 424 |
| 队列快照＋移除 | 16 待发，确认 1 | 2394 ± 2002 | 123 ± 187 | — | 248 | 104 |
| 队列快照＋移除 | 128 待发，确认 1 | 10010 ± 10602 | 182 ± 285 | — | 696 | 552 |
| 队列快照＋移除 | 128 待发，全部确认 | 55014 ± 26389 | 2303 ± 203 | — | 689 | 576 |

队列一项在普通 `ArrayBlockingQueue` 上对比两种算法，旧算法误差极大（迭代器登记与逐个 `poll`
受负载影响明显），只记方向不记倍数。

## 4. JMH：分开运行的前后对比（`-f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc`）

真实 tracker（`TrackerDeliveryBenchmark` 的 `Level.Invocation` 补队列开销计入 B/op）：

| 基准 | 待发 | 前 ns/op | 后 ns/op | 前 B/op | 后 B/op |
|---|---:|---:|---:|---:|---:|
| tryDeliverBatch 全部确认 | 1 | 177 ± 15 | 97 ± 5 | 240 | 120 |
| tryDeliverBatch 全部确认 | 16 | 664 ± 9 | 116 ± 20 | 1016 | 896 |
| tryDeliverBatch 全部确认 | 128 | 4348 ± 140 | 490 ± 51 | 6864 | 6720 |
| 稳态：快照 N、确认 1、补 1 | 128 | 2382 ± 99 | 295 ± 41 | 744 | 624 |

JSONL（含 Unicode 消息）：时间在所有 9 组参数下下降 1.8–2.8×；分配在 7 组下降，Unicode 消息
两组持平或略增（`unicode1k`／0 帧 3336 → 3384 B，`unicode1k`／8 帧 6848 → 7816 B），原因是
按 ASCII 估算后非 ASCII 尾部需要再翻倍扩容。尝试过“按剩余估算扩容”，这两组反而更差（3784／
9768 B），已回退为翻倍。

压缩：低于阈值的单条 RAW 帧 `encode` 110 → 66 ns、1144 → 592 B；`decode` 110 → 59 ns、
1120 → 552 B；配对接收 177 → 119 ns。16 条压缩帧的 deflate／inflate 本身未改，最后一轮该组误差
达 ±35%，不作结论；基线中会话相对无会话的 `encode` 约快 10%、分配减半（14.7 vs 13.2 µs，
17.3 vs 8.9 KB），这一建议保持不变。

## 5. 捕获到确认的延迟

`PollerLatencyBenchmark`（SampleTime，单线程 `ScheduledThreadPoolExecutor`，内存 transport，基准
线程充当 Hook 线程并自旋等待；每次采样前在计时外随机停顿一个轮询周期内的时长）。配置为
`轮询 ms／等待 ms／数量门槛`，`drain 4`、批 64。单位 µs。

| 配置 | 突发 | 唤醒 | p50 | p90 | p99 | p99.9 |
|---|---:|---|---:|---:|---:|---:|
| 2/0/1 | 1 | 周期（基线） | 1027 | 1849 | 2060 | 2241 |
| 2/0/1 | 1 | 信号 | 21 | 53 | 257 | 443 |
| 10/0/1 | 1 | 周期（基线） | 5071 | 9110 | 9961 | 10186 |
| 10/0/1 | 1 | 信号 | 48 | 183 | 423 | 5085 |
| 5/20/16 | 16 | 周期（基线） | 2355 | 4563 | 5028 | 5172 |
| 5/20/16 | 16 | 信号 | 37 | 173 | 279 | 460 |
| 5/20/16 | 1 | 周期（基线） | 22938 | 25133 | 25592 | 26968 |
| 5/20/16 | 1 | 信号 | 22446 | 24642 | 25166 | 25395 |

结论：周期模式的延迟约为 U(0, 轮询间隔)，等待路径为“等待 + 量化”。信号只缩短数量门槛路径
（含门槛 1／等待 0）；单条记录未达门槛时仍按等待时间发送，这是有意保留的语义。信号模式的 p99.9
尾部（数 ms）与周期模式在同一主机上超过轮询间隔的尾部同源，来自负载下的线程唤醒延迟；
多生产者压力测试（1 h 周期、1／4 个执行器线程）确认没有丢失唤醒。

Hook 线程代价（`CaptureSignalCostBenchmark`，连续失败、每次捕获后立即丢弃）：无信号 136–150 ns、
48 B；有信号 416–461 ns、约 78 B。即启用信号后每次失败约多 0.3 µs，只发生在失败路径。

## 6. 评估后未做

- `TraceBatchDispatcher`：每次提交分配 Attempt、CountDownLatch 和方法引用。改用对象监视器可省
  两次分配，但要逐一复现 `CountDownLatch.await` 的中断与超时语义，而提交本身由
  `Executor.execute` 主导，收益与风险不成比例。
- `BinderTrace102`：宿主无法运行 Parcel／Binder，任何改动都无法在本环境验证；预设 Parcel 容量
  和用整数读取指纹带来的节省相对一次同步往返可忽略，且会改变畸形 Parcel 的异常类型。
- 无会话 `codec` 内部复用 Deflater：会改变“无会话不保留 native 状态”的文档承诺；需要复用时用
  `Session`／`SerializedSession`。
- 压缩帧在批帧缓冲区前预留 20 字节以省去最后一次复制：deflate 占主导，节省不足 1%。
- 默认路径的 Hook 线程唤醒：会在 Hook 线程上执行执行器提交，只能显式选择。
- 等待到期的定时评估（在 `observedAt + wait` 处安排一次性评估）：可去掉等待路径的量化误差，但
  需要管理并取消额外的 `ScheduledFuture`，本轮未做。

## 7. 下一轮

- 在设备上（ART，真实 Binder 往返）重测编码、批帧和信号唤醒，尤其是唤醒延迟与功耗；本文数字
  不能外推。
- 等待到期定时评估（见上）；或允许 `requestPoll` 携带“立即发送”提示，供进程退到后台时刷新。
- `RecordWriter` 的切片变体（数组＋偏移＋长度）可让批帧发送直接
  `writeByteArray(frame, 0, size)`，省去最后的裁剪复制；需要新的可选接口，线格式不变。
- JSONL 估算对非 ASCII 消息偏小：可只在消息包含非 ASCII 时做一次 UTF-8 长度扫描。
- 模块侧接收：`compressedReceiver` 与 `ModuleRemoteTraceWriter102.writeBatch` 组合后，可评估在
  文件流上合并一次写入的系统调用数与额外复制的取舍。
