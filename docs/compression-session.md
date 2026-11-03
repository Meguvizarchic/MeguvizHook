# 显式压缩会话

`BoundedTraceCompression.newSession()` 为连续处理多条记录的调用方提供可复用的有界编解码
会话；普通 `codec.encode` / `codec.decode` 不需要创建会话，既有行为与帧格式保持不变。
会话不创建线程、队列或传输连接，也不修改同步接收确认和失败重试的语义。
与显式批量投递组合时，队首预约、对端确认和 `BUSY` 的语义仍由
[非阻塞批量投递](nonblocking-trace-delivery.md)管理。
如要将批帧生成和会话压缩配对，可使用 `session.batchSender(writer, ...)`；
它避免重复快照内部新建的批帧，普通 `session.encode(...)` 仍会快照调用方输入。

```java
// 必须在调用方自己的后台 worker 上创建、使用并关闭同一个 session。
try (BoundedTraceCompression.Session session = codec.newSession()) {
    JsonLineTraceTransport.RecordWriter encoded = session.encodingWriter(wire);
    for (byte[] record : callerOwnedRecords) {
        encoded.writeRecord(record);
    }
}
```

接收方可使用同一个会话的 `decode(frame)`，或通过 `decodingWriter(plainWriter)`
把验证通过的明文交给后续消费者。接收方必须先完成自己的发送方授权和输入限制，并在
后台 worker 中解码；不要在 Binder 回调线程做解压或文件 I/O。两端仍需显式约定压缩帧
格式和各自大小上限，未配对的旧 JSONL 接收端不能直接读取该帧。

会话绑定创建它的线程，编码、解码、创建 writer 和关闭均需在该线程完成；跨线程误用
会抛出异常，不能把同一会话同时交给多个 worker。调用者应始终使用 `try` / `finally`
或 try-with-resources 关闭会话，释放其惰性创建的 native 压缩器和解压器；没有自动后台
清理或隐式 ThreadLocal。若由其他线程管理模块生命周期，须先协调原 worker 完成会话关闭，
不能直接把它交给可能在其他线程执行的清理回调。会话持有创建线程的引用，关闭后也应
释放会话本身。

与 [`TraceBatchPoller`](trace-batch-poller.md) 或其他执行器组合时，注意调度器的线程不一定
是创建会话的线程，线程池也不保证周期任务每次在同一线程执行。即使只配置一个线程，worker
退出后被替换也会改变线程身份。不要在主线程创建 session 后直接把 `session.batchSender(...)`
交给 poller：后台发送会因跨线程使用而抛错并使 poller 暂停，未确认的记录留在队列。

通用调度器上可使用不持有会话的 `codec.batchSender(...)`，或显式选择下述串行会话；完整接线见
[轮询批量诊断](trace-batch-poller.md)。原 `Session` 仅当调用方能够保证创建、所有使用、最后关闭
均在**同一个存活的后台 worker 线程**上进行时，才应交给调度任务复用；调用方还须在关闭前协调
已认领工作退出。poller 不负责创建或关闭 session，关闭 poller 也不等待已有工作完成；原会话
没有自动迁移、隐式 ThreadLocal 或 worker 退出后的自动重建。

## 可跨线程顺序复用的串行会话

`codec.newSerializedSession()` 创建另一种显式会话，允许不同后台线程**依次**复用同一套 codec；
它不改变上面的原 `Session` 线程归属。串行会话不创建线程或排队：编解码、创建 writer 或关闭
与另一会话操作重叠时立即抛 `IllegalStateException`，同线程在编解码期间重入也被拒绝。若这种
异常从 transport 传给 poller，poller 会暂停并保留未确认队首；其他进程已经发生的副作用仍需
按各自 transport 合同处理。

串行会话的独占仅覆盖编解码和资源状态操作。批帧形成及调用方传入的 writer 不在 codec 锁内，
writer 可以重入或与其他 writer 重叠；调用方须自己保证发送顺序、线程安全和接收确认。会话
可能在编码已完成、writer 仍运行时成功关闭，因此 `close()` 成功不表示发送完成，也不会等待
或中断 writer。poller 的 `close()` 同样不等待已认领工作，且不会关闭会话或执行器；应先停止
认领并协调所有已认领工作和其他使用者退出，再由调用方关闭会话及管理执行器。

接收端可使用 `BatchTraceTransport.compressedSerializedReceiver(session, batchWriter, ...)`
配对串行会话与批帧解析；这是调用方进程内的编解码接口，不建立连接或授权。发送端和接收端
通常各自管理会话，仍须分别限制输入及处理生命周期。

## 显式选择输出缓冲策略

当前开发版本允许在创建会话时选择输出缓冲策略。无参的 `newSession()` 和
`newSerializedSession()` 仍使用 `OutputBufferStrategy.STANDARD`；普通无会话编解码不受影响。
对压缩率较高的大批次，可显式选择 `STAGED`：

```java
// 在线程归属明确的后台 worker 上创建、使用并关闭。
try (BoundedTraceCompression.Session session = codec.newSession(
        BoundedTraceCompression.OutputBufferStrategy.STAGED)) {
    JsonLineTraceTransport.RecordWriter encoded = session.encodingWriter(wire);
    for (byte[] record : callerOwnedRecords) {
        encoded.writeRecord(record);
    }
}

// 需要跨线程顺序复用时，由调用方协调在途任务后再离开作用域。
try (BoundedTraceCompression.SerializedSession serialized = codec.newSerializedSession(
        BoundedTraceCompression.OutputBufferStrategy.STAGED)) {
    // 调用方管理 worker；确保所有已认领任务和 writer 在关闭前退出。
}
```

`STAGED` 不对输入采样，也不把一条记录拆成多个压缩帧：它先将同一压缩流的输出写入
最多 16 KiB 的会话缓冲；输出未完成且仍在本次候选大小上限内时，再分配更大的缓冲并
继续。压缩是否采用、净节省门槛、RAW 回退和必须压缩才能放入的判断仍基于实际输出，
帧格式和接收端协议不变。不同压缩实现或输出分段方式不承诺生成逐字节相同的压缩结果。

强压缩的大批次可能避免分配完整大小的候选数组；随机或低压缩率数据可能更慢，且会话
可能额外保留最多 16 KiB 暂存数据直至关闭。暂存内容不保证擦除，异常或资源耗尽的发生
时机也可能变化。是否选择此策略应按实际数据和运行平台测量；它不改变任何会话的线程归属、
重入、重置、关闭及 writer 在途规则。

每个压缩帧完成或失败后都会重置对应 codec；字典模式每帧重新设置固定字典，帧头、CRC、
完整试压、净节省门槛和 RAW 回退与原始接口相同。普通损坏帧会报错，成功重置后仍可处理
后续帧；若底层 reset 本身失败，会话进入不可再用状态，仍须显式关闭。会话重置不承诺对
native 内存进行安全擦除。`reset()` 和 `end()` 的底层合同见 [Java Deflater 文档](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/Deflater.html)；
CRC 只能检查意外损坏，不能认证或保密。

显式 `Session` 和 `SerializedSession` 可惰性保留一个最多 16 KiB 的压缩输出暂存数组供后续编码
复用；`STANDARD` 对需要更大输出空间的候选使用单次分配，`STAGED` 则可先使用暂存数组，
两者均不会扩大这个保留上限。返回的帧使用独立数组，不向 writer
暴露暂存数组。关闭会话时会释放其对暂存数组的引用，即使后续 native 清理失败也不保留该引用；
这不保证立即回收，也不擦除数组内容。会话存活期间残留的压缩数据可能在托管堆中保留更久；
调用方应按自身隐私及资源管理要求决定是否使用会话。stateless `codec.encode` 不保留该缓冲。

复用可避免每条压缩记录重新创建和释放 codec，但是否降低总耗时或内存占用取决于记录大小、
数据分布、GC 与运行平台；没有真实设备和端到端传输测试时不能宣称性能提升。
