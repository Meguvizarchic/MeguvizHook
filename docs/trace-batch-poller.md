# 使用调用方后台执行器轮询批量诊断

`TraceBatchPoller` 是显式启用的后台轮询器，在调用方提供的 `ScheduledExecutorService` 上按固定延迟检查待发送数量：当待发数量达到阈值，或从观察窗口首次看到非空队列起经过最大观察等待时间时，调用一次 `tryDeliverBatch`（`start` 每次最多发送 1 批；`startDraining` 每次最多连续发送指定批次数）。SDK 不自建执行器，也不在 Hook 或 UI 线程安排任务；调用方执行器不得内联运行任务或静默丢弃提交。

> **适用范围与验证说明**：下文示例参数仅用于接线演示，不构成延迟保证；文中所引研究数据均测自宿主 JDK 17 x86-64 环境，不代表 ART 或实机表现，且接收确认不代表持久化落盘。

## 基础用法与积压排空

```java
// worker：调用方创建并负责关闭的后台 ScheduledExecutorService
// writer：具有明确接收语义的 RecordWriter
CallbackExceptionTracker.BatchTransport transport = BatchTraceTransport.sender(
        writer, false, 8, 16 * 1024, 32);
TraceBatchPoller poller = TraceBatchPoller.start(
        worker, tracker, transport, 16, 32, 20, 100, TimeUnit.MILLISECONDS);

// 在后台线程查看状态（ACCEPTED 仅代表 transport 接受了该前缀）
TraceBatchPoller.State state = poller.state();
Throwable deliveryFailure = poller.lastFailure();
Throwable schedulingFailure = poller.schedulingFailure();
CallbackExceptionTracker.BatchAttempt result = poller.lastAttempt();

// 按需检查周期任务句柄状态（INACTIVE / HANDLE_PENDING / NOT_DONE / DONE / CHANGED / CHECK_FAILED）
TraceBatchPoller.ScheduleObservation observation = poller.scheduleObservation();

// 确认处理故障且接受潜在重复后显式恢复：poller.resume();
// 生命周期结束时关闭轮询器（不等待在途发送完成，也不关闭 worker）
poller.close();
```

若需要积压排空，使用 `startDraining` 并指定单次轮询最多发送的批次数（例如 `4` 批）：

```java
TraceBatchPoller draining = TraceBatchPoller.startDraining(
        worker, tracker, transport, 16, 32, 4, 20, 100, TimeUnit.MILLISECONDS);
TraceBatchPoller.DeliveryProgress progress = draining.progress();
long batches = progress.acceptedBatches();
long records = progress.acceptedRecords();
```

## 搭配压缩与线程归属要求

需要压缩传输时，应根据调度器的线程模型选择对应方式：

- **多线程通用调度器（无线程绑定状态）**：使用 `codec.batchSender(...)`。

```java
BoundedTraceCompression codec = new BoundedTraceCompression(
        16384, 16404, 1024, 64, BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
CallbackExceptionTracker.BatchTransport compressed =
        codec.batchSender(wire, false, 0, 16384, 32);
TraceBatchPoller compressedPoller = TraceBatchPoller.start(
        worker, tracker, compressed, 16, 32, 20, 100, TimeUnit.MILLISECONDS);
```

- **需要复用压缩状态且后台线程可能轮换**：使用串行会话 `codec.newSerializedSession()`（禁止把单线程 `Session` 直接交给可能更换工作线程的池化调度器，否则跨线程检查会抛错并暂停 poller）：

```java
BoundedTraceCompression.SerializedSession shared = codec.newSerializedSession();
CallbackExceptionTracker.BatchTransport compressed =
        shared.batchSender(wire, false, 0, 16384, 32);
TraceBatchPoller compressedPoller = TraceBatchPoller.start(
        worker, tracker, compressed, 16, 32, 20, 100, TimeUnit.MILLISECONDS);
// 结束时先关闭 poller 并协调所有在途任务退出，再关闭 shared 与 worker。
```

## 状态规则与异常行为

- 轮询间隔须大于 `0`，观察等待可为 `0`，两者上限均为 1 天；可选传入单调纳秒时钟 `Clock`（在状态锁外读取，抛错会暂停 poller）。
- 发送字节上限由 `sender` 在编码时强制校验；若队首单条记录超限，会报错保留原记录并进入 `SUSPENDED`。遇到 `BUSY`、`EMPTY` 或异常时，当次排空立即停止，不忙等或自动重试。
- `close()` 会拒绝新周期认领并尝试取消周期句柄，不等待已经认领的在途发送完成，随后清空自身直接持有的 `tracker` 与 `transport` 引用。`ACTIVE` 只表明轮询器内部未暂停/关闭，调用方仍须独立监控执行器自身存活状态（参见 [ScheduledExecutorService 合同](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html) 与 [非阻塞批量预约](nonblocking-trace-delivery.md)）。

## 显式提前触发（可选）

`poller.requestPoll()` 可请求调度器立即额外执行一次普通评估（阈值、等待时间与暂停逻辑不变；并发重复请求会自动合并，不会强制发送）。可在创建 tracker 时注入 `CaptureSignal` 主动唤醒后台轮询：

```java
AtomicReference<TraceBatchPoller> target = new AtomicReference<>();
CallbackExceptionTracker tracker = new CallbackExceptionTracker("target-process", 128, () -> {
    TraceBatchPoller poller = target.get();
    if (poller != null) poller.requestPoll();
});
// worker 的 execute 严禁阻塞或内联执行。
target.set(TraceBatchPoller.start(worker, tracker, transport, 1, 32, 1, 0, TimeUnit.SECONDS));
```

该信号只在成功入队时由 **Hook 线程**触发（队满丢弃时不触发）；捕获路径仅多两次 volatile 读与未合并时的一次 CAS + `execute`（宿主 JDK 17 下约多 `0.3 µs`），非致命异常计入 `trackingFailureCount()`。启用过 `requestPoll()` 后，若一次评估达到 `maxBatchesPerTick` 上限且队列仍非空，评估结束时会自动再排一次评估，逐步排空积压。详见[传输优化第一轮](research/transport-round-1.md)。

## 按到期时刻评估观察等待（可选）

默认 `WaitExpiry.NEXT_POLL` 下，未达数量门槛的记录最多可能比观察等待时间晚一个轮询间隔才发出。显式传入 `WaitExpiry.DEADLINE` 后，评估结束时若观察窗口仍开启，poller 会在执行器线程上额外通过 `executor.schedule` 安排**一次**到期评估：

```java
TraceBatchPoller poller = TraceBatchPoller.startDraining(
        worker, tracker, transport, 16, 32, 4, 100, 20, TimeUnit.MILLISECONDS,
        System::nanoTime, TraceBatchPoller.WaitExpiry.DEADLINE);
```

- 每个观察窗口至多安排一次到期评估（仅在执行器线程调度，不在 Hook 线程发生）；若窗口提前达到数量阈值也不急着取消该一次性任务，避免调度队列堆积已取消任务。
- 暂停、恢复或关闭时会取消待执行的到期评估；`scheduleObservation()` 仅观察周期任务句柄，不含到期任务。宿主测量（轮询 `5 ms`、等待 `20 ms`、门槛 `16` 配合 `CaptureSignal`）显示单条记录 p50 从 `22.7 ms` 降至 `20.1 ms`，p99 从 `25.2 ms` 降至 `20.5 ms`，详见[传输优化第二轮](research/transport-round-2.md)。
