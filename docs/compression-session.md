# 显式压缩会话

`BoundedTraceCompression.newSession()` 为连续编码或解码多条记录的场景提供可复用的有界会话，避免每条记录反复创建和销毁底层压缩器。普通无状态的 `codec.encode` / `codec.decode` 无需创建会话，帧格式保持不变。会话不创建线程、队列或网络连接，也不改变同步确认语义（与批量投递配合时的队首预约见[非阻塞批量预约](nonblocking-trace-delivery.md)）。

> **适用范围与验证说明**：是否节省耗时或内存取决于记录分布、GC 与运行平台，真实设备端效果须结合实际负载与端到端传输测量；此外，会话保留的最多 `16 KiB` 暂存数组与底层 native 内存均不保证安全擦除。

## 1. 线程绑定会话 `Session`

```java
// 必须在同一个后台 worker 线程中创建、使用并关闭会话。
try (BoundedTraceCompression.Session session = codec.newSession()) {
    JsonLineTraceTransport.RecordWriter encoded = session.encodingWriter(wire);
    for (byte[] record : callerOwnedRecords) {
        encoded.writeRecord(record);
    }
}
```

- **线程归属**：`Session` 严格绑定创建它的线程，所有编码、解码、创建 writer 及 `close()` 都必须在该线程执行，跨线程调用直接抛错。请勿把 `session.batchSender(...)` 交给可能更换线程的通用线程池或 `TraceBatchPoller`。
- **资源释放**：务必使用 `try / finally` 或 `try-with-resources` 关闭，释放惰性创建的压缩器、解压器及暂存数组引用；不存在隐式后台清理或 `ThreadLocal` 自动迁移。
- **接收端用法**：在授权与大小限制校验后的后台线程中，调用 `session.decode(frame)` 或 `session.decodingWriter(plainWriter)`；搭配批帧发送可使用 `session.batchSender(writer, ...)`，避免重复快照新建批帧。

## 2. 跨线程顺序复用 `SerializedSession`

当 `TraceBatchPoller` 等调度器可能在不同后台线程依次运行任务时，可显式使用 `codec.newSerializedSession()`：

- **非阻塞并发互斥**：`SerializedSession` 允许不同线程顺序复用，但不排队等待——任何重叠的编解码或关闭尝试，以及同线程在编解码途中重入，均直接抛出 `IllegalStateException`。
- **锁外写入与安全关闭**：独占保护仅覆盖编解码与状态转换；批帧构造及调用方传入的 writer 在锁外执行（支持并发写或重入，顺序由调用方协调）。关闭会话前须先停止新任务认领并等待已认领的发送任务退出，再调用 `close()`；对应接收端可使用 `BatchTraceTransport.compressedSerializedReceiver(session, batchWriter, ...)`。

## 3. 输出缓冲策略 `OutputBufferStrategy`

`newSession()` 与 `newSerializedSession()` 默认使用 `OutputBufferStrategy.STANDARD`。针对高压缩率的大批次，可显式传入 `OutputBufferStrategy.STAGED`：

```java
try (BoundedTraceCompression.Session session = codec.newSession(
        BoundedTraceCompression.OutputBufferStrategy.STAGED)) {
    JsonLineTraceTransport.RecordWriter encoded = session.encodingWriter(wire);
    for (byte[] record : callerOwnedRecords) {
        encoded.writeRecord(record);
    }
}

try (BoundedTraceCompression.SerializedSession serialized = codec.newSerializedSession(
        BoundedTraceCompression.OutputBufferStrategy.STAGED)) {
    // 由调用方协调后台 worker，确保在途任务退出后再退出作用域。
}
```

- **`STAGED` 策略行为**：先将压缩流写入最多 `16 KiB` 的会话缓冲，仅在输出未完成且仍处于大小上限内时才继续分配更大缓冲。它不采样输入、不拆帧，也不改变净节省门槛与 RAW 回退判定（不同压缩缓冲分段方式不承诺产出逐字节相同的压缩序列）。高压缩大批次可借此避免分配完整超大候选数组，但随机或难压缩数据可能变慢。
- **错误重置**：每帧编码完成或异常后均会重置底层 `Deflater` / `Inflater`（字典模式重设字典，详见 [Java Deflater 文档](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/Deflater.html)）。若底层 `reset()` 本身失败，会话进入不可用状态，仍须显式关闭；CRC 校验和仅用于发现意外损坏。
