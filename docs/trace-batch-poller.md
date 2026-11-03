# 使用调用方后台执行器轮询批量诊断

`TraceBatchPoller` 是显式启用的可选工具。它在调用方提供的后台 `ScheduledExecutorService`
上按固定间隔查看待发送数量；达到数量门槛，或从当前观察窗口首次看到非空队列起经过指定等待时间，
就调用一次 `tryDeliverBatch`。默认 `start` 每次轮询最多一个批次。SDK 不创建或关闭执行器，不在 Hook
回调中安排任务，也不会将同步 Binder 改成异步 Binder。启动、恢复和关闭都应在 Hook 与 UI
线程之外执行；执行器必须正常调度、不得内联执行到这些线程或静默丢弃任务。

```java
// worker 为调用方创建并管理的后台 ScheduledExecutorService。
// writer 为调用方准备好的、有明确接收确认语义的 RecordWriter。
CallbackExceptionTracker.BatchTransport transport = BatchTraceTransport.sender(
        writer, false, 8, 16 * 1024, 32);
TraceBatchPoller poller = TraceBatchPoller.start(
        worker, tracker, transport, 16, 32, 20, 100, TimeUnit.MILLISECONDS);

// 在合适的后台位置观察；ACCEPTED 是 transport 接受前缀，并不证明持久化。
TraceBatchPoller.State state = poller.state();
Throwable deliveryFailure = poller.lastFailure();
Throwable schedulingFailure = poller.schedulingFailure();
CallbackExceptionTracker.BatchAttempt result = poller.lastAttempt();
// 在后台线程按需观察周期句柄；这不是任务存活或送达证明。
TraceBatchPoller.ScheduleObservation observation = poller.scheduleObservation();
TraceBatchPoller.ScheduleObservationStatus scheduleStatus = observation.status();
Throwable inspectionFailure = observation.checkFailure();

// 仅在调用方处理失败并接受潜在重复后显式恢复。
// poller.resume();
// 生命周期结束时调用；调用方仍负责 worker 的生命周期。
poller.close();
```

参数依次为执行器、tracker、传输、触发记录数、单批最多记录数、轮询间隔、观察等待时间及
时间单位。上述数字只是接线示例，不能作为延迟保证或调优建议。帧字节上限由 sender 在编码时
限制；poller 无法获知待发记录的编码字节数，也不能按该字节数提前触发。队首单条过大时
sender 报错，保留队列并进入暂停，调用方需明确处理；不会跳过队首。

需要同时压缩时，使用不绑定线程的 codec 接口构造传输。以下例子保留 20 字节压缩帧头的
空间；`wire` 是调用方已有的发送端，接收端必须先明确配对压缩格式和限制：

```java
BoundedTraceCompression codec = new BoundedTraceCompression(
        16384, 16404, 1024, 64, BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
CallbackExceptionTracker.BatchTransport compressed =
        codec.batchSender(wire, false, 0, 16384, 32);
TraceBatchPoller compressedPoller = TraceBatchPoller.start(
        worker, tracker, compressed, 16, 32, 20, 100, TimeUnit.MILLISECONDS);

// 在调用方后台生命周期结束时，关闭 poller；它不会关闭 worker 或等待已认领的发送。
compressedPoller.close();
```

`codec.batchSender` 不使用 `Session`，可供调用方正常调度的后台线程执行。压缩后的 wire
限额还必须与实际发送端和接收端匹配；正常返回只代表 wire 约定的接收，不能推断已经解码
或持久化。

不要把其他线程创建的 `session.batchSender(...)` 直接交给 poller。`ScheduledExecutorService`
尤其是线程池不保证任务在 session 的创建线程执行，也不保证周期任务永远使用同一条线程；
即使是单线程调度器，worker 终止后被替换也会改变线程身份。跨线程发送会抛错并使 poller
暂停，未确认的队列记录保留。若要复用 session，调用方必须保证 session 的创建、每次使用和
关闭均发生在同一个存活的后台 worker，并在关闭前协调已认领工作退出；poller 不自动关闭或
迁移原 `Session`。

若需要复用压缩状态且周期任务可能在不同后台线程执行，可显式使用串行会话：

```java
BoundedTraceCompression.SerializedSession shared = codec.newSerializedSession();
CallbackExceptionTracker.BatchTransport compressed =
        shared.batchSender(wire, false, 0, 16384, 32);
TraceBatchPoller compressedPoller = TraceBatchPoller.start(
        worker, tracker, compressed, 16, 32, 20, 100, TimeUnit.MILLISECONDS);

// 结束时先关闭 poller；由调用方协调所有已认领工作和其他会话使用者退出后，
// 再调用 shared.close() 并管理 worker 的生命周期。
compressedPoller.close();
```

串行会话允许不同线程顺序使用，**不排队等待**重叠的编解码或关闭；竞争抛错会按现有规则
暂停 poller，未确认队首保留。批帧形成和 wire writer 在 codec 锁外，writer 可重入或重叠，
其顺序与线程安全仍由调用方管理。编码完成后，即使 writer 或已认领发送还在进行，会话关闭
也可能成功；poller 关闭不会等待它们、不会关闭会话或执行器。接收侧的配对入口及完整限制见
[显式压缩会话](compression-session.md)。

有积压时可显式选择有界排空；下面的 `4` 是每次触发最多尝试的批次数，由调用方根据自己的
后台执行器和传输容量选择：

```java
TraceBatchPoller draining = TraceBatchPoller.startDraining(
        worker, tracker, transport, 16, 32, 4, 20, 100, TimeUnit.MILLISECONDS);
TraceBatchPoller.DeliveryProgress progress = draining.progress();
long batches = progress.acceptedBatches();
long records = progress.acceptedRecords();
```

排空模式触发后按队首顺序分别发送、确认每个批次；一次达到所选次数就返回调度器。
一批只确认部分前缀时，后续尝试从剩余队首继续；它也可能包含触发后新入队的记录，或在
等待时间未到前发送不足数量门槛的短前缀。`BUSY`、`EMPTY` 或异常立即结束当次排空，
不会忙等或自动重试；若后续批次失败，之前已确认的前缀不会回滚。次数必须为正；较大的值
会让任务占用后台执行器更久，单次 transport 本身仍可能阻塞，不提供硬时间上限。
固定延迟从一次任务结束后开始计算；积压时在一次任务内处理多个批次可以减少等待调度间隔的
次数，但实际耗时仍受传输和调度影响，不能据此推断设备延迟或吞吐提升。参见
[ScheduledExecutorService 合同](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html)。

`progress()` 在同步状态下返回不可变快照，分别累计这个 poller 观察到正常确认的批次与
记录数；其他消费者的操作、失败事务可能产生的副作用和持久化均不在其中。即使稍后一次发送
失败或 poller 关闭，之前正常确认的计数仍保留；已认领的发送完成后计数也可能继续增加。
`lastAttempt()` 仍只表示最近一次返回的单批预约结果，不代表整个排空周期。

轮询间隔必须大于零，观察等待可为零，两者均不得超过一天；时间单位转换溢出会被拒绝。
默认使用 `System.nanoTime()`；可选的时钟重载供测试等用途使用，调用方必须提供单调的纳秒
时钟。时钟在状态锁外调用，抛错会暂停 poller。

`BUSY` 不发送也不删除记录，下个轮询周期再检查；`EMPTY` 只表示该次预约为空。成功时仅
移除 transport 确认的连续前缀；确认后观察到队列为空时重置等待起点，否则保留先前起点，
避免剩余记录被反复延迟。由于只能采样队列，若队列在两次观察之间被清空并重新入队，
新记录可能沿用先前起点而提前发送；这不是按每条记录精确计时。
队列满时 tracker 沿用原有丢弃计数，容量按事件数而非 Throwable 对象图的字节数计算。

传输失败、非法确认或处理异常会暂停，不自动重试；异常保留在 `lastFailure()`，调度或取消
异常保留在 `schedulingFailure()`。即使抛错，对端也可能已产生副作用，显式 `resume()` 可能
产生重复。最近一次成功返回的预约结果保留在 `lastAttempt()`；失败不会清除旧结果，调用方
需结合状态和错误判断。`resume()` 返回 true 表示本次接受恢复请求，并不保证随后调度成功，
仍须检查状态和调度错误。致命 VM 异常会继续传播；若投递和取消均发生致命异常，
执行线程保留先发生的投递异常，取消异常仍可通过 `schedulingFailure()` 查看。

`close()` 关闭新的轮询认领，并取消周期句柄，不中断已经认领的工作；这些工作可能在关闭后
开始或完成，其结果仍可观察。首批沿用 tick 开始时的认领：即使关闭发生在时钟或门槛检查期间，
该批仍可能在 `close()` 返回后开始。排空模式的后续每批须先在状态锁内检查 `ACTIVE` 和代次；
检查通过即已认领，即使其后关闭，该批也可完成；关闭先发生则不再认领下一批。关闭不等待传输完成，
但调用方提供的 `cancel(false)` 可能阻塞或
失败；失败可从 `schedulingFailure()` 观察。执行器可能在接收或启动任务后才抛出异常，故
调度异常也不能证明传输没有发生。已被替换或取消的旧任务即使随后被执行，也不能再认领工作。
关闭时 poller 释放自身直接持有的 tracker 和 transport；已认领的工作仍保有引用直到退出。
执行器、时钟、记录的异常或调用方也可能间接保留这些对象，关闭不保证对象已被回收。

`ACTIVE` 仅表示 poller 内部接受轮询认领，不证明调用方执行器仍在运行或任务仍被调度。
例如执行器关闭后周期任务可能被取消而 poller 状态不变；调用方须独立管理并监控执行器。

`scheduleObservation()` 可显式观察当前周期句柄，不改变 poller 状态，也不自动重试：

- `INACTIVE`：内部状态为 `SUSPENDED` 或 `CLOSED`；不调用句柄。
- `HANDLE_PENDING`：内部处于 `ACTIVE`，但新句柄尚未安装；不保证句柄最终会出现。
- `NOT_DONE`／`DONE`：当次 `ScheduledFuture.isDone()` 的返回值；前者不证明执行器有
  可用线程或任务将被执行，后者也不说明取消原因。已认领工作可能仍在执行，须结合
  `inFlight()` 判断。
- `CHECK_FAILED`：句柄检查抛出非致命异常或错误，可通过 `checkFailure()` 查看原对象；
  它不写入 `schedulingFailure()`，也不会暂停 poller。
- `CHANGED`：检查期间内部状态、代次或当前句柄已改变，不能把旧句柄结果用于新代次；
  如果旧句柄检查也抛出非致命异常，`checkFailure()` 仍保留该异常。

该方法只在锁外调用一次 `isDone()`，随后重新核对代次；自定义句柄的实现可能阻塞、
重入或抛错，因此只能在调用方合适的后台线程调用。致命 VM 错误原样传播。返回中的
`state()` 和 `inFlight()` 是复核时的内部状态；句柄与内部状态分时采样，返回后也可能
立即变化。无需调用 `Future.get()`，它可能等待任务且不能证明持久化或后续执行。

等待时间从后台**观察到**非空队列开始，不是事件真实入队时刻；窗口按上面的采样规则重置。
执行器排队、同步传输、系统
调度和暂停均可增加延迟；该接口不承诺硬期限，也不证明端到端速度有所改善。接收确认与队列
规则详见 [非等待批量预约](nonblocking-trace-delivery.md)。

## 显式提前触发（可选）

`poller.requestPoll()` 请求调用方执行器**立即**额外执行一次普通评估，而不必等下一个固定延迟
周期。评估规则与周期任务完全相同：数量门槛、观察等待、排空上限、`BUSY`／`EMPTY` 与失败暂停
都不变，它不会强制发送。请求会合并：已提交的评估尚未开始时，后续调用只返回 `true`；评估进行
中到达的请求会在该次评估结束后再触发一次评估，避免它读取队列之后才入队的记录等待整个周期。
返回 `false` 表示 poller 不处于 `ACTIVE`，或执行器拒绝了提交；拒绝异常保留在
`schedulingFailure()`，poller 不因此暂停。`requestPoll()` 不等待 poller 的状态锁，也不执行
传输；若执行器在调用线程内联执行任务，该次评估会被跳过，仍由周期任务负责。

需要由捕获直接唤醒时，可显式为 tracker 提供 `CaptureSignal`：

```java
AtomicReference<TraceBatchPoller> target = new AtomicReference<>();
CallbackExceptionTracker tracker = new CallbackExceptionTracker("target-process", 128, () -> {
    TraceBatchPoller poller = target.get();
    if (poller != null) poller.requestPoll();
});
// worker 的 execute 必须不阻塞、不内联执行，例如 ScheduledThreadPoolExecutor。
target.set(TraceBatchPoller.start(worker, tracker, transport, 1, 32, 1, 0, TimeUnit.SECONDS));
```

信号只在记录成功入队后、在 **Hook 线程**上调用，被丢弃的记录不触发。不使用该构造器时没有
信号，Hook 线程在失败时仍只做入队。启用后的代价只在失败路径：两次 volatile 读；未被合并时再加
一次 CAS 和一次 `execute`（分配任务对象、获取执行器队列锁，必要时唤醒工作线程）。在宿主
JDK 17 x86-64 上连续失败时，每次捕获平均多约 0.3 µs（`CaptureSignalCostBenchmark`，不代表
ART 或设备）。信号抛出的非致命异常计入 `trackingFailureCount()`，不替换回调异常；致命 VM
事件照常传播。

搭配数量门槛 1 或观察等待 0 时，单条记录在执行器线程被唤醒后即发送；发送进行中到达的记录会在
下一次评估中合并为一批，批量随负载自然增大。调用过 `requestPoll()` 后，若一次评估达到每次批次
上限、最后一批被确认而队列仍非空，评估结束后会再请求一次评估，以有界、让出执行器的步骤继续
排空，而不是把积压留到下一个周期；从未调用 `requestPoll()` 的 poller 行为不变。周期任务仍是唤醒丢失、执行器异常等情况的兜底，
间隔可适当拉长以减少空闲唤醒。观察等待是否到期仍只在评估时检查，请求不会让等待提前结束；
宿主测量结果见 [传输优化第一轮](research/transport-round-1.md)。

## 按到期时刻评估观察等待（可选）

默认（`WaitExpiry.NEXT_POLL`）下，低于数量门槛的记录要等到观察等待到期后的**下一次**评估才发送，
因此比等待时间最多晚一个轮询间隔。显式选择 `WaitExpiry.DEADLINE` 后，评估结束时若观察窗口仍开着
且等待尚未到期，poller 会用 `executor.schedule` 安排**一次**普通评估，延迟为剩余等待时间（按该次评估
读取的时钟计算）：

```java
TraceBatchPoller poller = TraceBatchPoller.startDraining(
        worker, tracker, transport, 16, 32, 4, 100, 20, TimeUnit.MILLISECONDS,
        System::nanoTime, TraceBatchPoller.WaitExpiry.DEADLINE);
```

- 只有评估会安排这次评估，所以它总在执行器线程上发生，不会在 Hook 线程上执行；Hook 线程上的代价
  仍只来自可选的 `CaptureSignal`。
- 这是一次普通评估：数量门槛、排空上限、`BUSY`／`EMPTY`、失败暂停都与周期任务相同，它不会强制发送。
  观察等待仍从**观察到**非空队列时算起。与 `CaptureSignal` 组合时，窗口在捕获后被唤醒的那次评估中
  打开，单条记录大约在“捕获时刻 + 等待时间”发送；只用周期任务时，窗口仍在捕获后的第一次轮询打开，
  省去的是结束一侧最多一个轮询间隔的量化。
- 每个 poller 同时最多只有一个待执行的到期评估，每个观察窗口最多安排一次。窗口提前关闭（例如达到
  数量门槛）时不取消它，它到时作为一次普通评估运行，如有新窗口则为新窗口安排；因此
  `ScheduledThreadPoolExecutor` 的队列里不会堆积已取消的任务。
- 到期评估遇到另一次评估正在进行、而那次评估读取的时钟尚未到期时，那次评估结束后会再提交一次普通
  评估（与 `requestPoll()` 的后续评估相同，不会因此启用 `requestPoll()` 的积压排空行为）。
- 自定义时钟与执行器的时间不一致、到期评估运行时时钟仍未到期，则不再重复安排，由周期任务兜底，
  不会形成忙循环。执行器拒绝安排时异常记入 `schedulingFailure()`，poller 不暂停，该窗口由周期任务
  兜底。暂停和关闭会以 `cancel(false)` 取消待执行的到期评估。观察等待为 0 时从不安排。
- 这不是硬期限：执行器排队、同步传输、系统调度和暂停仍会增加延迟。`scheduleObservation()` 只观察
  周期句柄，不包括到期评估。

宿主测量（JDK 17 x86-64，不是 ART 或设备证据）见 [传输优化第二轮](research/transport-round-2.md)：
轮询 5 ms、等待 20 ms、门槛 16 的单条记录，配合 `CaptureSignal` 时 p50 由约 22.7 ms 降到约 20.2 ms。
