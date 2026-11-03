# 显式并行预解析与顺序安装

`HookEnvironment.prepareConcurrently(spec, maxCandidates)` 允许调用方把多个描述符目标的
**解析**交给自己管理的工作线程；它不创建线程、队列或重试。每个 `READY` 结果携带一个
一次性 `PreparedInstall`。由调用方的**后台协调线程**按业务指定顺序调用 `commit()`，
实际安装仍沿用环境的串行检查、后端安装和逐目标回执。不要在 Android UI 线程或 Hook
回调中等待准备任务或提交：解析、后端和清理都可能阻塞。

这是一条显式路径，不改变现有 `install`／`adopt` 的调用顺序。它适合确实存在多个待解析
描述符且解析器支持并发的场景；如果调用方已验证并持有具体 `Method`／`Constructor`，
可直接用 `HookSpec.forMember(...)`，不需要再次枚举目标。并行准备不是性能保证，是否
值得采用需要用实际目标、命中率、provider 和执行器测量。

并行准备可能增加线程调度、同步和会话管理开销，应结合应用实际负载比较；性能设计依据与
验证边界见[性能研究记录](performance-research.md)。

## 准备与提交

调用方可在其有界执行器中对每个 `HookSpec` 提交一项：

```java
// prepareExecutor 由调用方创建、限流和关闭；spec 是已创建的描述符 HookSpec。
Future<HookEnvironment.Preparation> future = prepareExecutor.submit(
        () -> environment.prepareConcurrently(spec, 128));
```

协调层应按原输入顺序保存各任务及其结果，**即使某项失败也要接管其他任务的结果**。
下方只展示已由协调层取得并拥有的单项结果如何消费；`moduleReport...` 是调用方自己实现
的记录／决策方法，不是 SDK 方法：

```java
void processPrepared(HookEnvironment.Preparation preparation) {
    if (preparation.status() != HookEnvironment.PreparationStatus.READY) {
        moduleReportPreparation(preparation.status(), preparation.failure(), preparation.checkFailure());
        return;
    }
    try (HookEnvironment.PreparedInstall token = preparation.prepared()) {
        RegistrationReceipt receipt = token.commit();
        moduleReportInstall(receipt);
        // PARTIALLY_INSTALLED 的 registration 仍可能非空；调用方必须继续持有并管理它。
    }
}
```

在后台协调线程按业务顺序处理这些结果；每项 `commit()` 返回自己的 `RegistrationReceipt`，
应检查状态、逐目标结果和非空 registration。多目标安装不保证跨目标原子性；部分成功必须
按回执管理，不能把整个批次当成自动回滚。提交可能抛异常；不管成功、失败还是抛错，
**已领取的令牌都不能再提交**。关闭未领取的令牌表示放弃；关闭不会中断已经领取的提交。

超时、取消、调度拒绝、`Future.get()` 异常或协调线程中断时，协调层仍必须负责已得到的
每个 `READY` 令牌，并确保迟到结果也由任务持有者接收并关闭。仅调用 `Future.cancel()`
不能证明 resolver/provider 已停止，也不能保证迟到的已生成令牌可被调用方取回；应让工作
任务与结果持有器共同实现明确的所有权交接。不要在超时后无条件重试：类初始化、provider
或后端可能已有副作用。执行器若内联运行任务，则提交本身也可能阻塞调用线程。

## 结果、身份与生命周期

- 只有 `READY` 有可提交令牌。`CLOSED` 包括已请求关闭但尚未实际卸载；`FILTERED`、`UNSUPPORTED`、`TARGET_UNSUPPORTED`、
  `NOT_FOUND_NOW`、`TOO_MANY`、`INVALID_RESULT`、`STALE_IDENTITY`、`RESOLUTION_FAILED` 和
  `IDENTITY_CHECK_FAILED` 都不是安装成功；应根据业务判断是否停止或重新准备。
- 检查 `failure()` 和 `checkFailure()`：解析 checked 异常和随后身份复核的非致命异常可能
  同时存在，即使状态是 `CLOSED` 也可能有错误。致命 VM 事件按原对象传播；尝试附加的
  suppressed 异常不保证一定成功。调用方执行器可能将线程异常封装在 `ExecutionException` 中。
- `READY` 是环境锁内的某一时点结果；调用方收到它之前环境也可能已经关闭。令牌绑定原环境、
  spec、ClassLoader 身份和 code epoch；提交会重新检查。身份可靠性依赖调用方提供真实、线程
  安全、单调且不发生 ABA 的 epoch，采样不能取代框架提供的原子身份认证。
- 令牌持有候选成员、spec 和环境等强引用，可能使类加载器继续可达；提交领取或 `close()`
  会清空令牌直接持有的负载，但返回的结果、回调、异常、执行器或业务对象仍可能持有引用。
  这不保证 ClassLoader 已回收。请及时提交或放弃，不要把令牌作为跨进程或跨重启数据保存。
- 若使用 `MeguvizRefResolverSession`，已得到 `READY` 的令牌不会因 session 关闭而自动撤销；
  它仍在提交时检查环境、loader 和 epoch。会话关闭也不会自动关闭已安装的 Hook。反之，
  准备仍在途时 session 关闭可能使该准备失败；按会话的 `requestClose()`／`report()` 合同
  管理资源，注意无在途查询时 provider 清理仍可能同步阻塞。

## 并发前提与边界

调用方必须确认所选 resolver、backend context、查询工厂、provider 以及其返回列表均支持
需要的并发访问，且列表在复制期间保持稳定；已有 `MemberResolver` 接口不会自动赋予这项
能力。loader 和 epoch 读取为分时采样。准备方法在当前线程已持环境监视器时直接拒绝；
预检和身份复核回调中同步递归准备任何 ID 都会被拒绝，resolver 内则只拒绝递归准备相同 ID。
它不占用安装事务 guard 跨越锁外 resolver，因此其他线程可在
解析期间关闭环境。READY 即使产生，也仍需在提交时重检。

准备阶段自身不调用 `backend.install`，但外部的 context、resolver、provider 或工厂可能
自行重入并产生安装或其他副作用。预检和身份复核的 backend/context 调用在环境监视器外执行，
每步之间短暂检查关闭状态；检查后环境仍可能立即关闭，已开始或即将开始的外部调用可以在关闭
后执行或完成。类加载、初始化、查询及 provider 的操作也可以比普通 `install()` 更早发生，
因此准备结果是分时观察，不承诺普通安装的副作用先后顺序；context 必须支持这种并发及关闭竞争。
最终 token 在环境锁内检查关闭状态，`commit()` 仍重新检查身份并沿用普通串行安装。
初始预检中抛出的未检查异常，以及 resolver 返回列表在复制时抛出的异常，仍直接传播；
并发关闭不会保证将这些异常转换为 `CLOSED` 或 `checkFailure()`。

普通安装和替换仍可能持环境监视器调用 backend/context；持环境监视器重入关闭时也沿用原同步
关闭路径。外部实现不得同步等待需要当前持有的环境或注册监视器的其他线程，否则仍可能形成
等待环。预解析的短状态检查也可能等待其他线程持有的环境锁。普通环境关闭在快照后释放环境
监视器，再逐个关闭注册。

`maxCandidates` 必须为 1–4096；它限制的是准备结果保留的候选副本，**不限制** resolver
在返回前已分配的集合、provider 工作量、Throwable 对象图或执行时间。结果为空时不创建
可提交令牌，也不将未命中缓存为未来代码代次的结论。不要根据这个限制推断整个系统的内存
上限，也不要根据 JVM 代理验证推断真实框架、ART、BIL 或设备性能。
