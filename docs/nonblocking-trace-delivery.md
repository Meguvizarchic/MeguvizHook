# 显式非等待批量预约

`CallbackExceptionTracker.tryDeliverBatch` 为调用方提供非阻塞加锁的批量交付入口：短暂获取交付锁预约队首前缀后，**立即释放交付锁**并在当前线程同步调用 `BatchTransport.sendBatch`；仅在传输正常返回并确认有效连续前缀数量后，才重新加锁移除该前缀。它不创建线程、不自动连接或重试，因同步 Binder 仍可能阻塞，必须在调用方后台工作线程调用（严禁放在 Hook 回调或 UI 线程）。

```java
// 在调用方的后台工作线程执行；batch 可由 BatchTraceTransport.sender 创建。
CallbackExceptionTracker.BatchAttempt attempt = tracker.tryDeliverBatch(batch, 32);
switch (attempt.status()) {
    case ACCEPTED:
        int accepted = attempt.acceptedCount();
        // 传输层接受了该有序前缀；是否落盘取决于接收端自身保证。
        break;
    case BUSY:
        // 其他交付或丢弃操作正在占用队列；按调度策略延后重试，切勿忙等。
        break;
    case EMPTY:
        break;
}
```

- **`BUSY` / `EMPTY`**：`BUSY` 表示当前已有预约或交付在进行，本次未读取队列也未调用 transport；`EMPTY` 表示此刻队列为空。
- **部分确认与失败处理**：正整数返回值表示从队首起被连续接受的条数；若抛异常或返回无效数量，队列保留原记录，但接收端可能已产生副作用，显式重试可能造成重复。

## 观察本次是否进入实际传输线

若能保证**同一时间仅有一个后台 worker 串行调用**，可在发送器外包装一层轻量子标记，在调用前复位并在结束后读取：

```java
AtomicBoolean wireEntered = new AtomicBoolean();
JsonLineTraceTransport.RecordWriter observedWire = frame -> {
    wireEntered.set(true); // 紧邻实际写入调用，不要提前到编码阶段
    wire.writeRecord(frame);
};
CallbackExceptionTracker.BatchTransport batch = codec.batchSender(
        observedWire, false, 0, 16 * 1024, 32);

void sendOnce() throws Throwable {
    wireEntered.set(false);
    try {
        CallbackExceptionTracker.BatchAttempt attempt = tracker.tryDeliverBatch(batch, 32);
        boolean enteredThisAttempt = wireEntered.get();
        switch (attempt.status()) {
            case BUSY:
            case EMPTY:
                // 本次未进入传输；enteredThisAttempt 为 false
                break;
            case ACCEPTED:
                int accepted = attempt.acceptedCount();
                break;
        }
    } catch (Throwable failure) {
        boolean enteredThisAttempt = wireEntered.get();
        // 同步记录 failure 与 enteredThisAttempt；切勿无条件自动重试
        throw failure;
    }
}
```

该标记为 `false` 仅说明未进入被包装的 `wire`（不证明编码阶段未访问异常对象属性）；为 `true` 时若抛出异常，对端可能已处理也可能未处理。并发尝试场景应使用各自独立的标记与包装器。

## 与旧同步接口混用的约束

当 `tryDeliverBatch` 的传输回调正在锁外执行时，另一个 `tryDeliverBatch` 调用会返回 `BUSY`，而 `deliver`、`deliverBatch`、`discardNext` 与 `discardIfNext` 会直接抛出 `IllegalStateException` 而不是阻塞等待；`peekNext` 可读取队首引用但不锁定该元素。仅使用旧同步接口时原有阻塞串行行为不变，建议不要在预约期内混用新旧接口。

> 不持有 SDK 交付锁并不代表接收端队列或 Binder 服务本身不会互锁，Android 同步 Binder 调用规则参见 [AOSP Binder 线程文档](https://source.android.com/docs/core/architecture/ipc/binder-threading)。
