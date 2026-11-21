# 显式请求关闭环境

当外部 backend 或解析器正在阻塞等待另一线程返回时，那个线程不能直接同步调用并等待同一个 `HookEnvironment.close()`，否则可能与正在持有环境锁的安装或替换操作互相等待。`environment.requestClose()` 提供了一个非阻塞的关闭请求入口：仅原子置位关闭标志，不获取环境锁或注册锁，不调用 backend，也不创建线程。重复调用无副作用；`isCloseRequested()` 仅用于观察此标志。

```java
// 在不能等待清理完成的外部回调线程中：
environment.requestClose();
// 立即返回给等待此回调的 backend；切勿在此处同步等待环境关闭。

// 在独立的后台协调线程中完成实际卸载与清理，并自行检查完成状态或异常：
environment.close();
```

## 调度与竞态规则

- **独立协调**：`requestClose()` 与 `close()` 可运行在不同线程。若使用执行器调度后台 `close()`，必须确保该执行器不会把任务内联到正在等待的回调线程运行；SDK 不会自动安排后续 `close()`，只请求而不关闭会使环境一直保持待清理状态。正常的 `close()` 通过重入检查后也会自动置位请求标记。
- **门禁效果**：若关闭请求先于状态检查到达，新的 `install`、`adopt` 与 `replace` 会直接返回 `TEMPORARILY_UNAVAILABLE`；有效描述符的 `prepareConcurrently` 在检查中发现关闭请求时会返回 `CLOSED`（若预解析已越过最后一次发布检查，仍可能返回 `READY`，但在 `token.commit()` 时会被拦截并消费为未安装回执）。
- **锁外保证与已接收操作**：普通安装与替换在检查关闭标志前仍需获取环境监视器并读取 `backend.id()`，因此请求关闭不会让阻塞在外部的调用立刻返回。一旦某个事务越过关闭检查门禁，仍按原合同继续执行，后续 `close()` 会统一处理已发布的注册；已经触发的原有 Hook 回调也不会因仅调用 `requestClose()` 而中止，只有实际关闭注册后才进入透传。
- **状态含义**：`isCloseRequested()` 为 `true`（或预解析返回 `CLOSED`）不代表已执行 `close()`，更不代表所有框架入口已恢复或底层资源已释放；需要了解关闭完成情况的调用方应监控自己发起的后台 `close()` 任务本身。

> 仅调用 `requestClose()` 本身不依赖任何监视器锁，但直接调用同步 `close()`、等待清理任务或外部自定义锁导致的互等仍需调用方自行避免。`requestClose()` 与 `isCloseRequested()` 已包含在 Maven Central `1.2.0` 发布版本中。
