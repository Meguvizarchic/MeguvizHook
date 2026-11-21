# API 102 可选异常诊断和远程文件边界

默认不启用异常跟踪、不创建队列或后台线程，也不主动连接 service。

> **适用范围与验证说明**：以下模块均需由调用方显式启用并自行提供后台工作线程；`BinderTrace102`、`RemoteFiles102` 与 `ModuleRemoteTraceWriter102` 涉及的跨进程 Binder 身份、畸形 Parcel 处理、远程文件写入及真实端到端性能尚未在实机 ART 环境完成全面认证，须在目标设备自行验证。此外，所有传输层的“正常返回”仅表示接收端已接收或入队，**不代表数据已持久化落盘或绝不重复**。

## 1. 回调异常跟踪 `CallbackExceptionTracker`

`CallbackExceptionTracker` 是显式回调包装器，只捕获被调用方包装过的 `before` / `after` / `around` 回调中逃逸的非致命异常（`after(..., true)` 可额外记录当前结果槽中的异常，但该异常可能来自提前返回或其他回调，不能自动归因于原方法）。未被包装的回调、其他模块、回调内部自行捕获的异常以及致命 VM 事件（`VirtualMachineError`、`ThreadDeath`）不会入队，致命事件继续直接传播。

- **复用包装器以维持幂等**：重新包装会改变回调对象身份，需要幂等重复安装时应保存并复用同一个包装实例。
- **按条数有界**：队列按记录条数设上限，队满时新记录直接丢弃并递增计数；事件内部保留原 `Throwable` 引用，因此按条数有界不等于按内存字节有界。
- **显式后台投递**：调用方必须在自己的后台线程调用 `deliver`、`deliverBatch` 或 `tryDeliverBatch`。发送失败时记录仍留在队首，不会自动跳过或重试（若对端已有部分副作用，后续显式重试可能重复）。可使用 `peekNext()` 观察队首，并用 `discardIfNext(event)` / `discardNext()` 丢弃超大或确认作废的记录。

```java
CallbackExceptionTracker tracker = new CallbackExceptionTracker("target-process", 128);
BeforeCallback guarded = tracker.before(invocation -> {
    // 正常的 Hook 逻辑；禁止在此执行文件、网络或同步 Binder I/O。
});
// 在 HookSpec 中安装 guarded，需要幂等安装时复用该引用。

// 在调用方自己的后台线程中投递（须在模块接到 onModuleLoaded 之后）：
CallbackExceptionTracker.Transport log = new FrameworkLogTraceTransport102(module, "MyHook");
int accepted = tracker.deliver(log, 32);
```

- `FrameworkLogTraceTransport102` 调用框架自带的 `log(..., Throwable)`，保留完整异常消息与堆栈。
- `JsonLineTraceTransport` 在后台将单条记录编码为版本 1 的 UTF-8 JSONL，交由调用方提供的 `RecordWriter` 转发；默认不包含异常消息，并可限制栈帧数量（异常类名与帧名仍可能含敏感信息，调用方须自行脱敏）。

## 2. Remote Files 与模块端写入

- **`RemoteFiles102`**（被注入进程侧）：须在 `onModuleLoaded` 之后创建。`isSupported()` 会真实查询框架 `PROP_CAP_REMOTE`，`list()` 返回文件列表副本，`openReadOnly(name)` 返回调用方负责关闭的只读文件描述符（文件名仅允许平坦名称）。该类不建立 IPC，不允许目标进程反向写回远程文件。
- **`ModuleRemoteTraceWriter102`**（模块进程侧）：接受调用方提供的 `XposedService.openRemoteFile(...)` 流打开器，不增加对 service 组件的直接依赖：

```java
ModuleRemoteTraceWriter102 writer = new ModuleRemoteTraceWriter102(
        "trace.jsonl", 131072, name -> {
            // service 是模块侧已连接并由调用方管理的 XposedService。
            ParcelFileDescriptor descriptor = service.openRemoteFile(name);
            ParcelFileDescriptor.AutoCloseOutputStream stream =
                    new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
            try {
                stream.getChannel().position(stream.getChannel().size());
                return stream;
            } catch (IOException | RuntimeException failure) {
                stream.close();
                throw failure;
            }
        });

// 模块侧后台单写者消费者收到来自自建受信任 IPC 的记录后调用：
writer.writeRecord(receivedRecord);
```

该定位方式仅适用于**单写者**，不保证跨进程原子追加、描述符可 seek 或落盘顺序；`writeRecord` 每次写入并 flush 后关闭流，打开器不得截断既有数据。

## 3. 可选 Binder 上行桥 `BinderTrace102`

`BinderTrace102.receiver` 构造一个未自动导出的 `IBinder` 接收端；模块须自行通过受控组件发布，并提供逐次事务的 `UidAuthorizer` 策略与有界接收队列。接收端把收到的字节视为不可信输入（同 UID 下的其他代码也可能发送数据，同进程或嵌套调用还会继承外层 calling UID），消费端写盘前仍应校验格式、频率与累计配额。

```java
ArrayBlockingQueue<byte[]> accepted = new ArrayBlockingQueue<>(128);
IBinder endpoint = BinderTrace102.receiver("trace.jsonl", 65536, allowedUid::test, bytes -> {
    if (!accepted.offer(bytes)) throw new IOException("trace queue full");
});

JsonLineTraceTransport.RecordWriter uplink = BinderTrace102.sender(endpointFromModule, "trace.jsonl", 65536);
CallbackExceptionTracker.Transport transport = new JsonLineTraceTransport(uplink, false, 0);
// 目标进程后台线程：tracker.deliver(transport, 32);
// 模块进程后台单写者线程：writer.writeRecord(accepted.take());
```

- **同步阻塞边界**：`BinderTrace102.sender` 执行同步 Binder 事务，严禁放在 Hook 回调或 UI 线程。接收端回调运行在 Binder 线程，只应快速校验并入队，不得直接做文件 I/O。
- **零拷贝切片通道**：`BinderTrace102.sender` 实现了 `JsonLineTraceTransport.SlicedRecordWriter`，在配合 `JsonLineTraceTransport.send`、`BatchTraceTransport.sender` 或 `BoundedTraceCompression` 时直接向 `Parcel.writeByteArray(buffer, offset, length)` 传递编码缓冲区切片，省去额外数组复制。Central `1.2.0` 另提供 `BinderTrace102.slicedReceiver` / `slicedReceiverForFrameSizes` 与 `ModuleRemoteTraceWriter102.slicedBatchReceiver`，用于在接收端复用帧槽或直接切片流式写入。
- **非等待、调度与轮询组合**：可按需搭配[`tryDeliverBatch`](nonblocking-trace-delivery.md)、[`TraceBatchDispatcher`](trace-batch-dispatcher.md) 与[`TraceBatchPoller`](trace-batch-poller.md)（支持 `CaptureSignal` 提前唤醒与 `WaitExpiry.DEADLINE`），以及[显式压缩会话](compression-session.md)。

## 4. 显式批量与压缩传输

`BatchTraceTransport` 与 `BoundedTraceCompression` 是收发双方显式约定的二进制协议（不可直接写入纯 JSONL 文件，也不能发往未配对的旧接收端）：

```java
BoundedTraceCompression codec = new BoundedTraceCompression(16384, 16404, 1024, 64,
        BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
int batchLimitBytes = codec.rawSafeBatchLimit(16404); // 确保不可压缩数据按 RAW 发送也不超线路上限
ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(32);
IBinder endpoint = BinderTrace102.receiver("batch.trace", 16404, allowedUid::test, frame -> {
    if (!frames.offer(frame)) throw new IOException("batch frame queue full");
});

// 目标进程后台线程：
JsonLineTraceTransport.RecordWriter wire = BinderTrace102.sender(endpointFromModule, "batch.trace", 16404);
CallbackExceptionTracker.BatchTransport batch = codec.batchSender(wire, false, 0, batchLimitBytes, 32);
tracker.deliverBatch(batch, 32);

// 模块侧单个后台消费者（仅在处理成功后移除队首）：
JsonLineTraceTransport.RecordWriter decode = BatchTraceTransport.compressedReceiver(
        codec, writer, batchLimitBytes, 32);
byte[] head = frames.peek();
if (head != null) {
    decode.writeRecord(head);
    frames.poll();
}
```

- `ModuleRemoteTraceWriter102` 同样实现了 `BatchTraceTransport.BatchWriter`：`writeBatch` 会先检查整批长度上限，再仅打开一次输出流按序写出全部记录，中途出错不回滚已追加部分。
- `compressedReceiver` 与 `BatchTraceTransport.receiver` 均按单次读取帧头与长度的方式校验，防止并发修改输入数组导致校验与交付不一致（仍应避免并发改动传入缓冲）。需要跨批次复用解压与压缩状态时，可使用 `compressedSessionReceiver` 或 `compressedSerializedReceiver`。
- `BoundedTraceCompression` 对整帧完整试压后决定采用压缩或 RAW 帧（不采样），校验和仅检测意外损坏，不提供加密认证。使用 `codec.rawSafeBatchLimit(wireRecordLimit)` 时，收发双方仍须共同匹配真实的线路单记录上限。
