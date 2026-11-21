# 使用调用方执行器投递批次

`TraceBatchDispatcher` 是显式便利工具，用于在调用方传入的 `Executor` 上执行一次 `CallbackExceptionTracker.tryDeliverBatch`。SDK 不自建线程、队列或定时器，不重试也不关闭执行器；请由调用方传入有界后台执行器，并避开 Hook 回调与 UI 线程（若执行器内联在调用线程运行任务，`submit` 本身也可能阻塞）。

```java
TraceBatchDispatcher.Attempt attempt = TraceBatchDispatcher.submit(
        workerExecutor, tracker, batchTransport, 32);
Throwable submissionFailure = attempt.submissionFailure();
// 在合适的后台线程通过 attempt.get() 或带超时 get(...) 观察结果。
```

## 取消、提交异常与引用释放

- **取消语义**：`cancel(false)` 与 `cancel(true)` 都不会中断工作线程。仅当任务尚未开始执行时取消才返回 `true`（即使执行器随后调度到该任务也不会再调用 transport，`get()` 抛 `CancellationException`）；一旦任务开始，取消返回 `false`，可继续通过 `get()` 获取 `BUSY` / `EMPTY` / `ACCEPTED` 或异常（超时 `get` 只结束等待）。
- **提交异常处理**：`Executor.execute` 抛出的非致命异常记录在 `submissionFailure()` 且仍返回 `Attempt` 句柄（部分执行器可能启动任务后再抛错，因此不能仅凭提交异常断言 transport 未运行）。若处理提交异常时任务尚未开始，SDK 会标记失败阻止后续执行；若任务已开始或完成，`get()` 保留实际投递结果。
- **失败传播与引用释放**：transport 抛出异常或返回非法确认数时，`get()` 抛出包装后的 `ExecutionException` 并保留原记录在队列中；致命 VM 事件会在释放预约后沿执行线程传播。任务终结或开始前取消后，句柄会清空自身直接持有的 `tracker` 与 `transport` 引用。

底层预约、FIFO、确认与禁止混用旧接口规则见[非阻塞批量预约](nonblocking-trace-delivery.md)。接收确认仅说明传输层接受了批次，不代表已落盘。
