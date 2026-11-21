# MeguvizHook 迁移对照与排障

本文提供从经典 `XC_MethodHook` / 裸 `XposedInterface.hook` 迁移至 MeguvizHook 的对照范式、热重载与异常上行接线、状态处理及常见排障方法。

## 1. 常见写法迁移对照

### 模式 1：裸 `XposedInterface.hook` / `XC_MethodHook` → 按形状分层声明

**迁移前（不管是否改写结果、是否读参数都全量获取并分配对象）：**

```java
xposed.hook(targetMethod).intercept(chain -> {
    if ("block".equals(chain.getArg(0))) {
        return "";
    }
    return chain.proceed();
});
```

**迁移后（选用匹配的专用无帧条件形状，安装前校验全部参数与返回类型）：**

```java
RegistrationReceipt receipt = environment.install(
        HookSpec.forMember("feature.block-check", "my-module", targetMethod)
                .replaceIfStringArgumentEquals(0, "block", "", null)
                .build());
```

- 纯固定返回值且跳过原方法：改用 `.replaceConstant("")`。
- 原参数执行原方法一次、仅正常返回后替换值：改用 `.replaceResultOnSuccess(fallback)`。
- 仅统计调用或读结果：使用 `.before(...)` / `.after(...)`（保持默认 `NO_ARGS`）。
- 仅只读参数：使用 `.beforeArgs(...)` / `.afterArgs(...)`（自动选用 `READ_ARGS_VIEW`）。

### 模式 2：循环逐个安装描述符 → `installAll`（共享批次类表）

```kotlin
val hooks = environment.forOwner("my-module")
val receipts = hooks.installAll {
    hook("feature.a", method("com.example.Target", "alpha") {
        parameterTypes(String::class); returning(Void.TYPE)
    }) { beforeArgs { inv -> inspect(inv.getArgument(0)) } }

    hook("feature.b", method("com.example.Target", "beta") {
        parameterTypes(); returning(Int::class)
    }) { replaceConstant(0) }
}
```

- 若搭配 `MeguvizRefMemberResolver`：在 Central `1.2.0` 中 `installAll` 会在同一 `loader` 与 `codeEpoch` 内自动共享一个 `Batch`（在 `Ref 1.2` 上减少同一类的重复 `getDeclaredMethods()` 复制）。
- 需要跨线程并发解析描述符时，在调用方工作线程执行 `environment.prepareConcurrently(spec, 128)`，收集 `READY` 令牌后在后台协调线程按序调用 `token.commit()`。

### 模式 3：从 DEX 扫描直连 Hook 安装（Central `1.2.0` `hook-dex-bridge`）

```java
DexRefHookBridge.QueryBatch located = DexRefHookBridge.queryAndBindBatch(
        snapshot, existingDexRules, hostLoader, 128);
// 调用方核对 executionStatus、coverage、decision 及业务代次并挑选目标
DexRefHookBridge.CandidateBinding selected = located.bindings().queries().get(0).candidates().get(0);
if (selected.status() == DexRefHookBridge.Status.BOUND) {
    RegistrationReceipt receipt = environment.install(
            selected.hook("feature.dex-target", "my-module")
                    .replaceConstant(Boolean.FALSE)
                    .build());
}
```

### 模式 4：API 102 热重载接管与安全清理

```java
// 旧代 onHotReloading：只保存基础类型/字符串等跨类加载器中性状态，不关闭旧 environment
@Override
public boolean onHotReloading(HotReloadingParam param) {
    lifecycle.requestQuiescence();
    if (lifecycle.tryCleanup().status() == ModuleLifecycle.CleanupStatus.BUSY) {
        lifecycle.resumeAdmissions();
        return false; // 有在途模块工作尚未退出，拒绝热重载
    }
    param.setSavedInstanceState(buildNeutralBundle());
    return true;
}

// 新代 onHotReloaded：用新 loader 与递增 epoch 建环境，并在回调结束前关闭 session
@Override
public void onHotReloaded(HotReloadedParam param) {
    LibXposed102Backend backend = new LibXposed102Backend(
            this, new LibXposed102Context(param.getProcessName(), hostLoader, nextEpoch));
    HookEnvironment env = HookEnvironment.create(backend);
    try (LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, param)) {
        for (XposedInterface.HookHandle old : session.oldHandles()) {
            HookSpec nextSpec = resolveUnambiguousSpec(old, param.getSavedInstanceState());
            if (nextSpec == null) continue;
            RegistrationReceipt receipt = env.adopt(nextSpec, session.adoption(old));
            // 根据 receipt 处理结果；异常或 STALE_IDENTITY 时禁止盲目 unhook 旧 handle
        }
    }
}
```

### 模式 5：非阻塞异常跟踪与后台批量轮询

```java
CallbackExceptionTracker tracker = new CallbackExceptionTracker("target-process", 128);
HookSpec spec = HookSpec.forMember("feature.safe", "my-module", targetMethod)
        .before(tracker.before(invocation -> riskyWork()))
        .build();

// 仅在调用方后台线程与后台调度器上发送；BinderTrace102.sender 严禁在 Hook/UI 线程调用
JsonLineTraceTransport.RecordWriter uplink = BinderTrace102.sender(endpointBinder, "trace.jsonl", 65536);
CallbackExceptionTracker.BatchTransport batch = BatchTraceTransport.sender(uplink, false, 8, 16384, 32);
TraceBatchPoller poller = TraceBatchPoller.start(
        workerScheduler, tracker, batch, 16, 32, 20, 100, TimeUnit.MILLISECONDS);
```

### 模式 6：使用 `FakeBackend` 进行 JVM 契约单元测试

```java
FakeBackend backend = FakeBackend.create();
try (HookEnvironment env = HookEnvironment.create(backend)) {
    Method add = Fixtures.class.getMethod("add", int.class, int.class);
    backend.onTarget(add, (self, args) -> (Integer) args[0] + (Integer) args[1]);
    RegistrationReceipt r = env.install(HookSpec.forMember("test.add", "test", add)
            .beforeMutableArgs(inv -> inv.setArgument(0, 10)).build());
    assert Integer.valueOf(12).equals(backend.call(add, null, 1, 2));
    assert r.registration().close().newCallbacksRejected();
}
```

## 2. 生命周期、借用与隔离铁律

1. **借用生命周期**：`HookInvocation` 只能在回调线程且回调返回前读取；禁止传入异步任务或保存至字段。`around` 中如需采样当前参数槽，使用 `InvocationArguments.copyOf(invocation)`。
2. **多回调异常状态机（`ErrorPolicy.PROPAGATE`）**：`before` 抛异常则原方法执行 `0` 次；原方法抛异常则保持原异常实例交给 `after`；若原方法成功而 `after` 抛异常，则传播 `after` 异常且绝不重放原方法；若两者皆失败，保留原方法异常并将 `after` 异常送入有界 `DiagnosticsSink`。
3. **非等待关闭**：处于外部回调线程无法等待环境锁时，先调 `environment.requestClose()` 拒绝新操作，再由协调线程调用 `environment.close()` 完成逐个卸载。
4. **不确定结果隔离**：`install`、`replace` 或 `adopt` 期间若后端抛出未声明异常或返回 `null` 句柄，SDK 无法确认框架入口状态，会在当前环境中隔离该 `HookId`；调用方不得对该句柄盲目重试或强制卸载。

## 3. 高频问题排查表

| 现象 / 状态 | 根因分析 | 处理与回退建议 |
| --- | --- | --- |
| 回调读参抛 `HookContractException` | 使用了 `before` / `after`（默认 `NO_ARGS`）却调用了参数 getter | 改用 `beforeArgs` / `afterArgs` 或显式设置 `access(ParameterAccess.READ_ARGS_VIEW)` |
| `HookSpec.Builder.build()` 报错 `typed argument callbacks require...` | 显式声明了 `access(NO_ARGS)` 却混用 `beforeArgs` 等类型化回调 | 去掉冲突的 `access(...)` 让其自动推导，或使用匹配的读写形状 |
| `RegistrationReceipt.Status.CONFLICT` | 同一 `HookId` 已存在且回调对象/策略/目标不完全一致 | 需幂等时复用同一回调和常量实例；需更新回调时改用 `replace` 或先 `close()` 旧句柄 |
| `ALREADY_INSTALLED` 返回 `registration() == null` | 配置完全一致，SDK 未新建代次 | 属于正常满足状态（`isSatisfied() == true`），继续管理初次安装时保留的句柄 |
| `UNSUPPORTED_CAPABILITY` | 选用了当前后端不支持的能力（如在 API 101 请求 `HOT_RELOAD`） | 检查 `environment.capabilities()`；API 101 保持单代使用或升级至 API 102 后端 |
| `TARGET_MISSING` / `AMBIGUOUS_TARGET` | 描述符未命中或 `UNIQUE` 下命中多重载 | 检查 `ClassLoader`、类型名（注意内部类 `$` 与数组 `[` 写法），补全参数类型或改用 `TargetSpec.ofMember` |
| `TEMPORARILY_UNAVAILABLE` | 环境已关闭、已请求关闭、旧代仍在退出或该 ID 已被故障隔离 | 检查 `isCloseRequested()` 及前次回执；被隔离的 ID 须在生命周期重建时用新环境处理 |
| APK 构建报类重复或运行期缺类 | 同时打包了 101/102 后端或错误用 `implementation` 引入了 `libxposed:api` | 仅留一套后端并确保 `io.github.libxposed:api` 为 `compileOnly` |
