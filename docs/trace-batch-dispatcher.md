# 使用调用方执行器投递批次

`TraceBatchDispatcher` 是显式启用的便利接口，使用调用方提供的 `Executor` 执行一次
`CallbackExceptionTracker.tryDeliverBatch`。SDK 不创建线程、队列、定时器，不自动重试或
关闭执行器。请由调用方提供有界执行器，并在 Hook 回调和 UI 线程之外处理过载。
执行器可以选择在调用线程内直接运行任务，此时 `submit` 本身可能阻塞；它不会把同步
Binder 调用变为 oneway，也不会让接收确认自动持久化。

```java
TraceBatchDispatcher.Attempt attempt = TraceBatchDispatcher.submit(
        workerExecutor, tracker, batchTransport, 32);
Throwable submissionFailure = attempt.submissionFailure();
// 在适当的工作线程观察 attempt.get()；必要时使用带超时的 get。
```

- `cancel(false)` 与 `cancel(true)` 都不会中断线程。只有任务尚未开始时取消才成功；
  即使执行器稍后运行该任务，它也不会调用 transport。取消成功后 `get` 抛出
  `CancellationException`。
- 任务开始后，取消返回 false，SDK 不会中断工作线程或在途 Binder 调用。调用方仍可用
  `get` 观察最终的 BUSY、EMPTY、ACCEPTED 或失败；带超时的 `get` 只停止等待。
- `Executor.execute` 正常返回只说明执行器按自身合同接受了请求。其非致命异常通过
  `submissionFailure()` 单独报告并仍返回句柄。自定义执行器可能接手或启动任务后再抛错，
  因此不能只凭这个异常推断 transport 未被调用。如果处理异常时任务尚未开始，SDK 将其
  置为失败，保留的任务即使随后运行也不会发送；`get` 的 `ExecutionException` 包含该
  调度错误。若任务已开始或完成，`get` 保留真实投递结果，独立于 `submissionFailure()`。
  如果执行器正常返回却永不运行任务，句柄可能一直等待；在任务开始前可由调用方取消。
- transport 抛错或返回无效确认时，`get` 的 `ExecutionException` 保留原错误；队列按其
  合同保留记录，但接收端可能已有副作用，显式重试可能重复。致命 VM 事件在释放预约后
  沿执行线程传播；若调用方持有句柄，`get` 也可观察到该失败。内联执行或提交阶段的致命
  错误向 `submit` 调用者传播。

句柄在成功、失败或开始前取消后，会释放其直接持有的 tracker 和 transport 引用；运行
期间仍会持有。保留的错误、执行器或调用方自身仍可能间接引用它们，因此这不保证对象或
ClassLoader 已被回收，也不代表传输已持久化。

底层预约、FIFO、确认和混用旧接口的规则见[非等待批量预约](nonblocking-trace-delivery.md)。
接收确认不代表已经持久化，也不代表端到端时延已改善。
