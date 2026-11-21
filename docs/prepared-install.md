# 显式并行预解析与顺序安装

`HookEnvironment.prepareConcurrently(spec, maxCandidates)` 允许调用方把多个描述符目标的**解析**交给自己管理的工作线程执行；SDK 本身不创建线程、队列或重试。每个 `READY` 结果携带一个一次性 `PreparedInstall`，由调用方的**后台协调线程**按业务顺序调用 `commit()`，实际安装仍沿用环境的串行检查、后端安装与逐目标回执。切勿在 Android UI 线程或 Hook 回调中等待准备任务或提交，因为解析、后端调用与清理都可能阻塞。

> **适用范围与验证说明**：并行准备用于确实存在多描述符并发定位需求的场景，本身不构成性能保证（性能设计与验证边界见[性能研究记录](performance-research.md)）。如已持有验证过的 `Method` / `Constructor`，直接使用 `HookSpec.forMember(...)` 即可。

## 准备与提交

调用方可在有界执行器中为每个 `HookSpec` 提交准备任务：

```java
// prepareExecutor 由调用方创建、限流和关闭；spec 为已构造的描述符 HookSpec。
Future<HookEnvironment.Preparation> future = prepareExecutor.submit(
        () -> environment.prepareConcurrently(spec, 128));
```

协调层应按输入顺序保存各任务与结果，即使某项失败也必须接管并处理其余任务返回的令牌（下面 `moduleReport...` 为调用方自定义记录方法）：

```java
void processPrepared(HookEnvironment.Preparation preparation) {
    if (preparation.status() != HookEnvironment.PreparationStatus.READY) {
        moduleReportPreparation(preparation.status(), preparation.failure(), preparation.checkFailure());
        return;
    }
    try (HookEnvironment.PreparedInstall token = preparation.prepared()) {
        RegistrationReceipt receipt = token.commit();
        moduleReportInstall(receipt);
        // PARTIALLY_INSTALLED 下的 registration 仍可能非空，调用方须继续持有并管理。
    }
}
```

- **单次提交原则**：不管 `commit()` 成功、失败或抛异常，已领取的令牌均不能再次提交；关闭未领取令牌代表放弃，不会中断已经开始领取的提交。
- **超时与取消处理**：超时、取消、调度拒绝或线程中断时，协调层仍须对已生成的 `READY` 令牌以及迟到完成的结果负责关闭，不能仅靠 `Future.cancel()` 假定工作已停止。超时后切勿盲目重试，以免放大类初始化或 Provider 的副作用。

## 结果、身份与生命周期

- **状态枚举**：仅 `READY` 携带可提交令牌；`CLOSED`（含已收到 `requestClose()` 请求）、`FILTERED`、`UNSUPPORTED`、`TARGET_UNSUPPORTED`、`NOT_FOUND_NOW`、`TOO_MANY`、`INVALID_RESULT`、`STALE_IDENTITY`、`RESOLUTION_FAILED` 与 `IDENTITY_CHECK_FAILED` 均需按未就绪状态处理。
- **检查异常字段**：同时检查 `failure()` 与 `checkFailure()`；致命 VM 事件原样传播。
- **时点快照与强引用**：`READY` 仅反映准备期间某一时刻的状态，提交时会再次验证环境、`ClassLoader` 与 `codeEpoch`（依赖调用方提供线程安全、单调递增、无 ABA 的 epoch）。令牌持有候选成员、spec 与环境强引用，请及时提交或 `close()` 放弃。
- **搭配 `MeguvizRefResolverSession`**：获得 `READY` 令牌后即使关闭解析会话，令牌也不会失效（提交时仍重检环境身份）；反之会话关闭不会卸载已安装的 Hook，准备期间关闭会话则可能使该准备失败。

## 并发前提与边界

- 调用方必须自行确保所选 `MemberResolver`、`BackendContext`、查询工厂及 Provider 支持并发访问。
- 准备方法禁止在当前线程已持有环境锁时调用，预检与复核回调中也禁止同线程重入；方法在环境锁外执行，不占用事务锁，因此其他线程可在解析中途关闭环境。
- `maxCandidates`（1–4096）仅限制 `Preparation` 最终保留的候选副本数量，不限制解析器在返回前已分配的集合或执行时间；结果为空时不创建令牌，也不缓存负结果。
