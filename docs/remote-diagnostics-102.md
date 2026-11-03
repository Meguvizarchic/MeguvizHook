# API102 可选异常诊断和远程文件边界

默认不启用异常跟踪、不创建队列或线程，也不连接 service。`CallbackExceptionTracker` 是显式的回调
包装器，只跟踪由调用方包装的 SDK before、after、around 回调中逃逸的非致命异常；after 可另行显式
选择记录当前结果槽中的异常，该异常可能来自提前结果或其他回调，不能自动归因于原方法。其他模块、
未包装的回调、被回调自行捕获的异常、框架内部异常及真正的
崩溃追踪均不自动覆盖。fatal VM 事件仍按既有合同立即传播、不进入队列。重建包装器不保证保留回调
实例身份，因此需要幂等重复安装时应复用同一包装对象。

队列有固定记录数上限，满时新记录丢弃并递增计数；记录保留原 `Throwable` 引用，**按记录数有界不等于
按字节有界**。没有自动 worker、调度或 I/O。调用方必须在自己的后台线程调用 `deliver`；运输实现返回
代表它接受该记录，不代表持久化成功。发送抛错时当前记录留在队列，不自动重试；如果外部操作已部分或
完全提交，之后显式重试可能重复。线程死锁、队列溢出、进程退出和宿主未调度都不能视为成功交付。
调用方可以显式 `discardNext()` 并获取被丢弃的事件用于审计，`discardedCount()` 保留丢弃次数；
队列不会自动跳过交付失败的事件。

需要检查无法装入批帧的队首时，可在后台调用 `peekNext()`；它只返回当前事件引用，既不移除也不
预约，返回后队首可能已变化。检查后使用 `discardIfNext(event)`，仅当该对象仍是队首时才丢弃并
递增计数，返回 `false` 表示队首已变化或为空。它沿用 `discardNext()` 的交付锁：普通交付可能使其
等待；非等待批预约期间即使事件已不匹配也会先抛出 `IllegalStateException`。传入事件不能为
`null`。观察不提供独占访问；事件仍持有原始 Throwable，重新编码以估算大小可能与交付并发，
并再次调用其 getter，结果或副作用可能变化。

```java
CallbackExceptionTracker tracker = new CallbackExceptionTracker("target-process", 128);
BeforeCallback guarded = tracker.before(invocation -> {
    // 正常的 Hook 业务；这里不执行文件、网络或 Binder I/O。
});
// 在 HookSpec 中安装 guarded，并在需要幂等安装时复用它。

// 调用方在自己的后台线程执行；模块入口必须已经接到 onModuleLoaded。
CallbackExceptionTracker.Transport log = new FrameworkLogTraceTransport102(module, "MyHook");
int accepted = tracker.deliver(log, 32);
```

`FrameworkLogTraceTransport102` 使用框架现有 `log(..., Throwable)`，其行为和持久性由运行框架决定，
不等于远程文件或统一跨进程异常采集。它传入完整 Throwable，包括原异常消息与堆栈，与 JSON 是否包含
消息无关。`JsonLineTraceTransport` 可在后台按版本 1 编码单条 UTF-8 JSONL，
调用方提供 `RecordWriter` 转交自己的受信任传输。默认只有显式选择时才包含异常消息；可限定栈帧数，
标明字段／帧截断，但异常类名、帧名、source 等仍可能包含敏感信息，调用方负责脱敏和保留期。
异常对象的消息或堆栈读取器抛非致命异常、返回 null 时，编码使用空值并标记对应错误；读取器抛 fatal
时仍立即传播。编码不自动展开 cause／suppressed，不保证完整崩溃堆栈。

## Remote Files：读、写与跨进程

`RemoteFiles102` 只面向被注入进程，调用方必须等框架 attach 并调用 `onModuleLoaded` 后才创建；构造时
检查 API 102，`isSupported()` 查询实际 `PROP_CAP_REMOTE`，不是按版本猜测。`list()` 返回数组副本，
`openReadOnly(name)` 返回调用方须关闭的只读描述符；不支持、缺失文件与框架/安全异常分别传播。
文件名只允许平坦名称。这个适配器不建立 IPC，不允许目标进程以 Remote Files 反向写异常记录。

模块应用侧可使用自身已经持有的 libxposed service 并显式注入流工厂；SDK 不增加 service 依赖：

```java
ModuleRemoteTraceWriter102 writer = new ModuleRemoteTraceWriter102(
        "trace.jsonl", 131072, name -> {
            // service 是模块侧已连接并由调用方维护的 XposedService，不是目标进程对象。
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

// 模块侧后台消费者收到一条来自调用方自建受信任 IPC 的 JSONL 记录后调用：
writer.writeRecord(receivedRecord);
```

`JsonLineTraceTransport` 的 writer 回调可交由调用方自己的进程内队列或 IPC，模块侧收到字节后再用
上述 writer。上面的文件定位只是一个**单写者**示例，既不是跨进程原子追加，也不保证描述符可 seek、
磁盘持久化、顺序或 exactly-once；实现自己的 service 生命周期、能力检查、单写者协调、流所有权、
接收方认证、大小限制和失败处理。模块服务未连接／断开／无能力均应显式反馈，不得静默记为已保存。
`writeRecord` 打开、写入、flush 并关闭调用方提供的流，不主动截断；调用方的 opener 不得截断旧数据。

## 可选 Binder 上行桥

`BinderTrace102.receiver` 构造一个未自动发布的接收端；模块必须显式提供每次事务的 UID 授权策略和
有界接收队列，并自行绑定组件、管理连接及关闭。接收端把字节视为不可信输入；即使 UID 经授权，
同 UID 的其他代码也可能伪造记录。队列消费前仍需检查格式、隐私规则、频率和累计存储配额。
跨进程事务使用 Binder 当前调用 UID；同进程直调或嵌套事务可能继承外层调用身份，组件发布者须
自行约束调用上下文，不能把该值视为对 SDK 代码本身的认证。

```java
// 以下由模块应用显式接入；allowedUid 必须由模块自己的用户、目标和授权策略判断。
ArrayBlockingQueue<byte[]> accepted = new ArrayBlockingQueue<>(128);
IBinder endpoint = BinderTrace102.receiver("trace.jsonl", 65536, allowedUid::test, bytes -> {
    if (!accepted.offer(bytes)) throw new IOException("trace queue full");
});
// 模块通过自己受控的组件提供 endpoint；目标进程由调用方负责取得已连接的 IBinder。
JsonLineTraceTransport.RecordWriter uplink = BinderTrace102.sender(endpointFromModule, "trace.jsonl", 65536);
CallbackExceptionTracker.Transport transport = new JsonLineTraceTransport(uplink, false, 0);
// 目标进程的调用方后台工作线程：tracker.deliver(transport, 32);
// 模块的单写者后台线程：writer.writeRecord(accepted.take());
```

发送端执行同步 Binder，禁止放在 Hook 回调线程；接收端在 Binder 线程仅调用所提供的 writer，
上例只做有界入队，文件写入另在线程上执行。传输返回只表示接收 writer 已正常返回；它不证明
文件已写入、持久化或不重复。进程死亡、部分写入和重试结果可能不确定，SDK 不自动重试。
SDK 不自动声明／导出服务，Binder 身份、畸形 Parcel 和真实跨进程行为尚需设备验证。

如果调用方需要在批量发送时避免持有 tracker 的交付锁，可显式选择
[`tryDeliverBatch`](nonblocking-trace-delivery.md)；需要交给自己的执行器时可组合
[`TraceBatchDispatcher`](trace-batch-dispatcher.md)，或使用调用方后台调度器进行
[批量轮询](trace-batch-poller.md)。需要缩短捕获到投递的延迟时，可显式为 tracker 提供
`CaptureSignal` 并调用 `poller.requestPoll()`，见[显式提前触发](trace-batch-poller.md#显式提前触发可选)；
默认构造的 tracker 没有信号。这些工具均保留真实回执边界，不自动创建线程、重试或提供持久化。压缩状态复用为单线程、显式关闭的
[可选会话](compression-session.md)。

## 显式批量与压缩

`deliverBatch` 在调用方后台线程同步发送一个有界前缀，仅在发送方收到正常确认后从原队列移除该前缀。
发送异常或确认数量无效时不移除；对端可能已经产生副作用，调用方显式重试可能产生重复。单条记录放不进
批帧时不会跳过，调用方可选择原逐条路径或显式丢弃。没有自动调度、超时刷新、后台线程或跨重启恢复。
交付期间持有该 tracker 的交付锁；接收回调不得同步等待另一个正在调用同一 tracker 的交付或丢弃操作，
以免相互等待。批帧接收器会解析并复制数据，直接放在 Binder 回调线程执行会占用该线程。

`BatchTraceTransport` 和 `BoundedTraceCompression` 是双方显式约定的二进制格式，不改变现有 Binder v2，
不能直接写入原 JSONL 文件，也不能发给未配对的旧接收端。以下展示模块侧先接收帧到有界队列、再由单个
后台消费者解压及拆批；目标侧和模块侧的参数必须一致，并由模块自行管理连接和权限：

```java
// 两端分别构造同一组限制；实际阈值应按数据和端到端成本确定。
BoundedTraceCompression codec = new BoundedTraceCompression(16384, 16404, 1024, 64,
        BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
int batchLimitBytes = codec.rawSafeBatchLimit(16404); // 两端约定的 wire 单记录上限
ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(32);
IBinder endpoint = BinderTrace102.receiver("batch.trace", 16404, allowedUid::test, frame -> {
    if (!frames.offer(frame)) throw new IOException("batch frame queue full");
});

// 目标进程的调用方后台线程；endpointFromModule 是已连接的受信任端点。
JsonLineTraceTransport.RecordWriter wire = BinderTrace102.sender(endpointFromModule, "batch.trace", 16404);
CallbackExceptionTracker.BatchTransport batch = codec.batchSender(wire, false, 0, batchLimitBytes, 32);
tracker.deliverBatch(batch, 32);

// 模块侧单个后台消费者：只在处理成功后移除队首。
JsonLineTraceTransport.RecordWriter decode = BatchTraceTransport.compressedReceiver(codec, records -> {
    for (byte[] record : records) writer.writeRecord(record);
}, batchLimitBytes, 32);
byte[] head = frames.peek();
if (head != null) {
    decode.writeRecord(head);
    frames.poll();
}
```

此示例的 Binder 确认**仅代表压缩帧进入模块侧易失内存队列**，不表示该帧已经解码、拆批、写盘或持久化；
解码必须在经过授权后的后台线程进行。该示例要求队列只有一个消费者、生产者不移除队首；处理失败的帧
仍留在队首，由调用方审计、重试或显式处理。若写入部分记录后失败，重试整个帧可能重复；进程退出会丢失
队列。若需要持久化确认或去重，必须由应用实现相应协议和存储，不能由本示例推导。

`compressedReceiver` 等价于显式组合 codec 解码与批帧接收；它仅在内部复用 codec 新建的明文数组，
避免再复制整个批帧。codec 只读取一次帧头：RAW 负载直接复制为返回的明文，校验和正是对这份副本
计算；压缩负载仍先做有界快照再解压。`BatchTraceTransport.receiver` 同样只读取一次长度字段，并把
每条记录复制为独立数组，不再先复制整帧。因此返回后修改输入不影响已交付记录，并发修改也不会让
校验结果与交付内容分离；但并发复制不是原子操作，调用方仍应避免同时修改输入。需要复用编解码状态时可使用
`compressedSessionReceiver(session, writer, ...)`，session 仍须在创建它的后台线程使用并关闭。
codec 的解压上限和批帧上限分别检查；若前者配置得更大，数据可能先完成解压分配，再因
超过批帧上限被拒绝。处理不可信输入时应同时约束两者。

`codec.batchSender(...)` 将新建且尚未暴露的批帧直接交给 codec，省去一次整帧输入快照；
`session.batchSender(...)` 是复用会话状态的对应入口，仍只能在会话所属线程使用。普通
`codec.encode(...)`、`codec.encodingWriter(...)` 和 `BatchTraceTransport.sender(...)` 的行为不变，
编码后的帧仍传给调用方指定的 writer，确认和失败语义与原有组合一致。

压缩对整个输入完整试压，不使用可能误判的采样；根据配置阈值与包含帧头的节省量决定是否选压缩，
否则发 RAW 帧。即使选 RAW，显式压缩协议仍有固定帧头。解码独立限制压缩帧和解压后数据大小、拒绝
错误长度及尾随内容；校验和只用于检测意外损坏，不提供认证或保密。批帧、压缩帧和 Binder 的大小上限
必须同时匹配；真实 Binder、远程服务与设备端性能仍需单独验证。

为了让不可压缩数据也能按 RAW 发送，`codec.rawSafeBatchLimit(wireRecordLimit)` 根据该 codec
的配置和调用方给出的线路单记录上限，计算完整批帧上限。若限制无法容纳最小批帧，它立即抛出
`IllegalArgumentException`；计算本身不发送、不预留缓冲，也不协商接收端配置。双方应分别核对
codec、批帧、线路的大小和格式上限；实际使用的线路上限必须由调用方提供，不能仅假设对端一致。
某条记录仍无法容纳时，应由调用方选择减小记录或显式处理队首，不能跳过它。该预算只保证满足
所给长度限制，不能保证共享 Binder 事务缓冲始终有空间，或表示接收端已经持久化。
