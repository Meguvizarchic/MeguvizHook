# 显式非等待批量预约

`CallbackExceptionTracker.tryDeliverBatch` 为调用方提供一个独立的批量交付选项：
在短暂持有交付锁时预约队首，再**释放交付锁**，由当前调用线程同步执行现有
`BatchTransport.sendBatch`。传输正常返回且确认的连续前缀数量有效后，才重新取得锁并
移除该前缀。它不自动创建线程、连接服务或重试；同步 Binder 仍可能阻塞调用线程，必须放在
调用方自己管理的后台工作线程上，而不能在 Hook 回调或 UI 线程调用。

```java
// 在调用方的后台工作线程；batch 可由 BatchTraceTransport.sender 创建。
CallbackExceptionTracker.BatchAttempt attempt = tracker.tryDeliverBatch(batch, 32);
switch (attempt.status()) {
    case ACCEPTED:
        int accepted = attempt.acceptedCount();
        // 传输层接受了这个有序前缀；是否持久化取决于接收端合同。
        break;
    case BUSY:
        // 其他交付或丢弃操作正在使用队列；由调用方的有界调度策略处理，勿忙等。
        break;
    case EMPTY:
        break;
}
```

`BUSY` 表示未预约队列，也未调用 transport。`EMPTY` 表示本次预约时无可发送记录；
并发生产者之后仍可能添加记录。正常返回的有效正数只确认相应连续前缀；回调抛出异常或
返回无效数量时队列不移除这些记录，异常原样传播。远端可能已产生部分或全部副作用，
调用方显式重试可能重复。进程死亡或极端中断期间不提供事务恢复或持久化保证。

## 观察本次是否进入 wire

如果调用方能够保证**只有一个后台 worker 串行执行这些尝试**，可复用一个标记和压缩
sender，在每次开始前重置，并在该次同步调用已经结束后读取：

```java
// 以下对象由调用方初始化；wire 是已经配置好的同步 RecordWriter。
AtomicBoolean wireEntered = new AtomicBoolean();
JsonLineTraceTransport.RecordWriter observedWire = frame -> {
    wireEntered.set(true); // 紧邻实际调用，不能放在编码开始之前。
    wire.writeRecord(frame);
};
CallbackExceptionTracker.BatchTransport batch = codec.batchSender(
        observedWire, false, 0, 16 * 1024, 32);

// 每次由同一个后台 worker 调用；codec、tracker、batch 和标记均由调用方管理。
void sendOnce() throws Throwable {
    wireEntered.set(false);
    try {
        CallbackExceptionTracker.BatchAttempt attempt = tracker.tryDeliverBatch(batch, 32);
        boolean enteredThisAttempt = wireEntered.get();
        switch (attempt.status()) {
            case BUSY:
            case EMPTY:
                // 本次未调用 batch；enteredThisAttempt 为 false。
                break;
            case ACCEPTED:
                int accepted = attempt.acceptedCount();
                // 已确认连续前缀；该标记不证明持久化。
                break;
        }
    } catch (Throwable failure) {
        boolean enteredThisAttempt = wireEntered.get();
        // 在受控诊断中同时记录 enteredThisAttempt 和 failure；不要自动重试。
        throw failure; // 保持原异常对象，包括 fatal VM 事件。
    }
}
```

示例需要导入 `java.util.concurrent.atomic.AtomicBoolean`。`false` 只说明本次**这个
wrapper** 没有进入给定的 wire，不证明事件 getter、编码或其他调用方代码没有副作用。
`true` 表示已进入 wire；发送失败时，对端可能已产生副作用，也可能尚未处理，标记不能
判定远端状态。调用方仍须分别检查 `BUSY`、`EMPTY`、`ACCEPTED` 或异常；异常不会被包装，
也不会自动重试或改变已有确认语义。

此示例要求上一尝试及其 wrapper 调用已结束，才能重置标记；不能并行、重入或让其他路径
调用这个 wrapper，也不能在任务尚未终结时用共享标记推断结果。若通过执行器提交任务，
执行器可能启动任务后又抛出异常，提交失败本身不是任务已结束的证明。并发尝试需要各自
独立的标记和 wrapper，并由调用方协调 wire 的线程安全与生命周期。

当此新接口的 transport 正在运行时，另一个 `tryDeliverBatch` 返回 `BUSY`，
`deliver`、`deliverBatch`、`discardNext` 和 `discardIfNext` 会抛出 `IllegalStateException` 而不是等待。
`peekNext` 可观察当前队首，但它不预约队首，返回的引用可能马上过期。
只使用旧接口时，其原有跨线程串行等待和同线程递归拒绝行为不变；旧接口仍会在调用
transport 期间持锁。请勿在新接口预约期混用旧接口，除非调用方明确处理这一状态差异。

不持本组件交付锁不能证明其他锁、接收端队列或 Binder 服务不存在死锁；调度队列的容量、
忙碌后的重试时机、授权、连接状态、进程退出及数据保留仍由应用负责。该接口不将本地内存
入队当成对端确认，也不把回调正常返回称为已写盘。Android 对同步调用和跨 Binder 调用锁的
约束参见 [AOSP Binder 线程文档](https://source.android.com/docs/core/architecture/ipc/binder-threading)。
